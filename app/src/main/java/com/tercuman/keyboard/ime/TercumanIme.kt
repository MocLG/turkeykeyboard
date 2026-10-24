package com.tercuman.keyboard.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.text.TextUtils
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tercuman.keyboard.CrashLog
import com.tercuman.keyboard.R
import com.tercuman.keyboard.Settings
import com.tercuman.keyboard.engine.Direction
import com.tercuman.keyboard.engine.Lang
import com.tercuman.keyboard.engine.Prompts
import com.tercuman.keyboard.engine.Translator
import com.tercuman.keyboard.ui.SetupActivity
import java.io.File
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class TercumanIme : InputMethodService(), KeyboardView.Listener, Translator.StateListener {
    private lateinit var settings: Settings
    private lateinit var translator: Translator

    private var keyboard: KeyboardView? = null
    private var translateBtn: Button? = null
    private var swapBtn: Button? = null
    private var statusView: TextView? = null
    private var panel: LinearLayout? = null
    private var panelText: TextView? = null
    private var panelInsert: Button? = null

    private var job: Translator.Job? = null
    private var undo: Pair<String, String>? = null
    private var panelResult: String? = null
    private var capsFromEditor = false
    private var suggestionBar: LinearLayout? = null
    private var suggestionSlots: List<TextView> = emptyList()
    private var suggestionsAllowed = false
    private val predictors = ConcurrentHashMap<Lang, Predictor>()
    private val dictWorker = Executors.newSingleThreadExecutor()
    private val refreshSuggestions = Runnable { updateSuggestions() }
    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        translator = Translator.get(this)
        loadPredictor(settings.direction.from)
    }

    private fun loadPredictor(lang: Lang) {
        if (predictors.containsKey(lang)) return
        dictWorker.execute {
            if (predictors.containsKey(lang)) return@execute
            try {
                val dict = assets.open("dict/${lang.code}.txt").reader(Charsets.UTF_8).use { WordDictionary.parse(it) }
                val user = UserWords(File(filesDir, "user_words_${lang.code}.tsv"))
                val locale = Locale.forLanguageTag(lang.code)
                predictors[lang] = Predictor(dict, user, locale)
                keyboard?.post { updateSuggestions() }
            } catch (e: Throwable) {
                CrashLog.record(this, "Loading word list failed", e)
            }
        }
    }

    private fun predictor(): Predictor? {
        val lang = settings.direction.from
        return predictors[lang] ?: run {
            loadPredictor(lang)
            null
        }
    }

    override fun onDestroy() {
        job?.cancel()
        translator.removeListener(this)
        val toSave = predictors.values.toList()
        dictWorker.execute { toSave.forEach { it.save() } }
        dictWorker.shutdown()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) Color.rgb(0x1B, 0x1C, 0x1F) else Color.rgb(0xE8, 0xEA, 0xED)
        val fg = if (dark) Color.rgb(0xE8, 0xEA, 0xED) else Color.rgb(0x20, 0x21, 0x24)
        val chip = if (dark) Color.rgb(0x2A, 0x2B, 0x2F) else Color.rgb(0xD5, 0xD8, 0xDC)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        panelText = TextView(this).apply {
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            maxLines = 5
            isVerticalScrollBarEnabled = true
            movementMethod = ScrollingMovementMethod()
            setTextIsSelectable(false)
            setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
        }
        panelInsert = smallButton(getString(R.string.panel_insert), chip, fg) { insertPanelResult() }
        val panelButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            addView(panelInsert)
            addView(smallButton(getString(R.string.panel_copy), chip, fg) { copyPanelResult() })
            addView(smallButton("✕", chip, fg) { hidePanel() })
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(panelText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(panelButtons, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        root.addView(panel)

        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), 0)
        }
        translateBtn = smallButton("", Color.rgb(0x1A, 0x73, 0xE8), Color.WHITE) { onTranslatePressed() }.also {
            it.setOnLongClickListener { openSetup(); true }
        }
        swapBtn = smallButton("⇄", chip, fg) { swapDirection() }
        statusView = TextView(this).apply {
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding((8 * dp).toInt(), 0, (4 * dp).toInt(), 0)
            setOnClickListener { onStatusClicked() }
        }
        strip.addView(translateBtn)
        strip.addView(swapBtn)
        strip.addView(statusView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        strip.addView(smallButton(getString(R.string.clipboard_button), chip, fg) { translateClipboard() })
        root.addView(strip)

        suggestionSlots = List(3) { i ->
            TextView(this).apply {
                setTextColor(fg)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (i == 1) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setOnClickListener { (tag as? String)?.let { word -> guarded { pickSuggestion(word) } } }
                setOnLongClickListener {
                    (tag as? String)?.let { word ->
                        predictor()?.forget(word)
                        updateSuggestions()
                    }
                    true
                }
            }
        }
        suggestionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            suggestionSlots.forEach { addView(it, LinearLayout.LayoutParams(0, (40 * dp).toInt(), 1f)) }
        }
        root.addView(suggestionBar)

        keyboard = KeyboardView(this, this).also { root.addView(it) }

        updateTranslateButton()
        translator.addListener(this)
        return root
    }

    private fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashLog.record(this, "Keyboard action failed", e)
            setStatus(getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun smallButton(text: String, bg: Int, fg: Int, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 18 * dp
                setColor(bg)
            }
            stateListAnimator = null
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * dp).toInt()).apply {
                marginStart = (3 * dp).toInt()
                marginEnd = (3 * dp).toInt()
            }
            setOnClickListener {
                try {
                    onClick()
                } catch (e: Throwable) {
                    CrashLog.record(this@TercumanIme, "Keyboard button failed", e)
                    job = null
                    hidePanel()
                    updateTranslateButton()
                    setStatus(getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
                }
            }
        }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val kb = keyboard ?: return
        kb.vibrate = settings.vibrate
        kb.numberRow = settings.showNumberRow
        kb.letterLayout = if (settings.turkishQLayout) LetterLayout.TURKISH_Q else LetterLayout.QWERTY
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        kb.page = if (cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE ||
            cls == InputType.TYPE_CLASS_DATETIME) Page.SYMBOLS else Page.LETTERS
        kb.enterLabel = when (info.imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEND -> "➤"
            EditorInfo.IME_ACTION_SEARCH -> "→"
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> "→"
            EditorInfo.IME_ACTION_DONE -> "✓"
            else -> "⏎"
        }.let { if (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0) "⏎" else it }
        kb.shift = KeyboardView.ShiftState.OFF
        updateAutoCaps()
        if (!restarting) {
            undo = null
            hidePanel()
        }
        updateTranslateButton()
        showIdleStatus()

        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        suggestionsAllowed = settings.suggestions && !password && cls == InputType.TYPE_CLASS_TEXT
        suggestionBar?.visibility = if (suggestionsAllowed) View.VISIBLE else View.GONE
        updateSuggestions()

        translator.preload()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        job?.cancel()
        job = null
        val toSave = predictors.values.toList()
        if (!dictWorker.isShutdown) dictWorker.execute { toSave.forEach { it.save() } }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
                                   candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateAutoCaps()
        keyboard?.removeCallbacks(refreshSuggestions)
        keyboard?.postDelayed(refreshSuggestions, 30)
    }

    private fun updateSuggestions() {
        if (!suggestionsAllowed) return
        val words = runCatching {
            val ic = currentInputConnection ?: return@runCatching emptyList()
            if (!ic.getSelectedText(0).isNullOrEmpty()) return@runCatching emptyList()
            val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return@runCatching emptyList()
            predictor()?.suggest(Predictor.context(before)).orEmpty()
        }.getOrDefault(emptyList())
        val order = when (words.size) {
            0 -> listOf(null, null, null)
            1 -> listOf(null, words[0], null)
            2 -> listOf(words[1], words[0], null)
            else -> listOf(words[1], words[0], words[2])
        }
        suggestionSlots.forEachIndexed { i, slot ->
            slot.text = order[i].orEmpty()
            slot.tag = order[i]
        }
    }

    private fun learnWordBeforeCursor() {
        if (!suggestionsAllowed) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString() ?: return
        val ctx = Predictor.context(before)
        if (ctx.partial.length >= 2) predictor()?.learn(ctx.previous, ctx.partial)
    }

    private fun pickSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(64, 0)?.toString().orEmpty()
        val ctx = Predictor.context(before)
        ic.beginBatchEdit()
        if (ctx.partial.isNotEmpty()) ic.deleteSurroundingText(ctx.partial.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        predictor()?.learn(ctx.previous, word)
        keyboard?.let { if (it.shift == KeyboardView.ShiftState.ONCE && !capsFromEditor) it.shift = KeyboardView.ShiftState.OFF }
    }

    override fun onKey(key: Key) {
        val ic = currentInputConnection ?: return
        val kb = keyboard ?: return
        if (key.type != KeyType.SHIFT && undo != null) {
            undo = null
            showIdleStatus()
        }
        when (key.type) {
            KeyType.CHAR -> {
                if (key.text.none(TextFold::isWordChar)) learnWordBeforeCursor()
                val text = if (kb.page == Page.LETTERS && kb.shift != KeyboardView.ShiftState.OFF) Layouts.upper(key.text) else key.text
                ic.commitText(text, 1)
                if (kb.shift == KeyboardView.ShiftState.ONCE) kb.shift = KeyboardView.ShiftState.OFF
            }
            KeyType.SPACE -> {
                learnWordBeforeCursor()
                ic.commitText(" ", 1)
            }
            KeyType.BACKSPACE -> {
                val sel = ic.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic.commitText("", 1) else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            }
            KeyType.ENTER -> {
                learnWordBeforeCursor()
                val ei = currentInputEditorInfo
                val action = (ei?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                val noAction = ei == null || (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0 ||
                    action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED
                if (noAction) ic.commitText("\n", 1) else ic.performEditorAction(action)
            }
            KeyType.SHIFT -> {
                capsFromEditor = false
                kb.shift = when (kb.shift) {
                    KeyboardView.ShiftState.OFF -> KeyboardView.ShiftState.ONCE
                    KeyboardView.ShiftState.ONCE -> KeyboardView.ShiftState.LOCKED
                    KeyboardView.ShiftState.LOCKED -> KeyboardView.ShiftState.OFF
                }
            }
            KeyType.SYMBOLS -> kb.page = Page.SYMBOLS
            KeyType.MORE_SYMBOLS -> kb.page = Page.MORE_SYMBOLS
            KeyType.LETTERS -> kb.page = Page.LETTERS
            KeyType.GLOBE -> switchKeyboard()
        }
    }

    override fun onLongPressSpace() {
        getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun switchKeyboard() {
        val switched = runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
        if (!switched) getSystemService(InputMethodManager::class.java)?.showInputMethodPicker()
    }

    private fun updateAutoCaps() {
        val kb = keyboard ?: return
        if (kb.page != Page.LETTERS || kb.shift == KeyboardView.ShiftState.LOCKED) return
        val ei = currentInputEditorInfo ?: return
        val ic = currentInputConnection ?: return
        val caps = ei.inputType != InputType.TYPE_NULL && ic.getCursorCapsMode(ei.inputType) != 0
        if (caps && kb.shift == KeyboardView.ShiftState.OFF) {
            kb.shift = KeyboardView.ShiftState.ONCE
            capsFromEditor = true
        } else if (!caps && capsFromEditor && kb.shift == KeyboardView.ShiftState.ONCE) {
            kb.shift = KeyboardView.ShiftState.OFF
            capsFromEditor = false
        }
    }

    private fun onTranslatePressed() {
        if (job != null) {
            job?.cancel()
            return
        }
        if (translator.state == Translator.State.NO_MODEL) {
            openSetup()
            return
        }
        val ic = currentInputConnection ?: return
        val field = FieldTextReader.read(ic)
        if (field == null || field.text.isBlank()) {
            setStatus(getString(R.string.status_empty))
            return
        }
        if (field.text.length > Prompts.MAX_INPUT_CHARS) {
            setStatus(getString(R.string.status_too_long, Prompts.MAX_INPUT_CHARS))
            return
        }
        val direction = settings.direction
        undo = null
        showPanel(getString(R.string.status_translating), insertable = false)
        job = translator.translate(field.text, direction,
            onPartial = { partial -> if (partial.isNotEmpty()) panelText?.text = partial },
            onDone = { result ->
                job = null
                updateTranslateButton()
                result.onSuccess { translated -> applyTranslation(field, translated) }
                    .onFailure { e -> onTranslationFailed(e) }
            })
        updateTranslateButton()
    }

    private fun applyTranslation(field: FieldText, translated: String) {
        if (translated.isBlank()) {
            hidePanel()
            setStatus(getString(R.string.status_error, "empty result"))
            return
        }
        val ic = currentInputConnection
        if (ic != null && FieldTextReader.stillContains(ic, field.target, field.text) &&
            FieldTextReader.replace(ic, field.target, translated)) {
            hidePanel()
            undo = field.text to translated
            setStatus("↶ " + getString(R.string.undo))
        } else {
            showPanel(translated, insertable = true)
            setStatus(getString(R.string.status_changed))
        }
    }

    private fun onTranslationFailed(e: Throwable) {
        if (e is CancellationException) {
            hidePanel()
            showIdleStatus()
            return
        }
        hidePanel()
        setStatus(getString(R.string.status_error, e.message ?: e.javaClass.simpleName))
    }

    private fun onStatusClicked() {
        val (original, translated) = undo ?: run {
            if (translator.state == Translator.State.NO_MODEL || translator.state == Translator.State.ERROR) openSetup()
            return
        }
        val ic = currentInputConnection ?: return

        if (!FieldTextReader.replaceLast(ic, translated, original)) {
            setStatus(getString(R.string.status_undo_failed))
            undo = null
            return
        }
        undo = null
        showIdleStatus()
    }

    private fun swapDirection() {
        settings.direction = if (settings.direction == Direction.TR_TO_EN) Direction.EN_TO_TR else Direction.TR_TO_EN
        loadPredictor(settings.direction.from)
        updateSuggestions()
        updateTranslateButton()
        showIdleStatus()
    }

    private fun translateClipboard() {
        if (job != null) return
        if (translator.state == Translator.State.NO_MODEL) {
            openSetup()
            return
        }
        val cm = getSystemService(ClipboardManager::class.java)
        val text = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrBlank()) {
            setStatus(getString(R.string.status_clipboard_empty))
            return
        }
        val clip = text.take(Prompts.MAX_INPUT_CHARS)
        val dir = if (settings.direction == Direction.TR_TO_EN) Direction.EN_TO_TR else Direction.TR_TO_EN
        showPanel(getString(R.string.status_translating), insertable = false)
        setStatus(getString(R.string.status_clipboard, dir.label))
        job = translator.translate(clip, dir,
            onPartial = { partial -> if (partial.isNotEmpty()) panelText?.text = partial },
            onDone = { result ->
                job = null
                updateTranslateButton()
                result.onSuccess { showPanel(it, insertable = true); showIdleStatus() }
                    .onFailure { onTranslationFailed(it) }
            })
        updateTranslateButton()
    }

    private fun showPanel(text: String, insertable: Boolean) {
        panelResult = if (insertable) text else null
        panelText?.text = text
        panelText?.scrollTo(0, 0)
        panelInsert?.visibility = if (insertable) View.VISIBLE else View.GONE
        panel?.visibility = View.VISIBLE
    }

    private fun hidePanel() {
        panel?.visibility = View.GONE
        panelResult = null
    }

    private fun insertPanelResult() {
        val text = panelResult ?: return
        currentInputConnection?.commitText(text, 1)
        hidePanel()
    }

    private fun copyPanelResult() {
        val text = panelResult ?: panelText?.text?.toString() ?: return
        getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("translation", text))
        setStatus(getString(R.string.copied))
    }

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun onStateChanged(state: Translator.State, message: String?) {
        if (job == null && undo == null) showIdleStatus()
    }

    private fun updateTranslateButton() {
        translateBtn?.text = if (job != null) getString(R.string.stop)
        else getString(R.string.translate_button, settings.direction.label)
    }

    private fun showIdleStatus() {
        val s = when (translator.state) {
            Translator.State.NO_MODEL -> getString(R.string.status_no_model)
            Translator.State.LOADING -> getString(R.string.status_loading)
            Translator.State.ERROR -> getString(R.string.status_error, translator.lastError ?: "?")
            else -> if (settings.direction == Direction.TR_TO_EN) getString(R.string.status_hint_tr)
            else getString(R.string.status_hint_en)
        }
        setStatus(s)
    }

    private fun setStatus(text: String) {
        statusView?.text = text
    }
}
