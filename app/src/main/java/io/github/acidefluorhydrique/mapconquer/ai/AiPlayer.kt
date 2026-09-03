// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.ai

import io.github.acidefluorhydrique.mapconquer.game.AiProfile
import io.github.acidefluorhydrique.mapconquer.game.Orders
import io.github.acidefluorhydrique.mapconquer.game.Relation
import io.github.acidefluorhydrique.mapconquer.game.Session
import io.github.acidefluorhydrique.mapconquer.game.UnitMoveRules
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Combat
import io.github.acidefluorhydrique.mapconquer.units.Domain
import io.github.acidefluorhydrique.mapconquer.units.TechBranch
import io.github.acidefluorhydrique.mapconquer.units.UnitKind

/**
 * 電腦玩家。
 *
 * 設計上有兩個硬性要求，其他都是為它們服務的：
 *
 * 1. **可分段執行。** 世界地圖上可能有三十個 AI 國家，如果一個回合結束就
 *    在同一影格裡把它們全部跑完，畫面會凍住好幾秒。所以整個 AI 寫成狀態機，
 *    [step] 一次只做一件事，由 GameView 決定一影格要推進幾步 ——
 *    順帶讓玩家看得到 AI 的部隊一支一支地動，而不是瞬間傳送。
 *
 * 2. **不作弊，但看得到全局。** AI 讀的是完整棋盤（沒有為它另做一套迷霧），
 *    可是它的部隊、金錢、戰鬥公式跟玩家完全一樣。難度調的是資源而不是規則，
 *    這樣玩家在高難度學到的東西才不會是錯的。
 *
 * 決策本身是貪心的：每支部隊獨立挑「這回合我能做的最好的一件事」。
 * 這在六角格戰棋上意外地夠用 —— 因為 ZOC 與補給會自動把貪心的部隊
 * 逼成一條戰線，不需要一個真的懂戰略的規劃器。
 */
class AiPlayer(private val session: Session, private val nationId: Int) {

    private enum class Phase { DIPLOMACY, RESEARCH, PRODUCTION, UNITS, DONE }

    private var phase = Phase.DIPLOMACY
    private val queue = ArrayDeque<Int>()
    private val reachable = ArrayList<Int>(128)
    private val targets = ArrayList<Int>(32)
    private val path = ArrayList<Int>(32)

    private val nation get() = session.nations[nationId]
    private val profile get() = nation.aiProfile

    val isDone: Boolean get() = phase == Phase.DONE

    /** 做一件事。回傳 false 代表這個 AI 這回合已經沒事做了。 */
    fun step(): Boolean {
        when (phase) {
            Phase.DIPLOMACY -> {
                considerDiplomacy()
                phase = Phase.RESEARCH
            }
            Phase.RESEARCH -> {
                considerResearch()
                phase = Phase.PRODUCTION
            }
            Phase.PRODUCTION -> {
                if (!buildOneUnit()) {
                    enqueueUnits()
                    phase = Phase.UNITS
                }
            }
            Phase.UNITS -> {
                if (queue.isEmpty()) {
                    phase = Phase.DONE
                } else {
                    val id = queue.removeFirst()
                    session.unitById(id)?.let { if (it.isAlive) actOn(it) }
                }
            }
            Phase.DONE -> return false
        }
        return phase != Phase.DONE
    }

    // ------------------------------------------------------------------
    // 外交
    // ------------------------------------------------------------------

    /**
     * 宣戰判斷。
     *
     * 只看兩件事：接壤，以及國力比。刻意不做長期的敵意累積 ——
     * 那種模型在幾十個 AI 同時跑的時候會產生一堆玩家看不懂的連鎖反應，
     * 而「弱的鄰居會被打」是任何人第一眼就能理解的規則。
     */
    private fun considerDiplomacy() {
        if (profile == AiProfile.TURTLE) return

        // 一次掃過整張地圖算出「誰跟我接壤」與「每個國家多強」，
        // 而不是每考慮一個對象就重掃一次。世界劇本有上百個國家，
        // 後者是 O(國家² × 省份)，在手機上一個回合就要好幾秒。
        val neighbours = collectBorderingNations()
        if (neighbours.isEmpty()) return
        val strength = strengthTable()

        val myStrength = strength[nationId]
        if (myStrength <= 0) return

        var bestTarget = -1
        var bestRatio = 0f
        for (other in neighbours) {
            if (other == nationId) continue
            val candidate = session.nations[other]
            if (candidate.eliminated) continue
            // 同陣營不互打。沒有這一條，1939 年的德國會因為義大利弱而吃掉它 ——
            // 玩家看到的是一張標著「軸心」卻在內鬥的地圖。
            if (session.sameBloc(nationId, other)) continue
            if (session.diplomacy.relation(nationId, other) != Relation.PEACE) continue
            if (session.diplomacy.truceLeft(nationId, other) > 0) continue

            var ratio = myStrength.toFloat() / strength[other].coerceAtLeast(1)
            // 機會主義者專挑已經在別處交戰的對象。
            if (profile == AiProfile.OPPORTUNIST && isFightingSomeone(other)) ratio *= 1.6f
            if (profile == AiProfile.AGGRESSIVE) ratio *= 1.25f
            if (ratio > bestRatio) {
                bestRatio = ratio
                bestTarget = other
            }
        }
        if (bestTarget >= 0 && bestRatio >= DECLARE_WAR_RATIO) {
            if (session.diplomacy.declareWar(nationId, bestTarget)) {
                session.pushEvent(
                    "event_war_declared",
                    listOf(nation.nameKey, session.nations[bestTarget].nameKey),
                    -1,
                    nationId
                )
            }
        }
    }

