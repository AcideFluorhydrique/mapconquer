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

    /**
     * 編制數 1..[MAX_SIZE]。
     *
     * HP 仍然是 0..100，但它是「這個編制還剩幾成」，不是絕對血量。大編制的
     * 耐打靠 [scaledDamage] 表達：同一發傷害，三個編制掉的比例比一個編制少。
     * 這樣補給、整補、血條這些以百分比運作的東西全部不必改。
     */
    var size: Int = 1

    var level: Int = 1
    var exp: Int = 0

    /** 0..100。低於 [SUPPLY_STRAINED] 開始掉戰力，歸零會逐回合失血。 */
    var supply: Int = MAX_SUPPLY

    /**
     * 謠言層數。
     *
     * 士氣本身**不存**，它是由當下態勢推導出來的（見 Session.moraleOf）：
     * 夾擊 −1、包圍 −2、每層謠言再 −1。只有謠言需要持久化，因為它是
     * 「上一回合被做了什麼」而不是「現在站在哪」。
     *
     * 第一版把士氣做成會逐回合累加的計數器，結果只要待在接觸線上就必然崩潰，
     * 跟站位完全無關 —— 那讓「先包圍再打」這個戰術失去了意義，因為不包圍也會垮。
     */
    var rumour: Int = 0

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

    fun addRumour(stacks: Int = 1) {
        rumour = (rumour + stacks).coerceIn(0, MAX_RUMOUR)
    }

    /** 每回合散去一層。謠言是壓制，不是永久減益。 */
    fun decayRumour() {
        if (rumour > 0) rumour--
    }

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
        hp = (hp - scaledDamage(amount)).coerceAtLeast(0)
    }

    /** 一發原始傷害落在這個編制上，實際會掉幾成。 */
    fun scaledDamage(raw: Int): Int {
        if (raw <= 0) return 0
        val percent = hpPercent(size)
        return ((raw * 100 + percent - 1) / percent).coerceAtLeast(1)
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
        const val MAX_SIZE = 4

        /**
         * 編制的攻擊與耐打倍率（百分比）。
         *
         * 兩條曲線都遞增、但每一格都追不上「拆開的同樣幾支」：兩個一編制的部隊
         * 合計 200% 攻擊、200% 耐打，併成一個兩編制只剩 130% 與 160%。所以併編
         * 永遠有代價 —— 它換到的是「一格裡的集中」，而在一格一支部隊的規則下，
         * 那是守窄正面、或打動單支部隊打不動的目標唯一的方法。
         */
        fun attackPercent(size: Int): Int = when (size.coerceIn(1, MAX_SIZE)) {
            1 -> 100
            2 -> 130
            3 -> 155
            else -> 175
        }

        fun hpPercent(size: Int): Int = when (size.coerceIn(1, MAX_SIZE)) {
            1 -> 100
            2 -> 160
            3 -> 215
            else -> 265
        }

        /** 低於這個補給開始掉戰力。 */
        const val SUPPLY_STRAINED = 40
        const val SUPPLY_CRITICAL = 20

        /** 士氣階梯：+1 高昂、0 正常、−1 夾擊、−2 包圍、−3 混亂。 */
        const val MIN_MORALE = -3
        const val MAX_MORALE = 1

        /** 三層謠言單獨就足以打進混亂。 */
        const val MAX_RUMOUR = 3
    }
}
