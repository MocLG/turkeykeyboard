package com.tercuman.keyboard

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashLog {
    private const val FILE_NAME = "last_error.txt"

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, "Crash in thread ${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(context: Context, what: String, error: Throwable) {
        Log.e("Tercuman", what, error)
        runCatching {
            val sw = StringWriter()
            error.printStackTrace(PrintWriter(sw))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "$time\n$what\nApp ${BuildInfo.version(context)}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}\n\n$sw"
            File(context.applicationContext.filesDir, FILE_NAME).writeText(text)
        }
    }

    fun recordText(context: Context, what: String) {
        Log.w("Tercuman", what)
        runCatching {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "$time\n$what\nApp ${BuildInfo.version(context)}, Android ${Build.VERSION.RELEASE}, ${Build.MODEL}\n"
            File(context.applicationContext.filesDir, FILE_NAME).writeText(text)
        }
    }

    fun read(context: Context): String? =
        runCatching { File(context.filesDir, FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull()

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }

    private object BuildInfo {
        fun version(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
}