    /** 與本國接壤的所有國家 id。 */
    private fun collectBorderingNations(): List<Int> {
        val found = HashSet<Int>(8)
        for (province in session.map.provinces) {
            if (session.provinceOwner[province.id] != nationId) continue
            for (n in province.neighbours) {
                val owner = session.provinceOwner[n]
                if (owner >= 0 && owner != nationId) found.add(owner)
            }
        }
        return found.toList()
    }

    /** 一次算出所有國家的戰力，索引就是國家 id。 */
    private fun strengthTable(): IntArray {
        val table = IntArray(session.nations.size)
        for (unit in session.units) {
            if (!unit.isAlive) continue
            if (unit.nationId in table.indices) {
                table[unit.nationId] += unit.kind.cost * unit.hp / 100
            }
        }
        for (owner in session.provinceOwner) {
            if (owner in table.indices && owner >= 0) table[owner] += 40
        }
        return table
    }

    private fun isFightingSomeone(id: Int): Boolean =
        session.nations.any { it.id != id && !it.eliminated && session.isHostile(id, it.id) }

    // ------------------------------------------------------------------
    // 研發
    // ------------------------------------------------------------------

    /**
     * 研發：只在錢多到「造完兵還有剩」時才點，而且優先補自己最常用的分支。
     * 這讓 AI 不會在缺兵的時候把錢燒在科技上，也不會四個分支平均攤成一事無成。
     */
    private fun considerResearch() {
        if (nation.funds < RESEARCH_RESERVE) return
        var best: TechBranch? = null
        var bestScore = 0
        for (branch in TechBranch.values()) {
            if (!nation.canResearch(branch)) continue
            val cost = nation.techCost(branch)
            if (nation.funds - cost < RESEARCH_RESERVE / 2) continue
            var score = 100 - nation.techLevel(branch) * 10
            score += countUnitsInBranch(branch) * 12
            if (branch == TechBranch.LOGISTICS) score += 20
            if (score > bestScore) {
                bestScore = score
                best = branch
            }
        }
        best?.let { Orders.research(session, nationId, it) }
    }

    private fun countUnitsInBranch(branch: TechBranch): Int =
        session.units.count { it.nationId == nationId && it.isAlive && it.kind.branch == branch }

    // ------------------------------------------------------------------
    // 生產
    // ------------------------------------------------------------------

    /** 一次只造一支，讓 [step] 保持細粒度。回傳 false 代表這回合造完了。 */
    private fun buildOneUnit(): Boolean {
        if (nation.funds < MIN_BUILD_FUNDS) return false
        val kind = chooseUnitKind() ?: return false

        var bestProvince = -1
        var bestScore = Int.MIN_VALUE
        for (province in session.map.provinces) {
            if (session.provinceOwner[province.id] != nationId) continue
            if (!Orders.canBuild(session, nationId, province.id, kind)) continue
            // 靠近戰線的城市優先出兵，省下行軍的回合。
            val score = province.cityTier * 10 - distanceToNearestThreat(province.capitalTile)
            if (score > bestScore) {
                bestScore = score
                bestProvince = province.id
            }
        }
        if (bestProvince < 0) return false
        return Orders.build(session, nationId, bestProvince, kind) != null
    }

