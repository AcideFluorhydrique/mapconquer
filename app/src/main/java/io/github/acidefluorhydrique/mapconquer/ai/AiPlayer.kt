// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.ai

import io.github.acidefluorhydrique.mapconquer.game.AiProfile
import io.github.acidefluorhydrique.mapconquer.game.AirMission
import io.github.acidefluorhydrique.mapconquer.game.AirOps
import io.github.acidefluorhydrique.mapconquer.game.Orders
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

    private enum class Phase { RESEARCH, AIR, PRODUCTION, UNITS, DONE }

    private var phase = Phase.RESEARCH
    private val queue = ArrayDeque<Int>()
    private val reachable = ArrayList<Int>(128)
    private val targets = ArrayList<Int>(32)
    private val path = ArrayList<Int>(32)
    private val airTargets = ArrayList<Int>(64)
    private var sortiesFlown = 0

    private val nation get() = session.nations[nationId]
    private val profile get() = nation.aiProfile

    val isDone: Boolean get() = phase == Phase.DONE

    // AI 沒有外交階段。誰跟誰打由陣營決定，開局就寫在劇本裡 ——
    // 原本會依「接壤 + 國力比」挑弱鄰宣戰，結果是幾十個國家互相亂打，
    // 玩家看到一張跟陣營完全對不上的地圖：軸心內鬥、誰都在打中立國。
    // 宣戰是玩家的權利，不是 AI 的日常行為。

    /** 做一件事。回傳 false 代表這個 AI 這回合已經沒事做了。 */
    fun step(): Boolean {
        // 旁觀者整個回合不做事。守軍留在原地，資金不動，也不研發 ——
        // 中立不是「暫時還沒開打」，是根本不參加。
        if (session.isBystander(nationId)) {
            phase = Phase.DONE
            return false
        }
        when (phase) {
            Phase.RESEARCH -> {
                considerResearch()
                phase = Phase.AIR
            }
            Phase.AIR -> {
                if (!flyOneMission()) phase = Phase.PRODUCTION
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
    // 空中任務
    // ------------------------------------------------------------------

    /**
     * 先轟再走：空襲排在生產與部隊行動之前，讓地面部隊去收打殘的目標。
     *
     * 只在「打下去的價值」接近任務價錢時才飛 —— 拿一百多塊的轟炸去刮一支
     * 九十塊的步兵是虧本生意。每回合架次設上限，錢才不會全燒在天上。
     * 空降不給 AI 用：它挑落點的眼光不夠，只會把傘兵丟進包圍圈送死。
     */
    private fun flyOneMission(): Boolean {
        if (sortiesFlown >= MAX_SORTIES_PER_TURN) return false
        var bestMission: AirMission? = null
        var bestTile = -1
        var bestRatio = MIN_SORTIE_VALUE_RATIO
        for (mission in AirMission.ALL) {
            if (!mission.isStrike) continue
            if (nation.funds < mission.totalCost + AIR_RESERVE) continue
            AirOps.collectTargets(session, nationId, mission, airTargets)
            for (tile in airTargets) {
                val defender = AirOps.strikeTarget(session, nationId, mission, tile) ?: continue
                val share = defender.scaledDamage(AirOps.previewDamage(session, nationId, mission, defender))
                var value = share * defender.kind.cost * defender.size / 100f
                if (share >= defender.hp) value += defender.kind.cost * defender.size * 0.5f
                val ratio = value / mission.totalCost
                if (ratio > bestRatio) {
                    bestRatio = ratio
                    bestMission = mission
                    bestTile = tile
                }
            }
        }
        val mission = bestMission ?: return false
        if (AirOps.fly(session, nationId, mission, bestTile) == null) return false
        sortiesFlown++
        return true
    }

    // ------------------------------------------------------------------
    // 生產
    // ------------------------------------------------------------------

    /** 一次只造一支，讓 [step] 保持細粒度。回傳 false 代表這回合造完了。 */
    private fun buildOneUnit(): Boolean {
        if (nation.funds < MIN_BUILD_FUNDS) return false
        val kind = chooseUnitKind() ?: return false
        val size = chooseFormationSize(kind)

        var bestProvince = -1
        var bestScore = Int.MIN_VALUE
        for (province in session.map.provinces) {
            if (session.provinceOwner[province.id] != nationId) continue
            if (!Orders.canBuild(session, nationId, province.id, kind, size)) continue
            // 靠近戰線的城市優先出兵，省下行軍的回合。
            val score = province.cityTier * 10 - distanceToNearestThreat(province.capitalTile)
            if (score > bestScore) {
                bestScore = score
                bestProvince = province.id
            }
        }
        if (bestProvince < 0) return false
        return Orders.build(session, nationId, bestProvince, kind, size) != null
    }

    /**
     * 編制在徵召時就決定。花不超過手上一半的錢，讓一回合的預算還能分給
     * 別的城市與兵種；錢少的時候退回一個編制。
     */
    private fun chooseFormationSize(kind: UnitKind): Int =
        (nation.funds / 2 / kind.cost).coerceIn(1, ArmyUnit.MAX_SIZE)

    /**
     * 兵種選擇：先補齊「缺什麼」再談「想要什麼」。
     *
     * 配額是寫死的比例而不是一個效用函式 —— 效用函式在這種多兵種互剋的空間裡
     * 很容易收斂到只造一種兵，而固定配額至少保證 AI 的軍隊是均衡的，
     * 玩家也不會遇到「整場只碰到步兵」這種無聊的對手。
     */
    private fun chooseUnitKind(): UnitKind? {
        // 配額照編制數算：一支四編制的步兵是四份步兵，不是一份。
        val owned = session.units.filter { it.nationId == nationId && it.isAlive }
        val total = owned.sumOf { it.size }.coerceAtLeast(1)
        val infantry = owned.filter { it.kind.branch == TechBranch.INFANTRY }.sumOf { it.size }
        val armour = owned.filter { it.kind.branch == TechBranch.ARMOUR }.sumOf { it.size }
        val artillery = owned.filter { it.kind.branch == TechBranch.ARTILLERY }.sumOf { it.size }
        val logistics = owned.filter { it.kind.isSupplier }.sumOf { it.size }

        val wants = ArrayList<UnitKind>(6)
        if (infantry * 100 / total < 40) wants.add(UnitKind.INFANTRY)
        if (logistics == 0 || logistics * 100 / total < 8) wants.add(UnitKind.SUPPLY_TRUCK)
        if (artillery * 100 / total < 20) wants.add(UnitKind.ARTILLERY)
        if (armour * 100 / total < 25) wants.add(UnitKind.ARMOUR)
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
            val defender = Orders.findTarget(session, unit, tile)
            if (defender == null) {
                // 空城：沒有部隊可以評估，用打掉的城防當分數。壓低權重是
                // 因為拆城防不會直接減少對方的戰力，只是為佔領開路。
                val pid = Orders.cityTargetAt(session, unit, tile)
                if (pid < 0) continue
                val score = session.cityHp[pid].coerceAtMost(30) * 0.4f
                if (score > bestScore) {
                    bestScore = score
                    bestTile = tile
                }
                continue
            }
            val ctx = Orders.buildContext(session, unit, defender)
            val dealt = Combat.previewDamage(ctx)
            val taken = if (Combat.canRetaliate(ctx)) {
                Orders.previewDamage(session, defender, unit) * 6 / 10
            } else 0

            // 價值換算成錢：打掉一支貴的部隊比打掉一支便宜的值錢，
            // 而自己的損失也用同一把尺量，AI 才不會拿戰列艦去換運輸船。
            // 預測傷害是原始值，要先換算成對方實際會掉的成數再比。一個四編制的
            // 部隊值四支的錢，也要四支的傷害才打得死。
            val dealtShare = defender.scaledDamage(dealt)
            val takenShare = unit.scaledDamage(taken)
            var score = dealtShare * defender.kind.cost * defender.size / 100f -
                takenShare * unit.kind.cost * unit.size / 100f
            if (dealtShare >= defender.hp) score += defender.kind.cost * defender.size * 0.5f
            if (takenShare >= unit.hp) score -= unit.kind.cost * unit.size * 0.8f
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

        /** 低於這個錢就不研發，留著造兵。 */
        const val RESEARCH_RESERVE = 900

        const val MIN_BUILD_FUNDS = 90

        /** 空襲之後至少留這麼多錢給生產。 */
        const val AIR_RESERVE = 250
        const val MAX_SORTIES_PER_TURN = 3

        /** 預期戰果至少要值任務價錢的這個比例才飛。 */
        const val MIN_SORTIE_VALUE_RATIO = 0.75f
        const val MAX_GOAL_DISTANCE = 28
        const val CITY_ALERT_RANGE = 6
    }
}
