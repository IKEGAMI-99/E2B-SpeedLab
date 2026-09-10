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
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
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

    data class TurboSample(
        val info: BenchmarkInfo,
        val maxContext: Int,
        val fastestCpuCount: Int,
        val affinityMask: Long,
    )

    data class TurboSweep(
        val warmup: TurboSample,
        val contextRuns: List<TurboSample>,
        val affinityRuns: List<TurboSample>,
        val best: TurboSample,
        val mtpOff: TurboSample,
    ) {
        val mtpSpeedup: Double
            get() = if (mtpOff.info.lastDecodeTokensPerSecond > 0.0) {
                best.info.lastDecodeTokensPerSecond / mtpOff.info.lastDecodeTokensPerSecond
            } else {
                Double.NaN
            }
    }

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var generationDispatcher: ExecutorCoroutineDispatcher? = null
    private var activeContextTokens: Int = MAX_CONTEXT_TOKENS
    private var activeFastestCpuCount: Int = 0
    private var activeAffinityMask: Long = 0L

    val isLoaded: Boolean
        get() = engine != null && conversation != null

    val loadedContextTokens: Int
        get() = activeContextTokens

    val loadedFastestCpuCount: Int
        get() = activeFastestCpuCount

    val loadedAffinityMask: Long
        get() = activeAffinityMask

    suspend fun load(
        modelPath: String,
        maxContext: Int = MAX_CONTEXT_TOKENS,
        fastestCpuCount: Int = 0,
    ): Double = withContext(Dispatchers.Default) {
        require(maxContext in 768..4096) { "Context must be between 768 and 4096" }
        require(fastestCpuCount == 0 || fastestCpuCount == 2 || fastestCpuCount == 4) {
            "CPU mode must be ALL, FAST2, or FAST4"
        }

        closeInternal()
        configureRuntimeFlags(enableMtp = true)
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        cacheDirectory.mkdirs()

        val originalMask = CpuAffinity.currentMask()
        val tunedMask = if (fastestCpuCount > 0) CpuAffinity.pinFast(fastestCpuCount) else originalMask
        val started = System.nanoTime()
        val newEngine = createGpuEngine(modelPath, maxContext)

        try {
            // Engine/GPU worker creation happens while the caller is pinned. Newly-created Linux
            // threads inherit the creator's affinity mask, while the coroutine worker is restored
            // before returning to the shared Dispatchers.Default pool.
            newEngine.initialize()
            val newConversation = newEngine.createConversation(fastConversationConfig(MAX_OUTPUT_TOKENS))
            engine = newEngine
            conversation = newConversation
            activeContextTokens = maxContext
            activeFastestCpuCount = fastestCpuCount
            activeAffinityMask = tunedMask

            // Normal Generate gets its own single host thread when Turbo CPU affinity is active.
            // Keeping streaming on one Java thread is important: sched_setaffinity is thread-local,
            // so a regular coroutine pool could resume on a different thread and silently lose FAST2.
            generationDispatcher = if (fastestCpuCount > 0) {
                Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "E2B-Generate-FAST$fastestCpuCount").apply {
                        priority = Thread.MAX_PRIORITY
                    }
                }.asCoroutineDispatcher()
            } else {
                null
            }
        } catch (t: Throwable) {
            runCatching { newEngine.close() }
            runCatching { generationDispatcher?.close() }
            generationDispatcher = null
            throw t
        } finally {
            runCatching { CpuAffinity.restore(originalMask) }
        }

        (System.nanoTime() - started) / 1_000_000_000.0
    }

    suspend fun generate(
        prompt: String,
        onChunk: (String) -> Unit,
    ): BenchmarkInfo {
        val dispatcher = generationDispatcher ?: Dispatchers.Default
        return withContext(dispatcher) {
            val active = conversation ?: error("GPU engine is not loaded")
            val originalMask = if (activeFastestCpuCount > 0) CpuAffinity.currentMask() else 0L

            try {
                if (activeFastestCpuCount > 0) {
                    // Apply the Turbo winner to the whole host-side generation path, not only to
                    // Engine initialization. For the verified profile this is FAST2 / mask 0xC0.
                    activeAffinityMask = CpuAffinity.pinFast(activeFastestCpuCount)
                }

                active.sendMessageAsync(
                    text = prompt,
                    maxOutputToken = MAX_OUTPUT_TOKENS,
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                ).collect { message ->
                    onChunk(message.toString())
                }

                active.getBenchmarkInfo()
            } finally {
                if (activeFastestCpuCount > 0) {
                    runCatching { CpuAffinity.restore(originalMask) }
                }
            }
        }
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
            configureRuntimeFlags(enableMtp = true)
        }
    }

    /**
     * Safe Turbo sweep that stays on the already-proven Maven GPU runtime.
     * No second LiteRT-LM C runtime is loaded, so there is no linker/GPU-driver ABI experiment here.
     */
    suspend fun benchmarkTurboSweep(
        modelPath: String,
        contexts: IntArray,
        onStage: (String) -> Unit = {},
    ): TurboSweep = withContext(Dispatchers.Default) {
        closeInternal()
        cacheDirectory.mkdirs()
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val safeContexts = contexts.filter { it in 768..4096 }.distinct()
        require(safeContexts.isNotEmpty()) { "No valid Turbo context candidates" }

        try {
            val warmContext = safeContexts.minBy { kotlin.math.abs(it - MAX_CONTEXT_TOKENS) }
            onStage("TURBO WARMUP · CTX $warmContext")
            val warmup = benchmarkOnceTuned(
                modelPath = modelPath,
                enableMtp = true,
                maxContext = warmContext,
                fastestCpuCount = 0,
            )

            val contextRuns = ArrayList<TurboSample>()
            safeContexts.forEachIndexed { index, context ->
                onStage("TURBO CTX ${index + 1}/${safeContexts.size} · $context")
                runCatching {
                    benchmarkOnceTuned(modelPath, true, context, 0)
                }.onSuccess { contextRuns += it }
            }
            check(contextRuns.isNotEmpty()) { "Every context candidate failed" }

            val bestContextRun = contextRuns.maxBy { it.info.lastDecodeTokensPerSecond }
            val bestContext = bestContextRun.maxContext

            val affinityRuns = ArrayList<TurboSample>()
            affinityRuns += bestContextRun
            intArrayOf(4, 2).forEachIndexed { index, fastCpus ->
                onStage("TURBO CPU ${index + 1}/2 · FAST$fastCpus")
                runCatching {
                    benchmarkOnceTuned(modelPath, true, bestContext, fastCpus)
                }.onSuccess { affinityRuns += it }
            }

            val best = affinityRuns.maxBy { it.info.lastDecodeTokensPerSecond }
            onStage("TURBO VERIFY · MTP OFF")
            val mtpOff = benchmarkOnceTuned(
                modelPath = modelPath,
                enableMtp = false,
                maxContext = best.maxContext,
                fastestCpuCount = best.fastestCpuCount,
            )

            TurboSweep(
                warmup = warmup,
                contextRuns = contextRuns,
                affinityRuns = affinityRuns,
                best = best,
                mtpOff = mtpOff,
            )
        } finally {
            configureRuntimeFlags(enableMtp = true)
        }
    }

    private fun benchmarkOnce(modelPath: String, enableMtp: Boolean): BenchmarkInfo =
        benchmarkOnceTuned(
            modelPath = modelPath,
            enableMtp = enableMtp,
            maxContext = MAX_CONTEXT_TOKENS,
            fastestCpuCount = 0,
        ).info

    private fun benchmarkOnceTuned(
        modelPath: String,
        enableMtp: Boolean,
        maxContext: Int,
        fastestCpuCount: Int,
    ): TurboSample {
        configureRuntimeFlags(enableMtp)

        val originalMask = CpuAffinity.currentMask()
        val activeMask = if (fastestCpuCount > 0) {
            CpuAffinity.pinFast(fastestCpuCount)
        } else {
            originalMask
        }

        val benchEngine = createGpuEngine(modelPath, maxContext)
        try {
            benchEngine.initialize()
            benchEngine.createConversation(fastConversationConfig(BENCH_DECODE_TOKENS)).use { benchConversation ->
                benchConversation.sendMessage(benchmarkPrompt())
                return TurboSample(
                    info = benchConversation.getBenchmarkInfo(),
                    maxContext = maxContext,
                    fastestCpuCount = fastestCpuCount,
                    affinityMask = activeMask,
                )
            }
        } finally {
            runCatching { benchEngine.close() }
            runCatching { CpuAffinity.restore(originalMask) }
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

    private fun createGpuEngine(modelPath: String, maxContext: Int) = Engine(
        EngineConfig(
            modelPath = modelPath,
            backend = Backend.GPU(),
            visionBackend = null,
            audioBackend = null,
            maxNumTokens = maxContext,
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
        runCatching { generationDispatcher?.close() }
        generationDispatcher = null
        activeContextTokens = MAX_CONTEXT_TOKENS
        activeFastestCpuCount = 0
        activeAffinityMask = 0L
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
