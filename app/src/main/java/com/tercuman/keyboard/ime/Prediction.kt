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

class Predictor(private val dictionary: WordDictionary?, private val user: UserWords, private val locale: Locale) {

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
