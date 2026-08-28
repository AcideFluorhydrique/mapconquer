// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

import io.github.acidefluorhydrique.mapconquer.core.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 亂數必須是決定性的：存檔存的是 state，讀檔要能接著跑出同一串。 */
class RngTest {

    @Test
    fun `same seed gives the same sequence`() {
        val a = Rng(12345)
        val b = Rng(12345)
        repeat(500) { assertEquals(a.nextInt(1000), b.nextInt(1000)) }
    }

    @Test
    fun `restoring the state resumes the sequence`() {
        val original = Rng(999)
        repeat(37) { original.nextLong() }
        val resumed = Rng(original.state)
        repeat(50) { assertEquals(original.nextInt(64), resumed.nextInt(64)) }
    }

    @Test
    fun `values stay inside their range and spread out`() {
        val rng = Rng(7)
        val buckets = IntArray(10)
        repeat(100_000) {
            val v = rng.nextInt(10)
            assertTrue(v in 0..9)
            buckets[v]++
        }
        // 十萬次之後每一格都該落在期望值的兩成以內。
        for (count in buckets) assertTrue("distribution $count", count in 8000..12000)
        repeat(1000) {
            val f = rng.nextFloat()
            assertTrue(f >= 0f && f < 1f)
        }
    }

    @Test
    fun `string seeds are stable`() {
        assertEquals(Rng.seedOf("conquest_modern"), Rng.seedOf("conquest_modern"))
        assertTrue(Rng.seedOf("a") != Rng.seedOf("b"))
    }
}
