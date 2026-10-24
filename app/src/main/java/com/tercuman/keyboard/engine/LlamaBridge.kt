package com.tercuman.keyboard.engine

object LlamaBridge {
    fun interface PartialListener {
        fun onPartial(utf8: ByteArray): Boolean
    }

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded(backendDir: String) {
        if (loaded) return
        System.loadLibrary("tercuman")
        nativeInit(backendDir)
        loaded = true
    }

    @Synchronized
    fun loadForHostTest(libraryPath: String) {
        if (loaded) return
        System.load(libraryPath)
        nativeInit("")
        loaded = true
    }

    @JvmStatic external fun nativeInit(backendDir: String)

    @JvmStatic external fun nativeLoad(modelPath: String, threads: Int, contextSize: Int): Long

    @JvmStatic external fun nativeGenerate(
        handle: Long,
        promptUtf8: ByteArray,
        maxTokens: Int,
        listener: PartialListener?,
    ): ByteArray

    @JvmStatic external fun nativeCancel(handle: Long)

    @JvmStatic external fun nativeFree(handle: Long)

    @JvmStatic external fun nativeSystemInfo(handle: Long): String
}
