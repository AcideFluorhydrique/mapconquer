// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Combat
import io.github.acidefluorhydrique.mapconquer.units.CommanderSkill
import io.github.acidefluorhydrique.mapconquer.units.TargetClass
import io.github.acidefluorhydrique.mapconquer.units.TechBranch
import io.github.acidefluorhydrique.mapconquer.units.UnitKind

/**
 * 空中任務。
 *
 * 飛機不是棋盤上的棋子。原本的戰鬥機、轟炸機、運輸機是會站格子的部隊，
 * 結果它們停在敵城裡擋住佔領、在前線上空疊成一團、還要玩家每回合一架一架
 * 地移 —— 全是這個尺度用不著的細節。現在空軍是「花錢買一次出擊」：
 * 從機場或航艦起飛，打一下（或放一支傘兵下去），然後就回去了。
 *
 * [strike] 的三個值依序是對 SOFT / ARMOURED / SHIP 的火力：
 * 戰鬥機掃射步兵、轟炸機專打戰車與軍艦，兩者互補，沒有哪個全能。
 */
enum class AirMission(
    val key: String,
    val cost: Int,
    /** 起飛的城市至少要有這個工業等級 —— 那就是「有機場」的意思。 */
    val industry: Int,
    /** 離起飛點的最大距離（格）。 */
    val range: Int,
    val strike: IntArray
) {
    FIGHTER("mission_fighter", 70, 2, 6, intArrayOf(46, 12, 10)),
    BOMBER("mission_bomber", 110, 3, 8, intArrayOf(16, 48, 52)),

    /** 空降：在目標格放下一支新的步兵。價錢另加一支步兵。 */
    AIRDROP("mission_airdrop", 60, 2, 6, intArrayOf(0, 0, 0));

    val descKey: String get() = key + "_desc"

    val isStrike: Boolean get() = this != AIRDROP

    /** 這個任務實際要付的錢。 */
    val totalCost: Int get() = if (this == AIRDROP) cost + AirOps.PARATROOPER.cost else cost

    fun strikeAgainst(target: TargetClass): Int =
        if (target.ordinal < strike.size) strike[target.ordinal] else 0

    companion object {
        val ALL: Array<AirMission> = values()
    }
}

/** 一次出擊的結果，給 UI 播報用。 */
class AirResult(
    val damageToUnit: Int,
    val damageToCity: Int,
    val destroyed: Boolean,
    /** 空降下來的那一支；打擊任務是 null。 */
    val dropped: ArmyUnit?
)

/**
 * 空中任務的規則。玩家與 AI 共用，跟 [Orders] 一樣「先驗證、再執行」。
 *
 * 限制只有兩條，而且都看得見：錢，以及每個起飛點每回合只飛一次。
 * 後者讓機場本身變成值得爭的東西 —— 多一座大城就多一個出擊架次。
 */
object AirOps {

    enum class Blocker { NONE, NO_FUNDS, NO_BASE }

    /** 空降下來的兵種。 */
    val PARATROOPER: UnitKind = UnitKind.INFANTRY

    /** 敵方防空能罩到目標的距離。 */
    const val FLAK_RANGE = 2

    /** 附近有空戰專家指揮官時的加成，半徑與司令部光環相同。 */
    private const val AIR_EXPERT_RANGE = 3

    /** 空降部隊落地時，每一點防空火力扣掉的血（百分比），上限見下。 */
    private const val DROP_FLAK_LOSS_DIVISOR = 2
    private const val DROP_FLAK_MAX_LOSS = 60

    /**
     * 起飛點的鍵：城市用省份 id，航艦用「省份數 + 部隊 id」。
     * 兩者放在同一個集合裡記錄這回合飛過了沒有。
     */
    private fun cityBaseKey(provinceId: Int) = provinceId
    private fun carrierBaseKey(session: Session, unit: ArmyUnit) = session.map.provinces.size + unit.id

