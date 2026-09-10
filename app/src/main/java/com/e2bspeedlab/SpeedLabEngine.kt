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
        const val BENCH_SAMPLES_PER_MODE = 3
        const val BENCH_WARMUP_RUNS = 2
        const val BENCH_MEASURED_RUNS = BENCH_SAMPLES_PER_MODE * 2
    }

    data class BenchStats(val samples: List<BenchmarkInfo>) {
        init {
            require(samples.isNotEmpty()) { "Benchmark samples must not be empty" }
        }

        val decodeMedian: Double
            get() = median(samples.map { it.lastDecodeTokensPerSecond })
        val decodeMin: Double
            get() = samples.minOf { it.lastDecodeTokensPerSecond }
        val decodeMax: Double
            get() = samples.maxOf { it.lastDecodeTokensPerSecond }

        val prefillMedian: Double
            get() = median(samples.map { it.lastPrefillTokensPerSecond })
        val prefillMin: Double
            get() = samples.minOf { it.lastPrefillTokensPerSecond }
        val prefillMax: Double
            get() = samples.maxOf { it.lastPrefillTokensPerSecond }

        val ttftMedianSeconds: Double
            get() = median(samples.map { it.timeToFirstTokenInSecond })
        val ttftMinSeconds: Double
            get() = samples.minOf { it.timeToFirstTokenInSecond }
        val ttftMaxSeconds: Double
            get() = samples.maxOf { it.timeToFirstTokenInSecond }

        val initMedianSeconds: Double
            get() = median(samples.map { it.initTimeInSecond })
    }

    data class MtpComparison(
        val mtpOff: BenchStats,
        val mtpOn: BenchStats,
        val warmupOff: BenchmarkInfo,
        val warmupOn: BenchmarkInfo,
        val measuredOrder: List<Boolean>,
    ) {
        val decodeSpeedup: Double
            get() = if (mtpOff.decodeMedian > 0.0) mtpOn.decodeMedian / mtpOff.decodeMedian else Double.NaN

        val prefillSpeedup: Double
            get() = if (mtpOff.prefillMedian > 0.0) mtpOn.prefillMedian / mtpOff.prefillMedian else Double.NaN

        val ttftRatio: Double
            get() = if (mtpOff.ttftMedianSeconds > 0.0) mtpOn.ttftMedianSeconds / mtpOff.ttftMedianSeconds else Double.NaN
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    val isLoaded: Boolean
        get() = engine != null && conversation != null

    suspend fun load(modelPath: String): Double = withContext(Dispatchers.Default) {
        closeInternal()
        configureRuntimeFlags(enableMtp = true)
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cacheDirectory.mkdirs()

        val started = System.nanoTime()
        val newEngine = createGpuEngine(modelPath)

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
     * A less biased MTP A/B benchmark.
     *
     * First, one OFF and one ON run are used only as warmups and discarded. Then six measured runs
     * are performed in a near-balanced ABBAAB order: OFF, ON, ON, OFF, OFF, ON. This gives three
     * samples per mode while reducing first-run cache effects and linear thermal/order bias.
     * Engines are strictly sequential and never coexist in memory.
     */
    suspend fun benchmarkMtpComparison(
        modelPath: String,
        onStage: (String) -> Unit = {},
    ): MtpComparison = withContext(Dispatchers.Default) {
        closeInternal()
        cacheDirectory.mkdirs()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        try {
            onStage("WARMUP 1/2 · MTP OFF")
            val warmupOff = benchmarkOnce(modelPath, enableMtp = false)

            onStage("WARMUP 2/2 · MTP ON")
            val warmupOn = benchmarkOnce(modelPath, enableMtp = true)

            // Three measured samples per mode. Position sums are almost equal (OFF=10, ON=11),
            // which limits simple linear thermal drift from favoring one mode too strongly.
            val measuredOrder = listOf(false, true, true, false, false, true)
            val offSamples = ArrayList<BenchmarkInfo>(BENCH_SAMPLES_PER_MODE)
            val onSamples = ArrayList<BenchmarkInfo>(BENCH_SAMPLES_PER_MODE)

            measuredOrder.forEachIndexed { index, mtpEnabled ->
                val mode = if (mtpEnabled) "ON" else "OFF"
                onStage("${index + 1}/$BENCH_MEASURED_RUNS · MTP $mode")
                val info = benchmarkOnce(modelPath, enableMtp = mtpEnabled)
                if (mtpEnabled) onSamples += info else offSamples += info
            }

            MtpComparison(
                mtpOff = BenchStats(offSamples),
                mtpOn = BenchStats(onSamples),
                warmupOff = warmupOff,
                warmupOn = warmupOn,
                measuredOrder = measuredOrder,
            )
        } finally {
            // Normal chat mode is always GPU + MTP after benchmarking.
            configureRuntimeFlags(enableMtp = true)
        }
    }

    private fun benchmarkOnce(modelPath: String, enableMtp: Boolean): BenchmarkInfo {
        configureRuntimeFlags(enableMtp)
        val benchEngine = createGpuEngine(modelPath)

        try {
            benchEngine.initialize()
            benchEngine.createConversation(fastConversationConfig(BENCH_DECODE_TOKENS)).use { benchConversation ->
                benchConversation.sendMessage(benchmarkPrompt())
                return benchConversation.getBenchmarkInfo()
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

    /** These flags are sampled when the native engine is created. */
    private fun configureRuntimeFlags(enableMtp: Boolean) {
        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = enableMtp
    }

    private fun createGpuEngine(modelPath: String) = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = MAX_CONTEXT_TOKENS,
            cacheDir = cacheDirectory.absolutePath,
        )
    )

    private fun benchmarkPrompt() = buildString {
        repeat(24) {
            append("On-device language models benefit from low latency, efficient memory use, and fast token generation. ")
        }
        append("Explain the performance tradeoffs in detail.")
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

private fun median(values: List<Double>): Double {
    require(values.isNotEmpty())
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}
