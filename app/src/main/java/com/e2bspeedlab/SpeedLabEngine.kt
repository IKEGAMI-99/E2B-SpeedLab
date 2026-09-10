package com.e2bspeedlab

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.BenchmarkInfo
import com.google.ai.edge.litertlm.Conversation
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext

/**
 * SpeedLab deliberately exposes only the fastest intended path:
 * Gemma 4 E2B -> LiteRT-LM -> GPU -> MTP.
 */
@OptIn(ExperimentalApi::class)
class SpeedLabEngine(private val cacheDirectory: File) : Closeable {

    companion object {
        const val MAX_CONTEXT_TOKENS = 2048
        const val MAX_OUTPUT_TOKENS = 512
        const val BENCH_DECODE_TOKENS = 256
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isLoaded: Boolean
        get() = engine != null && conversation != null

    suspend fun load(modelPath: String): Double = withContext(Dispatchers.Default) {
        closeInternal()
        enableFastRuntimeFlags()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cacheDirectory.mkdirs()

        val started = System.nanoTime()
        val newEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            )
        )

        try {
            newEngine.initialize()
            val newConversation = newEngine.createConversation(fastConversationConfig(MAX_OUTPUT_TOKENS))
            engine = newEngine
            conversation = newConversation
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            throw t
        }

        (System.nanoTime() - started) / 1_000_000_000.0
    }

    suspend fun generate(
        prompt: String,
        onChunk: (String) -> Unit,
    ): BenchmarkInfo = withContext(Dispatchers.Default) {
        val active = conversation ?: error("GPU engine is not loaded")

        active.sendMessageAsync(
            text = prompt,
            maxOutputToken = MAX_OUTPUT_TOKENS,
            thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
        ).collect { message ->
            onChunk(message.toString())
        }

        active.getBenchmarkInfo()
    }

    /**
     * Benchmarks the exact same GPU + MTP path used by chat and returns native LiteRT-LM metrics.
     */
    suspend fun benchmark(modelPath: String): BenchmarkInfo = withContext(Dispatchers.Default) {
        closeInternal()
        enableFastRuntimeFlags()
        cacheDirectory.mkdirs()

        val benchEngine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = MAX_CONTEXT_TOKENS,
                cacheDir = cacheDirectory.absolutePath,
            )
        )

        try {
            benchEngine.initialize()
            benchEngine.createConversation(fastConversationConfig(BENCH_DECODE_TOKENS)).use { benchConversation ->
                val prompt = buildString {
                    repeat(24) {
                        append("On-device language models benefit from low latency, efficient memory use, and fast token generation. ")
                    }
                    append("Explain the performance tradeoffs in detail.")
                }
                benchConversation.sendMessage(prompt)
                benchConversation.getBenchmarkInfo()
            }
        } finally {
            runCatching { benchEngine.close() }
        }
    }

    suspend fun resetConversation() = withContext(Dispatchers.Default) {
        val activeEngine = engine ?: return@withContext
        runCatching { conversation?.close() }
        conversation = activeEngine.createConversation(fastConversationConfig(MAX_OUTPUT_TOKENS))
    }

    override fun close() {
        closeInternal()
    }

    /** These flags are sampled when Engine.initialize() creates the native EngineSettings. */
    private fun enableFastRuntimeFlags() {
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = true
    }

    private fun fastConversationConfig(maxOutput: Int) = ConversationConfig(
        tools = emptyList(),
        automaticToolCalling = false,
        channels = emptyList(),
        samplerConfig = SamplerConfig(
            topK = 1,
            topP = 1.0,
            temperature = 0.0,
            seed = 0,
        ),
        prefillPrefaceOnInit = false,
        maxOutputToken = maxOutput,
        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
        enableResponseFormat = false,
    )

    private fun closeInternal() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }
}
