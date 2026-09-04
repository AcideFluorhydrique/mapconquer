// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Combat
import io.github.acidefluorhydrique.mapconquer.units.CombatContext
import io.github.acidefluorhydrique.mapconquer.units.CombatResult
import io.github.acidefluorhydrique.mapconquer.units.CommanderSkill
import io.github.acidefluorhydrique.mapconquer.units.Domain
import io.github.acidefluorhydrique.mapconquer.units.TechBranch
import io.github.acidefluorhydrique.mapconquer.units.UnitKind
import io.github.acidefluorhydrique.mapconquer.world.MoveRules

/**
 * 一支部隊在目前戰局下的移動規則。
 *
 * 每次要算移動範圍時建一個新的（很輕，只有幾個欄位），
 * 而不是複用一個可變物件 —— AI 會在同一輪裡交錯評估多支部隊，
 * 共用可變狀態在那裡會變成很難查的錯誤。
 */
class UnitMoveRules(
    private val session: Session,
    private val unit: ArmyUnit,
    /** 忽略敵軍阻擋，用於 AI 的「理想路線」規劃。 */
    private val ignoreEnemies: Boolean = false
) : MoveRules {

    private val map = session.map
    private val mountaineer = unit.kind.isMountaineer

    /** stopsAt 躺在 Dijkstra 的內迴圈上，這裡配置陣列會被跑上萬次。 */
    private val zocBuf = IntArray(6)

    override fun enterCost(from: Int, to: Int): Int {
        val terrain = map.terrainAt(to)
        when (unit.kind.domain) {
            // 陸軍可以直接下海浮渡。它在水上防禦幾乎歸零、火力剩三成，
            // 所以這不是把陸軍變強，而是把「要不要冒險渡海」交還給玩家決定。
            // 步兵進得了山地與叢林，只是很慢；輪車與履帶進不去。
            Domain.LAND -> if (terrain.isLand && unit.kind.vehicle && !terrain.vehiclePassable) return -1
            Domain.SEA -> if (!terrain.isWater) return -1
            Domain.AIR -> Unit
        }

        if (!ignoreEnemies) {
            // 用「這支部隊在那一格會佔哪一層」去查阻擋 —— 浮渡中的陸軍
            // 跟軍艦是互相排擠的，這樣敵方艦隊才堵得住渡海的部隊。
            val blocker = session.unitAt(to, session.layerFor(unit.kind, to))
            if (blocker != null && blocker.nationId != unit.nationId &&
                !session.diplomacy.isAllied(blocker.nationId, unit.nationId)
            ) return -1
        }

        return when {
            unit.kind.domain == Domain.AIR -> 1
            // 軍艦在水上是一格一點 —— 海洋就是它的地盤。
            unit.kind.domain == Domain.SEA -> 1
            // 只有浮渡的陸軍才付較高的代價：擠在駁船上就是比軍艦慢。
            terrain.isWater -> EMBARK_MOVE_COST
            else -> {
                var cost = terrain.moveCost
                if (mountaineer && cost > 2) cost = 2
                cost.coerceAtLeast(1)
            }
        }
    }

    /**
     * 走到這格之後必須停下嗎。
     *
     * 兩條規則疊在一起：
     *
     * **敵方控制區** —— 踏進敵人隔壁就得停。這是整個戰術層的支點：沒有它，
     * 高機動部隊可以直接穿過防線去點後方城市，「戰線」就不存在了。
     * 裝甲的突破能力做成「無視 ZOC」，讓它仍然是打開缺口的鑰匙，但不是萬能的。
     *
     * **入海與登陸各要一整個回合** —— 海岸線因此變成真正的門檻，
     * 登陸不再是行軍途中順手做的事，而是一次要規劃的作戰。
     */
    override fun stopsAt(from: Int, to: Int): Boolean {
        if (unit.kind.domain == Domain.AIR) return false

        if (unit.kind.domain == Domain.LAND && from >= 0 && map.isWater(from) != map.isWater(to)) {
            return true
        }
        if (unit.kind.isBreakthrough) return false

        val n = map.neighbours(to, zocBuf)
        for (i in 0 until n) {
            val other = session.unitAt(zocBuf[i], Domain.LAND) ?: continue
            if (session.isHostile(other.nationId, unit.nationId)) return true
        }
        return false
    }

    override fun canEndOn(tile: Int): Boolean = session.isTileFreeFor(unit, tile)

    private companion object {
        /**
         * 陸軍浮渡的每格成本。
         *
         * 只作用在陸軍身上。第一版把它套給所有水上移動，結果一併把每艘軍艦的
         * 航程砍半（驅逐艦從八格掉到四格），而且沒有任何地方講得出這個改動 ——
         * 是運輸艦的測試把它抓出來的。
         */
        const val EMBARK_MOVE_COST = 2
    }
}

