package com.tercuman.keyboard.engine

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.tercuman.keyboard.CrashLog
import com.tercuman.keyboard.Settings
import com.tercuman.keyboard.model.ModelStore
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class Translator private constructor(private val app: Context) {
    enum class State { NO_MODEL, UNLOADED, LOADING, READY, ERROR }

    fun interface StateListener {
        fun onStateChanged(state: State, message: String?)
    }

    inner class Job internal constructor() {
        @Volatile var cancelled = false
            private set

        fun cancel() {
            cancelled = true
        }
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "tercuman-llm").apply { priority = Thread.NORM_PRIORITY + 1 } }
    private val main = Handler(Looper.getMainLooper())
    private val settings = Settings(app)
    private val store = ModelStore(app)

    @Volatile private var handle = 0L
    @Volatile private var loadedPath: String? = null
    @Volatile var state: State = if (store.isInstalled()) State.UNLOADED else State.NO_MODEL
        private set
    @Volatile var lastError: String? = null
        private set

    private val listeners = mutableSetOf<StateListener>()
    private val lastUse = AtomicLong(0)
    private val idleCheck = Runnable { unloadIfIdle() }

    fun addListener(l: StateListener) {
        listeners.add(l)
        l.onStateChanged(state, lastError)
    }

    fun removeListener(l: StateListener) {
        listeners.remove(l)
    }

    fun refreshModelState() {
        if (state == State.NO_MODEL || state == State.ERROR || state == State.UNLOADED) {
            setState(if (store.isInstalled()) State.UNLOADED else State.NO_MODEL, null)
        }
    }

    fun preload() {
        if (state == State.READY || state == State.LOADING) {
            touch()
            return
        }
        if (!store.isInstalled()) {
            setState(State.NO_MODEL, null)
            return
        }
        worker.execute {
            busy(true)
            loadBlocking()
            busy(false)
        }
    }

    fun translate(
        text: String,
        direction: Direction,
        onPartial: (String) -> Unit,
        onDone: (Result<String>) -> Unit,
    ): Job {
        val job = Job()
        val prompt = Prompts.translateGemma(text, direction).toByteArray(Charsets.UTF_8)
        val maxTokens = Prompts.maxOutputTokens(text)
        if (state != State.READY && state != State.LOADING) preload()

        worker.execute {
            busy(true)
            val result: Result<String> = try {
                if (job.cancelled) throw CancellationException()
                val h = if (handle != 0L) handle else loadBlocking()
                    ?: throw IOException(lastError ?: "Model is not available.")
                touch()
                var lastPost = 0L
                val listener = LlamaBridge.PartialListener { bytes ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastPost > 80) {
                        lastPost = now
                        val partial = Prompts.cleanOutput(String(bytes, Charsets.UTF_8))
                        main.post { if (!job.cancelled) safely("Showing partial translation") { onPartial(partial) } }
                    }
                    !job.cancelled
                }
                val started = SystemClock.uptimeMillis()
                val out = LlamaBridge.nativeGenerate(h, prompt, maxTokens, listener)
                Log.i(TAG, "Translated ${text.length} chars in ${SystemClock.uptimeMillis() - started} ms")
                touch()
                Result.success(Prompts.cleanOutput(String(out, Charsets.UTF_8)))
            } catch (e: CancellationException) {
                Result.failure(e)
            } catch (e: Throwable) {
                Log.w(TAG, "Translation failed", e)
                Result.failure(e)
            }
            busy(false)
            main.post { safely("Finishing translation") { onDone(if (job.cancelled) Result.failure(CancellationException()) else result) } }
        }
        return job
    }

    fun unload() {
        main.removeCallbacks(idleCheck)
        worker.execute {
            freeNative()
            setState(if (store.isInstalled()) State.UNLOADED else State.NO_MODEL, null)
        }
    }

    fun onLowMemory() {
        if (SystemClock.uptimeMillis() - lastUse.get() > 15_000) unload()
    }

    fun systemInfo(): String = runCatching { LlamaBridge.nativeSystemInfo(handle) }.getOrDefault("not loaded")

    private fun loadBlocking(): Long? {
        val file = store.installedModel()
        if (file == null) {
            setState(State.NO_MODEL, null)
            return null
        }
        if (handle != 0L && loadedPath == file.absolutePath) return handle
        freeNative()

        setState(State.LOADING, null)
        return try {
            LlamaBridge.ensureLoaded(app.applicationInfo.nativeLibraryDir)
            val threads = settings.effectiveThreads()
            val t0 = SystemClock.uptimeMillis()
            val h = LlamaBridge.nativeLoad(file.absolutePath, threads, CONTEXT_SIZE)
            Log.i(TAG, "Loaded ${file.name} with $threads threads in ${SystemClock.uptimeMillis() - t0} ms")
            handle = h
            loadedPath = file.absolutePath
            touch()
            setState(State.READY, null)
            h
        } catch (e: Throwable) {
            Log.e(TAG, "Model load failed", e)
            setState(State.ERROR, e.message ?: e.toString())
            null
        }
    }

    private val busyMarker = File(app.filesDir, "engine_busy")

    init {
        if (busyMarker.isFile) {
            CrashLog.recordText(
                app,
                "The keyboard was stopped while the model was loading or translating. " +
                    "This usually means Android closed it because the phone ran low on memory.",
            )
            busyMarker.delete()
        }
    }

    private fun busy(on: Boolean) {
        runCatching { if (on) busyMarker.writeText("1") else busyMarker.delete() }
    }

    private inline fun safely(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashLog.record(app, what, e)
        }
    }

    private fun freeNative() {
        val h = handle
        if (h != 0L) {
            handle = 0L
            loadedPath = null
            LlamaBridge.nativeFree(h)
            Log.i(TAG, "Model unloaded")
        }
    }

    private fun touch() {
        lastUse.set(SystemClock.uptimeMillis())
        main.removeCallbacks(idleCheck)
        main.postDelayed(idleCheck, settings.keepLoadedMinutes * 60_000L)
    }

    private fun unloadIfIdle() {
        val idleFor = SystemClock.uptimeMillis() - lastUse.get()
        if (idleFor >= settings.keepLoadedMinutes * 60_000L - 1000) unload()
        else main.postDelayed(idleCheck, settings.keepLoadedMinutes * 60_000L - idleFor)
    }

    private fun setState(s: State, error: String?) {
        state = s
        lastError = error
        main.post { listeners.toList().forEach { it.onStateChanged(s, error) } }
    }

    companion object {
        private const val TAG = "Translator"
        const val CONTEXT_SIZE = 2048

        @Volatile private var instance: Translator? = null

        fun get(context: Context): Translator =
            instance ?: synchronized(this) {
                instance ?: Translator(context.applicationContext).also { instance = it }
            }
    }
}
