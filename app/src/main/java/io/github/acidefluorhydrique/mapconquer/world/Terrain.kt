// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

/**
 * 地形。
 *
 * 每種地形只帶三個真正影響玩法的數字：陸上移動成本、防禦加成、以及能不能通行。
 * 刻意不做「地形 × 兵種」的大表 —— 兵種的差異放在 [io.github.acidefluorhydrique.mapconquer.units.UnitKind]
 * 的 domain 與 mountaineer 這類旗標上，兩邊相乘就夠出戰術，卻不會變成沒人看得懂的平衡黑箱。
 *
 * [code] 是地圖檔裡的單一字元。改動它等於改動 assets/maps 底下的每一張地圖，別亂動。
 */
enum class Terrain(
    val code: Char,
    val key: String,
    /** 陸軍進入此格要花的移動點；水域為 0（陸軍根本進不去）。 */
    val moveCost: Int,
    /** 守方防禦加成百分比。 */
    val defenceBonus: Int,
    val isWater: Boolean,
    /** 深水：登陸艇與淺水單位進不來，主力艦隊的活動空間。 */
    val isDeep: Boolean,
    /** 輪車與履帶（裝甲、火炮、卡車）能不能進。 */
    val vehiclePassable: Boolean,
    /**
     * 地圖上的底色。只有水域真的拿來畫 —— 陸地格的底色是國色，
     * 地形改由 render.TerrainGlyphs 的符號表達。陸地的值留著當地形的代表色。
     */
    val fill: String,
    /** 每回合的基礎產值，城市會在這之上再加。 */
    val income: Int
) {
    OCEAN('~', "terrain_ocean", 0, 0, true, true, false, "#16354F", 0),
    SEA('-', "terrain_sea", 0, 0, true, false, false, "#1E4F70", 0),
    PLAIN('.', "terrain_plain", 1, 0, false, false, true, "#4E6B41", 2),
    FARMLAND(':', "terrain_farmland", 1, 0, false, false, true, "#6E7F3E", 4),
    FOREST('f', "terrain_forest", 2, 20, false, false, true, "#33512F", 2),
    JUNGLE('j', "terrain_jungle", 3, 25, false, false, false, "#2C5732", 1),
    HILLS('h', "terrain_hills", 2, 25, false, false, true, "#6A6238", 2),
    MOUNTAIN('^', "terrain_mountain", 4, 45, false, false, false, "#7A6B57", 1),
    DESERT('d', "terrain_desert", 1, -5, false, false, true, "#A08A55", 1),
    SWAMP('s', "terrain_swamp", 3, 10, false, false, false, "#40573F", 1),
    TUNDRA('t', "terrain_tundra", 2, 5, false, false, true, "#6B7461", 1),
    ICE('*', "terrain_ice", 3, 5, false, false, false, "#B7C6CE", 0),
    RIVER('r', "terrain_river", 3, 15, false, false, true, "#3C6272", 3);

    val isLand: Boolean get() = !isWater

    /** 供補給線與登陸判定使用：淺海緊貼陸地。 */
    val isShallow: Boolean get() = isWater && !isDeep

    companion object {
        private val byCode = HashMap<Char, Terrain>(values().size * 2).apply {
            for (t in values()) put(t.code, t)
        }

        fun ofCode(code: Char): Terrain = byCode[code] ?: OCEAN

        val ALL: Array<Terrain> = values()
    }
}
