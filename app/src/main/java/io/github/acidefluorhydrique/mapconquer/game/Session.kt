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

    /**
     * 逐省的城防值。無城的省份恆為 0。
     *
     * 這是可變的局面狀態，所以放在 Session 而不是地圖上 —— 地圖是唯讀的，
     * 同一張圖要能被許多局共用。
     */
    val cityHp: IntArray = IntArray(map.provinces.size) { map.provinces[it].maxCityHp }

    /** 省份的地形產值總和，開局算一次。 */
    private val provinceTerrainIncome: IntArray = IntArray(map.provinces.size)

    val units = ArrayList<ArmyUnit>(256)
    private val unitsById = HashMap<Int, ArmyUnit>(256)
    private var nextUnitId = 1

    /**
     * 兩層佔位表：陸、海各一份，值是單位 id（-1 = 空）。
     *
     * 一格仍然只放得下一支部隊（見 [isTileFree]），分層只是為了讓
     * 「浮渡的陸軍佔海層」那條規則有地方寫。
     */
    private val occupancy = IntArray(map.tileCount * DOMAINS) { -1 }

    /**
     * 這回合已經起飛過的空中任務起飛點（鍵的定義見 [AirOps]）。
     * 每個起飛點每回合只飛一次，回合開始時清空。
     */
    val sortieBases = HashSet<Int>()

    /** 目前行動國的補給覆蓋，回合開始時重算。 */
    private val suppliedTiles = BooleanArray(map.tileCount)
    private val supplyCost = IntArray(map.tileCount)

    /**
     * 補給車與司令部那一段擴散自己的成本表。跟城市的分開算：城市的成本比較低的
     * 格子，補給車仍然要從自己的五點預算往外推 —— 否則停在自家城市旁邊的補給車
     * 會被整台跳過，它伸進敵境的那一圈就不生效了。
     */
    private val columnCost = IntArray(map.tileCount)

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
        // 玩家永遠排第一：每一輪由玩家先動，AI 在玩家結束回合之後才依序行動。
        // 劇本檔的順序（依國力排）只決定 AI 之間的先後。開新局時不該先看著
        // 一百多個 AI 跑完一輪才輪到自己。
        val order = scenario.nations.sortedBy { if (it.code == playerCode) 0 else 1 }
        order.forEachIndexed { index, sn ->
            val nation = Nation(
                index, sn.code, sn.nameKey, sn.colour,
                sn.aiProfile, sn.capitalProvince, sn.flag, sn.bloc, sn.warTurn
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
        activateBlocWars()
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

    /** 這格上的那一支部隊（一格只會有一支，先查陸層只是習慣）。 */
    fun primaryUnitAt(tile: Int): ArmyUnit? =
        unitAt(tile, Domain.LAND) ?: unitAt(tile, Domain.SEA)

    fun anyUnitAt(tile: Int): Boolean =
        occupancy[tile] >= 0 || occupancy[map.tileCount + tile] >= 0

    fun ownerOfTile(tile: Int): Int {
        val p = map.provinceOf[tile]
        return if (p >= 0) provinceOwner[p] else -1
    }

    fun unitsOf(nationId: Int): List<ArmyUnit> = units.filter { it.nationId == nationId && it.isAlive }

    fun provincesOf(nationId: Int): Int = provinceOwner.count { it == nationId }

    fun isHostile(a: Int, b: Int): Boolean = diplomacy.isHostile(a, b)

    /**
     * 這格能不能站人。
     *
     * 一格只容得下一支部隊，敵我與軍種都不例外。分層的索引留著是為了
     * 「浮渡的陸軍佔海層」那條規則與 [unitAt] 的查詢，但它不再是能不能
     * 站進去的判準 —— 疊在同一格的戰鬥機與步兵，玩家點下去根本不知道
     * 自己選到了誰。
     */
    fun isTileFree(tile: Int): Boolean = !anyUnitAt(tile)

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

    fun isTileFreeFor(unit: ArmyUnit, tile: Int): Boolean = isTileFree(tile)

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
        if (!isTileFree(tile)) return null
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
        // 接手的是一座殘城。全滿會讓易主的城市立刻變成新的堡壘，
        // 反覆爭奪的城市就永遠打不下來。
        cityHp[provinceId] = province.maxCityHp * CITY_HP_AFTER_CAPTURE_PERCENT / 100
        pushEvent(
            if (province.hasCity) "event_city_captured" else "event_province_captured",
            listOf(province.nameKey, nations[newOwner].nameKey),
            province.capitalTile,
            newOwner
        )
        if (old >= 0) {
            if (province.hasCity && !ownsAnyCity(old)) surrender(old, newOwner) else checkElimination(old)
        }
        return true
    }

    fun ownsAnyCity(nationId: Int): Boolean =
        map.provinces.any { it.hasCity && provinceOwner[it.id] == nationId }

    /**
     * 投降：失去最後一座城市的國家退出戰局。
     *
     * 規則取自原版的可觀察行為（docs/original-behavior.md「投降」）：它剩下的
     * 部隊全部消失 —— 包括還在海上、還在別國境內的 —— 剩下的領土歸給攻下
     * 最後那座城的國家。這讓「打下它的城市」就是打垮一個國家的全部條件，
     * 不必把散在各處的最後幾支部隊一一追殺乾淨。
     */
    private fun surrender(nationId: Int, captor: Int) {
        val nation = nations[nationId]
        if (nation.eliminated) return
        for (pid in provinceOwner.indices) {
            if (provinceOwner[pid] == nationId) provinceOwner[pid] = captor
        }
        // 載具沉沒時會連同貨艙一起處理，已經死掉的就跳過，免得重複計入損失。
        for (unit in units.filter { it.nationId == nationId }) if (unit.isAlive) destroyUnit(unit)
        nation.eliminated = true
        pushEvent(
            "event_nation_surrendered",
            listOf(nation.nameKey, nations[captor].nameKey),
            -1,
            nationId
        )
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
                announceScheduledEntries()
                activateBlocWars()
                wrapped = true
            }
            guard++
        } while (nations[activeNationId].eliminated && guard <= nations.size * 2)

        beginNationTurn(nations[activeNationId])
        evaluateOutcome()
        return wrapped
    }

    private fun beginNationTurn(nation: Nation) {
        sortieBases.clear()
        collectIncome(nation)
        computeSupply(nation.id)
        refreshUnits(nation)
        repairCities(nation.id)
        pressCities(nation.id)
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
            if (unit.nationId == nation.id && unit.isAlive) upkeep += unit.kind.upkeep * unit.size
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
     *
     * 補給車與司令部是第二種來源，而且**不受領土限制**：它們就是隨軍的補給站，
     * 推到哪裡撐到哪裡。原本它們跟城市走同一條規則，結果在敵境只供應自己站的
     * 那一格 —— 攻城部隊就在隔壁也拿不到，等於「帶著補給車進攻」完全沒有作用。
     * 它們傳得比城市近（[MOBILE_SUPPLY_RANGE]），所以補給線仍然要靠打下城市才真的前移。
     */
    private fun computeSupply(nationId: Int) {
        java.util.Arrays.fill(suppliedTiles, false)
        java.util.Arrays.fill(supplyCost, Int.MAX_VALUE)

        val fromCities = ArrayDeque<Int>()
        for (province in map.provinces) {
            if (provinceOwner[province.id] != nationId) continue
            if (!province.hasCity) continue
            val tile = province.capitalTile
            suppliedTiles[tile] = true
            supplyCost[tile] = 0
            fromCities.add(tile)
        }
        spreadSupply(fromCities, nationId, throughEnemyLand = false)

        java.util.Arrays.fill(columnCost, Int.MAX_VALUE)
        val fromColumns = ArrayDeque<Int>()
        for (unit in units) {
            if (unit.nationId != nationId || !unit.isAlive || unit.isLoaded || !unit.kind.isSupplier) continue
            // 自己都快餓死的補給車發不出補給。
            if (unit.supply < ArmyUnit.SUPPLY_CRITICAL) continue
            val start = SUPPLY_RANGE - MOBILE_SUPPLY_RANGE
            if (columnCost[unit.tile] <= start) continue
            suppliedTiles[unit.tile] = true
            columnCost[unit.tile] = start
            fromColumns.add(unit.tile)
        }
        spreadSupply(fromColumns, nationId, throughEnemyLand = true, cost = columnCost)
    }

    /**
     * 一支補給車或司令部自己撐起的補給圈，寫進 [out]（顯示用）。
     *
     * 跟 [computeSupply] 的第二段走同一支擴散，只是換一組陣列 —— 畫出來的範圍
     * 永遠就是實際生效的範圍。形狀不是圓：沿平原伸得遠，遇到山、河就縮回來。
     * 自己補給不足（低於 [ArmyUnit.SUPPLY_CRITICAL]）的補給車發不出補給，圈是空的。
     */
    fun supplyBubble(unit: ArmyUnit, out: BooleanArray) {
        java.util.Arrays.fill(out, false)
        if (!unit.isAlive || unit.isLoaded || !unit.kind.isSupplier) return
        if (unit.supply < ArmyUnit.SUPPLY_CRITICAL) return
        val cost = IntArray(map.tileCount) { Int.MAX_VALUE }
        cost[unit.tile] = SUPPLY_RANGE - MOBILE_SUPPLY_RANGE
        out[unit.tile] = true
        val frontier = ArrayDeque<Int>()
        frontier.add(unit.tile)
        spreadSupply(frontier, unit.nationId, throughEnemyLand = true, cost = cost, reached = out)
    }

    /**
     * 補給的擴散。走 BFS 而不是 Dijkstra：補給距離的上限很小，代價又都是個位數，
     * 重複入列的次數遠比維護一個堆積便宜。
     */
    private fun spreadSupply(
        frontier: ArrayDeque<Int>,
        nationId: Int,
        throughEnemyLand: Boolean,
        cost: IntArray = supplyCost,
        reached: BooleanArray = suppliedTiles
    ) {
        while (frontier.isNotEmpty()) {
            val tile = frontier.removeFirst()
            val spent = cost[tile]
            if (spent == Int.MAX_VALUE) continue
            val n = map.neighbours(tile, neighbourBuf)
            for (i in 0 until n) {
                val next = neighbourBuf[i]
                val terrain = map.terrainAt(next)
                // 補給沿陸地走；水域成本高到只夠把補給遞過一道海峽。
                val step = if (terrain.isWater) SEA_SUPPLY_COST else terrain.moveCost
                if (!throughEnemyLand) {
                    val owner = ownerOfTile(next)
                    if (terrain.isLand && owner >= 0 && !diplomacy.isAllied(owner, nationId)) continue
                }
                val total = spent + step
                if (total > SUPPLY_RANGE) continue
                if (cost[next] <= total) continue
                cost[next] = total
                reached[next] = true
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
                // 軍艦出港時就帶著幾週的油料與糧食，補給斷了會變弱，不會沉。
                // 陸軍不一樣：彈藥與糧食是每天要送上去的，斷了就會餓死。
                val ship = unit.kind.domain == Domain.SEA
                var drain = if (ship) NAVAL_ATTRITION_RATE else ATTRITION_RATE
                if (unit.commander?.has(CommanderSkill.IRON_WILL) == true) drain /= 2
                val floor = if (ship) ArmyUnit.SUPPLY_CRITICAL else 0
                unit.supply = (unit.supply - drain).coerceAtLeast(floor)
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

    /** 載在自己的船上時算有補給。 */
    private fun carriedByFriendlyBase(unit: ArmyUnit): Boolean {
        if (!unit.isLoaded) return false
        val transport = unitsById[unit.transportId] ?: return false
        return transport.nationId == unit.nationId
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
        // 征服模式沒寫目標時的預設：攻下所有敵國的城市（見 docs/original-behavior.md
        // 「征服的勝利條件」）。敵國失去最後一座城就投降，所以「敵人全部投降」
        // 跟「敵人的城市全在你手上」是同一件事。盟國與中立國不算 —— 原本的
        // 「拿下八成地圖」會逼玩家去打它們，那不是這個模式的勝利。
        if (objectives.isEmpty()) {
            val enemies = conquestEnemiesOf(playerNationId)
            if (enemies.isNotEmpty() && enemies.all { it.eliminated }) {
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
    /**
     * 這個國家是不是「旁觀者」：沒有陣營，而且沒有跟任何人交戰。
     *
     * 旁觀者不建軍、不移動、不宣戰。它有城市、有守軍、擋在路上，但除非
     * 有人對它動手，它在棋盤上就是地形的一部分。1939 年的瑞士不會因為
     * 鄰居打起來就開始擴軍。
     */
    /** 這一格是不是某個省的城市所在。 */
    fun cityProvinceAt(tile: Int): Int {
        val province = map.provinceAt(tile) ?: return -1
        return if (province.hasCity && province.capitalTile == tile) province.id else -1
    }

    /**
     * 這格是不是一座對 [nationId] 關著門的城：交戰國或無主的城，而且城防還在。
     *
     * 還有城防的城跟一支敵軍一樣擋路 —— 走不進、穿不過、空降也落不下去。
     * 要先從旁邊把城防打到零，城裡沒有守軍時才能走進去佔領
     * （docs/original-behavior.md「佔領城市」）。原本部隊可以直接走進還有城防
     * 的空城，站在裡面慢慢圍，旗子卻還是對方的，看起來就像佔了又沒佔。
     */
    fun isCityShutTo(tile: Int, nationId: Int): Boolean {
        val pid = cityProvinceAt(tile)
        if (pid < 0 || cityHp[pid] <= 0) return false
        val owner = provinceOwner[pid]
        if (owner == nationId) return false
        return owner < 0 || isHostile(owner, nationId)
    }

    /**
     * 這支部隊是否正受到城防保護。
     *
     * 只有站在城市格上的陸軍算數：飛機在天上，船在水裡，兩者都不在城牆後面。
     * 城防歸零之後也不再保護 —— 那就是「城破了」的定義。
     */
    fun cityShields(unit: ArmyUnit): Boolean {
        if (unit.kind.domain != Domain.LAND) return false
        val pid = cityProvinceAt(unit.tile)
        return pid >= 0 && cityHp[pid] > 0 && holdsCity(unit, pid)
    }

    /**
     * 這座城是不是站在裡面的這支部隊自己（或盟友）的。
     *
     * 城牆只護著守軍。圍城的部隊站在敵城格上，原本也被算成「在城牆後面」：
     * 打它的傷害一半扣在城防上，守方等於在拆自己的城，拆到零之後圍攻又
     * 因為「城防已經是零」而跳過，那座城就再也不會易主。
     */
    fun holdsCity(unit: ArmyUnit, provinceId: Int): Boolean {
        val owner = provinceOwner.getOrElse(provinceId) { -1 }
        return owner >= 0 && diplomacy.isAllied(owner, unit.nationId)
    }

    /** 對城市造成傷害，回傳實際扣掉的量。 */
    fun damageCity(provinceId: Int, amount: Int): Int {
        if (provinceId !in cityHp.indices || amount <= 0) return 0
        val before = cityHp[provinceId]
        cityHp[provinceId] = (before - amount).coerceAtLeast(0)
        return before - cityHp[provinceId]
    }

    /**
     * 城防每回合自行修復，條件有兩個：城裡有自己的守軍，而且旁邊沒有敵人。
     *
     * 要守軍才修得動，是因為城防不該自己長回來。打下一座城之後城防只剩
     * 三成五，要不要留一支部隊把它補起來，就是「佔領」與「路過」的差別 ——
     * 而留下來的那一支，也正好是這座城下次被打時的第一層護盾。
     *
     * 有敵人貼著就停修：否則攻城會退化成「傷害要大於修復速度」的數值檢定，
     * 防守方光靠時間就能拖垮攻勢。
     */
    private fun repairCities(nationId: Int) {
        for (province in map.provinces) {
            if (!province.hasCity) continue
            if (provinceOwner[province.id] != nationId) continue
            val max = province.maxCityHp
            if (cityHp[province.id] >= max) continue
            val garrison = unitAt(province.capitalTile, Domain.LAND)
            if (garrison == null || garrison.nationId != nationId) continue
            // 城裡站著敵人也算被威脅 —— 不然圍攻期間城防還會一邊自修，
            // 攻城就變成「傷害要跑得比修復快」的數值檢定。
            var threatened = hasHostileAt(province.capitalTile, nationId)
            if (!threatened) {
                val count = map.neighbours(province.capitalTile, neighbourBuf)
                for (i in 0 until count) {
                    if (hasHostileAt(neighbourBuf[i], nationId)) { threatened = true; break }
                }
            }
            if (threatened) continue
            cityHp[province.id] = (cityHp[province.id] + CITY_DEFENCE_REPAIR).coerceAtMost(max)
        }
    }

    /**
     * 收下城防已經歸零、而且己方陸軍正站在裡面的敵城。
     *
     * 正常情況下佔領發生在走進城的那一刻（[Orders.tryCapture]），城防還在的城
     * 根本走不進去（[isCityShutTo]）。這裡只是安全網：劇本把部隊直接擺在敵城上、
     * 或城防在部隊進城之後才被打光時，下一個己方回合照樣易主。
     */
    private fun pressCities(nationId: Int) {
        for (province in map.provinces) {
            if (!province.hasCity) continue
            val owner = provinceOwner[province.id]
            if (owner == nationId) continue
            if (owner >= 0 && !isHostile(owner, nationId)) continue
            val besieger = unitAt(province.capitalTile, Domain.LAND) ?: continue
            if (besieger.nationId != nationId || !besieger.isAlive || !besieger.kind.canCapture) continue
            if (cityHp[province.id] <= 0) captureProvince(province.id, nationId)
        }
    }

    fun isBystander(nationId: Int): Boolean {
        val nation = nations.getOrNull(nationId) ?: return false
        return nation.bloc.isEmpty() && !isAtWarWithAnyone(nationId)
    }

    fun isAtWarWithAnyone(nationId: Int): Boolean =
        nations.indices.any { it != nationId && diplomacy.isAtWar(nationId, it) }

    /** 兩國是否分屬敵對陣營。任一方沒有陣營就不算。 */
    fun opposingBlocs(a: Int, b: Int): Boolean {
        val blocA = nations.getOrNull(a)?.bloc.orEmpty()
        val blocB = nations.getOrNull(b)?.bloc.orEmpty()
        return blocA.isNotEmpty() && blocB.isNotEmpty() && blocA != blocB
    }

    /**
     * 這個國家是否已經加入陣營戰爭。
     *
     * 兩種方式進場：輪到它的參戰回合，或是被敵對陣營的國家先打了。後者是
     * 為了讓「提早對美國宣戰」有代價 —— 那不只是跟美國一國開戰，而是把整個
     * 同盟提早拖進來。被無陣營的國家攻擊則不算：巴西打美國是雙邊戰爭，
     * 不會因此把美國推進歐洲戰場。
     */
    fun isBelligerent(nationId: Int): Boolean {
        val nation = nations.getOrNull(nationId) ?: return false
        if (nation.bloc.isEmpty() || nation.eliminated) return false
        if (nation.warTurn <= turn) return true
        return nations.indices.any { opposingBlocs(nationId, it) && diplomacy.isAtWar(nationId, it) }
    }

    /** 所有已參戰、分屬敵對陣營的國家兩兩開戰。重複呼叫沒有副作用。 */
    fun activateBlocWars() {
        for (a in nations.indices) {
            if (!isBelligerent(a)) continue
            for (b in a + 1 until nations.size) {
                if (!opposingBlocs(a, b) || diplomacy.isAtWar(a, b)) continue
                if (!isBelligerent(b)) continue
                diplomacy.set(a, b, Relation.WAR)
            }
        }
    }

    /** 回合推進時，替這一回合照表參戰的國家發一則戰報。 */
    private fun announceScheduledEntries() {
        for (nation in nations) {
            if (nation.eliminated || nation.bloc.isEmpty() || nation.warTurn != turn) continue
            val alreadyIn = nations.indices.any {
                opposingBlocs(nation.id, it) && diplomacy.isAtWar(nation.id, it)
            }
            if (!alreadyIn) pushEvent("event_war_entered", listOf(nation.nameKey), -1, nation.id)
        }
    }

    /**
     * 征服模式裡 [nationId] 必須打垮的國家：敵對陣營的全部成員（包括還沒到
     * 參戰回合的），加上目前跟它交戰中的任何國家（例如被它宣戰的中立國）。
     * 盟國與沒有交戰的中立國不在其中。
     */
    fun conquestEnemiesOf(nationId: Int): List<Nation> {
        val bloc = blocEnemiesOf(nationId)
        return nations.filter { it.id != nationId && (it in bloc || diplomacy.isAtWar(nationId, it.id)) }
    }

    /** 與 [nationId] 分屬敵對陣營的國家；兩邊都要有陣營才算數。 */
    fun blocEnemiesOf(nationId: Int): List<Nation> {
        val bloc = nations.getOrNull(nationId)?.bloc.orEmpty()
        if (bloc.isEmpty()) return emptyList()
        return nations.filter { it.bloc.isNotEmpty() && it.bloc != bloc }
    }

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
                ?.let { it.size = su.size.coerceIn(1, ArmyUnit.MAX_SIZE) }
        }
        // 開局的部隊立刻可以行動。
        for (unit in units) {
            unit.movesLeft = movementFor(unit)
            unit.hasAttacked = false
        }
    }

    companion object {
        private val DOMAINS = Domain.values().size

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

        /**
         * 軍艦離開補給範圍時每回合掉的補給。
         *
         * 只有陸軍的三分之一，而且掉到 [ArmyUnit.SUPPLY_CRITICAL] 就停住 ——
         * 遠洋的艦隊會滑到八成戰力，得回自己的港口才補得回來，但不會在海上
         * 餓死。原本海軍跟陸軍同一條規則，可是補給出海只伸得出兩格
         * （水域一格就吃掉 [SEA_SUPPLY_COST]），一支艦隊離岸三格待著，
         * 十幾回合後會自己沉光 —— 橫渡大洋因此是不可能的。
         */
        const val NAVAL_ATTRITION_RATE = 7
        const val STARVATION_DAMAGE = 8
        const val CITY_REPAIR = 22
        const val FIELD_REPAIR = 6

        /**
         * 有守軍時城防每回合的修復量。
         *
         * 比舊值高，因為現在要留一支部隊才修得動 —— 條件變嚴了，速度就該
         * 補回來一點。仍然低於一次砲擊的傷害，所以圍攻不會被修復追平。
         *
         * 注意跟 [CITY_REPAIR] 不是同一件事：那個是「城市替駐軍回血」，
         * 這個是「駐軍替城市補城防」。兩個方向都有，互為代價。
         */
        const val CITY_DEFENCE_REPAIR = 12

        /** 易主之後城防剩下的比例：新主人接手的是一座殘城。 */
        const val CITY_HP_AFTER_CAPTURE_PERCENT = 35
        const val MAX_EVENTS = 40
    }
}
