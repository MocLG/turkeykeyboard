package com.tercuman.keyboard

import android.content.Context
import android.content.SharedPreferences
import com.tercuman.keyboard.engine.Direction
import com.tercuman.keyboard.model.ModelOption
import java.io.File

class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("tercuman", Context.MODE_PRIVATE)

    var threads: Int
        get() = prefs.getInt(KEY_THREADS, 0)
        set(v) = prefs.edit().putInt(KEY_THREADS, v.coerceIn(0, 16)).apply()

    var keepLoadedMinutes: Int
        get() = prefs.getInt(KEY_KEEP, 5)
        set(v) = prefs.edit().putInt(KEY_KEEP, v.coerceIn(1, 120)).apply()

    var direction: Direction
        get() = if (prefs.getString(KEY_DIRECTION, "tr-en") == "en-tr") Direction.EN_TO_TR else Direction.TR_TO_EN
        set(v) = prefs.edit().putString(KEY_DIRECTION, if (v == Direction.EN_TO_TR) "en-tr" else "tr-en").apply()

    var vibrate: Boolean
        get() = prefs.getBoolean(KEY_VIBRATE, true)
        set(v) = prefs.edit().putBoolean(KEY_VIBRATE, v).apply()

    var showNumberRow: Boolean
        get() = prefs.getBoolean(KEY_NUMBER_ROW, true)
        set(v) = prefs.edit().putBoolean(KEY_NUMBER_ROW, v).apply()

    var turkishQLayout: Boolean
        get() = prefs.getBoolean(KEY_TURKISH_Q, false)
        set(v) = prefs.edit().putBoolean(KEY_TURKISH_Q, v).apply()

    var modelOption: ModelOption
        get() = ModelOption.fromId(prefs.getString(KEY_MODEL, null))
        set(v) = prefs.edit().putString(KEY_MODEL, v.id).apply()

    var customModelPath: String?
        get() = prefs.getString(KEY_CUSTOM_MODEL, null)?.takeIf { File(it).isFile }
        set(v) = prefs.edit().putString(KEY_CUSTOM_MODEL, v).apply()

    var downloadId: Long
        get() = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        set(v) = prefs.edit().putLong(KEY_DOWNLOAD_ID, v).apply()

    fun effectiveThreads(): Int = if (threads > 0) threads else CpuInfo.recommendedThreads()

    companion object {
        private const val KEY_THREADS = "threads"
        private const val KEY_MODEL = "model_option"
        private const val KEY_TURKISH_Q = "turkish_q_layout"
        private const val KEY_KEEP = "keep_loaded_minutes"
        private const val KEY_DIRECTION = "direction"
        private const val KEY_VIBRATE = "vibrate"
        private const val KEY_NUMBER_ROW = "number_row"
        private const val KEY_CUSTOM_MODEL = "custom_model"
        private const val KEY_DOWNLOAD_ID = "download_id"
    }
}
