// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

/**
 * 決定性亂數（xorshift64*）。
 *
 * 刻意不用 kotlin.random.Random：戰鬥結果、AI 決策與地形細節都要能靠
 * 「種子 + 回合序」完整重現，存檔才存得下、bug 才回放得出來。
 * 種子是存檔的一部分。
 */
class Rng(seed: Long) {

    var state: Long = if (seed == 0L) -0x61C8864680B583EBL else seed
        private set

    fun nextLong(): Long {
        var x = state
        x = x xor (x ushr 12)
        x = x xor (x shl 25)
        x = x xor (x ushr 27)
        state = x
        return x * -0x61c8864680b583ebL
    }

    /** 0 (含) 到 bound (不含)。 */
    fun nextInt(bound: Int): Int {
        if (bound <= 1) return 0
        val v = (nextLong() ushr 1) % bound
        return v.toInt()
    }

    /** lo..hi 皆含。 */
    fun range(lo: Int, hi: Int): Int = if (hi <= lo) lo else lo + nextInt(hi - lo + 1)

    fun nextFloat(): Float = ((nextLong() ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()

    /** 以 percent%（0..100）的機率為真。 */
    fun chance(percent: Int): Boolean = nextInt(100) < percent

    fun <T> pick(items: List<T>): T = items[nextInt(items.size)]

    companion object {
        /** 由字串推出穩定種子，讓同一張劇本每次開局的地形細節一致。 */
        fun seedOf(text: String): Long {
            var h = -0x340d631b7bdddcdbL
            for (c in text) {
                h = h xor c.code.toLong()
                h *= 0x100000001B3L
            }
            return h
        }
    }
}
