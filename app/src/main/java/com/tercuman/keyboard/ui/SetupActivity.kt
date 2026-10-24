package com.tercuman.keyboard.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.tercuman.keyboard.CpuInfo
import com.tercuman.keyboard.R
import com.tercuman.keyboard.Settings
import com.tercuman.keyboard.engine.Direction
import com.tercuman.keyboard.engine.Translator
import com.tercuman.keyboard.model.DefaultModel
import com.tercuman.keyboard.model.DownloadState
import com.tercuman.keyboard.model.ModelStore
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

class SetupActivity : Activity(), Translator.StateListener {
    private lateinit var settings: Settings
    private lateinit var store: ModelStore
    private lateinit var translator: Translator

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var polling = false
    private var importing = false
    private var tryJob: Translator.Job? = null

    private lateinit var modelStatus: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var downloadBtn: Button
    private lateinit var importBtn: Button
    private lateinit var imeStatus: TextView
    private lateinit var tryInput: EditText
    private lateinit var tryOutput: TextView
    private lateinit var tryInfo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        settings = Settings(this)
        store = ModelStore(this)
        translator = Translator.get(this)

        modelStatus = findViewById(R.id.modelStatus)
        modelProgress = findViewById(R.id.modelProgress)
        downloadBtn = findViewById(R.id.downloadBtn)
        importBtn = findViewById(R.id.importBtn)
        imeStatus = findViewById(R.id.imeStatus)
        tryInput = findViewById(R.id.tryInput)
        tryOutput = findViewById(R.id.tryOutput)
        tryInfo = findViewById(R.id.tryInfo)

