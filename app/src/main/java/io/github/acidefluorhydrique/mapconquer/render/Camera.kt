// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import io.github.acidefluorhydrique.mapconquer.core.Ui
import io.github.acidefluorhydrique.mapconquer.hex.Hex
import io.github.acidefluorhydrique.mapconquer.hex.HexLayout
import io.github.acidefluorhydrique.mapconquer.world.WorldMap

/**
 * 地圖攝影機：平移與縮放。
 *
 * 縮放做成「改變六角格的半徑」而不是對 Canvas 下 scale()：
 * 後者會把線寬、字級一起放大，縮到最小時邊界會糊成一團、地名會小到看不見。
 * 改變格子半徑之後，UI 元素（地名、血條、圖示）可以各自決定要不要跟著縮，
 * 於是「拉遠看全局」時仍然讀得到國界，「拉近看戰場」時才顯示細節。
 */
class Camera(private val map: WorldMap) {

    val layout = HexLayout(DEFAULT_HEX_SIZE)

    /** 視窗左上角對應的世界座標。 */
    var offsetX: Float = 0f
        private set
    var offsetY: Float = 0f
        private set

    var viewWidth: Int = 0
        private set
    var viewHeight: Int = 0
        private set

    /** 地圖區的上下留白：讓出 HUD 的空間，避免格子被壓在資訊列底下。 */
    var insetTop: Float = 0f
    var insetBottom: Float = 0f

    val hexSize: Float get() = layout.size

    fun onResize(width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        clamp()
    }

    fun panBy(dx: Float, dy: Float) {
        offsetX -= dx
        offsetY -= dy
        clamp()
    }

    /** 以螢幕上的 [focusX], [focusY] 為錨點縮放，讓兩指捏合的中心留在原地。 */
    fun zoomTo(newSize: Float, focusX: Float, focusY: Float) {
        val clamped = newSize.coerceIn(minHexSize(), maxHexSize())
        if (clamped == layout.size) return
        val worldX = offsetX + focusX
        val worldY = offsetY + focusY
        val ratio = clamped / layout.size
        layout.size = clamped
        offsetX = worldX * ratio - focusX
        offsetY = worldY * ratio - focusY
        clamp()
    }

    fun zoomBy(factor: Float, focusX: Float, focusY: Float) =
        zoomTo(layout.size * factor, focusX, focusY)

    fun screenX(worldX: Float): Float = worldX - offsetX

    fun screenY(worldY: Float): Float = worldY - offsetY

    fun centreOnTile(tile: Int) {
        val col = map.colOf(tile)
        val row = map.rowOf(tile)
        offsetX = layout.centerXOffset(col, row) - viewWidth / 2f
        offsetY = layout.centerYOffset(row) - (insetTop + viewHeight - insetBottom) / 2f
        clamp()
    }

    /** 螢幕座標 → 格子索引；點在地圖外回傳 -1。 */
    fun tileAt(screenX: Float, screenY: Float): Int {
        val hex = layout.hexAt(screenX + offsetX, screenY + offsetY)
        return tileOf(hex)
    }

    fun tileOf(hex: Hex): Int =
        if (map.inBounds(hex.col, hex.row)) map.indexWrapped(hex.col, hex.row) else -1

    /** 環繞地圖上，一整圈在世界座標裡的寬度。接縫要對得起來就得用這個值。 */
    val worldSpan: Float get() = HexLayout.SQRT3 * layout.size * map.cols

    /**
     * 目前可見的列與欄範圍，供繪製剔除使用。
     * 多留一格邊界，避免捲動時邊緣的格子閃爍。
     *
     * 環繞地圖的欄號**不夾制**：呼叫端會拿到像 -3..97 這種跨過接縫的範圍，
     * 由它負責把欄號折回去取格子、同時用原始欄號算螢幕位置 ——
     * 這正是接縫兩側能無縫接上的原因。
     */
    fun visibleBounds(out: IntArray) {
        val rowStride = layout.rowStride
        val hexWidth = layout.hexWidth
        val firstRow = ((offsetY + insetTop - layout.size) / rowStride).toInt() - 1
        val lastRow = ((offsetY + viewHeight - insetBottom + layout.size) / rowStride).toInt() + 1
        val firstCol = ((offsetX - hexWidth) / hexWidth).toInt() - 1
        val lastCol = ((offsetX + viewWidth + hexWidth) / hexWidth).toInt() + 1
        out[1] = firstRow.coerceAtLeast(0)
        out[3] = lastRow.coerceAtMost(map.rows - 1)
        if (map.wrapX) {
            out[0] = firstCol
            // 拉得再遠也不必畫超過一整圈，否則同一格會被重複畫好幾次。
            out[2] = minOf(lastCol, firstCol + map.cols - 1)
        } else {
            out[0] = firstCol.coerceAtLeast(0)
            out[2] = lastCol.coerceAtMost(map.cols - 1)
        }
    }

    private fun minHexSize(): Float {
        // 至少要能把整張地圖的寬度塞進畫面，否則玩家會失去「這是世界地圖」的感覺。
        val fitWidth = viewWidth / (HexLayout.SQRT3 * (map.cols + 0.5f))
        return maxOf(Ui.dp(5f), fitWidth * 0.85f)
    }

    private fun maxHexSize(): Float = Ui.dp(34f)

    private fun clamp() {
        if (viewWidth == 0 || viewHeight == 0) return
        val worldWidth = layout.worldWidth(map.cols)
        val worldHeight = layout.worldHeight(map.rows)
        if (map.wrapX) {
            // 環繞方向沒有邊界，只有相位：一路往東推會回到出發點。
            val span = worldSpan
            offsetX = ((offsetX % span) + span) % span
        } else {
            offsetX = if (worldWidth <= viewWidth) {
                (worldWidth - viewWidth) / 2f
            } else {
                offsetX.coerceIn(0f, worldWidth - viewWidth)
            }
        }
        val visibleHeight = viewHeight - insetTop - insetBottom
        offsetY = if (worldHeight <= visibleHeight) {
            (worldHeight - visibleHeight) / 2f - insetTop
        } else {
            offsetY.coerceIn(-insetTop, worldHeight - visibleHeight - insetTop)
        }
    }

    /** 開局時把畫面對到自己的首都，並選一個看得見週邊局勢的縮放。 */
    fun frameOn(tile: Int) {
        layout.size = Ui.dp(20f).coerceIn(minHexSize(), maxHexSize())
        centreOnTile(tile)
    }

    companion object {
        val DEFAULT_HEX_SIZE get() = Ui.dp(18f)
    }
}
