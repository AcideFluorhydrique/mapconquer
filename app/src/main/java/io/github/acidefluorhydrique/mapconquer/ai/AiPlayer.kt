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
import io.github.acidefluorhydrique.mapconquer.world.WorldMap

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
    private var builtThisTurn = 0

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
                val dealt = AirOps.previewDamage(session, nationId, mission, defender)
                var value = worth(defender, dealt)
                if (dealt >= defender.hp) value += defender.kind.cost * defender.size * 0.5f
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
        // 固守的國家一回合只補幾支兵。這條是為了地盤很大的守方（1980 年的灰燼軍團）：
        // 沒有上限的話，兩百座城的收入每回合都會整筆變成部隊，開局幾回合就把所有人淹死。
        if (profile == AiProfile.TURTLE && builtThisTurn >= TURTLE_BUILDS_PER_TURN) return false
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
        if (Orders.build(session, nationId, bestProvince, kind, size) == null) return false
        builtThisTurn++
        return true
    }

    /**
     * 編制在徵召時就決定。花不超過手上一半的錢，讓一回合的預算還能分給
     * 別的城市與兵種；錢少的時候退回一個編制。
     */
    private fun chooseFormationSize(kind: UnitKind): Int =
        // 運輸艦一艘就是一艘：編制大不會讓它多載人。
        if (kind.isTransport) 1 else (nation.funds / 2 / kind.cost).coerceIn(1, ArmyUnit.MAX_SIZE)

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
        // 有人在岸上等船、船又不夠：先造船。沒有船，隔著海的敵人永遠打不到。
        if (needsAnotherTransport()) wants.add(UnitKind.TRANSPORT_SHIP)
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
        if (unit.kind.isTransport) {
            actAsTransport(unit)
            return
        }
        if (unit.kind.isSupplier) {
            moveTowardsFriendlyFront(unit)
            return
        }
        // 先看站著不動能不能打到人 —— 砲兵尤其常常已經在射程內了。
        if (tryAttackFrom(unit)) return

        val goal = chooseGoal(unit)
        if (goal >= 0) {
            // 遠渡重洋搭船去：旁邊有船就上，附近有船就走過去等，都沒有才自己下水。
            val ferry = if (wantsFerry(unit, goal)) nearestFerry(unit) else null
            if (ferry != null) {
                if (Orders.canLoad(session, unit, ferry)) {
                    Orders.load(session, unit, ferry)
                    return
                }
                moveTowards(unit, ferry.tile, stayAshore = true)
                if (Orders.canLoad(session, unit, ferry)) Orders.load(session, unit, ferry)
                return
            }
            moveTowards(unit, goal)
        }
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
                val wall = Combat.previewCityStrike(
                    unit,
                    nation.techBonus(unit.kind.branch),
                    session.commandAura(unit),
                    session.moraleOf(unit)
                )
                val score = wall.coerceAtMost(session.cityHp[pid]).coerceAtMost(CITY_STRIKE_SCORE_CAP) * 0.4f
                if (score > bestScore) {
                    bestScore = score
                    bestTile = tile
                }
                continue
            }
            val ctx = Orders.buildContext(session, unit, defender)
            val dealt = Combat.previewDamage(ctx)
            // 反擊跟主動攻擊同一條公式，而且看的是挨打之後的殘血。
            val taken = Combat.previewRetaliation(ctx, dealt)

            // 價值換算成錢：打掉一支貴的部隊比打掉一支便宜的值錢，
            // 而自己的損失也用同一把尺量，AI 才不會拿戰列艦去換運輸船。
            // 一個四編制的部隊值四支的錢，也要夠多的傷害才打得死。
            var score = worth(defender, dealt) - worth(unit, taken)
            if (dealt >= defender.hp) score += defender.kind.cost * defender.size * 0.5f
            if (taken >= unit.hp) score -= unit.kind.cost * unit.size * 0.8f
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
    /** 打掉 [damage] 點 HP 值多少錢：依打掉的比例換算成這支部隊的造價。 */
    private fun worth(unit: ArmyUnit, damage: Int): Float {
        if (unit.maxHp <= 0) return 0f
        val share = damage.coerceAtMost(unit.hp).toFloat() / unit.maxHp
        return share * unit.kind.cost * unit.size
    }

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
            // 隔著海的目標要渡海才到得了：同一塊陸地上還有事做就先做。
            if (needsCrossing(unit, province.capitalTile)) score -= OVERSEAS_GOAL_PENALTY
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
    private fun moveTowards(unit: ArmyUnit, goal: Int, stayAshore: Boolean = false) {
        if (unit.movesLeft <= 0) return
        Orders.computeReachable(session, unit, reachable)
        if (reachable.isEmpty()) return

        val map = session.map
        var bestTile = -1
        var bestScore = Float.NEGATIVE_INFINITY
        val currentDistance = map.distance(unit.tile, goal)
        // 目標在另一塊陸地上（或這支部隊已經在海上）：這一趟就是渡海，水不再是要避開的東西。
        val crossing = needsCrossing(unit, goal)

        for (tile in reachable) {
            var score = (currentDistance - map.distance(tile, goal)) * 10f
            // 同樣的推進距離下，挑戰車與火炮難打的地形。
            val terrain = map.terrainAt(tile)
            // 要去搭船的部隊留在岸上等，不自己下水。
            if (stayAshore && terrain.isWater) continue
            // AI 不替盟友代守：那是玩家的判斷，電腦去做只會佔住盟友的城。
            if (isAlliedCity(tile)) continue
            score += (terrain.armourPenalty + terrain.artilleryPenalty) * 0.25f
            val afloat = unit.kind.domain == Domain.LAND && terrain.isWater
            // 別走出補給範圍。軍艦自帶物資，離港只是慢慢變弱，罰得輕得多。
            // 渡海途中本來就沒有補給，不為這個罰。
            if (!session.isSupplied(tile) && !(crossing && afloat)) {
                score -= if (unit.kind.domain == Domain.SEA) 3f else 12f
            }
            if (afloat) {
                // 浮渡中的陸軍沒有防禦，是活靶。不必渡海的時候，只有能大幅拉近距離才值得
                // 下水（抄近路過窄海峽）。必須渡海的時候不罰 —— 原本一律扣 35 分，而一回合
                // 最多只賺得到 40 分，於是 AI 永遠站在岸邊，隔著海的敵人它一輩子打不到。
                if (!crossing) score -= 35f
            } else if (crossing && landmassOf(tile) == landmassOf(goal)) {
                // 上岸：踏上目標那塊陸地本身就是進展。
                score += 15f
            }
            if (tile == goal) score += 40f
            if (score > bestScore) {
                bestScore = score
                bestTile = tile
            }
        }
        if (bestTile < 0 || bestScore <= 0f) return
        Orders.move(session, unit, bestTile, path)
    }

    // ------------------------------------------------------------------
    // 運輸艦
    // ------------------------------------------------------------------

    /**
     * 這支陸軍該不該搭船去 [goal]：它在岸上、目標在海的另一邊，而且遠到不適合浮渡。
     * 浮渡沒有補給也沒有防禦，過窄海峽可以，跨海不行。
     */
    private fun wantsFerry(unit: ArmyUnit, goal: Int): Boolean {
        if (unit.kind.domain != Domain.LAND || unit.isLoaded) return false
        if (landmassOf(unit.tile) < 0 || !needsCrossing(unit, goal)) return false
        return session.map.distance(unit.tile, goal) > FLOAT_RANGE
    }

    /** 離 [unit] 最近、還有空位、而且近到值得走過去的己方運輸艦。 */
    private fun nearestFerry(unit: ArmyUnit): ArmyUnit? {
        var best: ArmyUnit? = null
        var bestDistance = FERRY_CALL_RANGE + 1
        for (other in session.units) {
            if (other.nationId != nationId || !other.isAlive || !other.kind.isTransport) continue
            if (other.cargo.size >= other.kind.capacity) continue
            val d = session.map.distance(unit.tile, other.tile)
            if (d < bestDistance) {
                bestDistance = d
                best = other
            }
        }
        return best
    }

    /**
     * 運輸艦的一回合。
     *
     * 載著人：開向船上那支部隊想去的地方，一靠上對岸就讓人下船。
     * 空船：去接最近一支在岸上等船的部隊。兩件事都沒有就待著。
     */
    private fun actAsTransport(ship: ArmyUnit) {
        val first = ship.cargo.firstNotNullOfOrNull { session.unitById(it) }
        if (first != null) {
            val goal = chooseGoal(first)
            if (goal < 0) return
            if (landCargo(ship, goal)) return
            moveTowards(ship, goal)
            landCargo(ship, goal)
            return
        }
        var pickup = -1
        var bestDistance = Int.MAX_VALUE
        for (unit in session.units) {
            if (unit.nationId != nationId || !unit.isAlive || unit.isLoaded) continue
            if (unit.kind.domain != Domain.LAND || unit.kind.isSupplier) continue
            val goal = chooseGoal(unit)
            if (goal < 0 || !wantsFerry(unit, goal)) continue
            val d = session.map.distance(ship.tile, unit.tile)
            if (d < bestDistance) {
                bestDistance = d
                pickup = unit.tile
            }
        }
        if (pickup >= 0 && bestDistance > 1) moveTowards(ship, pickup)
    }

    /**
     * 讓船上的人下到 [goal] 那一塊陸地上，挑離目標最近的岸。回傳船是不是空了。
     * 只在目標那塊陸地下船 —— 不然船一離港就會把人放回出發的岸上。
     */
    private fun landCargo(ship: ArmyUnit, goal: Int): Boolean {
        val shore = IntArray(6)
        for (id in ship.cargo.toList()) {
            val passenger = session.unitById(id) ?: continue
            val n = session.map.neighbours(ship.tile, shore)
            var best = -1
            var bestDistance = Int.MAX_VALUE
            for (i in 0 until n) {
                val tile = shore[i]
                if (landmassOf(tile) < 0 || landmassOf(tile) != landmassOf(goal)) continue
                if (isAlliedCity(tile)) continue
                if (!Orders.canUnload(session, passenger, tile)) continue
                val d = session.map.distance(tile, goal)
                if (d < bestDistance) {
                    bestDistance = d
                    best = tile
                }
            }
            if (best >= 0) Orders.unload(session, passenger, best)
        }
        return ship.cargo.isEmpty()
    }

    /** 這一國有多少陸軍在等船，以及手上有幾艘運輸艦。決定要不要造船。 */
    private fun needsAnotherTransport(): Boolean {
        var waiting = 0
        var ships = 0
        for (unit in session.units) {
            if (unit.nationId != nationId || !unit.isAlive) continue
            if (unit.kind.isTransport) {
                ships++
                continue
            }
            if (unit.isLoaded || unit.kind.domain != Domain.LAND || unit.kind.isSupplier) continue
            val goal = chooseGoal(unit)
            if (goal >= 0 && wantsFerry(unit, goal)) waiting++
        }
        if (waiting == 0 || ships >= MAX_TRANSPORTS) return false
        // 一艘船載三支：等船的人多到現有的船兩趟也載不完，才再造一艘。
        return waiting > ships * UnitKind.TRANSPORT_SHIP.capacity * 2
    }

    /** 這一格是不是盟友（不是自己）的城市格。 */
    private fun isAlliedCity(tile: Int): Boolean {
        val pid = session.cityProvinceAt(tile)
        if (pid < 0) return false
        val owner = session.provinceOwner[pid]
        return owner >= 0 && owner != nationId && session.diplomacy.isAllied(owner, nationId)
    }

    /** 陸軍要到 [goal] 是不是得渡海：它已經在海上，或目標在另一塊陸地上。 */
    private fun needsCrossing(unit: ArmyUnit, goal: Int): Boolean {
        if (unit.kind.domain != Domain.LAND) return false
        val here = landmassOf(unit.tile)
        return here < 0 || here != landmassOf(goal)
    }

    /** 這一格屬於哪一塊相連的陸地；水域是 -1。 */
    private fun landmassOf(tile: Int): Int = landmasses[tile]

    /** 每一格的陸塊編號。地圖不會變，所以整局只算一次（見 [landmassesOf]）。 */
    private val landmasses: IntArray by lazy { landmassesOf(session.map) }

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

        /** 拆城防的分數上限（以一擊打掉的城防點數計），別讓 AI 光顧著拆牆。 */
        const val CITY_STRIKE_SCORE_CAP = 30

        /** 預期戰果至少要值任務價錢的這個比例才飛。 */
        const val MIN_SORTIE_VALUE_RATIO = 0.75f
        const val MAX_GOAL_DISTANCE = 28

        /** 固守性格的國家一回合最多造幾支兵。 */
        const val TURTLE_BUILDS_PER_TURN = 2

        /** 目標在這個距離以內就自己浮渡過去，不等船。大約是一道海峽的寬度。 */
        const val FLOAT_RANGE = 6

        /** 運輸艦在這個距離以內，陸軍才會走過去搭。 */
        const val FERRY_CALL_RANGE = 10

        /** AI 最多養幾艘運輸艦。 */
        const val MAX_TRANSPORTS = 3

        /** 隔海目標的扣分：大約等於十格的距離，本地的目標優先。 */
        const val OVERSEAS_GOAL_PENALTY = 25f

        private val landmassCache = java.util.WeakHashMap<WorldMap, IntArray>()

        /** 把陸地分成一塊一塊相連的陸塊，回傳每一格的編號（水域 -1）。 */
        @Synchronized
        fun landmassesOf(map: WorldMap): IntArray = landmassCache.getOrPut(map) {
            val ids = IntArray(map.tileCount) { -1 }
            val buf = IntArray(6)
            val stack = ArrayList<Int>()
            var next = 0
            for (start in 0 until map.tileCount) {
                if (ids[start] >= 0 || !map.isLand(start)) continue
                ids[start] = next
                stack.add(start)
                while (stack.isNotEmpty()) {
                    val tile = stack.removeAt(stack.size - 1)
                    val n = map.neighbours(tile, buf)
                    for (i in 0 until n) {
                        val other = buf[i]
                        if (ids[other] < 0 && map.isLand(other)) {
                            ids[other] = next
                            stack.add(other)
                        }
                    }
                }
                next++
            }
            ids
        }
        const val CITY_ALERT_RANGE = 6
    }
}
