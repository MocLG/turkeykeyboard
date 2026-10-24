package com.tercuman.keyboard.ime

import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

sealed class Target {
    data class Range(val start: Int, val end: Int) : Target()

    data object Selection : Target()

    data class AroundCursor(val before: Int, val after: Int) : Target()
}

data class FieldText(val text: String, val target: Target)

object FieldTextReader {
    private const val MAX_CHARS = 20_000

    fun read(ic: InputConnection): FieldText? {
        val req = ExtractedTextRequest().apply { hintMaxChars = MAX_CHARS }
        val et = ic.getExtractedText(req, 0)
        if (et?.text != null) {
            return pick(et.text.toString(), et.selectionStart, et.selectionEnd, et.startOffset)
        }

        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        if (selected.isNotBlank()) return FieldText(selected, Target.Selection)
        val before = ic.getTextBeforeCursor(MAX_CHARS, 0)?.toString() ?: return null
        val after = ic.getTextAfterCursor(MAX_CHARS, 0)?.toString().orEmpty()
        return FieldText(before + after, Target.AroundCursor(before.length, after.length))
    }

    fun pick(all: String, selStart: Int, selEnd: Int, base: Int = 0): FieldText {
        val a = minOf(selStart, selEnd).coerceIn(0, all.length)
        val b = maxOf(selStart, selEnd).coerceIn(0, all.length)
        return if (b > a && all.substring(a, b).isNotBlank()) {
            FieldText(all.substring(a, b), Target.Range(base + a, base + b))
        } else {
            FieldText(all, Target.Range(base, base + all.length))
        }
    }

    fun replace(ic: InputConnection, target: Target, replacement: String): Boolean {
        ic.beginBatchEdit()
        try {
            ic.finishComposingText()
            return when (target) {
                is Target.Range -> ic.setSelection(target.start, target.end) && ic.commitText(replacement, 1)
                Target.Selection -> ic.commitText(replacement, 1)
                is Target.AroundCursor ->
                    ic.deleteSurroundingText(target.before, target.after) && ic.commitText(replacement, 1)
            }
        } finally {
            ic.endBatchEdit()
        }
    }

    fun replaceLast(ic: InputConnection, find: String, replacement: String): Boolean {
        if (find.isEmpty()) return false
        val et = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_CHARS }, 0)
        val all = et?.text?.toString()
        if (et != null && all != null) {
            val idx = all.lastIndexOf(find)
            if (idx < 0) return false
            val start = et.startOffset + idx
            return replace(ic, Target.Range(start, start + find.length), replacement)
        }
        val field = read(ic) ?: return false
        return field.text == find && replace(ic, field.target, replacement)
    }

    fun stillContains(ic: InputConnection, target: Target, expected: String): Boolean =
        when (target) {
            is Target.Range -> {
                val et = ic.getExtractedText(ExtractedTextRequest().apply { hintMaxChars = MAX_CHARS }, 0)
                val t = et?.text?.toString()
                if (et == null || t == null) {
                    false
                } else {
                    val s = target.start - et.startOffset
                    val e = target.end - et.startOffset
                    s >= 0 && e <= t.length && s <= e && t.substring(s, e) == expected
                }
            }
            else -> read(ic)?.text == expected
        }
}
