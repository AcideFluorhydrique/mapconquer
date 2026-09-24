// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import io.github.acidefluorhydrique.mapconquer.game.AirMission
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

    /**
     * 正在挑目標的空中任務；null 代表一般的選取模式。
     * 挑目標時，合法的落點畫在 [attackable] 上，跟攻擊目標同一種紅框。
     */
    var mission: AirMission? = null

    val movable = BooleanArray(tileCount)
    val attackable = BooleanArray(tileCount)

    /** 選到補給車或司令部時，它撐起的補給圈（見 Session.supplyBubble）。 */
    val supplyBubble = BooleanArray(tileCount)
    var hasSupplyBubble: Boolean = false

    /** 正在預覽的行軍路線。 */
    val path = ArrayList<Int>(24)

    var showGrid: Boolean = true
    var showSupply: Boolean = false

    /** 部隊移動的補間動畫：從哪一格、到哪一格、進度 0..1。 */
    var animFrom: Int = -1
    var animTo: Int = -1
    var animProgress: Float = 1f
    var animUnit: ArmyUnit? = null

    /** 飛行中、或正在播命中效果的空中出擊；null 代表沒有。 */
    var sortie: Sortie? = null

    /**
     * 一次空中出擊的動畫。
     *
     * 出擊的結果要等飛機**到了**才結算 —— 先結算的話，目標會在飛機還在半路時
     * 就掉血甚至消失，動畫變成事後補拍。所以這裡只記路線與時間，
     * 抵達時由 GameView 呼叫 AirOps.fly，並把傷害填回 [damage] 給飄字用。
     */
    class Sortie(val mission: AirMission, val from: Int, val to: Int, val flightMs: Int) {
        var elapsedMs = 0

        /** 已經在抵達時結算過了。 */
        var resolved = false

        /** 命中飄字的數字；-1 代表不顯示（空降、或出擊失敗）。 */
        var damage = -1

        val arrived: Boolean get() = elapsedMs >= flightMs

        /** 飛行進度 0..1。 */
        val flight: Float get() = (elapsedMs / flightMs.toFloat()).coerceAtMost(1f)

        /** 抵達之後的命中（或開傘）效果進度 0..1。 */
        val impact: Float get() = ((elapsedMs - flightMs) / IMPACT_MS.toFloat()).coerceIn(0f, 1f)

        companion object {
            const val IMPACT_MS = 520
        }
    }

    /** 飛機還在路上：這段期間不接受任何操作，免得在結算之前換了回合或又下了別的命令。 */
    val sortieInFlight: Boolean get() = sortie?.arrived == false

    fun tickSortie(deltaMs: Int) {
        val s = sortie ?: return
        s.elapsedMs += deltaMs
        if (s.resolved && s.impact >= 1f) sortie = null
    }

    fun clearHighlights() {
        java.util.Arrays.fill(movable, false)
        java.util.Arrays.fill(attackable, false)
        if (hasSupplyBubble) java.util.Arrays.fill(supplyBubble, false)
        hasSupplyBubble = false
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
