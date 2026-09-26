// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

/**
 * 地形。
 *
 * 每種地形帶的玩法數字：陸上移動成本、兩個攻擊懲罰、以及能不能通行。
 *
 * 地形不替守方加防禦。跟參考遊戲一樣，它只懲罰**攻方**的裝甲與火炮：
 * 往森林裡開炮、把戰車開上山去打人，傷害會打折；步兵與軍艦不受影響。
 * 所以守山的是步兵，平原才是戰車的地方。
 *
 * [code] 是地圖檔裡的單一字元。改動它等於改動 assets/maps 底下的每一張地圖，別亂動。
 */
enum class Terrain(
    val code: Char,
    val key: String,
    /** 陸軍進入此格要花的移動點；水域為 0（陸軍根本進不去）。 */
    val moveCost: Int,
    /** 攻方是裝甲時，打進這一格的傷害少幾 %。 */
    val armourPenalty: Int,
    /** 攻方是火炮時，打進這一格的傷害少幾 %。 */
    val artilleryPenalty: Int,
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
    OCEAN('~', "terrain_ocean", 0, 0, 0, true, true, false, "#16354F", 0),
    SEA('-', "terrain_sea", 0, 0, 0, true, false, false, "#1E4F70", 0),
    PLAIN('.', "terrain_plain", 1, 0, 0, false, false, true, "#4E6B41", 2),
    FARMLAND(':', "terrain_farmland", 1, 0, 0, false, false, true, "#6E7F3E", 4),
    FOREST('f', "terrain_forest", 2, 5, 10, false, false, true, "#33512F", 2),
    JUNGLE('j', "terrain_jungle", 3, 10, 20, false, false, false, "#2C5732", 1),
    HILLS('h', "terrain_hills", 2, 10, 5, false, false, true, "#6A6238", 2),
    MOUNTAIN('^', "terrain_mountain", 4, 20, 10, false, false, false, "#7A6B57", 1),
    DESERT('d', "terrain_desert", 1, 10, 10, false, false, true, "#A08A55", 1),
    SWAMP('s', "terrain_swamp", 3, 10, 10, false, false, false, "#40573F", 1),
    TUNDRA('t', "terrain_tundra", 2, 0, 0, false, false, true, "#6B7461", 1),
    ICE('*', "terrain_ice", 3, 5, 5, false, false, false, "#B7C6CE", 0),
    RIVER('r', "terrain_river", 3, 0, 0, false, false, true, "#3C6272", 3);

    val isLand: Boolean get() = !isWater

    /**
     * 參考遊戲的地形表：沙漠 10/10、丘陵 10/5、山 20/10、樹林 5/10、森林 10/20
     * （裝甲/火炮），平原、農田、河流沒有懲罰。本專案的森林對應樹林、叢林對應森林；
     * 沼澤、凍原、冰原是參考遊戲沒有的地形，數值是本專案自訂的。
     */
    val hasAttackPenalty: Boolean get() = armourPenalty > 0 || artilleryPenalty > 0

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
