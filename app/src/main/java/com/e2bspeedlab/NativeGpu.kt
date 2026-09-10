package com.e2bspeedlab

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Client for final LiteRT-LM 0.17.0 regular-GPU work in the isolated :nativegpu process. */
object NativeGpu {

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

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val INSPECT_TIMEOUT_SECONDS = 30L
    private const val BENCH_TIMEOUT_SECONDS = 180L
    private const val STAGE_FILE_NAME = "native_gpu_stage.txt"

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
        val response = transact(
            what = MSG_INSPECT,
            timeoutSeconds = INSPECT_TIMEOUT_SECONDS,
            cacheDir = null,
        ) { bundle ->
            bundle.putString(KEY_MODEL_PATH, modelPath)
        }
        val raw = response.info ?: error("Native GPU inspection returned no capability data")
        val values = raw.split(';')
            .mapNotNull { item ->
                val pos = item.indexOf('=')
                if (pos <= 0) null else item.substring(0, pos) to item.substring(pos + 1)
            }
            .toMap()
        return Capabilities(
            runtime = values["runtime"],
            supportsMtp = values["mtp"]?.let { it == "1" },
            maxContext = values["max_context"]?.toIntOrNull(),
            dynamicContext = values["dynamic"]?.let { it == "1" },
            minRuntime = values["min_runtime"],
            backends = values["backends"].orEmpty().split(',').filter { it.isNotBlank() },
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
        require(maxContext in 768..32768)
        require(fastestCpuCount == 0 || fastestCpuCount == 2 || fastestCpuCount == 4)
        val response = transact(
            what = MSG_BENCH,
            timeoutSeconds = BENCH_TIMEOUT_SECONDS,
            cacheDir = cacheDir,
        ) { bundle ->
            bundle.putString(KEY_MODEL_PATH, modelPath)
            bundle.putString(KEY_CACHE_DIR, cacheDir)
            bundle.putInt(KEY_CONTEXT, maxContext)
            bundle.putBoolean(KEY_MTP, enableMtp)
            bundle.putInt(KEY_FAST_CPUS, fastestCpuCount)
        }
        val raw = checkNotNull(response.rawResult) { "Native GPU benchmark returned no result" }
        check(raw.size == 7) { "Unexpected native GPU result size: ${raw.size}" }
        return Result(
            maxContext = maxContext,
            mtpEnabled = enableMtp,
            fastestCpuCount = fastestCpuCount,
            initSeconds = raw[0],
            ttftSeconds = raw[1],
            prefillTokenCount = raw[2].toInt(),
            decodeTokenCount = raw[3].toInt(),
            prefillTokensPerSecond = raw[4],
            decodeTokensPerSecond = raw[5],
            affinityMask = raw[6].toLong(),
        )
    }

    private data class Response(
        val rawResult: DoubleArray?,
        val info: String?,
    )

    private fun transact(
        what: Int,
        timeoutSeconds: Long,
        cacheDir: String?,
        fill: (Bundle) -> Unit,
    ): Response {
        val context = checkNotNull(applicationContext) { "NativeGpu is not initialized" }
        cacheDir?.let { File(it, STAGE_FILE_NAME).delete() }

        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val replyThread = HandlerThread("E2B-NativeGPU-Reply").apply { start() }

        var service: Messenger? = null
        var remotePid = -1
        var rawResult: DoubleArray? = null
        var info: String? = null
        var remoteError: String? = null
        var disconnected = false

        val replyMessenger = Messenger(object : Handler(replyThread.looper) {
            override fun handleMessage(msg: Message) {
                when (msg.what) {
                    MSG_STARTED -> remotePid = msg.data.getInt(KEY_PID, -1)
                    MSG_RESULT -> {
                        rawResult = msg.data.getDoubleArray(KEY_RESULT)
                        completed.countDown()
                    }
                    MSG_INSPECT_RESULT -> {
                        info = msg.data.getString(KEY_INFO)
                        completed.countDown()
                    }
                    MSG_ERROR -> {
                        remoteError = msg.data.getString(KEY_ERROR) ?: "Unknown native GPU process error"
                        completed.countDown()
                    }
                }
            }
        })

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = binder?.let(::Messenger)
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                disconnected = true
                completed.countDown()
                connected.countDown()
            }
        }

        var bound = false
        try {
            bound = context.bindService(
                Intent(context, NativeGpuBenchmarkService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            check(bound) { "Could not start isolated native GPU service" }
            check(connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out connecting to native GPU process"
            }
            check(!disconnected) { "Native GPU process disconnected before starting" }

            val request = Message.obtain(null, what).apply {
                data = Bundle().also(fill)
                replyTo = replyMessenger
            }
            checkNotNull(service) { "Native GPU service binder is unavailable" }.send(request)

            if (!completed.await(timeoutSeconds, TimeUnit.SECONDS)) {
                val stage = cacheDir?.let {
                    runCatching { File(it, STAGE_FILE_NAME).readText().trim() }.getOrNull()
                }.takeUnless { it.isNullOrBlank() } ?: "UNKNOWN"
                if (remotePid > 0) runCatching { Process.killProcess(remotePid) }
                throw RuntimeException(
                    "Native GPU operation timed out after ${timeoutSeconds}s (stage: $stage)"
                )
            }
            remoteError?.let { throw RuntimeException(it) }
            check(!disconnected || rawResult != null || info != null) {
                "Native GPU process terminated unexpectedly"
            }
            return Response(rawResult = rawResult, info = info)
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            replyThread.quitSafely()
        }
    }
}