    /**
     * 兵種選擇：先補齊「缺什麼」再談「想要什麼」。
     *
     * 配額是寫死的比例而不是一個效用函式 —— 效用函式在這種多兵種互剋的空間裡
     * 很容易收斂到只造一種兵，而固定配額至少保證 AI 的軍隊是均衡的，
     * 玩家也不會遇到「整場只碰到步兵」這種無聊的對手。
     */
    private fun chooseUnitKind(): UnitKind? {
        val owned = session.units.filter { it.nationId == nationId && it.isAlive }
        val total = owned.size.coerceAtLeast(1)
        val infantry = owned.count { it.kind.branch == TechBranch.INFANTRY }
        val armour = owned.count { it.kind.branch == TechBranch.ARMOUR }
        val artillery = owned.count { it.kind.branch == TechBranch.ARTILLERY }
        val air = owned.count { it.kind.domain == Domain.AIR }
        val logistics = owned.count { it.kind.isSupplier }

        val wants = ArrayList<UnitKind>(6)
        if (infantry * 100 / total < 40) wants.add(UnitKind.INFANTRY)
        if (logistics == 0 || logistics * 100 / total < 8) wants.add(UnitKind.SUPPLY_TRUCK)
        if (artillery * 100 / total < 20) wants.add(UnitKind.ARTILLERY)
        if (armour * 100 / total < 25) wants.add(UnitKind.ARMOUR)
        if (air * 100 / total < 12) wants.add(UnitKind.FIGHTER)
        if (wants.isEmpty()) wants.add(if (session.rng.chance(50)) UnitKind.ARMOUR else UnitKind.INFANTRY)

        // 從想要的清單裡挑一個現在買得起、而且真的有城市造得出來的。
        for (kind in wants) {
            if (nation.funds < kind.cost) continue
            val buildable = session.map.provinces.any {
                session.provinceOwner[it.id] == nationId &&
                    Orders.canBuild(session, nationId, it.id, kind)
            }
            if (buildable) return kind
        }
        return null
    }

    private fun distanceToNearestThreat(tile: Int): Int {
        var best = Int.MAX_VALUE
        for (unit in session.units) {
            if (!unit.isAlive || !session.isHostile(unit.nationId, nationId)) continue
            val d = session.map.distance(tile, unit.tile)
            if (d < best) best = d
        }
        return if (best == Int.MAX_VALUE) 40 else best
    }

    // ------------------------------------------------------------------
    // 部隊行動
    // ------------------------------------------------------------------

    private fun enqueueUnits() {
        queue.clear()
        // 先動砲兵與空軍（打完再讓步兵上去收），最後才是補給車。
        val owned = session.units
            .filter { it.nationId == nationId && it.isAlive && !it.isLoaded }
            .sortedBy { orderPriority(it) }
        for (unit in owned) queue.add(unit.id)
    }

    private fun orderPriority(unit: ArmyUnit): Int = when {
        unit.kind.isBombard -> 0
        unit.kind.domain == Domain.AIR -> 1
        unit.kind.isSupplier -> 4
        unit.kind.canCapture -> 3
        else -> 2
    }

    private fun actOn(unit: ArmyUnit) {
        if (unit.kind.isSupplier) {
            moveTowardsFriendlyFront(unit)
            return
        }
        // 先看站著不動能不能打到人 —— 砲兵尤其常常已經在射程內了。
        if (tryAttackFrom(unit)) return

        val goal = chooseGoal(unit)
        if (goal >= 0) moveTowards(unit, goal)
        // 走完之後再試一次：移動常常會把目標帶進射程。
        tryAttackFrom(unit)
    }

    /** 從現在的位置挑一個最划算的目標開火。 */
    private fun tryAttackFrom(unit: ArmyUnit): Boolean {
        if (unit.hasAttacked || !unit.kind.canAttack) return false
        Orders.collectTargets(session, unit, targets)
        if (targets.isEmpty()) return false

        var bestTile = -1
        var bestScore = 0f
        for (tile in targets) {
            val defender = Orders.findTarget(session, unit, tile) ?: continue
            val ctx = Orders.buildContext(session, unit, defender)
            val dealt = Combat.previewDamage(ctx)
            val taken = if (Combat.canRetaliate(ctx)) {
                Orders.previewDamage(session, defender, unit) * 6 / 10
            } else 0

            // 價值換算成錢：打掉一支貴的部隊比打掉一支便宜的值錢，
            // 而自己的損失也用同一把尺量，AI 才不會拿戰列艦去換運輸船。
            var score = dealt * defender.kind.cost / 100f - taken * unit.kind.cost / 100f
            if (dealt >= defender.hp) score += defender.kind.cost * 0.5f
            if (taken >= unit.hp) score -= unit.kind.cost * 0.8f
            // 守著城市的敵人優先清掉，那是勝利條件所在。
            if (session.map.provinceAt(defender.tile)?.capitalTile == defender.tile) score *= 1.3f

            if (score > bestScore) {
                bestScore = score
                bestTile = tile
            }
        }
        if (bestTile < 0) return false
        // 勝算門檻由難度決定：簡單模式的 AI 只在很有把握時才出手。
        if (bestScore < session.difficulty.aiAggressionFloor / 10f) return false
        return Orders.attack(session, unit, bestTile) != null
    }