        downloadBtn.setOnClickListener { onDownloadButton() }
        importBtn.setOnClickListener { pickModelFile() }
        findViewById<Button>(R.id.enableImeBtn).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_INPUT_METHOD_SETTINGS))
        }
        findViewById<Button>(R.id.pickImeBtn).setOnClickListener {
            getSystemService(InputMethodManager::class.java).showInputMethodPicker()
        }
        findViewById<Button>(R.id.tryBtn).setOnClickListener { tryTranslate(Direction.TR_TO_EN) }
        findViewById<Button>(R.id.tryReverseBtn).setOnClickListener { tryTranslate(Direction.EN_TO_TR) }
        findViewById<Button>(R.id.deleteModelBtn).setOnClickListener { confirmDelete() }
        setUpSettings()

        if (savedInstanceState == null && !store.isInstalled() && settings.downloadId < 0 && !autoStarted()) {
            markAutoStarted()
            startDownload()
        }
    }

    override fun onResume() {
        super.onResume()
        translator.addListener(this)
        updateImeStatus()
        startPolling()
    }

    override fun onPause() {
        super.onPause()
        translator.removeListener(this)
        polling = false
    }

    override fun onDestroy() {
        tryJob?.cancel()
        io.shutdown()
        super.onDestroy()
    }

    override fun onStateChanged(state: Translator.State, message: String?) {
        if (!polling) refreshModelUi(null)
    }

    private fun autoStarted() = getPreferences(MODE_PRIVATE).getBoolean("auto_download_started", false)
    private fun markAutoStarted() = getPreferences(MODE_PRIVATE).edit().putBoolean("auto_download_started", true).apply()

    private fun onDownloadButton() {
        if (settings.downloadId >= 0) {
            store.cancelDownload()
            refreshModelUi(DownloadState.Idle)
        } else {
            startDownload()
        }
    }

    private fun startDownload() {
        val need = DefaultModel.SIZE_BYTES + 200L * 1024 * 1024
        if (store.freeSpaceBytes() < need) {
            refreshModelUi(DownloadState.Failed(getString(R.string.no_space, gb(need))))
            return
        }
        store.startDownload()
        startPolling()
    }

    private fun startPolling() {
        if (polling) return
        polling = true
        val tick = object : Runnable {
            override fun run() {
                if (!polling || io.isShutdown) return
                io.execute {
                    val s = store.pollDownload(onVerifying = { main.post { refreshModelUi(DownloadState.Verifying) } })
                    main.post {
                        refreshModelUi(s)
                        if (s is DownloadState.Done) translator.refreshModelState()
                        if (polling && (s is DownloadState.Running || s is DownloadState.Paused)) {
                            main.postDelayed(this, 1000)
                        } else {
                            polling = false
                        }
                    }
                }
            }
        }
        main.post(tick)
    }

    private fun refreshModelUi(state: DownloadState?) {
        if (importing) return
        val s = state ?: if (store.isInstalled()) DownloadState.Done else DownloadState.Idle
        modelProgress.visibility = View.GONE
        downloadBtn.isEnabled = true
        importBtn.isEnabled = true
        when (s) {
            DownloadState.Idle -> {
                modelStatus.text = getString(R.string.model_not_installed, DefaultModel.DISPLAY_NAME, gb(DefaultModel.SIZE_BYTES))
                downloadBtn.text = getString(R.string.download)
            }
            is DownloadState.Running -> {
                modelStatus.text = getString(R.string.model_downloading, gb(s.downloaded), gb(s.total))
                showProgress(s.downloaded, s.total)
                downloadBtn.text = getString(R.string.cancel)
            }
            is DownloadState.Paused -> {
                modelStatus.text = s.reason + " (" + gb(s.downloaded) + " / " + gb(s.total) + ")"
                showProgress(s.downloaded, s.total)
                downloadBtn.text = getString(R.string.cancel)
            }
            DownloadState.Verifying -> {
                modelStatus.text = getString(R.string.model_verifying)
                modelProgress.visibility = View.VISIBLE
                modelProgress.isIndeterminate = true
                downloadBtn.isEnabled = false
                importBtn.isEnabled = false
            }
            DownloadState.Done -> {
                val file = store.installedModel()
                val engineState = when (translator.state) {
                    Translator.State.LOADING -> getString(R.string.engine_loading)
                    Translator.State.READY -> getString(R.string.engine_ready)
                    Translator.State.ERROR -> getString(R.string.status_error, translator.lastError ?: "?")
                    else -> getString(R.string.engine_idle)
                }
                modelStatus.text = getString(R.string.model_installed, file?.name ?: "?", gb(file?.length() ?: 0)) +
                    "\n" + engineState
                downloadBtn.text = getString(R.string.download)
                downloadBtn.isEnabled = false
            }
            is DownloadState.Failed -> {
                modelStatus.text = s.reason
                downloadBtn.text = getString(R.string.retry)
            }
        }
    }

    private fun showProgress(done: Long, total: Long) {
        modelProgress.visibility = View.VISIBLE
        modelProgress.isIndeterminate = total <= 0
        if (total > 0) modelProgress.progress = (done * 1000 / total).toInt()
    }

    private fun pickModelFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        startActivityForResult(i, REQ_IMPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK || uri == null) return
        importing = true
        downloadBtn.isEnabled = false
        importBtn.isEnabled = false
        modelProgress.visibility = View.VISIBLE
        modelProgress.isIndeterminate = true
        io.execute {
            val result = runCatching {
                var lastUi = 0L
                store.importModel(uri) { bytes ->
                    val now = SystemClock.uptimeMillis()
                    if (now - lastUi > 300) {
                        lastUi = now
                        main.post { modelStatus.text = getString(R.string.importing, gb(bytes)) }
                    }
                }
            }
            main.post {
                importing = false
                translator.unload()
                translator.refreshModelState()
                result.exceptionOrNull()?.let {
                    refreshModelUi(DownloadState.Failed(it.message ?: it.toString()))
                } ?: refreshModelUi(null)
            }
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_model_confirm)
            .setPositiveButton(R.string.delete_model) { _, _ ->
                translator.unload()
                store.cancelDownload()
                store.useDefaultModel()
                store.deleteDefaultModel()
                main.postDelayed({ translator.refreshModelState(); refreshModelUi(null) }, 300)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun updateImeStatus() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.DEFAULT_INPUT_METHOD)
        val selected = current?.startsWith("$packageName/") == true
        imeStatus.text = getString(
            when {
                selected -> R.string.ime_selected
                enabled -> R.string.ime_enabled
                else -> R.string.ime_disabled
            },
        )
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateImeStatus()
    }

    private fun tryTranslate(direction: Direction) {
        tryJob?.cancel()
        val text = tryInput.text.toString()
        if (text.isBlank()) {
            tryOutput.text = getString(R.string.status_empty)
            return
        }
        if (!store.isInstalled()) {
            tryOutput.text = getString(R.string.status_no_model)
            return
        }
        tryOutput.text = getString(R.string.status_translating)
        tryInfo.text = ""
        val started = SystemClock.uptimeMillis()
        tryJob = translator.translate(text, direction,
            onPartial = { tryOutput.text = it },
            onDone = { r ->
                tryJob = null
                r.onSuccess {
                    tryOutput.text = it
                    tryInfo.text = getString(R.string.took_seconds, (SystemClock.uptimeMillis() - started) / 1000.0)
                }.onFailure {
                    if (it !is CancellationException) tryOutput.text = getString(R.string.status_error, it.message ?: it.toString())
                }
            })
    }

    private fun setUpSettings() {
        val threadsLabel = findViewById<TextView>(R.id.threadsLabel)
        val threadsSeek = findViewById<SeekBar>(R.id.threadsSeek)
        fun showThreads() {
            threadsLabel.text = if (settings.threads == 0) {
                getString(R.string.threads_auto, CpuInfo.recommendedThreads())
            } else {
                getString(R.string.threads_n, settings.threads)
            }
        }
        threadsSeek.progress = settings.threads
        showThreads()
        threadsSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                if (!fromUser) return
                settings.threads = value
                showThreads()
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = translator.unload()
        })

        val keepLabel = findViewById<TextView>(R.id.keepLabel)
        val keepSeek = findViewById<SeekBar>(R.id.keepSeek)
        keepSeek.progress = settings.keepLoadedMinutes - 1
        keepLabel.text = getString(R.string.keep_loaded, settings.keepLoadedMinutes)
        keepSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, value: Int, fromUser: Boolean) {
                settings.keepLoadedMinutes = value + 1
                keepLabel.text = getString(R.string.keep_loaded, settings.keepLoadedMinutes)
            }
            override fun onStartTrackingTouch(sb: SeekBar) = Unit
            override fun onStopTrackingTouch(sb: SeekBar) = Unit
        })

        findViewById<Switch>(R.id.numberRowSwitch).apply {
            isChecked = settings.showNumberRow
            setOnCheckedChangeListener { _, v -> settings.showNumberRow = v }
        }
        findViewById<Switch>(R.id.vibrateSwitch).apply {
            isChecked = settings.vibrate
            setOnCheckedChangeListener { _, v -> settings.vibrate = v }
        }
    }

    private fun gb(bytes: Long) = "%.2f GB".format(bytes / 1_000_000_000.0)

    companion object {
        private const val REQ_IMPORT = 1
    }
}
