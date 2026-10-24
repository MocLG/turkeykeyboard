package com.tercuman.keyboard.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.SparseArray
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View

@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {
    interface Listener {
        fun onKey(key: Key)
        fun onLongPressSpace()
    }

    enum class ShiftState { OFF, ONCE, LOCKED }

    var page: Page = Page.LETTERS
        set(v) { field = v; relayout() }
    var shift: ShiftState = ShiftState.OFF
        set(v) { field = v; invalidate() }
    var numberRow: Boolean = true
        set(v) { field = v; relayout() }
    var letterLayout: LetterLayout = LetterLayout.QWERTY
        set(v) { field = v; relayout() }
    var enterLabel: String = "⏎"
        set(v) { field = v; invalidate() }
    var vibrate: Boolean = true

    private data class Placed(val key: Key, val rect: RectF)

    private var placed: List<Placed> = emptyList()
    private val pressed = SparseArray<Placed>()
    private val handler = Handler(Looper.getMainLooper())

    private val dp = resources.displayMetrics.density
    private val rowHeight = 50 * dp
    private val gap = 4 * dp
    private val corner = 7 * dp

    private val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    private val bgColor = if (dark) Color.rgb(0x1B, 0x1C, 0x1F) else Color.rgb(0xE8, 0xEA, 0xED)
    private val keyColor = if (dark) Color.rgb(0x3A, 0x3B, 0x3F) else Color.WHITE
    private val specialColor = if (dark) Color.rgb(0x2A, 0x2B, 0x2F) else Color.rgb(0xC9, 0xCD, 0xD3)
    private val pressedColor = if (dark) Color.rgb(0x5A, 0x5C, 0x62) else Color.rgb(0xB0, 0xB5, 0xBC)
    private val accentColor = Color.rgb(0x1A, 0x73, 0xE8)
    private val textColor = if (dark) Color.rgb(0xE8, 0xEA, 0xED) else Color.rgb(0x20, 0x21, 0x24)

    private val popupColor = if (dark) Color.rgb(0x4A, 0x4C, 0x52) else Color.WHITE
    private val popupShadow = if (dark) Color.rgb(0x10, 0x10, 0x12) else Color.rgb(0x9A, 0xA0, 0xA6)

    private var popupPointer = -1
    private var popupOptions: List<String> = emptyList()
    private var popupRects: List<RectF> = emptyList()
    private var popupSelected = 0
    private var longPressPointer = -1

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        color = textColor
    }

    init {
        setBackgroundColor(bgColor)
        isHapticFeedbackEnabled = true
    }

    private fun rows() = Layouts.rows(page, numberRow, letterLayout)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (rows().size * rowHeight + gap).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }

    private fun relayout() {
        val w = width.toFloat()
        if (w <= 0f) {
            requestLayout()
            return
        }
        val unit = (w - gap) / Layouts.COLUMNS
        val result = ArrayList<Placed>()
        rows().forEachIndexed { r, row ->
            val rowW = Layouts.rowWidth(row) * unit
            var x = gap + (w - gap - rowW) / 2f
            val top = gap + r * rowHeight
            for (k in row) {
                x += k.gapBefore * unit
                val kw = k.width * unit
                result.add(Placed(k, RectF(x, top, x + kw - gap, top + rowHeight - gap)))
                x += kw
            }
        }
        placed = result
        pressed.clear()
        closePopup()
        requestLayout()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val isPressed = HashSet<Placed>()
        for (i in 0 until pressed.size()) pressed.valueAt(i)?.let { isPressed.add(it) }

        for (p in placed) {
            val k = p.key
            keyPaint.color = when {
                p in isPressed -> pressedColor
                k.type == KeyType.ENTER -> accentColor
                k.type == KeyType.SHIFT && shift != ShiftState.OFF -> accentColor
                k.type == KeyType.CHAR || k.type == KeyType.SPACE -> keyColor
                else -> specialColor
            }
            canvas.drawRoundRect(p.rect, corner, corner, keyPaint)

            val label = labelFor(k)
            val accentText = k.type == KeyType.ENTER || (k.type == KeyType.SHIFT && shift != ShiftState.OFF)
            textPaint.color = if (accentText) Color.WHITE else textColor
            textPaint.typeface = if (k.type == KeyType.CHAR) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            textPaint.textSize = when {
                k.type == KeyType.SPACE -> 13 * dp
                label.length > 2 -> 14 * dp
                else -> 21 * dp
            }
            if (k.type == KeyType.SPACE) textPaint.color = (textColor and 0x00FFFFFF) or 0x80000000.toInt()
            val y = p.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, p.rect.centerX(), y, textPaint)
            if (k.type == KeyType.CHAR && k.alternates.isNotEmpty() && page == Page.LETTERS) {
                val hint = displayText(k.alternates.first())
                textPaint.textSize = 10 * dp
                textPaint.typeface = Typeface.DEFAULT
                textPaint.color = (textColor and 0x00FFFFFF) or 0x99000000.toInt()
                canvas.drawText(hint, p.rect.right - 7 * dp, p.rect.top + 12 * dp, textPaint)
                textPaint.color = textColor
            }
            if (k.type == KeyType.SHIFT && shift == ShiftState.LOCKED) {
                canvas.drawRect(p.rect.centerX() - 7 * dp, p.rect.bottom - 9 * dp,
                    p.rect.centerX() + 7 * dp, p.rect.bottom - 7 * dp, textPaint)
            }
        }
        drawPopup(canvas)
    }

    private fun drawPopup(canvas: Canvas) {
        if (popupPointer < 0 || popupRects.isEmpty()) return
        val outer = RectF(popupRects.first().left, popupRects.first().top, popupRects.last().right, popupRects.last().bottom)
        keyPaint.color = popupShadow
        canvas.drawRoundRect(RectF(outer.left - 1 * dp, outer.top - 1 * dp, outer.right + 1 * dp, outer.bottom + 2 * dp), corner, corner, keyPaint)
        keyPaint.color = popupColor
        canvas.drawRoundRect(outer, corner, corner, keyPaint)
        textPaint.typeface = Typeface.DEFAULT
        textPaint.textSize = 22 * dp
        popupRects.forEachIndexed { i, r ->
            if (i == popupSelected) {
                keyPaint.color = accentColor
                canvas.drawRoundRect(r, corner, corner, keyPaint)
            }
            textPaint.color = if (i == popupSelected) Color.WHITE else textColor
            val y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(displayText(popupOptions[i]), r.centerX(), y, textPaint)
        }
        textPaint.color = textColor
    }

    private fun displayText(s: String): String =
        if (page == Page.LETTERS && shift != ShiftState.OFF) Layouts.upper(s) else s

    private val longPressChar = Runnable {
        val id = longPressPointer
        val p = pressed.get(id) ?: return@Runnable
        if (p.key.type != KeyType.CHAR || p.key.alternates.isEmpty()) return@Runnable
        openPopup(id, p)
    }

    private fun openPopup(pointerId: Int, p: Placed) {
        val options = p.key.alternates
        val cellW = maxOf(p.rect.width(), 34 * dp)
        val cellH = rowHeight - gap
        val totalW = cellW * options.size
        var left = p.rect.centerX() - cellW / 2f
        left = left.coerceIn(gap, maxOf(gap, width - gap - totalW))
        val top = maxOf(0f, p.rect.top - rowHeight)
        popupRects = options.indices.map { i -> RectF(left + i * cellW, top, left + (i + 1) * cellW, top + cellH) }
        popupOptions = options
        popupSelected = 0
        popupPointer = pointerId
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun closePopup() {
        popupPointer = -1
        popupOptions = emptyList()
        popupRects = emptyList()
    }

    private fun updatePopupSelection(x: Float) {
        if (popupRects.isEmpty()) return
        val i = popupRects.indexOfFirst { x < it.right }
        val sel = if (i < 0) popupRects.size - 1 else i
        if (sel != popupSelected) {
            popupSelected = sel
            invalidate()
        }
    }

    private fun labelFor(k: Key): String = when (k.type) {
        KeyType.CHAR -> if (page == Page.LETTERS && shift != ShiftState.OFF) Layouts.upper(k.text) else k.text
        KeyType.ENTER -> enterLabel
        else -> k.text
    }

    private val repeatBackspace = object : Runnable {
        override fun run() {
            val p = findPressed(KeyType.BACKSPACE) ?: return
            listener.onKey(p.key)
            handler.postDelayed(this, 50)
        }
    }

    private val longPressSpace = Runnable {
        val p = findPressed(KeyType.SPACE) ?: return@Runnable
        for (i in 0 until pressed.size()) if (pressed.valueAt(i) === p) pressed.setValueAt(i, null)
        invalidate()
        listener.onLongPressSpace()
    }

    private fun findPressed(type: KeyType): Placed? {
        for (i in 0 until pressed.size()) {
            val p = pressed.valueAt(i)
            if (p?.key?.type == type) return p
        }
        return null
    }

    private fun keyAt(x: Float, y: Float): Placed? {
        placed.firstOrNull { it.rect.contains(x, y) }?.let { return it }

        return placed.minByOrNull {
            val dx = maxOf(it.rect.left - x, 0f, x - it.rect.right)
            val dy = maxOf(it.rect.top - y, 0f, y - it.rect.bottom)
            dx * dx + dy * dy
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                releaseAllExceptRepeating(e)
                val idx = e.actionIndex
                val p = keyAt(e.getX(idx), e.getY(idx)) ?: return true
                pressed.put(e.getPointerId(idx), p)
                feedback()
                when (p.key.type) {
                    KeyType.BACKSPACE -> {
                        listener.onKey(p.key)
                        handler.postDelayed(repeatBackspace, 400)
                    }
                    KeyType.SPACE -> handler.postDelayed(longPressSpace, 500)
                    KeyType.CHAR -> if (p.key.alternates.isNotEmpty()) {
                        handler.removeCallbacks(longPressChar)
                        longPressPointer = e.getPointerId(idx)
                        handler.postDelayed(longPressChar, 350)
                    }
                    else -> Unit
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    if (id == popupPointer) {
                        updatePopupSelection(e.getX(i))
                        continue
                    }
                    val cur = pressed.get(id) ?: continue
                    if (cur.key.type == KeyType.BACKSPACE || cur.key.type == KeyType.SPACE) continue
                    val p = keyAt(e.getX(i), e.getY(i))
                    if (p != null && p !== cur) {
                        pressed.put(id, p)
                        invalidate()
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                release(e.getPointerId(e.actionIndex), commit = true)
            }
            MotionEvent.ACTION_CANCEL -> {
                closePopup()
                for (i in pressed.size() - 1 downTo 0) release(pressed.keyAt(i), commit = false)
            }
        }
        return true
    }

    private fun releaseAllExceptRepeating(e: MotionEvent) {
        val newId = e.getPointerId(e.actionIndex)
        for (i in pressed.size() - 1 downTo 0) {
            val id = pressed.keyAt(i)
            if (id == newId) continue
            val type = pressed.valueAt(i)?.key?.type
            if (type == KeyType.BACKSPACE || type == KeyType.SPACE) continue
            release(id, commit = true)
        }
    }

    private fun release(pointerId: Int, commit: Boolean) {
        val p = pressed.get(pointerId)
        pressed.remove(pointerId)
        handler.removeCallbacks(repeatBackspace)
        handler.removeCallbacks(longPressSpace)
        if (pointerId == longPressPointer) {
            handler.removeCallbacks(longPressChar)
            longPressPointer = -1
        }
        if (pointerId == popupPointer) {
            val chosen = popupOptions.getOrNull(popupSelected)
            closePopup()
            if (commit && chosen != null) listener.onKey(Key(KeyType.CHAR, chosen))
        } else if (p != null && commit && p.key.type != KeyType.BACKSPACE) {
            listener.onKey(p.key)
        }
        invalidate()
    }

    private fun feedback() {
        if (vibrate) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        pressed.clear()
        closePopup()
    }
}
