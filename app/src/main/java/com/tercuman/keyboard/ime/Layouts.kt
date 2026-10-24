package com.tercuman.keyboard.ime

import java.util.Locale

enum class KeyType { CHAR, SHIFT, BACKSPACE, ENTER, SPACE, SYMBOLS, LETTERS, MORE_SYMBOLS, GLOBE }

data class Key(
    val type: KeyType,
    val text: String = "",
    val width: Float = 1f,
    val gapBefore: Float = 0f,
    val alternates: List<String> = emptyList(),
    val defaultAlternate: Int = 0,
) {
    val preferredAlternate: String? get() = alternates.getOrNull(defaultAlternate)
}

typealias Row = List<Key>

enum class Page { LETTERS, SYMBOLS, MORE_SYMBOLS }

enum class LetterLayout { QWERTY, TURKISH_Q }

object Layouts {
    const val COLUMNS = 12f

    val TURKISH: Locale = Locale.forLanguageTag("tr")

    private fun chars(s: String, width: Float = 1f): List<Key> =
        s.split(" ").filter { it.isNotEmpty() }.map { Key(KeyType.CHAR, it, width) }

    private val numberRow: Row = chars("1 2 3 4 5 6 7 8 9 0", 1.2f)

    private val qwertyAlternates: Map<String, List<String>> = mapOf(
        "q" to listOf("1"),
        "w" to listOf("2"),
        "e" to listOf("3", "é", "ê"),
        "r" to listOf("4"),
        "t" to listOf("5"),
        "y" to listOf("6"),
        "u" to listOf("7", "ü", "û"),
        "i" to listOf("8", "ı", "î"),
        "o" to listOf("9", "ö", "ô"),
        "p" to listOf("0"),
        "a" to listOf("â"),
        "s" to listOf("ş"),
        "g" to listOf("ğ"),
        "c" to listOf("ç"),
    )

    private fun qwertyKeys(s: String, width: Float): List<Key> =
        chars(s, width).map { key ->
            val alts = qwertyAlternates[key.text].orEmpty()
            val firstLetter = alts.indexOfFirst { it.any(Char::isLetter) }
            key.copy(alternates = alts, defaultAlternate = if (firstLetter >= 0) firstLetter else 0)
        }

    private val qwerty: List<Row> = listOf(
        qwertyKeys("q w e r t y u i o p", 1.2f),
        qwertyKeys("a s d f g h j k l", 1.2f),
        listOf(Key(KeyType.SHIFT, "⇧", 1.8f)) + qwertyKeys("z x c v b n m", 1.2f) + Key(KeyType.BACKSPACE, "⌫", 1.8f),
    )

    private val turkishQ: List<Row> = listOf(
        chars("q w e r t y u ı o p ğ ü"),
        listOf(Key(KeyType.CHAR, "a", gapBefore = 0.5f)) + chars("s d f g h j k l ş i"),
        listOf(Key(KeyType.SHIFT, "⇧", 1.5f)) + chars("z x c v b n m ö ç") + Key(KeyType.BACKSPACE, "⌫", 1.5f),
    )

    private val symbols: List<Row> = listOf(
        chars("1 2 3 4 5 6 7 8 9 0", 1.2f),
        chars("@ # ₺ _ & - + ( ) /", 1.2f),
        listOf(Key(KeyType.MORE_SYMBOLS, "=\\<", 1.5f)) + chars("* \" ' : ; ! ?", 9f / 7f) +
            Key(KeyType.BACKSPACE, "⌫", 1.5f),
    )

    private val moreSymbols: List<Row> = listOf(
        chars("~ ` | • √ π ÷ × ¶ ∆", 1.2f),
        chars("€ $ £ ¢ ^ ° = { } \\", 1.2f),
        listOf(Key(KeyType.SYMBOLS, "?123", 1.5f)) + chars("% © ® ™ ✓ [ ]", 9f / 7f) +
            Key(KeyType.BACKSPACE, "⌫", 1.5f),
    )

    private fun bottomRow(page: Page): Row = listOf(
        if (page == Page.LETTERS) Key(KeyType.SYMBOLS, "?123", 1.5f) else Key(KeyType.LETTERS, "ABC", 1.5f),
        Key(KeyType.CHAR, ",", 1f),
        Key(KeyType.SPACE, "Türkçe", 6f),
        Key(KeyType.CHAR, ".", 1f),
        Key(KeyType.ENTER, "⏎", 2.5f),
    )

    fun rows(page: Page, numberRowOnLetters: Boolean, layout: LetterLayout = LetterLayout.QWERTY): List<Row> = when (page) {
        Page.LETTERS -> (if (numberRowOnLetters) listOf(numberRow) else emptyList()) +
            (if (layout == LetterLayout.QWERTY) qwerty else turkishQ) + listOf(bottomRow(page))
        Page.SYMBOLS -> symbols + listOf(bottomRow(page))
        Page.MORE_SYMBOLS -> moreSymbols + listOf(bottomRow(page))
    }

    fun upper(s: String): String = s.uppercase(TURKISH)

    fun rowWidth(row: Row): Float = row.sumOf { (it.width + it.gapBefore).toDouble() }.toFloat()
}
