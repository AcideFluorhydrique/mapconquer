// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit

/**
 * 地圖上那些「選了什麼、能走到哪、打得到誰」的暫時狀態。
 *
 * 用 BooleanArray 而不是 Set：這些旗標在繪製的內迴圈裡每格都要查一次，
 * 而世界地圖一畫就是好幾百格 —— 陣列索引是零成本，HashSet 不是。
 */
class MapOverlay(tileCount: Int) {

    var selectedUnit: ArmyUnit? = null
    var selectedTile: Int = -1

    val movable = BooleanArray(tileCount)
    val attackable = BooleanArray(tileCount)

    /** 正在預覽的行軍路線。 */
    val path = ArrayList<Int>(24)

    var showGrid: Boolean = true
    var showSupply: Boolean = false

    /** 部隊移動的補間動畫：從哪一格、到哪一格、進度 0..1。 */
    var animFrom: Int = -1
    var animTo: Int = -1
    var animProgress: Float = 1f
    var animUnit: ArmyUnit? = null

    fun clearHighlights() {
        java.util.Arrays.fill(movable, false)
        java.util.Arrays.fill(attackable, false)
        path.clear()
    }

    fun clearSelection() {
        selectedUnit = null
        selectedTile = -1
        clearHighlights()
    }

    fun setMovable(tiles: List<Int>) {
        java.util.Arrays.fill(movable, false)
        for (t in tiles) if (t in movable.indices) movable[t] = true
    }

    fun setAttackable(tiles: List<Int>) {
        java.util.Arrays.fill(attackable, false)
        for (t in tiles) if (t in attackable.indices) attackable[t] = true
    }

    val isAnimating: Boolean get() = animUnit != null && animProgress < 1f

    fun startMoveAnimation(unit: ArmyUnit, from: Int, to: Int) {
        animUnit = unit
        animFrom = from
        animTo = to
        animProgress = 0f
    }

    fun tickAnimation(step: Float) {
        if (animUnit == null) return
        animProgress += step
        if (animProgress >= 1f) {
            animProgress = 1f
            animUnit = null
            animFrom = -1
            animTo = -1
        }
    }
}
