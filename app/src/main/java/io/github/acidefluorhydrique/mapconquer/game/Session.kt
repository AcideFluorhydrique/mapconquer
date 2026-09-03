// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.core.Rng
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.CommanderSkill
import io.github.acidefluorhydrique.mapconquer.units.Domain
import io.github.acidefluorhydrique.mapconquer.units.TechBranch
import io.github.acidefluorhydrique.mapconquer.units.UnitKind
import io.github.acidefluorhydrique.mapconquer.world.Pathfinder
import io.github.acidefluorhydrique.mapconquer.world.WorldMap

enum class SessionStatus { PLAYING, VICTORY, DEFEAT }

/** 給玩家看的一則事件。訊息鍵在 strings.xml，參數由呼叫端填。 */
class GameEvent(val key: String, val args: List<Any>, val tile: Int, val nationId: Int)

/**
 * 一局進行中的遊戲。
 *
 * 這是整個 app 唯一的可變狀態容器 —— 地圖是唯讀的、劇本是唯讀的、
 * 兵種表是常數，會變的東西全部在這裡。所有規則判定也都在這裡或
 * [Orders]，renderer 與 AI 只讀不寫（除了透過 [Orders] 下指令）。
 *
 * 這條界線值得堅持：它讓存檔只需要序列化這一個物件，
 * 也讓「AI 推演一步」跟「玩家真的走一步」跑的是同一條程式碼。
 */