    /**
     * 找一個還沒飛過、而且搆得到 [target] 的起飛點，回傳它的鍵；沒有就回 -1。
     * [target] 傳 -1 代表不看距離，只問「這回合還有沒有能飛的」。
     */
    fun launchBase(session: Session, nationId: Int, mission: AirMission, target: Int): Int {
        val map = session.map
        var best = -1
        var bestDistance = Int.MAX_VALUE
        for (province in map.provinces) {
            if (!province.hasCity || province.industry < mission.industry) continue
            if (session.provinceOwner[province.id] != nationId) continue
            val key = cityBaseKey(province.id)
            if (session.sortieBases.contains(key)) continue
            val d = if (target < 0) 0 else map.distance(province.capitalTile, target)
            if (d > mission.range || d >= bestDistance) continue
            best = key
            bestDistance = d
        }
        for (unit in session.units) {
            if (unit.nationId != nationId || !unit.isAlive || !unit.kind.isAirbase) continue
            val key = carrierBaseKey(session, unit)
            if (session.sortieBases.contains(key)) continue
            val d = if (target < 0) 0 else map.distance(unit.tile, target)
            if (d > mission.range || d >= bestDistance) continue
            best = key
            bestDistance = d
        }
        return best
    }

    fun blocker(session: Session, nationId: Int, mission: AirMission): Blocker {
        if (session.nations[nationId].funds < mission.totalCost) return Blocker.NO_FUNDS
        if (launchBase(session, nationId, mission, -1) < 0) return Blocker.NO_BASE
        return Blocker.NONE
    }

    /** 這一格上 [nationId] 的飛機打得到的敵軍；沒有就回 null。 */
    fun strikeTarget(session: Session, nationId: Int, mission: AirMission, tile: Int): ArmyUnit? {
        if (!mission.isStrike) return null
        val unit = session.primaryUnitAt(tile) ?: return null
        if (!unit.isAlive || !session.isHostile(unit.nationId, nationId)) return null
        if (mission.strikeAgainst(unit.kind.targetClass) <= 0) return null
        // 看不見的潛艇炸不到 —— 跟地面部隊的規則一致。
        if (nationId == session.playerNationId && !session.isUnitVisibleToPlayer(unit)) return null
        return unit
    }

    /** 轟炸機可以直接炸一座沒有守軍的敵城，回傳省份 id；不行回 -1。 */
    fun cityTarget(session: Session, nationId: Int, mission: AirMission, tile: Int): Int {
        if (mission != AirMission.BOMBER) return -1
        if (session.anyUnitAt(tile)) return -1
        val pid = session.cityProvinceAt(tile)
        if (pid < 0 || session.cityHp[pid] <= 0) return -1
        val owner = session.provinceOwner[pid]
        if (owner == nationId) return -1
        if (owner >= 0 && !session.isHostile(owner, nationId)) return -1
        return pid
    }

    /** 空降能不能落在這一格。 */
    fun canDropAt(session: Session, nationId: Int, tile: Int): Boolean {
        val terrain = session.map.terrainAt(tile)
        if (!terrain.isLand || !session.isTileFree(tile)) return false
        val pid = session.cityProvinceAt(tile)
        if (pid < 0) return true
        // 還有城防的敵城落不下去，跟走不進去是同一條規則。
        if (session.isCityShutTo(tile, nationId)) return false
        // 跟走路進城同一條規則：自己與盟友的城、交戰國的城、無主的城可以；
        // 沒在打仗的第三國不行。
        val owner = session.provinceOwner[pid]
        return owner < 0 || session.diplomacy.isAllied(owner, nationId) || session.isHostile(owner, nationId)
    }

    private fun isValidTarget(session: Session, nationId: Int, mission: AirMission, tile: Int): Boolean =
        if (mission == AirMission.AIRDROP) {
            canDropAt(session, nationId, tile)
        } else {
            strikeTarget(session, nationId, mission, tile) != null ||
                cityTarget(session, nationId, mission, tile) >= 0
        }

    fun canFly(session: Session, nationId: Int, mission: AirMission, tile: Int): Boolean {
        if (tile !in 0 until session.map.tileCount) return false
        if (session.nations[nationId].funds < mission.totalCost) return false
        if (!isValidTarget(session, nationId, mission, tile)) return false
        return launchBase(session, nationId, mission, tile) >= 0
    }

