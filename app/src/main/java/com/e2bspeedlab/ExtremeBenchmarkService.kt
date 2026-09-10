package com.e2bspeedlab

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import java.io.File
import java.util.concurrent.Executors

/** Executes one Artisan-only native benchmark request in the dedicated :extreme process. */
class ExtremeBenchmarkService : Service() {

    companion object {
        private const val GENERIC_MODEL_BYTES = 2_588_147_712L
        private const val GPU_MODEL_BYTES = 2_008_432_640L
        private const val SIZE_TOLERANCE_BYTES = 96L * 1024L * 1024L
    }

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "E2B-Extreme-Native")
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != ExtremeNative.MSG_RUN) return

            val reply = msg.replyTo ?: return
            val request = msg.data
            val modelPath = request.getString(ExtremeNative.KEY_MODEL_PATH).orEmpty()
            val cacheDir = request.getString(ExtremeNative.KEY_CACHE_DIR).orEmpty()
            val steps = request.getInt(ExtremeNative.KEY_STEPS, 1)
            val mtp = request.getBoolean(ExtremeNative.KEY_MTP, true)

            runCatching {
                reply.send(Message.obtain(null, ExtremeNative.MSG_STARTED).apply {
                    data = Bundle().apply { putInt(ExtremeNative.KEY_PID, Process.myPid()) }
                })
            }

            executor.execute {
                try {
                    val modelBytes = File(modelPath).length()
                    if (nearSize(modelBytes, GENERIC_MODEL_BYTES) || nearSize(modelBytes, GPU_MODEL_BYTES)) {
                        throw IllegalArgumentException(
                            "Artisan SYNC tuning is not valid for the public generic or dedicated -gpu E2B package. " +
                                "Use Backend.GPU() + MTP instead."
                        )
                    }

                    val raw = ExtremeNativeLocal.benchmark(modelPath, cacheDir, steps, mtp)
                    reply.send(Message.obtain(null, ExtremeNative.MSG_RESULT).apply {
                        data = Bundle().apply { putDoubleArray(ExtremeNative.KEY_RESULT, raw) }
                    })
                } catch (t: Throwable) {
                    runCatching {
                        reply.send(Message.obtain(null, ExtremeNative.MSG_ERROR).apply {
                            data = Bundle().apply {
                                putString(
                                    ExtremeNative.KEY_ERROR,
                                    "${t.javaClass.simpleName}: ${t.message ?: "unknown native error"}",
                                )
                            }
                        })
                    }
                } finally {
                    stopSelf()
                }
            }
        }
    })

    private fun nearSize(actual: Long, expected: Long): Boolean =
        kotlin.math.abs(actual - expected) <= SIZE_TOLERANCE_BYTES

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
