// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

/**
 * 省份：地圖上的政治單位，也是本作唯一的「領土」概念。
 *
 * 為什麼領土算在省而不算在格上：一格一格易主會讓地圖在幾回合內變成碎花色，
 * 而且會逼玩家去鋪滿每一格。改成「打下省會＝整省易主」之後，
 * 地圖永遠是大塊顏色，玩家的注意力也自然被推到真正的目標 —— 城市。
 *
 * [cityTier] 0 = 無城（純鄉野省份，只有產值）、1 = 城鎮、2 = 城市、
 * 3 = 大城、4 = 具備首都資格的核心城市。
 */
class Province(
    val id: Int,
    val nameKey: String,
    val cityTier: Int,
    /** 省會所在格（陣列索引）。cityTier = 0 時仍有一格代表省的中心。 */
    val capitalTile: Int,
    /** 屬於本省的所有格。 */
    val tiles: IntArray,
    /** 相鄰省份，供 AI 判斷戰線與補給。 */
    val neighbours: IntArray,
    /** 是否臨海：決定能否造船與被登陸。 */
    val coastal: Boolean
) {
    val hasCity: Boolean get() = cityTier > 0

    /** 每回合基礎收入。城市級距刻意拉開，讓「奪一座大城」值得繞路。 */
    val baseIncome: Int
        get() = when (cityTier) {
            0 -> 2
            1 -> 6
            2 -> 14
            3 -> 26
            else -> 40
        }

    /** 城市自帶的守備加成百分比。 */
    val cityDefenceBonus: Int
        get() = when (cityTier) {
            0 -> 0
            1 -> 15
            2 -> 25
            3 -> 35
            else -> 45
        }

    /** 每回合可產出的工業點數上限：能不能造重裝備看這個。 */
    val industry: Int
        get() = when (cityTier) {
            0 -> 0
            1 -> 1
            2 -> 2
            3 -> 3
            else -> 4
        }

    override fun toString(): String = "Province#$id($nameKey)"
}
