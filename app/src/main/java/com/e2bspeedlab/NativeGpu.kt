package com.e2bspeedlab

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.LogSeverity
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import java.io.File

/**
 * Stable Turbo backend.
 *
 * v0.2.0/0.2.1 tried to benchmark the separately bundled final 0.17.0 C runtime. On the target
 * Android GPU that runtime could inspect the model but the process died during GPU Engine creation.
 * Turbo now deliberately uses the same Maven GPU runtime that already proves ~50 tok/s on-device,
 * and only varies safe knobs around it: max context and caller-thread CPU affinity.
 */
@OptIn(ExperimentalApi::class)
object NativeGpu {

    // Kept for the legacy Service class so old process declarations remain harmless.
    internal const val MSG_BENCH = 101
    internal const val MSG_INSPECT = 102
    internal const val MSG_STARTED = 103
    internal const val MSG_RESULT = 104
    internal const val MSG_INSPECT_RESULT = 105
    internal const val MSG_ERROR = 106
    internal const val KEY_MODEL_PATH = "model_path"
    internal const val KEY_CACHE_DIR = "cache_dir"
    internal const val KEY_CONTEXT = "context"
    internal const val KEY_MTP = "mtp"
    internal const val KEY_FAST_CPUS = "fast_cpus"
    internal const val KEY_PID = "pid"
    internal const val KEY_RESULT = "result"
    internal const val KEY_INFO = "info"
    internal const val KEY_ERROR = "error"

    private const val GENERIC_MODEL_BYTES = 2_588_147_712L
    private const val GPU_MODEL_BYTES = 2_008_432_640L
    private const val SIZE_TOLERANCE_BYTES = 96L * 1024L * 1024L

    @Volatile
    private var applicationContext: Context? = null

    data class Capabilities(
        val runtime: String?,
        val supportsMtp: Boolean?,
        val maxContext: Int?,
        val dynamicContext: Boolean?,
        val minRuntime: String?,
        val backends: List<String>,
        val raw: String,
    )

    data class Result(
        val maxContext: Int,
        val mtpEnabled: Boolean,
        val fastestCpuCount: Int,
        val initSeconds: Double,
        val ttftSeconds: Double,
        val prefillTokenCount: Int,
        val decodeTokenCount: Int,
        val prefillTokensPerSecond: Double,
        val decodeTokensPerSecond: Double,
        val affinityMask: Long,
    )

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun inspect(modelPath: String): Capabilities {
        val file = File(modelPath)
        val bytes = file.length()
        val generic = kotlin.math.abs(bytes - GENERIC_MODEL_BYTES) <= SIZE_TOLERANCE_BYTES
        val dedicatedGpu = kotlin.math.abs(bytes - GPU_MODEL_BYTES) <= SIZE_TOLERANCE_BYTES
        val mtp = when {
            generic -> true
            dedicatedGpu -> false
            else -> null
        }
        val raw = "runtime=0.17.0-alpha1-stable-gpu;mtp=${when (mtp) { true -> "1"; false -> "0"; null -> "unknown" }};backends=GPU"
        return Capabilities(
            runtime = "0.17.0-alpha1 stable GPU",
            supportsMtp = mtp,
            maxContext = null,
            dynamicContext = null,
            minRuntime = null,
            backends = listOf("GPU"),
            raw = raw,
        )
    }

    fun benchmark(
        modelPath: String,
        cacheDir: String,
        maxContext: Int,
        enableMtp: Boolean,
        fastestCpuCount: Int,
    ): Result {
        require(maxContext in 768..4096)
        require(fastestCpuCount == 0 || fastestCpuCount == 2 || fastestCpuCount == 4)
        checkNotNull(applicationContext) { "NativeGpu is not initialized" }

        ExperimentalFlags.enableBenchmark = true
        ExperimentalFlags.enableSpeculativeDecoding = enableMtp
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)

        val cache = File(cacheDir).apply { mkdirs() }
        val originalMask = CpuAffinity.currentMask()
        val activeMask = if (fastestCpuCount > 0) CpuAffinity.pinFast(fastestCpuCount) else originalMask

        val engine = Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                visionBackend = null,
                audioBackend = null,
                maxNumTokens = maxContext,
                cacheDir = cache.absolutePath,
            )
        )

        try {
            engine.initialize()
            engine.createConversation(
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
                    maxOutputToken = 256,
                    thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0),
                    enableResponseFormat = false,
                )
            ).use { conversation ->
                conversation.sendMessage(benchmarkPrompt())
                val info = conversation.getBenchmarkInfo()
                return Result(
                    maxContext = maxContext,
                    mtpEnabled = enableMtp,
                    fastestCpuCount = fastestCpuCount,
                    initSeconds = info.initTimeInSecond,
                    ttftSeconds = info.timeToFirstTokenInSecond,
                    prefillTokenCount = info.lastPrefillTokenCount,
                    decodeTokenCount = info.lastDecodeTokenCount,
                    prefillTokensPerSecond = info.lastPrefillTokensPerSecond,
                    decodeTokensPerSecond = info.lastDecodeTokensPerSecond,
                    affinityMask = activeMask,
                )
            }
        } finally {
            runCatching { engine.close() }
            runCatching { CpuAffinity.restore(originalMask) }
            ExperimentalFlags.enableSpeculativeDecoding = true
        }
    }

    private fun benchmarkPrompt() = buildString {
        repeat(24) {
            append("On-device language models benefit from low latency, efficient memory use, and fast token generation. ")
        }
        append("Explain the performance tradeoffs in detail.")
    }
}
