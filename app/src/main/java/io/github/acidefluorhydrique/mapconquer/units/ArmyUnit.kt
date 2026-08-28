// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

/**
 * 場上的一支部隊。
 *
 * 純資料容器：所有需要「看整個戰局才算得出來」的東西（實際攻擊力、
 * 可移動距離、補給是否接得上）都在 Session／Combat 那邊算，
 * 這裡只放屬於這支部隊自己的狀態。這條界線讓存檔變得單純 ——
 * 存下這個物件的欄位就夠還原，不必連帶存一堆推導值。
 *
 * HP 一律 0..100。不同兵種的耐打程度靠 [UnitKind.defence] 表達，
 * 而不是靠不同的血量上限 —— 統一血量讓「還剩幾成」在 UI 上一眼可比。
 */
class ArmyUnit(
    val id: Int,
    val kind: UnitKind,
    var nationId: Int,
    var tile: Int
) {
    var hp: Int = MAX_HP
    var level: Int = 1
    var exp: Int = 0

    /** 0..100。低於 [SUPPLY_STRAINED] 開始掉戰力，歸零會逐回合失血。 */
    var supply: Int = MAX_SUPPLY

    var commanderId: String = ""

    /** 本回合剩餘移動點。回合開始時重置。 */
    var movesLeft: Int = 0

    var hasAttacked: Boolean = false

    /** 原地不動累積的築壕值 0..3，每級 +8% 防禦。移動即歸零。 */
    var entrenchment: Int = 0

    /** 載在身上的部隊 id。 */
    val cargo: MutableList<Int> = ArrayList(4)

    /** 被誰載著；-1 代表在自己的腳上。 */
    var transportId: Int = -1

    val isAlive: Boolean get() = hp > 0

    val isLoaded: Boolean get() = transportId >= 0

    val hasCommander: Boolean get() = commanderId.isNotEmpty()

    val commander: Commander? get() = if (commanderId.isEmpty()) null else Commander.byId(commanderId)

    /** 還能不能做點什麼 —— 決定回合結束提示與「跳到下一支」。 */
    val isSpent: Boolean get() = movesLeft <= 0 && hasAttacked

    /** 補給等級對戰力的乘數。 */
    val supplyFactor: Float
        get() = when {
            supply >= SUPPLY_STRAINED -> 1f
            supply >= SUPPLY_CRITICAL -> 0.8f
            supply > 0 -> 0.6f
            else -> 0.45f
        }

    val entrenchBonus: Int get() = entrenchment * 8

    /** 升級所需累計經驗。刻意讓前兩級便宜、後兩級昂貴。 */
    fun expForNextLevel(): Int = when (level) {
        1 -> 40
        2 -> 100
        3 -> 200
        4 -> 340
        else -> Int.MAX_VALUE
    }

    /** 回傳是否升級了。 */
    fun gainExp(amount: Int): Boolean {
        if (level >= MAX_LEVEL || amount <= 0) return false
        exp += amount
        var levelled = false
        while (level < MAX_LEVEL && exp >= expForNextLevel()) {
            exp -= expForNextLevel()
            level++
            levelled = true
        }
        return levelled
    }

    fun damage(amount: Int) {
        hp = (hp - amount).coerceAtLeast(0)
    }

    fun heal(amount: Int) {
        hp = (hp + amount).coerceAtMost(MAX_HP)
    }

    fun resupply(amount: Int) {
        supply = (supply + amount).coerceAtMost(MAX_SUPPLY)
    }

    companion object {
        const val MAX_HP = 100
        const val MAX_SUPPLY = 100
        const val MAX_LEVEL = 5

        /** 低於這個補給開始掉戰力。 */
        const val SUPPLY_STRAINED = 40
        const val SUPPLY_CRITICAL = 20
    }
}