/**
 * 玩家與 AI 共用的指令入口。
 *
 * 所有會改變戰局的動作都集中在這裡，而且每個動作都是「先驗證、再執行」的形狀。
 * 好處是 UI 可以先呼叫 `canXxx` 決定按鈕要不要變灰，
 * 而 AI 可以放心亂試 —— 不合法的指令一律回 false，不會把戰局搞成半套狀態。
 */
object Orders {

    /** 移動範圍。結果留在 session.pathfinder 裡，之後可以直接查路徑。 */
    fun computeReachable(session: Session, unit: ArmyUnit, into: MutableList<Int>) {
        into.clear()
        if (unit.movesLeft <= 0 || unit.isLoaded) return
        session.pathfinder.explore(unit.tile, unit.movesLeft, UnitMoveRules(session, unit))
        for (tile in session.pathfinder.reached) {
            if (tile == unit.tile) continue
            if (!session.isTileFreeFor(unit, tile)) continue
            into.add(tile)
        }
    }

    fun canMoveTo(session: Session, unit: ArmyUnit, target: Int): Boolean {
        if (unit.movesLeft <= 0 || unit.isLoaded) return false
        if (!session.isTileFreeFor(unit, target)) return false
        return session.pathfinder.origin == unit.tile && session.pathfinder.isReachable(target)
    }

    /**
     * 執行移動。呼叫端必須先跑過 [computeReachable]（範圍計算與路徑回溯共用同一次搜尋）。
     * 回傳實際走到的格；沒動就回傳原地。
     */
    fun move(session: Session, unit: ArmyUnit, target: Int, path: MutableList<Int>): Int {
        if (!canMoveTo(session, unit, target)) return unit.tile
        session.pathfinder.buildPath(target, path)
        if (path.size < 2) return unit.tile

        val spent = session.pathfinder.costTo(target)
        session.relocate(unit, target)
        unit.movesLeft = (unit.movesLeft - spent).coerceAtLeast(0)
        unit.entrenchment = 0

        onArrived(session, unit)
        return target
    }

    /** 抵達之後的連鎖效果：佔領、視野、勝負重判。 */
    private fun onArrived(session: Session, unit: ArmyUnit) {
        tryCapture(session, unit)
        session.refreshOutcome()
    }

    /**
     * 佔領：陸軍站上省會就整省易主。
     *
     * 為什麼不需要「留守」：省一旦易主，補給、收入、增援點全部跟著換邊，
     * 對手要拿回去就得再打一次省會。這已經足夠讓玩家有守土的動機，
     * 不必再加一層駐軍規則。
     */
    fun tryCapture(session: Session, unit: ArmyUnit): Boolean {
        if (!unit.kind.canCapture || unit.kind.domain != Domain.LAND) return false
        val province = session.map.provinceAt(unit.tile) ?: return false
        if (province.capitalTile != unit.tile) return false
        val owner = session.provinceOwner[province.id]
        if (owner == unit.nationId) return false
        if (owner >= 0 && !session.isHostile(owner, unit.nationId)) return false
        // 城要先被打垮才易主。站上去不算佔領 —— 一座還在還手的城市，
        // 不會因為有人走到門口就換旗。
        if (province.hasCity && session.cityHp[province.id] > 0) return false
        return session.captureProvince(province.id, unit.nationId)
    }

    /**
     * 一次移動的快照，用來撤回。
     *
     * 沒有戰爭迷霧，所以移動不會揭露任何東西 —— 走錯一步純粹是手滑，
     * 沒有理由讓玩家為此付出一整支部隊的一回合。同類作品都允許撤回，
     * 條件是「還沒做別的事」。
     */
    class MoveRecord(
        val unitId: Int,
        val from: Int,
        val movesBefore: Int,
        val entrenchmentBefore: Int
    )

    fun snapshot(unit: ArmyUnit) =
        MoveRecord(unit.id, unit.tile, unit.movesLeft, unit.entrenchment)

    fun canUndo(session: Session, record: MoveRecord): Boolean {
        val unit = session.unitById(record.unitId) ?: return false
        if (!unit.isAlive || unit.hasAttacked || unit.isLoaded) return false
        if (unit.tile == record.from) return false
        return session.isTileFreeFor(unit, record.from)
    }

