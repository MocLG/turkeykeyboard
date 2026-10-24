package com.tercuman.keyboard.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.TextView
import com.tercuman.keyboard.R
import com.tercuman.keyboard.Settings
import com.tercuman.keyboard.engine.Direction
import com.tercuman.keyboard.engine.Prompts
import com.tercuman.keyboard.engine.Translator
import java.util.concurrent.CancellationException

class ProcessTextActivity : Activity() {
    private var job: Translator.Job? = null
    private var result: String? = null
    private lateinit var source: String
    private var readOnly = true
    private lateinit var direction: Direction

    private lateinit var output: TextView
    private lateinit var replaceBtn: Button
    private lateinit var swapBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        readOnly = intent.getBooleanExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        direction = Settings(this).direction

        if (readOnly) direction = if (direction == Direction.TR_TO_EN) Direction.EN_TO_TR else Direction.TR_TO_EN

        setContentView(R.layout.activity_process_text)
        output = findViewById(R.id.ptOutput)
        output.movementMethod = ScrollingMovementMethod()
        replaceBtn = findViewById(R.id.ptReplace)
        swapBtn = findViewById(R.id.ptSwap)
        replaceBtn.visibility = if (readOnly) View.GONE else View.VISIBLE
        replaceBtn.isEnabled = false
        replaceBtn.setOnClickListener { finishWith(result) }
        findViewById<Button>(R.id.ptCopy).setOnClickListener {
            result?.let {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("translation", it))
                finish()
            }
        }
        swapBtn.setOnClickListener {
            direction = if (direction == Direction.TR_TO_EN) Direction.EN_TO_TR else Direction.TR_TO_EN
            start()
        }

        if (source.isBlank()) {
            output.text = getString(R.string.status_empty)
            return
        }
        start()
    }

    private fun start() {
        job?.cancel()
        result = null
        replaceBtn.isEnabled = false
        swapBtn.text = "⇄ " + direction.label
        val translator = Translator.get(this)
        if (translator.state == Translator.State.NO_MODEL) {
            output.text = getString(R.string.status_no_model)
            return
        }
        output.text = getString(R.string.status_translating)
        job = translator.translate(source.take(Prompts.MAX_INPUT_CHARS), direction,
            onPartial = { output.text = it },
            onDone = { r ->
                job = null
                r.onSuccess {
                    result = it
                    output.text = it
                    replaceBtn.isEnabled = true
                }.onFailure {
                    if (it !is CancellationException) output.text = getString(R.string.status_error, it.message ?: "?")
                }
            })
    }

    private fun finishWith(text: String?) {
        if (text != null && !readOnly) {
            setResult(RESULT_OK, Intent().putExtra(Intent.EXTRA_PROCESS_TEXT, text))
        }
        finish()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }
}
