package com.tercuman.keyboard

import android.app.Application
import android.content.ComponentCallbacks2
import com.tercuman.keyboard.engine.Translator

class TercumanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        if (level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            Translator.get(this).onLowMemory()
        }
    }
}
