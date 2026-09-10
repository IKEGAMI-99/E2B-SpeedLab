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
import com.google.ai.edge.litertlm.benchmark as liteRtBenchmark
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
        const val BENCH_PREFILL_TOKENS = 256
        const val BENCH_DECODE_TOKENS = 256
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isLoaded: Boolean
        get() = engine != null && conversation != null

    suspend fun load(modelPath: String): Double = withContext(Dispatchers.Default) {
        closeInternal()

        // Gemma 4 MTP is exposed by LiteRT-LM as speculative decoding.
        ExperimentalFlags.enableSpeculativeDecoding = true
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
            val newConversation = newEngine.createConversation(
                ConversationConfig(
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
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    enableResponseFormat = false,
                    enableSpeculativeDecoding = true,
                )
            )
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
     * Runs LiteRT-LM's native benchmark path. A live chat engine should be closed first to avoid
     * keeping two copies of E2B resident at once.
     */
    suspend fun benchmark(modelPath: String): BenchmarkInfo = withContext(Dispatchers.Default) {
        closeInternal()
        ExperimentalFlags.enableSpeculativeDecoding = true
        cacheDirectory.mkdirs()

        liteRtBenchmark(
            modelPath = modelPath,
            backend = Backend.GPU(),
            prefillTokens = BENCH_PREFILL_TOKENS,
            decodeTokens = BENCH_DECODE_TOKENS,
            cacheDir = cacheDirectory.absolutePath,
            prompt = "Explain in one paragraph why low latency matters for an on-device language model.",
        )
    }

    suspend fun resetConversation() = withContext(Dispatchers.Default) {
        val activeEngine = engine ?: return@withContext
        runCatching { conversation?.close() }
        conversation = activeEngine.createConversation(
            ConversationConfig(
                tools = emptyList(),
                automaticToolCalling = false,
                channels = emptyList(),
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                prefillPrefaceOnInit = false,
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                enableResponseFormat = false,
                enableSpeculativeDecoding = true,
            )
        )
    }

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        runCatching { conversation?.close() }
        conversation = null
        runCatching { engine?.close() }
        engine = null
    }
}
