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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Client for the Extreme benchmark process.
 *
 * Normal chat uses the Maven LiteRT-LM JNI bridge in the main process, while Extreme mode uses
 * the final 0.17.0 C API in a dedicated :extreme process. Keeping them in separate linker
 * namespaces prevents GPU/OpenCL native dependencies from being mixed across SDK builds.
 */
object ExtremeNative {

    internal const val MSG_RUN = 1
    internal const val MSG_STARTED = 2
    internal const val MSG_RESULT = 3
    internal const val MSG_ERROR = 4

    internal const val KEY_MODEL_PATH = "model_path"
    internal const val KEY_CACHE_DIR = "cache_dir"
    internal const val KEY_STEPS = "steps"
    internal const val KEY_MTP = "mtp"
    internal const val KEY_PID = "pid"
    internal const val KEY_RESULT = "result"
    internal const val KEY_ERROR = "error"

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val RUN_TIMEOUT_SECONDS = 180L

    @Volatile
    private var applicationContext: Context? = null

    data class Result(
        val decodeStepsPerSync: Int,
        val mtpEnabled: Boolean,
        val initSeconds: Double,
        val ttftSeconds: Double,
        val prefillTokenCount: Int,
        val decodeTokenCount: Int,
        val prefillTokensPerSecond: Double,
        val decodeTokensPerSecond: Double,
    )

    fun init(context: Context) {
        applicationContext = context.applicationContext
    }

    fun benchmark(
        modelPath: String,
        cacheDir: String,
        decodeStepsPerSync: Int,
        enableMtp: Boolean = true,
    ): Result {
        require(decodeStepsPerSync in 1..32)
        val context = checkNotNull(applicationContext) { "ExtremeNative is not initialized" }

        val connected = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val replyThread = HandlerThread("E2B-Extreme-Reply").apply { start() }

        var service: Messenger? = null
        var remotePid = -1
        var rawResult: DoubleArray? = null
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
                    MSG_ERROR -> {
                        remoteError = msg.data.getString(KEY_ERROR) ?: "Unknown Extreme process error"
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
                Intent(context, ExtremeBenchmarkService::class.java),
                connection,
                Context.BIND_AUTO_CREATE,
            )
            check(bound) { "Could not start isolated Extreme benchmark service" }
            check(connected.await(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                "Timed out connecting to Extreme benchmark process"
            }
            check(!disconnected) { "Extreme benchmark process disconnected before starting" }

            val request = Message.obtain(null, MSG_RUN).apply {
                data = Bundle().apply {
                    putString(KEY_MODEL_PATH, modelPath)
                    putString(KEY_CACHE_DIR, cacheDir)
                    putInt(KEY_STEPS, decodeStepsPerSync)
                    putBoolean(KEY_MTP, enableMtp)
                }
                replyTo = replyMessenger
            }
            checkNotNull(service) { "Extreme benchmark service binder is unavailable" }.send(request)

            if (!completed.await(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                if (remotePid > 0) runCatching { Process.killProcess(remotePid) }
                throw RuntimeException(
                    "Extreme benchmark timed out after ${RUN_TIMEOUT_SECONDS}s at SYNC $decodeStepsPerSync"
                )
            }
            remoteError?.let { throw RuntimeException(it) }
            check(!disconnected || rawResult != null) { "Extreme benchmark process terminated unexpectedly" }

            val raw = checkNotNull(rawResult) { "Extreme benchmark returned no result" }
            check(raw.size == 6) { "Unexpected native benchmark result size: ${raw.size}" }
            return Result(
                decodeStepsPerSync = decodeStepsPerSync,
                mtpEnabled = enableMtp,
                initSeconds = raw[0],
                ttftSeconds = raw[1],
                prefillTokenCount = raw[2].toInt(),
                decodeTokenCount = raw[3].toInt(),
                prefillTokensPerSecond = raw[4],
                decodeTokensPerSecond = raw[5],
            )
        } finally {
            if (bound) runCatching { context.unbindService(connection) }
            replyThread.quitSafely()
        }
    }
}
