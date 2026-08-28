// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

import kotlin.math.max
import kotlin.math.min

/**
 * 全域介面縮放。
 *
 * 所有 UI 尺寸都用「基準單位」寫，再乘上依螢幕高度推出的係數，
 * 不依賴系統回報的 density —— 平板與模擬器上它常常不可靠。
 */
object Ui {

    private const val BASE_HEIGHT = 400f

    var scale: Float = 1f
        private set
    var screenWidth: Int = 0
        private set
    var screenHeight: Int = 0
        private set

    fun onSurfaceChanged(width: Int, height: Int) {
        screenWidth = width
        screenHeight = height
        scale = (height / BASE_HEIGHT).coerceIn(1f, 5f)
    }

    /** 以基準尺寸換算成實際像素。 */
    fun dp(value: Float): Float = value * scale

    /** 上方資訊列（回合、國家、資金、結束回合）。 */
    val topBarHeight: Float get() = dp(40f)

    /** 下方單位／地格資訊列。 */
    val bottomBarHeight: Float get() = dp(54f)

    /** 觸控判定的最小舒適邊長。 */
    val touchSlop: Float get() = dp(9f)

    fun clamp(value: Float, lo: Float, hi: Float): Float = max(lo, min(hi, value))

    fun clampInt(value: Int, lo: Int, hi: Int): Int = max(lo, min(hi, value))
}
