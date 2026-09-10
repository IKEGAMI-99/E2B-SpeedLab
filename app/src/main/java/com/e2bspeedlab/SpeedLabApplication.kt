package com.e2bspeedlab

import android.app.Application

class SpeedLabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ExtremeNative.init(this)
        NativeGpu.init(this)
    }
}
