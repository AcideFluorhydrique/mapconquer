// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.hex

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 尖頂六角格的世界座標換算。
 *
 * 這裡的「世界座標」是地圖自己的像素空間，與螢幕無關；
 * 攝影機（平移／縮放）在 render 層另外套用。
 *
 * size 是外接圓半徑（中心到頂點）。尖頂格的寬 = sqrt(3) * size，高 = 2 * size，
 * 相鄰列在垂直方向重疊 1/4 高度，所以列距是 1.5 * size。
 */
class HexLayout(var size: Float) {

    val hexWidth: Float get() = SQRT3 * size
    val hexHeight: Float get() = 2f * size

    /** 相鄰兩列的中心垂直距離。 */
    val rowStride: Float get() = 1.5f * size

    fun centerX(hex: Hex): Float = size * SQRT3 * (hex.q + hex.r / 2f)

    fun centerY(hex: Hex): Float = size * 1.5f * hex.r

    fun centerXOffset(col: Int, row: Int): Float =
        size * SQRT3 * (col + if (row and 1 == 1) 0.5f else 0f)

    fun centerYOffset(row: Int): Float = size * 1.5f * row

    /** 世界座標 → 格子。落在地圖外也照樣回傳，由呼叫端負責界檢。 */
    fun hexAt(worldX: Float, worldY: Float): Hex {
        val q = (SQRT3 / 3f * worldX - worldY / 3f) / size
        val r = (2f / 3f * worldY) / size
        return HexMath.round(q.toDouble(), r.toDouble())
    }

    /** 把六個頂點寫進 [out]（長度必須 >= 12，格式為 x0,y0,x1,y1,...）。 */
    fun corners(centerX: Float, centerY: Float, out: FloatArray, inset: Float = 0f) {
        val r = size - inset
        for (i in 0 until 6) {
            val angle = ANGLES[i]
            out[i * 2] = centerX + r * cos(angle)
            out[i * 2 + 1] = centerY + r * sin(angle)
        }
    }

    /** 整張地圖在世界座標下的寬度。 */
    fun worldWidth(cols: Int): Float = SQRT3 * size * (cols + 0.5f)

    /** 整張地圖在世界座標下的高度。 */
    fun worldHeight(rows: Int): Float = 1.5f * size * rows + 0.5f * size

    companion object {
        val SQRT3 = sqrt(3f)

        /** 尖頂：頂點在 -90°、-30°、30°、90°、150°、210°。 */
        private val ANGLES = FloatArray(6) { Math.toRadians(60.0 * it - 90.0).toFloat() }
    }
}
