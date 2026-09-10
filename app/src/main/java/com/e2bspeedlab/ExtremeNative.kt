package com.e2bspeedlab

/**
 * Thin JNI bridge into LiteRT-LM's exported C API.
 *
 * The public Kotlin EngineConfig does not currently expose GPU
 * num_decode_steps_per_sync. LiteRT-LM's C API does, so SpeedLab loads the
 * already-packaged liblitertlm_jni.so and changes only that native GPU knob.
 */
object ExtremeNative {

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

    init {
        // Ensure LiteRT-LM is in the app linker namespace before the shim calls dlopen/dlsym.
        System.loadLibrary("litertlm_jni")
        System.loadLibrary("e2b_extreme")
    }

    fun benchmark(
        modelPath: String,
        cacheDir: String,
        decodeStepsPerSync: Int,
        enableMtp: Boolean = true,
    ): Result {
        require(decodeStepsPerSync in 1..32)
        val raw = nativeBenchmark(modelPath, cacheDir, decodeStepsPerSync, enableMtp)
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
    }

    private external fun nativeBenchmark(
        modelPath: String,
        cacheDir: String,
        decodeStepsPerSync: Int,
        enableMtp: Boolean,
    ): DoubleArray
}
