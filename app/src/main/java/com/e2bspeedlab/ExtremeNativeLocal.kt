package com.e2bspeedlab

/** Native bridge loaded only inside the dedicated :extreme process. */
internal object ExtremeNativeLocal {
    init {
        // Do not load litertlm_jni here. The native shim dlopens the bundled final 0.17.0
        // liblitert-lm.so so Extreme mode has a clean native dependency graph.
        System.loadLibrary("e2b_extreme")
    }

    fun benchmark(
        modelPath: String,
        cacheDir: String,
        decodeStepsPerSync: Int,
        enableMtp: Boolean,
    ): DoubleArray = nativeBenchmark(modelPath, cacheDir, decodeStepsPerSync, enableMtp)

    private external fun nativeBenchmark(
        modelPath: String,
        cacheDir: String,
        decodeStepsPerSync: Int,
        enableMtp: Boolean,
    ): DoubleArray
}
