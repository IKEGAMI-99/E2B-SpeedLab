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

/** Runs final LiteRT-LM 0.17.0 C API work in a linker-isolated :nativegpu process. */
class NativeGpuBenchmarkService : Service() {

    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "E2B-NativeGPU")
    }

    private val messenger = Messenger(object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            if (msg.what != NativeGpu.MSG_BENCH && msg.what != NativeGpu.MSG_INSPECT) return
            val requestWhat = msg.what
            val reply = msg.replyTo ?: return
            // Message objects may be recycled as soon as handleMessage returns. Copy everything the
            // worker needs before handing work to another thread.
            val request = Bundle(msg.data)
            val modelPath = request.getString(NativeGpu.KEY_MODEL_PATH).orEmpty()

            runCatching {
                reply.send(Message.obtain(null, NativeGpu.MSG_STARTED).apply {
                    data = Bundle().apply { putInt(NativeGpu.KEY_PID, Process.myPid()) }
                })
            }

            executor.execute {
                try {
                    when (requestWhat) {
                        NativeGpu.MSG_INSPECT -> {
                            val info = NativeGpuLocal.inspect(modelPath)
                            reply.send(Message.obtain(null, NativeGpu.MSG_INSPECT_RESULT).apply {
                                data = Bundle().apply { putString(NativeGpu.KEY_INFO, info) }
                            })
                        }

                        NativeGpu.MSG_BENCH -> {
                            val raw = NativeGpuLocal.benchmark(
                                modelPath = modelPath,
                                cacheDir = request.getString(NativeGpu.KEY_CACHE_DIR).orEmpty(),
                                maxContext = request.getInt(NativeGpu.KEY_CONTEXT, 2048),
                                enableMtp = request.getBoolean(NativeGpu.KEY_MTP, true),
                                fastestCpuCount = request.getInt(NativeGpu.KEY_FAST_CPUS, 0),
                            )
                            reply.send(Message.obtain(null, NativeGpu.MSG_RESULT).apply {
                                data = Bundle().apply { putDoubleArray(NativeGpu.KEY_RESULT, raw) }
                            })
                        }
                    }
                } catch (t: Throwable) {
                    runCatching {
                        reply.send(Message.obtain(null, NativeGpu.MSG_ERROR).apply {
                            data = Bundle().apply {
                                putString(
                                    NativeGpu.KEY_ERROR,
                                    "${t.javaClass.simpleName}: ${t.message ?: "unknown native GPU error"}",
                                )
                            }
                        })
                    }
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
