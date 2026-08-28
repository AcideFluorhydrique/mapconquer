// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

import android.graphics.Color
import java.util.concurrent.ConcurrentHashMap

/**
 * 顏色字串的解析快取。
 *
 * 畫面全部是 Canvas 自繪，顏色寫成十六進位字串散在各個 renderer 裡。
 * 直接呼叫 [Color.parseColor] 的問題不在 CPU 而在配置：它內部要 substring
 * 出新字串再 parseLong，而這些呼叫全躺在每幀的 draw 路徑上 ——
 * 一張世界地圖一輪就有上千次，等於每秒丟幾萬個短命字串給 GC。
 */
object Colors {

    private val cache = ConcurrentHashMap<String, Int>()

    fun of(hex: String): Int = cache.computeIfAbsent(hex) { Color.parseColor(it) }

    /** 在既有顏色上換透明度，alpha 為 0..255。 */
    fun alpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    /** t=0 回傳 a、t=1 回傳 b 的線性內插，含 alpha 通道。 */
    fun lerp(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + (bv - av) * k).toInt().coerceIn(0, 255)
        }
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** 依係數調亮或調暗，用來從陣營底色推導邊界線與高光。 */
    fun scale(color: Int, factor: Float): Int {
        fun ch(shift: Int) = (((color shr shift) and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (color and 0xFF000000.toInt()) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}