    /** 這次出擊會從地圖上哪一格起飛（城市或航艦所在格）；沒有能飛的起飛點回 -1。給出擊動畫找起點用。 */
    fun launchTile(session: Session, nationId: Int, mission: AirMission, target: Int): Int {
        val key = launchBase(session, nationId, mission, target)
        if (key < 0) return -1
        val provinces = session.map.provinces
        return if (key < provinces.size) provinces[key].capitalTile
        else session.unitById(key - provinces.size)?.tile ?: -1
    }

    /** 所有合法目標。範圍用每個起飛點各掃一次，比整張地圖逐格試便宜得多。 */
    fun collectTargets(session: Session, nationId: Int, mission: AirMission, into: MutableList<Int>) {
        into.clear()
        if (blocker(session, nationId, mission) != Blocker.NONE) return
        val map = session.map
        val seen = BooleanArray(map.tileCount)
        val area = ArrayList<Int>(3 * mission.range * (mission.range + 1) + 1)
        val origins = ArrayList<Int>()
        for (province in map.provinces) {
            if (!province.hasCity || province.industry < mission.industry) continue
            if (session.provinceOwner[province.id] != nationId) continue
            if (session.sortieBases.contains(cityBaseKey(province.id))) continue
            origins.add(province.capitalTile)
        }
        for (unit in session.units) {
            if (unit.nationId != nationId || !unit.isAlive || !unit.kind.isAirbase) continue
            if (session.sortieBases.contains(carrierBaseKey(session, unit))) continue
            origins.add(unit.tile)
        }
        for (origin in origins) {
            map.collectWithin(origin, mission.range, area)
            for (tile in area) {
                if (seen[tile]) continue
                seen[tile] = true
                if (isValidTarget(session, nationId, mission, tile)) into.add(tile)
            }
        }
    }

    /**
     * 目標四周敵方防空的總火力。
     *
     * 用兵種表「對空」那一欄：防空炮最高，巡洋艦、驅逐艦其次。殘血的防空
     * 打得比較少，所以先把防空炮打殘，再派飛機過去，是划算的順序。
     */
    fun flakAt(session: Session, nationId: Int, tile: Int): Int {
        var total = 0
        for (unit in session.units) {
            if (!unit.isAlive || unit.isLoaded) continue
            if (!session.isHostile(unit.nationId, nationId)) continue
            val aa = unit.kind.attackAgainst(TargetClass.AIRCRAFT)
            if (aa <= 0) continue
            if (session.map.distance(unit.tile, tile) > FLAK_RANGE) continue
            total += aa * ArmyUnit.attackPercent(unit.size) / 100 * unit.hp / ArmyUnit.MAX_HP
        }
        return total
    }

    /** 一次打擊的火力：基礎值、空軍科技、空戰專家，再被防空削減。 */
    fun strikePower(session: Session, nationId: Int, mission: AirMission, target: TargetClass, tile: Int): Float {
        val base = mission.strikeAgainst(target).toFloat()
        if (base <= 0f) return 0f
        var p = base
        p *= 1f + session.nations[nationId].techBonus(TechBranch.AIR) / 100f
        if (hasAirExpertNear(session, nationId, tile)) p *= 1.2f
        return Combat.flakFactor(flakAt(session, nationId, tile)) * p
    }

    private fun hasAirExpertNear(session: Session, nationId: Int, tile: Int): Boolean =
        session.units.any {
            it.isAlive && it.nationId == nationId &&
                it.commander?.has(CommanderSkill.AIR_EXPERT) == true &&
                session.map.distance(it.tile, tile) <= AIR_EXPERT_RANGE
        }

    /** 不改變狀態的傷害預測（原始值，尚未依編制換算）。AI 拿它挑目標。 */
    fun previewDamage(session: Session, nationId: Int, mission: AirMission, defender: ArmyUnit): Int {
        val power = strikePower(session, nationId, mission, defender.kind.targetClass, defender.tile)
        return Combat.previewAirStrike(power, defenceOf(session, defender))
    }

