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
import java.util.concurrent.Executors

/**
 * Executes exactly one Extreme native benchmark request in the :extreme process.
 * The process is intentionally separate from the normal Maven LiteRT-LM JNI process.
 */
class ExtremeBenchmarkService : Service() {

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

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}
