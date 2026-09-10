package com.e2bspeedlab

/** Lightweight JNI helper that only changes affinity of the current calling thread. */
internal object CpuAffinity {
    init {
        System.loadLibrary("e2b_affinity")
    }

    fun currentMask(): Long = nativeCurrentMask()

    /** Pins the current thread to the fastest [count] CPUs and returns the resulting mask. */
    fun pinFast(count: Int): Long {
        require(count == 2 || count == 4)
        return nativePinFast(count)
    }

    fun restore(mask: Long) {
        if (mask != 0L) nativeSetMask(mask)
    }

    private external fun nativeCurrentMask(): Long
    private external fun nativePinFast(count: Int): Long
    private external fun nativeSetMask(mask: Long)
}
