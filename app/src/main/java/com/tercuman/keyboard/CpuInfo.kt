package com.tercuman.keyboard

import java.io.File

object CpuInfo {
    fun recommendedThreads(): Int {
        val maxFreqs = readMaxFrequencies()
        return threadsFor(maxFreqs, Runtime.getRuntime().availableProcessors())
    }

    fun threadsFor(maxFreqs: List<Long>, availableProcessors: Int): Int {
        if (maxFreqs.size >= 2) {
            val slowest = maxFreqs.min()
            val fast = maxFreqs.count { it > slowest }
            if (fast in 2..8) return fast
        }
        return (availableProcessors / 2).coerceIn(2, 4)
    }

    private fun readMaxFrequencies(): List<Long> {
        val result = mutableListOf<Long>()
        var i = 0
        while (true) {
            val f = File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
            if (!File("/sys/devices/system/cpu/cpu$i").exists()) break
            runCatching { f.readText().trim().toLong() }.getOrNull()?.let { result.add(it) }
            i++
            if (i > 64) break
        }
        return result
    }
}
