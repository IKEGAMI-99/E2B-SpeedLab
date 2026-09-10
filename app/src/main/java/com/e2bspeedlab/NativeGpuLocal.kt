package com.e2bspeedlab

/**
 * Final LiteRT-LM 0.17.0 C API bridge.
 *
 * This object is loaded only inside the dedicated :nativegpu process so the final C runtime never
 * shares a linker namespace with the Maven 0.17.0-alpha1 JNI used by normal chat.
 */
internal object NativeGpuLocal {
    init {
        System.loadLibrary("e2b_nativegpu")
    }

    fun inspect(modelPath: String): String = nativeInspect(modelPath)

    fun benchmark(
        modelPath: String,
        cacheDir: String,
        maxContext: Int,
        enableMtp: Boolean,
        fastestCpuCount: Int,
    ): DoubleArray = nativeBenchmark(
        modelPath,
        cacheDir,
        maxContext,
        enableMtp,
        fastestCpuCount,
    )

    private external fun nativeInspect(modelPath: String): String

    private external fun nativeBenchmark(
        modelPath: String,
        cacheDir: String,
        maxContext: Int,
        enableMtp: Boolean,
        fastestCpuCount: Int,
    ): DoubleArray
}
