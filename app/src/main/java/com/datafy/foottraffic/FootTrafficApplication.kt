// FootTrafficApplication.kt
package com.datafy.foottraffic

import android.app.Application
import org.opencv.android.OpenCVLoader
import timber.log.Timber

class FootTrafficApplication : Application() {

    companion object {
        lateinit var instance: FootTrafficApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Timber logging
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Timber.plant(Timber.DebugTree())
        }

        // Initialize OpenCV (optional - only if you plan to use OpenCV features)
        if (!OpenCVLoader.initDebug()) {
            Timber.e("Unable to load OpenCV!")
        } else {
            Timber.d("OpenCV loaded successfully")
        }

        Timber.d("FootTrafficApplication initialized")
    }
}