    /** 把部隊放回原位，移動點與築壕值一併還原。 */
    fun undoMove(session: Session, record: MoveRecord): Boolean {
        if (!canUndo(session, record)) return false
        val unit = session.unitById(record.unitId) ?: return false
        session.relocate(unit, record.from)
        unit.movesLeft = record.movesBefore
        unit.entrenchment = record.entrenchmentBefore
        return true
    }

    // ------------------------------------------------------------------
    // 戰鬥
    // ------------------------------------------------------------------

    fun canAttack(session: Session, attacker: ArmyUnit, targetTile: Int): Boolean =
        findTarget(session, attacker, targetTile) != null

    /** 這格上有沒有這支部隊打得到的敵人。 */
    fun findTarget(session: Session, attacker: ArmyUnit, targetTile: Int): ArmyUnit? {
        if (attacker.hasAttacked || !attacker.isAlive || attacker.isLoaded) return null
        if (!attacker.kind.canAttack) return null
        // 陷入混亂的部隊打不出去，這是包圍戰術的收益。
        if (session.isDisrupted(attacker)) return null
        val distance = session.map.distance(attacker.tile, targetTile)
        if (!Combat.canReach(attacker, distance)) return null
        for (domain in Domain.values()) {
            val target = session.unitAt(targetTile, domain) ?: continue
            if (!session.isHostile(target.nationId, attacker.nationId)) continue
            if (attacker.kind.attackAgainst(target.kind.targetClass) <= 0) continue
            if (attacker.nationId == session.playerNationId && !session.isUnitVisibleToPlayer(target)) continue
            return target
        }
        return null
    }

    fun collectTargets(session: Session, attacker: ArmyUnit, into: MutableList<Int>) {
        into.clear()
        if (attacker.hasAttacked || !attacker.kind.canAttack || attacker.isLoaded) return
        if (session.isDisrupted(attacker)) return
        val map = session.map
        val range = attacker.kind.maxRange
        val tiles = ArrayList<Int>(3 * range * (range + 1) + 1)
        map.collectWithin(attacker.tile, range, tiles)
        for (tile in tiles) {
            if (findTarget(session, attacker, tile) != null) into.add(tile)
        }
    }

    fun buildContext(session: Session, attacker: ArmyUnit, defender: ArmyUnit): CombatContext {
        val map = session.map
        val defenderProvince = map.provinceAt(defender.tile)
        val defenderCityBonus = if (
            defenderProvince != null &&
            defenderProvince.capitalTile == defender.tile &&
            defender.kind.domain == Domain.LAND
        ) defenderProvince.cityDefenceBonus else 0

        // 城防已經被打光的城市不再替駐軍擋傷害 —— 那才是「城破了」的意思。
        val defenderInCity = session.cityShields(defender)
        val attackerInCity = session.cityShields(attacker)

        // 空中單位不吃地形加成：它在天上，底下是山還是平原都一樣。
        val defenderTerrain =
            if (defender.kind.domain == Domain.AIR) 0 else map.terrainAt(defender.tile).defenceBonus
        val attackerTerrain =
            if (attacker.kind.domain == Domain.AIR) 0 else map.terrainAt(attacker.tile).defenceBonus

        return CombatContext(
            attacker = attacker,
            defender = defender,
            defenderTerrainBonus = defenderTerrain + defenderCityBonus,
            attackerTerrainBonus = attackerTerrain,
            distance = map.distance(attacker.tile, defender.tile),
            attackerTech = session.nations[attacker.nationId].techBonus(attacker.kind.branch),
            defenderTech = session.nations[defender.nationId].techBonus(defender.kind.branch),
            attackerAura = session.commandAura(attacker),
            defenderAura = session.commandAura(defender),
            attackerAtSea = session.isEmbarked(attacker),
            defenderAtSea = session.isEmbarked(defender),
            attackerMorale = session.moraleOf(attacker),
            defenderMorale = session.moraleOf(defender),
            attackerInCity = attackerInCity,
            defenderInCity = defenderInCity
        )
    }

    /** 不改變任何狀態的傷害預測，戰前面板與 AI 都用它。 */
    fun previewDamage(session: Session, attacker: ArmyUnit, defender: ArmyUnit): Int =
        Combat.previewDamage(buildContext(session, attacker, defender))

