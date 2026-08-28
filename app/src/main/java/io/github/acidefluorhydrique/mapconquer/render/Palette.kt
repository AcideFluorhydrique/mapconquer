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

    /** 未探索區域。 */
    val UNEXPLORED: Int get() = Colors.of("#0B1219")

    val GRID: Int get() = Colors.of("#22FFFFFF")
    val PROVINCE_BORDER: Int get() = Colors.of("#40000000")
    val SELECTION: Int get() = Colors.of("#FFD98A")
    val MOVE_RANGE: Int get() = Colors.of("#4C6FC7E8")
    val ATTACK_RANGE: Int get() = Colors.of("#59E86A4C")
    val SUPPLY_HINT: Int get() = Colors.of("#3358C08A")

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

    /** 疊在地形上的領土色。玩家自己的國家亮一些，一眼認得出來。 */
    fun ownershipTint(session: Session, nationId: Int): Int {
        if (nationId < 0) return 0
        val base = nationColour(session, nationId)
        val alpha = if (nationId == session.playerNationId) 0x8C else 0x70
        return Colors.alpha(base, alpha)
    }

    /** 探索過但目前看不到：壓暗並稍微去飽和。 */
    fun fogged(colour: Int): Int = Colors.lerp(colour, Colors.of("#FF0C141C"), 0.45f)

    /** 單位底板顏色：國色加深，好讓上面的白字讀得出來。 */
    fun unitPlate(session: Session, nationId: Int): Int =
        Colors.scale(nationColour(session, nationId), 0.72f)

    fun unitOutline(session: Session, nationId: Int): Int =
        Colors.scale(nationColour(session, nationId), 1.45f)

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