class Session(
    val map: WorldMap,
    val scenario: Scenario,
    val difficulty: Difficulty,
    playerCode: String,
    seed: Long
) {

    val rng = Rng(seed)
    val pathfinder = Pathfinder(map)

    val nations: List<Nation>
    private val nationIndexByCode = HashMap<String, Int>()

    val diplomacy: Diplomacy

    /** 省份 → 擁有國 id；-1 = 中立。 */
    val provinceOwner: IntArray = IntArray(map.provinces.size) { -1 }

    /** 省份的地形產值總和，開局算一次。 */
    private val provinceTerrainIncome: IntArray = IntArray(map.provinces.size)

    val units = ArrayList<ArmyUnit>(256)
    private val unitsById = HashMap<Int, ArmyUnit>(256)
    private var nextUnitId = 1

    /**
     * 三層佔位表：陸、海、空各一份，值是單位 id（-1 = 空）。
     *
     * 分層的理由是它直接對應規則：轟炸機飛過艦隊上空不會撞船，
     * 而如果只用一層，這件事就得靠一堆特例判斷來補。
     */
    private val occupancy = IntArray(map.tileCount * 3) { -1 }

    /** 目前行動國的補給覆蓋，回合開始時重算。 */
    private val suppliedTiles = BooleanArray(map.tileCount)
    private val supplyCost = IntArray(map.tileCount)

    var turn: Int = 1
        internal set

    var activeNationId: Int = 0
        internal set

    var status: SessionStatus = SessionStatus.PLAYING
        internal set

    val playerNationId: Int

    /** 最近的事件，UI 拿去跑訊息帶。刻意有上限，戰報不該無限長。 */
    val events = ArrayList<GameEvent>(32)

    private val neighbourBuf = IntArray(6)
    private val directionBuf = IntArray(6)
    private val starved = ArrayList<ArmyUnit>(8)

    init {
        val built = ArrayList<Nation>(scenario.nations.size)
        scenario.nations.forEachIndexed { index, sn ->
            val nation = Nation(
                index, sn.code, sn.nameKey, sn.colour,
                sn.aiProfile, sn.capitalProvince, sn.flag, sn.bloc
            )
            nation.funds = sn.funds
            for (i in sn.tech.indices) {
                if (i < nation.tech.size) nation.tech[i] = sn.tech[i].coerceIn(0, Nation.MAX_TECH_LEVEL)
            }
            built.add(nation)
            nationIndexByCode[sn.code] = index
        }
        nations = built
        diplomacy = Diplomacy(nations.size)

        playerNationId = nationIndexByCode[playerCode] ?: 0
        nations[playerNationId].isPlayer = true

        applyScenarioRelations()
        applyScenarioOwnership()
        applyScenarioFunds()
        computeProvinceIncome()
        spawnScenarioUnits()

        activeNationId = 0
        beginNationTurn(nations[activeNationId])
    }

    // ------------------------------------------------------------------
    // 查詢
    // ------------------------------------------------------------------

    val playerNation: Nation get() = nations[playerNationId]

    val activeNation: Nation get() = nations[activeNationId]

    val isPlayerTurn: Boolean get() = activeNationId == playerNationId && status == SessionStatus.PLAYING

    fun nationByCode(code: String): Nation? = nationIndexByCode[code]?.let { nations[it] }

    fun unitById(id: Int): ArmyUnit? = unitsById[id]

    fun unitAt(tile: Int, domain: Domain): ArmyUnit? {
        val id = occupancy[domain.ordinal * map.tileCount + tile]
        return if (id >= 0) unitsById[id] else null
    }

    /** 這格上「最該被選到」的那支：陸 > 海 > 空。 */
    fun primaryUnitAt(tile: Int): ArmyUnit? =
        unitAt(tile, Domain.LAND) ?: unitAt(tile, Domain.SEA) ?: unitAt(tile, Domain.AIR)

    fun anyUnitAt(tile: Int): Boolean =
        occupancy[tile] >= 0 ||
            occupancy[map.tileCount + tile] >= 0 ||
            occupancy[map.tileCount * 2 + tile] >= 0

    fun ownerOfTile(tile: Int): Int {
        val p = map.provinceOf[tile]
        return if (p >= 0) provinceOwner[p] else -1
    }

    fun unitsOf(nationId: Int): List<ArmyUnit> = units.filter { it.nationId == nationId && it.isAlive }

    fun provincesOf(nationId: Int): Int = provinceOwner.count { it == nationId }

    fun isHostile(a: Int, b: Int): Boolean = diplomacy.isHostile(a, b)

    /** 陸軍能不能在這格結束移動（地形＋佔位）。 */
    fun isTileFree(tile: Int, domain: Domain): Boolean =
        occupancy[domain.ordinal * map.tileCount + tile] < 0

    /**
     * 這支部隊在指定格子上佔哪一層。
     *
     * 浮渡中的陸軍佔的是**海層**，不是陸層。這一條很關鍵：它讓渡海的部隊
     * 跟軍艦互相排擠、也讓敵方艦隊真的能把它堵在海上，
     * 而不是幽靈一樣跟軍艦疊在同一格。
     */
    fun layerFor(kind: UnitKind, tile: Int): Domain =
        if (kind.domain == Domain.LAND && map.isWater(tile)) Domain.SEA else kind.domain

    fun layerOf(unit: ArmyUnit): Domain = layerFor(unit.kind, unit.tile)

    /** 陸軍站在水上就是在浮渡。不另外存狀態，地形本身就是答案。 */
    fun isEmbarked(unit: ArmyUnit): Boolean =
        unit.kind.domain == Domain.LAND && !unit.isLoaded && map.isWater(unit.tile)

    fun isTileFreeFor(unit: ArmyUnit, tile: Int): Boolean =
        isTileFree(tile, layerFor(unit.kind, tile))

    /** 這格是不是某國的補給來源（自己的城市）。 */
    fun isSupplySource(tile: Int, nationId: Int): Boolean {
        val province = map.provinceAt(tile) ?: return false
        if (!province.hasCity || province.capitalTile != tile) return false
        return provinceOwner[province.id] == nationId
    }

    fun isSupplied(tile: Int): Boolean = suppliedTiles[tile]

    /**
     * 指揮光環：附近有己方司令部時的攻擊加成。
     * 半徑固定 3，加成不疊加 —— 疊加會逼玩家把司令部堆成一坨。
     */
    fun commandAura(unit: ArmyUnit): Int {
        for (other in units) {
            if (!other.isAlive || other.nationId != unit.nationId) continue
            if (!other.kind.hasCommandAura) continue
            if (map.distance(other.tile, unit.tile) <= COMMAND_AURA_RANGE) return COMMAND_AURA_BONUS
        }
        return 0
    }

    // ------------------------------------------------------------------
    // 單位生命週期
    // ------------------------------------------------------------------

    fun spawnUnit(kind: UnitKind, nationId: Int, tile: Int, level: Int = 1, commanderId: String = ""): ArmyUnit? {
        if (!isTileFree(tile, layerFor(kind, tile))) return null
        val unit = ArmyUnit(nextUnitId++, kind, nationId, tile)
        unit.level = level.coerceIn(1, ArmyUnit.MAX_LEVEL)
        unit.commanderId = commanderId
        if (unit.commander?.has(CommanderSkill.VETERAN) == true && unit.level < 2) unit.level = 2
        unit.movesLeft = 0
        unit.hasAttacked = true
        units.add(unit)
        unitsById[unit.id] = unit
        setOccupancy(tile, layerFor(kind, tile), unit.id)
        return unit
    }

    fun destroyUnit(unit: ArmyUnit) {
        // 被載的部隊隨載具一起沉。
        if (unit.cargo.isNotEmpty()) {
            val doomed = unit.cargo.toList()
            unit.cargo.clear()
            for (id in doomed) unitsById[id]?.let { destroyUnit(it) }
        }
        detachFromTransport(unit)
        if (!unit.isLoaded) clearOccupancy(unit.tile, layerOf(unit), unit.id)
        unit.hp = 0
        units.remove(unit)
        unitsById.remove(unit.id)
        nations.getOrNull(unit.nationId)?.let { it.unitsLost++ }
    }

    internal fun setOccupancy(tile: Int, domain: Domain, unitId: Int) {
        occupancy[domain.ordinal * map.tileCount + tile] = unitId
    }

    internal fun clearOccupancy(tile: Int, domain: Domain, unitId: Int) {
        val slot = domain.ordinal * map.tileCount + tile
        if (occupancy[slot] == unitId) occupancy[slot] = -1
    }

    internal fun relocate(unit: ArmyUnit, toTile: Int) {
        // layerOf 讀的是 unit.tile，所以清舊位置要在改座標之前 —— 陸軍
        // 從陸地走到海上時，前後佔的是不同層。
        clearOccupancy(unit.tile, layerOf(unit), unit.id)
        unit.tile = toTile
        setOccupancy(toTile, layerOf(unit), unit.id)
        // 載著的部隊跟著走，但它們不佔格。
        for (id in unit.cargo) unitsById[id]?.tile = toTile
    }

    internal fun detachFromTransport(unit: ArmyUnit) {
        if (unit.transportId < 0) return
        unitsById[unit.transportId]?.cargo?.remove(unit.id)
        unit.transportId = -1
    }

    // ------------------------------------------------------------------
    // 領土
    // ------------------------------------------------------------------

    /** 省份易主。回傳是否真的換了主人。 */
    fun captureProvince(provinceId: Int, newOwner: Int): Boolean {
        if (provinceId !in provinceOwner.indices) return false
        val old = provinceOwner[provinceId]
        if (old == newOwner) return false
        provinceOwner[provinceId] = newOwner
        if (newOwner in nations.indices) nations[newOwner].provincesTaken++
        val province = map.provinces[provinceId]
        pushEvent(
            if (province.hasCity) "event_city_captured" else "event_province_captured",
            listOf(province.nameKey, nations[newOwner].nameKey),
            province.capitalTile,
            newOwner
        )
        if (old >= 0) checkElimination(old)
        return true
    }

    private fun checkElimination(nationId: Int) {
        val nation = nations[nationId]
        if (nation.eliminated) return
        val hasLand = provinceOwner.any { it == nationId }
        val hasUnits = units.any { it.nationId == nationId && it.isAlive }
        if (!hasLand && !hasUnits) {
            nation.eliminated = true
            pushEvent("event_nation_eliminated", listOf(nation.nameKey), -1, nationId)
        }
    }

    // ------------------------------------------------------------------
    // 回合流程
    // ------------------------------------------------------------------

    /**
     * 換到下一個還活著的國家。所有國家都走過一輪就 turn++。
     * 回傳 true 代表換了新的一輪。
     */
    fun advanceToNextNation(): Boolean {
        endNationTurn(nations[activeNationId])
        var wrapped = false
        var guard = 0
        do {
            activeNationId++
            if (activeNationId >= nations.size) {
                activeNationId = 0
                turn++
                diplomacy.tickTruces()
                wrapped = true
            }
            guard++
        } while (nations[activeNationId].eliminated && guard <= nations.size * 2)

        beginNationTurn(nations[activeNationId])
        evaluateOutcome()
        return wrapped
    }

    private fun beginNationTurn(nation: Nation) {
        collectIncome(nation)
        computeSupply(nation.id)
        refreshUnits(nation)
    }

    private fun endNationTurn(nation: Nation) {
        for (unit in units) {
            if (unit.nationId != nation.id || !unit.isAlive) continue
            // 整回合沒動過就繼續築壕；動過的話 Orders 已經把它歸零了。
            if (unit.movesLeft > 0 && !unit.hasAttacked && unit.entrenchment < MAX_ENTRENCHMENT) {
                unit.entrenchment++
            }
        }
    }

    private fun collectIncome(nation: Nation) {
        var income = 0
        for (pid in provinceOwner.indices) {
            if (provinceOwner[pid] != nation.id) continue
            income += map.provinces[pid].baseIncome + provinceTerrainIncome[pid]
        }
        // 後勤科技直接加收入：它沒有戰鬥數字，價值必須從別的地方回來。
        income += income * nation.techBonus(TechBranch.LOGISTICS) / 100
        val multiplier = if (nation.isPlayer) difficulty.playerIncome else difficulty.aiIncome
        income = income * multiplier / 100

        var upkeep = 0
        for (unit in units) {
            if (unit.nationId == nation.id && unit.isAlive) upkeep += unit.kind.upkeep
        }

        nation.lastIncome = income
        nation.lastUpkeep = upkeep
        nation.funds = (nation.funds + income - upkeep).coerceAtLeast(0)
    }

    /**
     * 補給覆蓋：從自己的城市出發，沿著自己或盟友的陸地擴散固定的「後勤距離」。
     *
     * 用移動成本當距離而不是格數，是為了讓地形自然產生補給難題 ——
     * 越過山脈的攻勢會比沿著平原推進更快斷補給，而玩家不必讀任何說明就能感覺到。
     */
    private fun computeSupply(nationId: Int) {
        java.util.Arrays.fill(suppliedTiles, false)
        java.util.Arrays.fill(supplyCost, Int.MAX_VALUE)
        val frontier = ArrayDeque<Int>()

        for (province in map.provinces) {
            if (provinceOwner[province.id] != nationId) continue
            if (!province.hasCity) continue
            val tile = province.capitalTile
            suppliedTiles[tile] = true
            supplyCost[tile] = 0
            frontier.add(tile)
        }
        // 補給車與司令部本身就是移動的補給站，但傳得比城市近。
        for (unit in units) {
            if (unit.nationId != nationId || !unit.isAlive || !unit.kind.isSupplier) continue
            if (unit.supply < ArmyUnit.SUPPLY_CRITICAL) continue
            val start = SUPPLY_RANGE - MOBILE_SUPPLY_RANGE
            if (supplyCost[unit.tile] <= start) continue
            suppliedTiles[unit.tile] = true
            supplyCost[unit.tile] = start
            frontier.add(unit.tile)
        }

        // 走 BFS 而不是 Dijkstra：補給距離的上限很小，代價又都是個位數，
        // 重複入列的次數遠比維護一個堆積便宜。
        while (frontier.isNotEmpty()) {
            val tile = frontier.removeFirst()
            val spent = supplyCost[tile]
            if (spent == Int.MAX_VALUE) continue
            val n = map.neighbours(tile, neighbourBuf)
            for (i in 0 until n) {
                val next = neighbourBuf[i]
                val terrain = map.terrainAt(next)
                // 補給沿陸地走；水域成本高到只夠把補給遞過一道海峽。
                val step = if (terrain.isWater) SEA_SUPPLY_COST else terrain.moveCost
                val owner = ownerOfTile(next)
                if (terrain.isLand && owner >= 0 && !diplomacy.isAllied(owner, nationId)) continue
                val total = spent + step
                if (total > SUPPLY_RANGE) continue
                if (supplyCost[next] <= total) continue
                supplyCost[next] = total
                suppliedTiles[next] = true
                frontier.add(next)
            }
        }
    }

    private fun refreshUnits(nation: Nation) {
        val logistics = nation.techBonus(TechBranch.LOGISTICS)
        // destroyUnit 會動到 units，所以餓死的先收集起來，跑完整輪再處理。
        for (unit in units) {
            if (unit.nationId != nation.id || !unit.isAlive) continue
            unit.movesLeft = movementFor(unit)
            unit.hasAttacked = false

            val supplied = suppliedTiles[unit.tile] || carriedByFriendlyBase(unit)
            if (supplied) {
                unit.resupply(RESUPPLY_RATE + logistics / 4)
                val repair = repairRate(unit)
                if (repair > 0 && unit.hp < ArmyUnit.MAX_HP) unit.heal(repair)
            } else {
                val drain = if (unit.commander?.has(CommanderSkill.IRON_WILL) == true) {
                    ATTRITION_RATE / 2
                } else {
                    ATTRITION_RATE
                }
                unit.supply = (unit.supply - drain).coerceAtLeast(0)
                // 完全斷補給就開始失血 —— 深入敵境的孤軍必須有代價。
                if (unit.supply <= 0) unit.damage(STARVATION_DAMAGE)
            }
            unit.decayRumour()
            if (!unit.isAlive) starved.add(unit)
        }
        for (unit in starved) {
            pushEvent("event_unit_starved", listOf(unit.kind.key), unit.tile, unit.nationId)
            destroyUnit(unit)
        }
        if (starved.isNotEmpty()) {
            starved.clear()
            rebuildOccupancy()
        }
        checkElimination(nation.id)
    }

    /**
     * 士氣。**不是存起來的狀態，而是當下態勢的函數。**
     *
     * ```
     *   +1  士氣高昂     待在自己的城市裡且無人接觸
     *    0  正常
     *   -1  士氣下降     被夾擊（對向兩格都有敵人）
     *   -2  士氣嚴重下降 被包圍（四面以上有敵人）
     *   -3  混亂         打不出去也還不了手
     * ```
     *
     * 謠言每層再往下壓一級，所以進入混亂有三條路：
     * 包圍＋一次謠言、夾擊＋兩次謠言、或是純粹三次謠言。
     *
     * 做成推導值而不是計數器，是因為士氣描述的是「現在被圍住」這件事 ——
     * 敵人散開，士氣就該立刻回來，而不是還要慢慢爬。
     */
    fun moraleOf(unit: ArmyUnit): Int {
        map.neighboursByDirection(unit.tile, directionBuf)
        var hostiles = 0
        var flanked = false
        for (i in 0 until 6) {
            if (!hasHostileAt(directionBuf[i], unit.nationId)) continue
            hostiles++
            // 對向：i 與 i+3。任何一組成立就算夾擊。
            if (i < 3 && hasHostileAt(directionBuf[i + 3], unit.nationId)) flanked = true
        }

        var level = when {
            hostiles >= ENCIRCLED_THRESHOLD -> -2
            flanked -> -1
            // 士氣高昂要求「完全沒有壓力」：有敵人接觸、或是還沒散去的謠言，
            // 都不算。少了謠言那個條件，三次謠言在城裡就打不進混亂，
            // 而那是這套階梯明確保證的三條路徑之一。
            hostiles == 0 && unit.rumour == 0 && isSupplySource(unit.tile, unit.nationId) -> 1
            else -> 0
        }
        level -= unit.rumour
        return level.coerceIn(ArmyUnit.MIN_MORALE, ArmyUnit.MAX_MORALE)
    }

    private fun hasHostileAt(tile: Int, nationId: Int): Boolean {
        if (tile < 0) return false
        for (domain in Domain.values()) {
            val other = unitAt(tile, domain) ?: continue
            if (isHostile(other.nationId, nationId)) return true
        }
        return false
    }

    fun isDisrupted(unit: ArmyUnit): Boolean = moraleOf(unit) <= ArmyUnit.MIN_MORALE

    /** 飛機停在自己的機場／航艦上時算有補給。 */
    /** 飛機停在自己的機場／航艦上時算有補給。 */
    private fun carriedByFriendlyBase(unit: ArmyUnit): Boolean {
        if (unit.isLoaded) {
            val transport = unitsById[unit.transportId] ?: return false
            return transport.nationId == unit.nationId
        }
        if (unit.kind.domain != Domain.AIR) return false
        val owner = ownerOfTile(unit.tile)
        return owner >= 0 && diplomacy.isAllied(owner, unit.nationId) &&
            map.provinceAt(unit.tile)?.hasCity == true
    }

    private fun repairRate(unit: ArmyUnit): Int {
        var rate = if (isSupplySource(unit.tile, unit.nationId)) CITY_REPAIR else FIELD_REPAIR
        if (unit.commander?.has(CommanderSkill.FIELD_MEDIC) == true) rate += 8
        return rate
    }

    private fun rebuildOccupancy() {
        java.util.Arrays.fill(occupancy, -1)
        for (unit in units) {
            if (!unit.isAlive || unit.isLoaded) continue
            setOccupancy(unit.tile, layerOf(unit), unit.id)
        }
    }

    fun movementFor(unit: ArmyUnit): Int {
        var move = unit.kind.movement
        if (unit.commander?.has(CommanderSkill.BLITZ) == true) move += 1
        if (unit.kind.branch == TechBranch.ARMOUR && nations[unit.nationId].techLevel(TechBranch.ARMOUR) >= 3) move += 1
        if (unit.supply < ArmyUnit.SUPPLY_CRITICAL &&
            unit.commander?.has(CommanderSkill.IRON_WILL) != true
        ) move -= 1
        return move.coerceAtLeast(1)
    }

    fun visionFor(unit: ArmyUnit): Int {
        var vision = unit.kind.vision
        if (unit.commander?.has(CommanderSkill.SCOUT) == true) vision += 1
        return vision
    }

    // ------------------------------------------------------------------
    // 偵察
    // ------------------------------------------------------------------

    /**
     * 本作**沒有戰爭迷霧**：整張地圖從第一回合起就完全可見。
     *
     * 這是刻意的。大戰略的樂趣在於看著整個棋盤做取捨 —— 該先打哪一國、
     * 戰線要拉多長、艦隊繞哪條航路 —— 而迷霧把這些決策換成了「派偵察兵去翻圖」，
     * 那是戰術層的樂趣，不是這個尺度該有的。同類作品也都是上帝視角。
     *
     * 唯一的例外是潛艇：它必須靠得夠近或被反潛單位盯上才會現形，
     * 否則潛艇這個兵種就完全沒有存在意義。[UnitKind.vision] 現在只用在這裡。
     */
    fun isUnitVisibleToPlayer(unit: ArmyUnit): Boolean {
        if (unit.nationId == playerNationId) return true
        if (!unit.kind.isStealth) return true
        for (own in units) {
            if (own.nationId != playerNationId || !own.isAlive) continue
            val d = map.distance(own.tile, unit.tile)
            if (own.kind.isSubHunter && d <= visionFor(own)) return true
            if (d <= 1) return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // 勝負
    // ------------------------------------------------------------------

    fun objectiveProgress(objective: Objective): Pair<Int, Int> = when (objective.type) {
        ObjectiveType.CAPTURE_PROVINCES ->
            objective.provinces.count { it in provinceOwner.indices && provinceOwner[it] == playerNationId } to
                objective.provinces.size
        ObjectiveType.HOLD_PROVINCES ->
            objective.provinces.count { it in provinceOwner.indices && provinceOwner[it] == playerNationId } to
                objective.provinces.size
        ObjectiveType.ELIMINATE_NATION ->
            (if (nationByCode(objective.nationCode)?.eliminated == true) 1 else 0) to 1
        ObjectiveType.SURVIVE_TURNS -> turn.coerceAtMost(objective.turn) to objective.turn
        ObjectiveType.CONTROL_COUNT -> provincesOf(playerNationId) to objective.amount
    }

    fun isObjectiveMet(objective: Objective): Boolean {
        val (done, total) = objectiveProgress(objective)
        return when (objective.type) {
            ObjectiveType.HOLD_PROVINCES -> done >= total && turn >= objective.turn
            else -> done >= total && total > 0
        }
    }

    private fun evaluateOutcome() {
        if (status != SessionStatus.PLAYING) return
        val player = playerNation
        if (player.eliminated) {
            status = SessionStatus.DEFEAT
            return
        }
        val objectives = scenario.objectives
        if (objectives.isNotEmpty() && objectives.all { isObjectiveMet(it) }) {
            status = SessionStatus.VICTORY
            return
        }
        // 征服模式沒寫目標時的預設：拿下地圖上八成的省份。
        if (objectives.isEmpty()) {
            val owned = provincesOf(playerNationId)
            val total = map.provinces.count { it.tiles.isNotEmpty() }
            if (total > 0 && owned * 100 / total >= CONQUEST_VICTORY_PERCENT) {
                status = SessionStatus.VICTORY
                return
            }
        }
        if (scenario.turnLimit > 0 && turn > scenario.turnLimit) {
            status = SessionStatus.DEFEAT
        }
    }

    /** 讓外部（Orders、AI）在改動之後重新判定勝負。 */
    fun refreshOutcome() = evaluateOutcome()

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 讀檔用：把開局展開出來的部隊清掉，再由存檔重建。 */
    internal fun clearAllUnits() {
        units.clear()
        unitsById.clear()
        java.util.Arrays.fill(occupancy, -1)
        nextUnitId = 1
    }

    /** 讀檔用：直接放一支已經有狀態的部隊回場上。 */
    internal fun restoreUnit(unit: ArmyUnit) {
        units.add(unit)
        unitsById[unit.id] = unit
        if (!unit.isLoaded) setOccupancy(unit.tile, layerOf(unit), unit.id)
        if (unit.id >= nextUnitId) nextUnitId = unit.id + 1
    }

    /** 讀檔收尾：載具與乘客的雙向連結要重新接起來。 */
    internal fun rebuildCargoLinks() {
        for (unit in units) unit.cargo.clear()
        for (unit in units) {
            if (unit.transportId < 0) continue
            val transport = unitsById[unit.transportId]
            if (transport == null) {
                unit.transportId = -1
                setOccupancy(unit.tile, layerOf(unit), unit.id)
            } else {
                transport.cargo.add(unit.id)
            }
        }
    }

    /** 讀檔後重新算一次補給覆蓋，否則 HUD 上的補給圈會是空的。 */
    internal fun refreshSupplyView() = computeSupply(activeNationId)

    fun pushEvent(key: String, args: List<Any>, tile: Int, nationId: Int) {
        events.add(GameEvent(key, args, tile, nationId))
        while (events.size > MAX_EVENTS) events.removeAt(0)
    }

    // ------------------------------------------------------------------
    // 劇本展開
    // ------------------------------------------------------------------

    /**
     * 兩國是否同一陣營。
     *
     * 中立（空字串）不算陣營：兩個中立國之間沒有任何默契，瑞士不會因為
     * 瑞典也中立就不打它。只有具名的陣營才互相約束。
     */
    fun sameBloc(a: Int, b: Int): Boolean {
        val blocA = nations.getOrNull(a)?.bloc ?: return false
        return blocA.isNotEmpty() && blocA == nations.getOrNull(b)?.bloc
    }

    private fun applyScenarioRelations() {
        for ((a, b, relation) in scenario.relations) {
            val ia = nationIndexByCode[a] ?: continue
            val ib = nationIndexByCode[b] ?: continue
            diplomacy.set(ia, ib, relation)
        }
    }

    private fun applyScenarioOwnership() {
        for ((code, provinces) in scenario.ownership) {
            val nationId = nationIndexByCode[code] ?: continue
            for (pid in provinces) {
                if (pid in provinceOwner.indices) provinceOwner[pid] = nationId
            }
        }
    }

    private fun applyScenarioFunds() {
        for (nation in nations) {
            if (nation.isPlayer) continue
            nation.funds = nation.funds * difficulty.aiStartFunds / 100
        }
    }

    private fun computeProvinceIncome() {
        for (province in map.provinces) {
            var sum = 0
            for (tile in province.tiles) sum += map.terrainAt(tile).income
            // 除以 4：省份大小差距很大，直接加總會讓西伯利亞比魯爾區還值錢。
            provinceTerrainIncome[province.id] = sum / 4
        }
    }

    private fun spawnScenarioUnits() {
        for (su in scenario.startingUnits) {
            val nationId = nationIndexByCode[su.nationCode] ?: continue
            val kind = UnitKind.byName(su.kindName) ?: continue
            if (!map.inBounds(su.col, su.row)) continue
            spawnUnit(kind, nationId, map.index(su.col, su.row), su.level, su.commanderId)
        }
        // 開局的部隊立刻可以行動。
        for (unit in units) {
            unit.movesLeft = movementFor(unit)
            unit.hasAttacked = false
        }
    }

    companion object {
        const val MAX_ENTRENCHMENT = 3

        /** 相鄰敵軍到這個數量就算被包圍。 */
        const val ENCIRCLED_THRESHOLD = 4
        const val COMMAND_AURA_RANGE = 3
        const val COMMAND_AURA_BONUS = 10

        /** 補給從城市能傳出去的移動成本上限。 */
        const val SUPPLY_RANGE = 12
        const val MOBILE_SUPPLY_RANGE = 5
        const val SEA_SUPPLY_COST = 6

        const val RESUPPLY_RATE = 34
        const val ATTRITION_RATE = 22
        const val STARVATION_DAMAGE = 8
        const val CITY_REPAIR = 22
        const val FIELD_REPAIR = 6

        const val CONQUEST_VICTORY_PERCENT = 80
        const val MAX_EVENTS = 40
    }
}