    /**
     * 目標選擇。順序就是優先級：
     * 先救自己快掉的城，再打對面最近的城，都沒有的話往最近的敵人靠。
     */
    private fun chooseGoal(unit: ArmyUnit): Int {
        if (profile == AiProfile.TURTLE) {
            threatenedOwnCity(unit)?.let { return it }
        }
        var best = -1
        var bestScore = Float.NEGATIVE_INFINITY
        for (province in session.map.provinces) {
            val owner = session.provinceOwner[province.id]
            val hostile = owner < 0 || session.isHostile(owner, nationId)
            if (!hostile) continue
            if (owner < 0 && !province.hasCity) continue

            val distance = session.map.distance(unit.tile, province.capitalTile)
            if (distance > MAX_GOAL_DISTANCE) continue
            var score = province.cityTier * 30f + 20f - distance * 2.5f
            // 中立省份是白送的，優先吃。
            if (owner < 0) score += 25f
            if (score > bestScore) {
                bestScore = score
                best = province.capitalTile
            }
        }
        if (best < 0) {
            threatenedOwnCity(unit)?.let { return it }
        }
        return best
    }

    private fun threatenedOwnCity(unit: ArmyUnit): Int? {
        var best: Int? = null
        var bestScore = Float.NEGATIVE_INFINITY
        for (province in session.map.provinces) {
            if (session.provinceOwner[province.id] != nationId || !province.hasCity) continue
            val threat = distanceToNearestThreat(province.capitalTile)
            if (threat > CITY_ALERT_RANGE) continue
            val score = province.cityTier * 20f - threat * 3f -
                session.map.distance(unit.tile, province.capitalTile) * 1.5f
            if (score > bestScore) {
                bestScore = score
                best = province.capitalTile
            }
        }
        return best
    }

    /** 補給車跟著自己人跑：找補給最低的友軍，站到它旁邊。 */
    private fun moveTowardsFriendlyFront(unit: ArmyUnit) {
        var target = -1
        var worstSupply = ArmyUnit.MAX_SUPPLY
        for (other in session.units) {
            if (other.nationId != nationId || !other.isAlive || other.id == unit.id) continue
            if (other.kind.isSupplier) continue
            if (other.supply < worstSupply) {
                worstSupply = other.supply
                target = other.tile
            }
        }
        if (target >= 0 && worstSupply < ArmyUnit.SUPPLY_STRAINED) moveTowards(unit, target)
    }

    /**
     * 朝目標走一步。
     *
     * 用「可達範圍裡離目標最近的格」而不是完整的多回合路徑：
     * 完整路徑在有敵軍的動態地圖上每回合都會作廢，算它是浪費；
     * 而貪心地縮短直線距離，配合 ZOC 造成的自然阻塞，
     * 產生的行軍路線在觀感上跟真的規劃過差不多。
     */
    private fun moveTowards(unit: ArmyUnit, goal: Int) {
        if (unit.movesLeft <= 0) return
        Orders.computeReachable(session, unit, reachable)
        if (reachable.isEmpty()) return

        val map = session.map
        var bestTile = -1
        var bestScore = Float.NEGATIVE_INFINITY
        val currentDistance = map.distance(unit.tile, goal)

        for (tile in reachable) {
            var score = (currentDistance - map.distance(tile, goal)) * 10f
            // 同樣的推進距離下，挑防禦地形好的那一格。
            score += map.terrainAt(tile).defenceBonus * 0.25f
            // 別走出補給範圍。
            if (!session.isSupplied(tile)) score -= 12f
            // 浮渡中的陸軍防禦幾乎歸零，是活靶。AI 只有在能大幅拉近距離時
            // 才值得下水 —— 這個懲罰讓它願意渡窄海峽，但不會整批走進大洋。
            if (unit.kind.domain == Domain.LAND && map.terrainAt(tile).isWater) score -= 35f
            if (tile == goal) score += 40f
            if (score > bestScore) {
                bestScore = score
                bestTile = tile
            }
        }
        if (bestTile < 0 || bestScore <= 0f) return
        Orders.move(session, unit, bestTile, path)
    }

    /** 給 AI 用的無敵人路徑規劃，保留給之後的長程海運調度。 */
    @Suppress("unused")
    private fun idealRouteLength(unit: ArmyUnit, goal: Int): Int =
        session.pathfinder.routeTo(unit.tile, goal, UnitMoveRules(session, unit, ignoreEnemies = true), path)

    companion object {
        /** 國力比要到這個倍數才會主動宣戰。 */
        const val DECLARE_WAR_RATIO = 1.35f

        /** 低於這個錢就不研發，留著造兵。 */
        const val RESEARCH_RESERVE = 900

        const val MIN_BUILD_FUNDS = 90
        const val MAX_GOAL_DISTANCE = 28
        const val CITY_ALERT_RANGE = 6
    }
}
