package com.tercuman.keyboard.ime

import java.io.File
import java.io.Reader
import java.util.Locale

object TextFold {
    private val TURKISH: Locale = Locale.forLanguageTag("tr")

    fun fold(s: String): String {
        val lower = s.lowercase(TURKISH)
        val sb = StringBuilder(lower.length)
        for (c in lower) {
            sb.append(
                when (c) {
                    'ç' -> 'c'
                    'ğ' -> 'g'
                    'ı' -> 'i'
                    'ö' -> 'o'
                    'ş' -> 's'
                    'ü' -> 'u'
                    'â' -> 'a'
                    'î' -> 'i'
                    'û' -> 'u'
                    'é' -> 'e'
                    'ê' -> 'e'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    fun isWordChar(c: Char): Boolean = Character.isLetter(c)
}

class WordDictionary private constructor(
    private val keys: Array<String>,
    private val words: Array<String>,
    private val counts: LongArray,
) {
    val size: Int get() = words.size

    fun count(word: String): Long {
        val key = TextFold.fold(word)
        var i = lowerBound(key)
        var best = 0L
        while (i < keys.size && keys[i] == key) {
            if (words[i].equals(word, ignoreCase = true)) best = maxOf(best, counts[i])
            i++
        }
        return best
    }

    fun forEachEntry(action: (key: String, word: String, count: Long) -> Unit) {
        for (i in keys.indices) action(keys[i], words[i], counts[i])
    }

    fun forEachWithPrefix(prefix: String, action: (word: String, count: Long) -> Unit) {
        val key = TextFold.fold(prefix)
        var i = lowerBound(key)
        while (i < keys.size && keys[i].startsWith(key)) {
            action(words[i], counts[i])
            i++
        }
    }

    private fun lowerBound(key: String): Int {
        var lo = 0
        var hi = keys.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (keys[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        fun parse(reader: Reader): WordDictionary {
            val entries = ArrayList<Triple<String, String, Long>>(50_000)
            reader.buffered().forEachLine { line ->
                val tab = line.indexOf('\t')
                if (tab > 0) {
                    val word = line.substring(0, tab)
                    val count = line.substring(tab + 1).trim().toLongOrNull() ?: return@forEachLine
                    entries.add(Triple(TextFold.fold(word), word, count))
                }
            }
            entries.sortWith(compareBy({ it.first }, { -it.third }))
            return WordDictionary(
                Array(entries.size) { entries[it].first },
                Array(entries.size) { entries[it].second },
                LongArray(entries.size) { entries[it].third },
            )
        }
    }
}

class KnownWords private constructor(private val data: ByteArray, private val starts: IntArray) {
    val size: Int get() = starts.size

    fun count(word: String): Long {
        val key = word.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = starts.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val c = compareAt(starts[mid], key)
            when {
                c < 0 -> lo = mid + 1
                c > 0 -> hi = mid - 1
                else -> return parseCount(starts[mid] + key.size + 1)
            }
        }
        return 0L
    }

    private fun compareAt(start: Int, key: ByteArray): Int {
        var i = 0
        while (true) {
            val p = start + i
            val a = if (p < data.size && data[p] != TAB) data[p].toInt() and 0xFF else -1
            val b = if (i < key.size) key[i].toInt() and 0xFF else -1
            if (a != b) return a - b
            if (a == -1) return 0
            i++
        }
    }

    private fun parseCount(from: Int): Long {
        var n = 0L
        var p = from
        while (p < data.size && data[p] in '0'.code.toByte()..'9'.code.toByte()) {
            n = n * 10 + (data[p] - '0'.code.toByte())
            p++
        }
        return n
    }

    companion object {
        private const val TAB = '\t'.code.toByte()
        private const val NL = '\n'.code.toByte()

        fun of(data: ByteArray): KnownWords {
            var lines = 0
            for (b in data) if (b == NL) lines++
            val starts = IntArray(lines)
            var n = 0
            var lineStart = 0
            for (i in data.indices) {
                if (data[i] == NL) {
                    if (i > lineStart && n < lines) starts[n++] = lineStart
                    lineStart = i + 1
                }
            }
            return KnownWords(data, if (n == lines) starts else starts.copyOf(n))
        }
    }
}

class AutoCorrector(
    private val dictionary: WordDictionary,
    private val known: KnownWords?,
    private val user: UserWords,
    private val locale: Locale,
) {
    fun correct(word: String): String? {
        if (word.length < 2 || word.length > 30 || !word.all(TextFold::isWordChar)) return null
        if (word.drop(1).any { it.isUpperCase() }) return null
        val lower = word.lowercase(locale)
        if (user.count(lower) > 0) return null
        val own = maxOf(known?.count(lower) ?: 0L, dictionary.count(lower))
        val folded = TextFold.fold(lower)
        val addsTurkishLetters = lower.none { it in SPECIAL }

        var bestWord: String? = null
        var bestCount = 0L
        var bestDistance = Int.MAX_VALUE
        dictionary.forEachEntry { key, candidate, count ->
            if (candidate == lower || kotlin.math.abs(key.length - folded.length) > 1) return@forEachEntry
            val distance = when {
                key == folded -> if (addsTurkishLetters) 0 else return@forEachEntry
                withinOneEdit(key, folded) -> 1
                else -> return@forEachEntry
            }
            if (distance < bestDistance || (distance == bestDistance && count > bestCount)) {
                if (acceptable(distance, count, own, folded.length)) {
                    bestWord = candidate
                    bestCount = count
                    bestDistance = distance
                }
            }
        }
        val chosen = bestWord ?: return null
        return matchCase(chosen, word)
    }

    private fun acceptable(distance: Int, count: Long, own: Long, length: Int): Boolean = when (distance) {
        0 -> count >= 300 && count >= 20 * own
        else -> when {
            length <= 2 -> false
            length <= 4 -> own < 20 && count >= 20_000
            else -> count >= 2_000 && ((own < 30 && count >= 300 * own) || count >= 3_000 * own)
        }
    }

    private fun matchCase(word: String, typed: String): String =
        if (typed.first().isUpperCase()) word.replaceFirstChar { it.toString().uppercase(locale) } else word

    companion object {
        private const val SPECIAL = "çğıöşüâîûéê"

        fun withinOneEdit(a: String, b: String): Boolean {
            if (a == b) return true
            val la = a.length
            val lb = b.length
            if (kotlin.math.abs(la - lb) > 1) return false
            if (la == lb) {
                var first = -1
                var diffs = 0
                for (i in 0 until la) {
                    if (a[i] != b[i]) {
                        if (first < 0) first = i
                        diffs++
                        if (diffs > 2) return false
                    }
                }
                if (diffs == 1) return true
                return diffs == 2 && first + 1 < la && a[first] == b[first + 1] && a[first + 1] == b[first] &&
                    a.substring(first + 2) == b.substring(first + 2)
            }
            val (s, l) = if (la < lb) a to b else b to a
            var i = 0
            var j = 0
            var skipped = false
            while (i < s.length && j < l.length) {
                if (s[i] == l[j]) {
                    i++
                    j++
                } else {
                    if (skipped) return false
                    skipped = true
                    j++
                }
            }
            return true
        }
    }
}

class UserWords(private val file: File?) {
    private val words = HashMap<String, Int>()
    private val pairs = HashMap<String, HashMap<String, Int>>()
    private var dirty = false

    init {
        load()
    }

    @Synchronized
    fun learn(previous: String?, word: String) {
        if (word.length < 2 || word.length > 40) return
        words[word] = (words[word] ?: 0) + 1
        if (previous != null) {
            val p = TextFold.fold(previous)
            val m = pairs.getOrPut(p) { HashMap() }
            m[word] = (m[word] ?: 0) + 1
        }
        dirty = true
        prune()
    }

    @Synchronized
    fun forget(word: String) {
        words.remove(word)
        pairs.values.forEach { it.remove(word) }
        dirty = true
    }

    @Synchronized
    fun count(word: String): Int = words[word] ?: 0

    @Synchronized
    fun withPrefix(prefix: String): List<Pair<String, Int>> {
        val key = TextFold.fold(prefix)
        return words.filter { TextFold.fold(it.key).startsWith(key) }.map { it.key to it.value }
    }

    @Synchronized
    fun following(previous: String): List<Pair<String, Int>> =
        pairs[TextFold.fold(previous)]?.map { it.key to it.value }.orEmpty()

    @Synchronized
    fun save() {
        val target = file ?: return
        if (!dirty) return
        runCatching {
            val tmp = File(target.path + ".tmp")
            tmp.bufferedWriter().use { w ->
                for ((word, n) in words) w.write("w\t$word\t$n\n")
                for ((p, m) in pairs) for ((word, n) in m) w.write("b\t$p\t$word\t$n\n")
            }
            tmp.renameTo(target)
            dirty = false
        }
    }

    @Synchronized
    fun clear() {
        words.clear()
        pairs.clear()
        file?.delete()
        dirty = false
    }

    private fun load() {
        val source = file ?: return
        if (!source.isFile) return
        runCatching {
            source.forEachLine { line ->
                val f = line.split('\t')
                when {
                    f.size == 3 && f[0] == "w" -> f[2].toIntOrNull()?.let { words[f[1]] = it }
                    f.size == 4 && f[0] == "b" -> f[3].toIntOrNull()?.let { pairs.getOrPut(f[1]) { HashMap() }[f[2]] = it }
                }
            }
        }
    }

    private fun prune() {
        if (words.size > MAX_WORDS) {
            val keep = words.entries.sortedByDescending { it.value }.take(MAX_WORDS * 3 / 4).map { it.key }.toSet()
            words.keys.retainAll(keep)
        }
        if (pairs.size > MAX_WORDS) {
            val keep = pairs.entries.sortedByDescending { e -> e.value.values.sum() }.take(MAX_WORDS * 3 / 4)
                .map { it.key }.toSet()
            pairs.keys.retainAll(keep)
        }
    }

    companion object {
        const val MAX_WORDS = 5000
    }
}

data class WordContext(val partial: String, val previous: String?)

class Predictor(
    private val dictionary: WordDictionary?,
    private val user: UserWords,
    private val locale: Locale,
    private val corrector: AutoCorrector? = null,
) {
    fun autocorrect(word: String): String? {
        if (locale.language == "en" && word == "i") return "I"
        return corrector?.correct(word)
    }

    fun suggest(context: WordContext, limit: Int = 3): List<String> {
        val partial = context.partial
        val raw = if (partial.isEmpty()) nextWords(context.previous, limit) else completions(partial, limit)
        return raw.map { matchCase(it, partial) }.distinct()
    }

    fun learn(previous: String?, word: String) {
        if (word.isEmpty() || !word.all(TextFold::isWordChar)) return
        user.learn(previous, if (word == "I") word else word.lowercase(locale))
    }

    fun forget(word: String) {
        user.forget(if (word == "I") word else word.lowercase(locale))
    }

    fun save() = user.save()

    private fun nextWords(previous: String?, limit: Int): List<String> {
        if (previous == null) return emptyList()
        return user.following(previous).sortedByDescending { it.second }.take(limit).map { it.first }
    }

    private fun completions(partial: String, limit: Int): List<String> {
        val scores = HashMap<String, Double>()
        val lowerPartial = partial.lowercase(locale)
        fun add(word: String, base: Double) {
            var s = base
            if (word.lowercase(locale).startsWith(lowerPartial)) s *= 4.0
            if (word.length == partial.length) s *= 1.5
            scores[word] = maxOf(scores[word] ?: 0.0, s)
        }
        dictionary?.forEachWithPrefix(partial) { word, count ->
            add(word, count.toDouble() + user.count(word) * USER_WEIGHT)
        }
        for ((word, n) in user.withPrefix(partial)) {
            add(word, (dictionary?.count(word) ?: 0L).toDouble() + n * USER_WEIGHT)
        }
        return scores.entries.sortedByDescending { it.value }.take(limit).map { it.key }
    }

    private fun matchCase(word: String, typed: String): String {
        if (typed.isEmpty()) return word
        val allUpper = typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() }
        return when {
            allUpper -> word.uppercase(locale)
            typed.first().isUpperCase() -> word.replaceFirstChar { it.toString().uppercase(locale) }
            else -> word
        }
    }

    companion object {
        const val USER_WEIGHT = 50_000.0

        fun context(textBeforeCursor: String): WordContext {
            var end = textBeforeCursor.length
            var start = end
            while (start > 0 && TextFold.isWordChar(textBeforeCursor[start - 1])) start--
            val partial = textBeforeCursor.substring(start, end)
            end = start
            while (end > 0 && textBeforeCursor[end - 1] == ' ') end--
            if (end > 0 && !TextFold.isWordChar(textBeforeCursor[end - 1])) {
                return WordContext(partial, null)
            }
            var pStart = end
            while (pStart > 0 && TextFold.isWordChar(textBeforeCursor[pStart - 1])) pStart--
            val previous = textBeforeCursor.substring(pStart, end).takeIf { it.isNotEmpty() }
            return WordContext(partial, previous)
        }
    }
}