    private fun defenceOf(session: Session, defender: ArmyUnit): Float {
        // 在天上往下打，地形只剩一半的遮蔽效果；城牆與築壕照算。
        val terrain = session.map.terrainAt(defender.tile).defenceBonus / 2
        val pid = session.cityProvinceAt(defender.tile)
        val city = if (pid >= 0 && session.holdsCity(defender, pid)) session.map.provinces[pid].cityDefenceBonus else 0
        return Combat.airDefence(
            defender,
            session.nations[defender.nationId].techBonus(defender.kind.branch),
            terrain + city,
            session.isEmbarked(defender)
        )
    }

    /** 出擊。不合法就回 null，不會留下半套狀態。 */
    fun fly(session: Session, nationId: Int, mission: AirMission, tile: Int): AirResult? {
        if (!canFly(session, nationId, mission, tile)) return null
        val base = launchBase(session, nationId, mission, tile)
        if (base < 0) return null
        val nation = session.nations[nationId]
        nation.funds -= mission.totalCost
        session.sortieBases.add(base)

        val result = if (mission == AirMission.AIRDROP) {
            drop(session, nationId, tile)
        } else {
            strike(session, nationId, mission, tile)
        }
        session.refreshOutcome()
        return result
    }

    private fun drop(session: Session, nationId: Int, tile: Int): AirResult {
        val unit = session.spawnUnit(PARATROOPER, nationId, tile)
            ?: return AirResult(0, 0, false, null)
        // 對著防空跳傘要付出代價，但不會整支掉光 —— 那是轟炸機的工作。
        val loss = (flakAt(session, nationId, tile) / DROP_FLAK_LOSS_DIVISOR).coerceAtMost(DROP_FLAK_MAX_LOSS)
        unit.hp = (ArmyUnit.MAX_HP - loss).coerceAtLeast(1)
        session.pushEvent(
            "event_airdrop",
            listOf(session.nations[nationId].nameKey),
            tile,
            nationId
        )
        Orders.tryCapture(session, unit)
        return AirResult(0, 0, false, unit)
    }

    private fun strike(session: Session, nationId: Int, mission: AirMission, tile: Int): AirResult {
        val defender = strikeTarget(session, nationId, mission, tile)
        if (defender == null) {
            val pid = cityTarget(session, nationId, mission, tile)
            if (pid < 0) return AirResult(0, 0, false, null)
            val power = strikePower(session, nationId, mission, TargetClass.ARMOURED, tile)
            val damage = Combat.airStrikeCity(power, session.map.provinces[pid].cityDefenceBonus, session.rng)
            val dealt = session.damageCity(pid, damage)
            session.pushEvent(
                "event_city_shelled",
                listOf(session.map.provinces[pid].nameKey, dealt),
                tile,
                nationId
            )
            return AirResult(0, dealt, false, null)
        }

        val power = strikePower(session, nationId, mission, defender.kind.targetClass, tile)
        val raw = Combat.airStrike(power, defenceOf(session, defender), session.rng)
        // 跟地面攻擊同一套城牆規則：駐軍吃一半，城防另外吃一份。
        val inCity = session.cityShields(defender)
        val toUnit = if (inCity) (raw * Combat.UNIT_DAMAGE_IN_CITY_PERCENT / 100).coerceAtLeast(1) else raw
        val toCity = if (inCity) raw * Combat.CITY_DAMAGE_PERCENT / 100 else 0
        val before = defender.hp
        defender.damage(toUnit)
        val lost = before - defender.hp
        val dealtCity = session.damageCity(session.cityProvinceAt(defender.tile), toCity)

        val defenderNation = session.nations[defender.nationId]
        session.pushEvent(
            "event_air_strike",
            listOf(defender.kind.key, defenderNation.nameKey, lost),
            tile,
            nationId
        )
        val destroyed = !defender.isAlive
        if (destroyed) {
            session.nations[nationId].unitsKilled++
            session.pushEvent(
                "event_unit_destroyed",
                listOf(defender.kind.key, defenderNation.nameKey),
                tile,
                nationId
            )
            session.destroyUnit(defender)
        }
        return AirResult(lost, dealtCity, destroyed, null)
    }
}
