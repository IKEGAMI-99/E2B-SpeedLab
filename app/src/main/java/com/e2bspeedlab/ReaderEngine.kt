package com.e2bspeedlab

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * Practical runtime for the FLASH reader.
 *
 * FAST preserves the verified SpeedLab profile. THINK trades a little decode speed for a larger KV
 * cache and an explicit hidden reasoning budget. A fresh Conversation is created for every START,
 * so there is no invisible history and no 3-4 turn context exhaustion.
 */
@OptIn(ExperimentalApi::class)
class ReaderEngine(private val cacheDirectory: File) : Closeable {

    enum class Mode(
        val contextTokens: Int,
        val maxOutputTokens: Int,
        val thinkingBudget: Int,
    ) {
        FAST(contextTokens = 1536, maxOutputTokens = 512, thinkingBudget = 0),
        THINK(contextTokens = 2048, maxOutputTokens = 1024, thinkingBudget = 512),
    }

    private var engine: Engine? = null
    private var generationDispatcher: ExecutorCoroutineDispatcher? = null
    private var loadedMode: Mode? = null
    private var activeAffinityMask: Long = 0L

    val isLoaded: Boolean
        get() = engine != null

    val mode: Mode?
        get() = loadedMode

    val loadedContextTokens: Int
        get() = loadedMode?.contextTokens ?: 0

    val loadedAffinityMask: Long
        get() = activeAffinityMask

    suspend fun load(modelPath: String, mode: Mode): Double = withContext(Dispatchers.Default) {
        if (engine != null && loadedMode == mode) return@withContext 0.0

        closeInternal()
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = true
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cacheDirectory.mkdirs()

        val originalMask = CpuAffinity.currentMask()
        val tunedMask = CpuAffinity.pinFast(2)
        val started = System.nanoTime()
        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = mode.contextTokens,
                cacheDir = cacheDirectory.absolutePath,
            )
        )

        try {
            newEngine.initialize()
            engine = newEngine
            loadedMode = mode
            activeAffinityMask = tunedMask
            generationDispatcher = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "E2B-Reader-FAST2").apply { priority = Thread.MAX_PRIORITY }
            }.asCoroutineDispatcher()
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        } finally {
            runCatching { CpuAffinity.restore(originalMask) }
        }

        (System.nanoTime() - started) / 1_000_000_000.0
    }

    suspend fun generate(
        prompt: String,
        mode: Mode,
        onChunk: (String) -> Unit,
    ): BenchmarkInfo {
        check(engine != null) { "GPU engine is not loaded" }
        check(loadedMode == mode) { "Reader mode changed before engine reload" }

        val dispatcher = generationDispatcher ?: Dispatchers.Default
        return withContext(dispatcher) {
            val activeEngine = engine ?: error("GPU engine is not loaded")
            val originalMask = CpuAffinity.currentMask()
            try {
                activeAffinityMask = CpuAffinity.pinFast(2)

                // A new conversation per START is deliberate. There is no visible chat history in
                // this product, so keeping invisible history would only waste the small fast KV cache.
                activeEngine.createConversation(conversationConfig(mode)).use { conversation ->
                    conversation.sendMessageAsync(
                        text = prompt,
                        maxOutputToken = mode.maxOutputTokens,
                        thinkingConfig = ThinkingConfig(
                            enableThinking = mode == Mode.THINK,
                            thinkingTokenBudget = mode.thinkingBudget,
                        ),
                    ).collect { message ->
                        // Message.toString() is primary content only. The dedicated `thought` channel
                        // stays hidden, so FLASH receives only the user-facing answer.
                        val text = message.toString()
                        if (text.isNotEmpty()) onChunk(text)
                    }
                    conversation.getBenchmarkInfo()
                }
            } finally {
                runCatching { CpuAffinity.restore(originalMask) }
            }
        }
    }

    private fun conversationConfig(mode: Mode): ConversationConfig {
        val thinking = mode == Mode.THINK
        return ConversationConfig(
            tools = emptyList(),
            automaticToolCalling = false,
            // null uses model metadata channels, allowing the thought channel to be parsed away from
            // primary content in THINK mode. FAST disables thinking explicitly below.
            channels = null,
            samplerConfig = if (thinking) {
                // Gemma 4 recommended quality-oriented sampling for reasoning mode.
                SamplerConfig(topK = 64, topP = 0.95, temperature = 1.0, seed = 0)
            } else {
                // Preserve the measured high-MTP-acceptance fast path.
                SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0)
            },
            prefillPrefaceOnInit = false,
            maxOutputToken = mode.maxOutputTokens,
            thinkingConfig = ThinkingConfig(
                enableThinking = thinking,
                thinkingTokenBudget = mode.thinkingBudget,
            ),
            enableResponseFormat = false,
        )
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { engine?.close() }
        engine = null
        runCatching { generationDispatcher?.close() }
        generationDispatcher = null
        loadedMode = null
        activeAffinityMask = 0L
    }
}
