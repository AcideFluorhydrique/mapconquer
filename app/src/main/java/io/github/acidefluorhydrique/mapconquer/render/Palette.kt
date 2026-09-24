// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.game.Session
import io.github.acidefluorhydrique.mapconquer.units.Domain
import io.github.acidefluorhydrique.mapconquer.world.Terrain

/**
 * 顏色決策集中在這裡。
 *
 * 一張戰略地圖同時要傳達三件事：地形、歸屬、以及戰爭迷霧。
 * 三者都用顏色表達，很容易互相打架 —— 所以這裡定死了各自的手法：
 *
 *  - **歸屬**獨佔陸地的底色：一格是誰的，就整格塗誰的國色；
 *  - **地形**不用顏色，改成印在國色上的符號（見 [TerrainGlyphs]）。
 *    早期的做法是地形色上疊半透明國色，結果兩者混成一片泥色，
 *    既看不出國界、也看不出山在哪；
 *  - **水域**是唯一保留地形色的地方，海色與國色分得開，海陸關係一眼可讀；
 *  - **迷霧**只降明度、不改色相，因為改色相會讓玩家誤以為換了主人。
 */
object Palette {

    val GRID: Int get() = Colors.of("#22FFFFFF")
    val PROVINCE_BORDER: Int get() = Colors.of("#40000000")
    val SELECTION: Int get() = Colors.of("#FFD98A")
    val MOVE_RANGE: Int get() = Colors.of("#4C6FC7E8")
    val ATTACK_RANGE: Int get() = Colors.of("#59E86A4C")
    val SUPPLY_HINT: Int get() = Colors.of("#3358C08A")

    /**
     * 陣營色。
     *
     * 刻意不用國色：陣營的重點是「站在哪一邊」，而國色的重點是「是哪一國」，
     * 兩者要能同時讀出來。所以陣營只用四個高辨識度的色，跟一百多個國色分開。
     */
    fun blocColour(bloc: String): Int = when (bloc) {
        "AXIS" -> Colors.of("#C9922F")
        "ALLIES" -> Colors.of("#5A93C4")
        "NATO" -> Colors.of("#5A93C4")
        "PACT" -> Colors.of("#C4565A")
        else -> Colors.of("#7F93A6")
    }

    /** 城防血條的顏色。跟部隊血條同一套語彙，玩家不必學第二組。 */
    fun cityHealthColour(hp: Int, max: Int): Int =
        healthColour(if (max <= 0) 1f else hp / max.toFloat())

    /** 陣營的翻譯鍵；空字串（中立）也有自己的字串。 */
    fun blocNameKey(bloc: String): String =
        if (bloc.isEmpty()) "bloc_neutral" else "bloc_" + bloc.lowercase()

    fun nationColour(session: Session, nationId: Int): Int =
        if (nationId < 0 || nationId >= session.nations.size) Colors.of("#7A8794")
        else Colors.of(session.nations[nationId].colour)

    /**
     * 一格的底色。
     *
     * 水域用地形色；陸地用擁有者的國色，無主之地一律是灰。國色往深藍灰拉一點：
     * 劇本裡的國色是為小色塊（旗底、長條圖）調的，整片平塗會太刺眼，
     * 而且壓暗後地形符號的黑墨與城市徽章才浮得起來。
     *
     * 明度抖動只有幾個百分點，一次乘法與位移的成本，讓大片國土不像死板的色塊，
     * 又不至於被誤讀成兩個國家。
     */
    fun tileColour(session: Session, terrain: Terrain, owner: Int, tile: Int): Int {
        if (terrain.isWater) return Colors.of(terrain.fill)
        val base = if (owner < 0) Colors.of(UNCLAIMED_LAND)
        else Colors.lerp(nationColour(session, owner), Colors.of(LAND_SHADE), 0.14f)
        val noise = ((tile * 2654435761L.toInt()) ushr 24) and 0x1F
        return Colors.scale(base, 0.97f + noise / 765f)
    }

    private const val UNCLAIMED_LAND = "#6F746C"
    private const val LAND_SHADE = "#27313B"

    /** 海岸線。陸地與水域之間畫一道亮線，是海陸分界最便宜也最有效的線索。 */
    val COASTLINE: Int get() = Colors.of("#B37FC4E0")

    /**
     * 部隊外框的敵我色。
     *
     * 這一層跟國色是分開的：一百多個國家的顏色再怎麼調都會有相近的，
     * 但「這支是不是我的」必須零猶豫。所以國色留給「是誰」，
     * 外框專門回答「是敵是友」。
     */
    fun relationOutline(session: Session, nationId: Int): Int = when {
        nationId == session.playerNationId -> Colors.of("#FFF3C4")
        session.diplomacy.isAllied(session.playerNationId, nationId) -> Colors.of("#7FE0A0")
        session.isHostile(session.playerNationId, nationId) -> Colors.of("#FF7B6B")
        else -> Colors.of("#9AA8B4")
    }

    /** 單位底板顏色：國色加深，好讓上面的白字讀得出來。 */
    fun unitPlate(session: Session, nationId: Int): Int =
        Colors.scale(nationColour(session, nationId), 0.72f)

    /** 陸海各給一個記號形狀的暗示色，海上單位不至於跟陸軍混在一起。 */
    fun domainAccent(domain: Domain): Int = when (domain) {
        Domain.LAND -> Colors.of("#E8EDF2")
        Domain.SEA -> Colors.of("#9FD8F0")
    }

    /** 空中任務的記號色，沿用原本空軍的那一個。 */
    val AIR_ACCENT: Int get() = Colors.of("#F6D9A0")

    fun healthColour(ratio: Float): Int = when {
        ratio > 0.6f -> Colors.of("#5FBF74")
        ratio > 0.3f -> Colors.of("#D9A93C")
        else -> Colors.of("#C4553C")
    }

    fun supplyColour(ratio: Float): Int = when {
        ratio > 0.4f -> Colors.of("#6FA8D9")
        ratio > 0.2f -> Colors.of("#D9A93C")
        else -> Colors.of("#C4553C")
    }
}
