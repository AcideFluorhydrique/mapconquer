// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.units.TechBranch

/**
 * AI 性格：一個國家**怎麼**打。它不決定**跟誰**打 —— 那是陣營的事，
 * 見 [Nation.bloc]。
 */
enum class AiProfile {
    /** 守成：只在被打時反擊，優先補防線。 */
    TURTLE,

    /** 穩健：先吃中立與弱鄰，戰線推得慢但扎實。 */
    BALANCED,

    /** 侵略：只要算得出勝算就打，願意拉長戰線。 */
    AGGRESSIVE,

    /** 機會主義：專打已經在跟別人交戰的國家。 */
    OPPORTUNIST
}

/**
 * 一個參戰勢力。
 *
 * [code] 是三字母識別碼，劇本檔靠它連結；它同時也是存檔的鍵，
 * 所以劇本一旦發布就不該再改。顯示用的名字走 [nameKey] 查翻譯。
 */
class Nation(
    val id: Int,
    val code: String,
    val nameKey: String,
    val colour: String,
    val aiProfile: AiProfile,
    /** 首都省份 id；-1 代表這個劇本不給它首都（例如流亡政權）。 */
    var capitalProvince: Int,
    /**
     * 國旗 emoji。
     *
     * 用區域指示符（🇩🇪 = U+1F1E9 U+1F1EA）而不是自己畫向量：系統字型本來就有，
     * APK 裡不必放任何素材，而在缺 emoji 字型的裝置上會退化成兩個字母 DE，
     * 仍然認得出是哪一國 —— 等於免費附帶一個合理的 fallback。
     */
    val flag: String = "",
    /**
     * 陣營代碼，空字串代表中立。
     *
     * 這是玩家看得懂的那一層：「軸心」「同盟」「北約」「華約」。
     * [aiProfile] 只描述打法，講不出立場 —— 一張 1939 年的地圖上，
     * 德國與義大利都可以是侵略性格，但它們不會互相宣戰。
     */
    val bloc: String = "",
    /** 參戰回合，見 ScenarioNation.warTurn。 */
    val warTurn: Int = 1
) {
    var funds: Int = 0

    /** 六個分支各自的研發等級 0..[MAX_TECH_LEVEL]。 */
    val tech: IntArray = IntArray(TechBranch.values().size)

    /** 已投入但尚未完成的研發點數，索引同 [tech]。 */
    val techProgress: IntArray = IntArray(TechBranch.values().size)

    var isPlayer: Boolean = false

    var eliminated: Boolean = false

    /** 本回合的收入快照，給 HUD 顯示用。 */
    var lastIncome: Int = 0
    var lastUpkeep: Int = 0

    /** 累計統計，結算畫面用。 */
    var unitsLost: Int = 0
    var unitsKilled: Int = 0
    var provincesTaken: Int = 0

    fun techLevel(branch: TechBranch): Int = tech[branch.ordinal]

    /** 該分支目前提供的百分比加成。 */
    fun techBonus(branch: TechBranch): Int = tech[branch.ordinal] * TECH_BONUS_PER_LEVEL

    fun canResearch(branch: TechBranch): Boolean = tech[branch.ordinal] < MAX_TECH_LEVEL

    /** 下一級所需的研發點數。刻意讓後期昂貴，避免單一分支一路點到底。 */
    fun techCost(branch: TechBranch): Int {
        val next = tech[branch.ordinal]
        return if (next >= MAX_TECH_LEVEL) Int.MAX_VALUE else TECH_COSTS[next]
    }

    companion object {
        const val MAX_TECH_LEVEL = 5
        const val TECH_BONUS_PER_LEVEL = 8

        private val TECH_COSTS = intArrayOf(300, 700, 1300, 2200, 3400)
    }
}