    fun attack(session: Session, attacker: ArmyUnit, targetTile: Int): CombatResult? {
        val defender = findTarget(session, attacker, targetTile) ?: return null
        val ctx = buildContext(session, attacker, defender)
        val result = Combat.resolve(ctx, session.rng)

        // 城市替駐軍擋下的那一層，扣在城防上。
        session.damageCity(session.cityProvinceAt(defender.tile), result.damageToDefenderCity)
        session.damageCity(session.cityProvinceAt(attacker.tile), result.damageToAttackerCity)

        // 開火即定身：本回合不能再走。這讓「移動到哪裡開火」變成一個真正的抉擇。
        attacker.movesLeft = 0
        attacker.entrenchment = 0
        // 消耗補給：連續進攻會把戰線推到補給極限，這是攻勢有節奏的原因。
        attacker.supply = (attacker.supply - ATTACK_SUPPLY_COST).coerceAtLeast(0)

        /*
         * 突擊：擊毀目標之後可以立刻再打一次，而且能一路連鎖下去。
         *
         * 這一條讓裝甲從「數值高一點的兵」變成「能一口氣打穿一條戰線的兵」，
         * 也是它值那個價錢的唯一理由。連鎖會自然停止 —— 打不死下一個就結束了 ——
         * 所以不需要另外設上限。移動點仍然歸零，它只能原地掃射鄰接目標。
         */
        val chains = result.defenderDestroyed && attacker.isAlive && attacker.kind.isAssault
        attacker.hasAttacked = !chains

        // 謠言：帶這個技能的指揮官出手之後，有機會再把目標往混亂推一級。
        if (defender.isAlive &&
            attacker.commander?.has(CommanderSkill.RUMOUR) == true &&
            session.rng.chance(RUMOUR_CHANCE)
        ) {
            defender.addRumour()
        }

        val attackerNation = session.nations[attacker.nationId]
        val defenderNation = session.nations[defender.nationId]

        if (result.defenderDestroyed) {
            attackerNation.unitsKilled++
            session.pushEvent(
                "event_unit_destroyed",
                listOf(defender.kind.key, defenderNation.nameKey),
                defender.tile,
                attacker.nationId
            )
            session.destroyUnit(defender)
        }
        if (result.attackerDestroyed) {
            defenderNation.unitsKilled++
            session.pushEvent(
                "event_unit_destroyed",
                listOf(attacker.kind.key, attackerNation.nameKey),
                attacker.tile,
                defender.nationId
            )
            session.destroyUnit(attacker)
        }

        session.refreshOutcome()
        return result
    }

    // ------------------------------------------------------------------
    // 運輸
    // ------------------------------------------------------------------

    fun canLoad(session: Session, passenger: ArmyUnit, transport: ArmyUnit): Boolean {
        if (passenger.isLoaded || transport.isLoaded) return false
        if (passenger.nationId != transport.nationId) return false
        if (!transport.kind.canCarry(passenger.kind)) return false
        if (transport.cargo.size >= transport.kind.capacity) return false
        if (passenger.movesLeft <= 0) return false
        return session.map.distance(passenger.tile, transport.tile) <= 1
    }

    fun load(session: Session, passenger: ArmyUnit, transport: ArmyUnit): Boolean {
        if (!canLoad(session, passenger, transport)) return false
        session.clearOccupancy(passenger.tile, session.layerOf(passenger), passenger.id)
        passenger.tile = transport.tile
        passenger.transportId = transport.id
        passenger.movesLeft = 0
        passenger.entrenchment = 0
        transport.cargo.add(passenger.id)
        return true
    }

    fun canUnload(session: Session, passenger: ArmyUnit, target: Int): Boolean {
        if (!passenger.isLoaded) return false
        val transport = session.unitById(passenger.transportId) ?: return false
        if (session.map.distance(transport.tile, target) > 1) return false
        if (!session.isTileFreeFor(passenger, target)) return false
        val terrain = session.map.terrainAt(target)
        return when (passenger.kind.domain) {
            Domain.LAND -> terrain.isLand && (!passenger.kind.vehicle || terrain.vehiclePassable)
            Domain.AIR -> true
            Domain.SEA -> terrain.isWater
        }
    }

    /**
     * 登陸。上岸的部隊本回合不能再動 —— 兩棲兵種例外，
     * 這正是海軍陸戰隊存在的理由：它是唯一能「下船就打」的部隊。
     */
    fun unload(session: Session, passenger: ArmyUnit, target: Int): Boolean {
        if (!canUnload(session, passenger, target)) return false
        val transport = session.unitById(passenger.transportId) ?: return false
        transport.cargo.remove(passenger.id)
        passenger.transportId = -1
        passenger.tile = target
        session.setOccupancy(target, session.layerFor(passenger.kind, target), passenger.id)
        if (passenger.kind.isAmphibious) {
            passenger.movesLeft = 0
            passenger.hasAttacked = false
        } else {
            passenger.movesLeft = 0
            passenger.hasAttacked = true
        }
        onArrived(session, passenger)
        return true
    }

