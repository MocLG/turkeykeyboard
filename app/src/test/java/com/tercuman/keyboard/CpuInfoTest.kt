package com.tercuman.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuInfoTest {
    @Test
    fun countsFastCoresOnBigLittle() {
        val freqs = List(4) { 1_960_000L } + List(5) { 2_600_000L } + listOf(3_110_000L)
        assertEquals(6, CpuInfo.threadsFor(freqs, 10))
    }

    @Test
    fun fallsBackWhenAllCoresEqual() {
        assertEquals(4, CpuInfo.threadsFor(List(8) { 2_000_000L }, 8))
        assertEquals(2, CpuInfo.threadsFor(emptyList(), 2))
    }
}
