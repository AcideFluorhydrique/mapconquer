// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

/**
 * 難度。
 *
 * 只調 AI 的資源與出手意願，不動戰鬥公式 ——
 * 讓 AI 的部隊憑空多打 30% 傷害會讓玩家學不到正確的直覺：
 * 同樣的兵力對比在不同難度下應該打出同樣的結果，
 * 難的地方在於高難度的 AI 有更多兵、更早展開、也更敢打。
 */
enum class Difficulty(
    val key: String,
    /** AI 收入倍率（百分比）。 */
    val aiIncome: Int,
    /** AI 開局資金倍率（百分比）。 */
    val aiStartFunds: Int,
    /** AI 願意進攻的最低勝算門檻，越低越敢打。 */
    val aiAggressionFloor: Int,
    /** 玩家的收入倍率，簡單模式給一點餘裕。 */
    val playerIncome: Int
) {
    RECRUIT("difficulty_recruit", 80, 80, 55, 120),
    OFFICER("difficulty_officer", 100, 100, 45, 100),
    COMMANDER("difficulty_commander", 125, 130, 38, 100),
    MARSHAL("difficulty_marshal", 155, 170, 30, 95);

    companion object {
        val ALL: Array<Difficulty> = values()
        fun byName(name: String): Difficulty =
            ALL.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFFICER
    }
}
