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
 *  - **地形**用色相（綠＝植被、褐＝乾旱、藍＝水），並帶一點依格子雜湊的明度抖動，
 *    讓大片平原不會看起來像一塊死板的色塊；
 *  - **歸屬**用疊在地形上的半透明國色，飽和度刻意壓低，
 *    這樣同一塊地既看得出是誰的、也還看得出底下是山還是平原；
 *  - **迷霧**只降明度、不改色相，因為改色相會讓玩家誤以為地形變了。
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

    /** 依格子索引做一點明暗抖動，成本只有一次乘法與位移。 */
    fun terrainColour(terrain: Terrain, tile: Int): Int {
        val base = Colors.of(terrain.fill)
        if (terrain.isWater) return base
        val noise = ((tile * 2654435761L.toInt()) ushr 24) and 0x1F
        val factor = 0.93f + noise / 255f
        return Colors.scale(base, factor)
    }

    fun nationColour(session: Session, nationId: Int): Int =
        if (nationId < 0 || nationId >= session.nations.size) Colors.of("#7A8794")
        else Colors.of(session.nations[nationId].colour)

    /**
     * 疊在地形上的領土色。
     *
     * alpha 刻意壓得很低。第一版用 0x8C／0x70，結果整張地圖變成一片平塗的國色，
     * 地形完全看不見 —— 更糟的是玩家會把自己的藍色領土誤認成海。
     * 歸屬的主要視覺線索應該是**國界線**（粗、亮、只畫在邊上），
     * 填色只負責「這一片大致是誰的」，不該壓過底下的森林與山地。
     */
    fun ownershipTint(session: Session, nationId: Int): Int {
        if (nationId < 0) return 0
        val base = nationColour(session, nationId)
        val alpha = if (nationId == session.playerNationId) 0x4A else 0x3A
        return Colors.alpha(base, alpha)
    }

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

    /** 三個層面各給一個記號形狀的暗示色，海空單位不至於跟陸軍混在一起。 */
    fun domainAccent(domain: Domain): Int = when (domain) {
        Domain.LAND -> Colors.of("#E8EDF2")
        Domain.SEA -> Colors.of("#9FD8F0")
        Domain.AIR -> Colors.of("#F6D9A0")
    }

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