    // ------------------------------------------------------------------
    // 生產與研發
    // ------------------------------------------------------------------

    /** 這個省能不能造這種兵。 */
    fun canBuild(session: Session, nationId: Int, provinceId: Int, kind: UnitKind): Boolean =
        buildBlocker(session, nationId, provinceId, kind) == BuildBlocker.NONE

    enum class BuildBlocker { NONE, NOT_OWNED, NO_CITY, LOW_INDUSTRY, NOT_COASTAL, NO_ROOM, NO_FUNDS }

    fun buildBlocker(session: Session, nationId: Int, provinceId: Int, kind: UnitKind): BuildBlocker {
        if (provinceId !in session.provinceOwner.indices) return BuildBlocker.NOT_OWNED
        if (session.provinceOwner[provinceId] != nationId) return BuildBlocker.NOT_OWNED
        val province = session.map.provinces[provinceId]
        if (!province.hasCity) return BuildBlocker.NO_CITY
        if (province.industry < kind.industry) return BuildBlocker.LOW_INDUSTRY
        if (session.nations[nationId].funds < kind.cost) return BuildBlocker.NO_FUNDS
        if (kind.isNaval) {
            if (!province.coastal) return BuildBlocker.NOT_COASTAL
            if (navalSpawnTile(session, province.capitalTile) < 0) return BuildBlocker.NO_ROOM
        } else {
            if (!session.isTileFree(province.capitalTile, kind.domain)) return BuildBlocker.NO_ROOM
        }
        return BuildBlocker.NONE
    }

    /** 港口旁邊第一個空著的水格。 */
    private fun navalSpawnTile(session: Session, cityTile: Int): Int {
        val buf = IntArray(6)
        val n = session.map.neighbours(cityTile, buf)
        for (i in 0 until n) {
            val tile = buf[i]
            if (session.map.isWater(tile) && session.isTileFree(tile, Domain.SEA)) return tile
        }
        return -1
    }

    fun build(session: Session, nationId: Int, provinceId: Int, kind: UnitKind): ArmyUnit? {
        if (!canBuild(session, nationId, provinceId, kind)) return null
        val province = session.map.provinces[provinceId]
        val tile = if (kind.isNaval) navalSpawnTile(session, province.capitalTile) else province.capitalTile
        if (tile < 0) return null
        val nation = session.nations[nationId]
        val unit = session.spawnUnit(kind, nationId, tile) ?: return null
        nation.funds -= kind.cost
        session.pushEvent("event_unit_built", listOf(kind.key, province.nameKey), tile, nationId)
        return unit
    }

    fun canResearch(session: Session, nationId: Int, branch: TechBranch): Boolean {
        val nation = session.nations[nationId]
        return nation.canResearch(branch) && nation.funds >= nation.techCost(branch)
    }

    fun research(session: Session, nationId: Int, branch: TechBranch): Boolean {
        if (!canResearch(session, nationId, branch)) return false
        val nation = session.nations[nationId]
        nation.funds -= nation.techCost(branch)
        nation.tech[branch.ordinal]++
        session.pushEvent("event_tech_advanced", listOf(branch.name, nation.nameKey), -1, nationId)
        return true
    }

    /** 花錢就地補血。前線修不滿，只能靠城市。 */
    fun repairCost(unit: ArmyUnit): Int {
        val missing = ArmyUnit.MAX_HP - unit.hp
        return (unit.kind.cost * missing) / 180
    }

    fun canRepair(session: Session, unit: ArmyUnit): Boolean {
        if (unit.hp >= ArmyUnit.MAX_HP) return false
        if (!session.isSupplySource(unit.tile, unit.nationId)) return false
        return session.nations[unit.nationId].funds >= repairCost(unit)
    }

    fun repair(session: Session, unit: ArmyUnit): Boolean {
        if (!canRepair(session, unit)) return false
        session.nations[unit.nationId].funds -= repairCost(unit)
        unit.hp = ArmyUnit.MAX_HP
        unit.resupply(ArmyUnit.MAX_SUPPLY)
        unit.movesLeft = 0
        unit.hasAttacked = true
        return true
    }

    /** 指派指揮官。一位指揮官同時只能帶一支部隊。 */
    fun assignCommander(session: Session, unit: ArmyUnit, commanderId: String): Boolean {
        if (commanderId.isNotEmpty()) {
            for (other in session.units) {
                if (other.id != unit.id && other.commanderId == commanderId) return false
            }
        }
        unit.commanderId = commanderId
        return true
    }

    const val ATTACK_SUPPLY_COST = 12

    /** 謠言技能生效的機率。 */
    const val RUMOUR_CHANCE = 55
}
