// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

import io.github.acidefluorhydrique.mapconquer.core.Rng
import io.github.acidefluorhydrique.mapconquer.world.Terrain

/**
 * 一次交戰的完整輸入。
 *
 * 把「戰局相關的修正」全部收成這個結構再丟給 [Combat]，
 * 好處是戰鬥公式本身完全不依賴 Session —— 它可以被單元測試直接餵數字，
 * 也可以被 AI 拿來做「如果我打這格會怎樣」的沙盤推演而不動到真實狀態。
 */
class CombatContext(
    val attacker: ArmyUnit,
    val defender: ArmyUnit,
    /** 守方站的地形：決定裝甲與火炮攻方的懲罰。 */
    val defenderTerrain: Terrain = Terrain.PLAIN,
    /** 攻方站的地形，反擊時換它當守方地形。 */
    val attackerTerrain: Terrain = Terrain.PLAIN,
    /** 距離 1 是貼身，> 1 是遠射（守方不還手）。 */
    val distance: Int,
    /** 攻方所屬國家在該兵種分支上的科技加成（百分比）。 */
    val attackerTech: Int,
    val defenderTech: Int,
    /** 附近有司令部時的攻擊加成（百分比）。 */
    val attackerAura: Int,
    val defenderAura: Int,
    /**
     * 陸軍是否正在浮渡。
     *
     * 下了海的陸軍等於擠在運輸駁船上：沒有防禦、火力大打折扣、
     * 還不了手。這讓「制海權」變成真的有意義 ——
     * 沒有護航就渡海，是把整支部隊送給對方的艦隊。
     */
    val attackerAtSea: Boolean = false,
    val defenderAtSea: Boolean = false,
    /** 士氣等級，由 Session 依當下態勢推導後傳進來（+1 高昂 … −3 混亂）。 */
    val attackerMorale: Int = 0,
    val defenderMorale: Int = 0,
    /**
     * 這一方是否站在城防還在的城市格上。
     *
     * 站在城裡的部隊只吃一半，城防另外挨一份（見 [Combat.resolve]）。
     */
    val attackerInCity: Boolean = false,
    val defenderInCity: Boolean = false
) {
    /** 角色互換：反擊用的就是這個。 */
    fun swapped(): CombatContext = CombatContext(
        attacker = defender,
        defender = attacker,
        defenderTerrain = attackerTerrain,
        attackerTerrain = defenderTerrain,
        distance = distance,
        attackerTech = defenderTech,
        defenderTech = attackerTech,
        attackerAura = defenderAura,
        defenderAura = attackerAura,
        attackerAtSea = defenderAtSea,
        defenderAtSea = attackerAtSea,
        attackerMorale = defenderMorale,
        defenderMorale = attackerMorale,
        attackerInCity = defenderInCity,
        defenderInCity = attackerInCity
    )
}

/** 一次交戰的結果。UI 用它播動畫，AI 用它評估要不要打。 */
class CombatResult(
    val damageToDefender: Int,
    val damageToAttacker: Int,
    val defenderDestroyed: Boolean,
    val attackerDestroyed: Boolean,
    val attackerLevelled: Boolean,
    val defenderLevelled: Boolean,
    /** 分攤到守方／攻方所在城市的傷害，由 Orders 套用到該省的城防上。 */
    val damageToDefenderCity: Int = 0,
    val damageToAttackerCity: Int = 0
)

/**
 * 戰鬥判定。規格見 docs/original-behavior.md 第 6 節。
 *
 * 一擊的傷害：
 *
 *     攻擊值 = 等級固定攻擊 + ⌊攻擊% × 擲骰⌋       擲骰 ∈ [最小攻擊, 最大攻擊] 均勻整數
 *     傷害   = ⌊攻擊值 × 1000 / (防禦 × 16 + 1000)⌋
 *     傷害   = ⌊傷害 × 兵種相剋 × 指揮官⌋
 *     傷害   = ⌊傷害 × 地形（城防還在時改成 0.5，浮渡中改成 1.5）⌋
 *     傷害   = ⌊傷害 × (100 − 減傷) / 100⌋，最少 1
 *
 * 防禦的作用是「每 1 點防禦 = 分母多 1.6%」：防禦 62.5 讓傷害減半，永遠不會歸零。
 * 這跟參考遊戲一致；兵種相剋、補給、築壕、指揮官是本專案自己的規則，
 * 以乘數的形式插在參考公式的對應位置上。
 *
 * 亂數只用在擲骰這一步，而且走 [Rng] 這條決定性的路：
 * 玩家需要能預期結果，AI 需要能推演結果，重播需要能重現結果。
 */
object Combat {

    /**
     * 士氣對攻擊% 的乘數（百分比）。+1 / −1 / −2 取參考遊戲的 125 / 75 / 50。
     * 混亂（−3）在參考遊戲裡是「不能攻擊也不能反擊」，這裡直接給 0。
     */
    fun moralePercent(level: Int): Int = when {
        level >= 1 -> 125
        level == 0 -> 100
        level == -1 -> 75
        level == -2 -> 50
        else -> 0
    }

    /** HP 低於這個百分比，攻擊% 減半（參考遊戲的步兵、裝甲、火炮、海軍都是這條）。 */
    const val WEAKENED_HP_PERCENT = 50
    const val WEAKENED_ATTACK_PERCENT = 50

    /** 站在城裡、城防還在的部隊，吃到的傷害比例。 */
    const val UNIT_DAMAGE_IN_CITY_PERCENT = 50

    /** 城防在一次交火（守方有還手）中承受的比例；沒有還手時整份都算。 */
    const val CITY_SHARE_IN_EXCHANGE_PERCENT = 70

    /** 浮渡中的陸軍只剩象徵性的自衛火力。本專案的規則。 */
    private const val AT_SEA_ATTACK_PERCENT = 30

    /**
     * 浮渡中的陸軍多挨的傷害（百分比）。本專案的規則。
     *
     * 參考公式裡防禦的份量很輕（步兵防禦 1 → 只差 1.6%），光把防禦歸零不足以讓
     * 「沒有護航就渡海」變成壞主意；參考遊戲是靠專打登船部隊的兵種特性做到這件事。
     */
    private const val AT_SEA_DAMAGE_TAKEN_PERCENT = 150

    /** 每級築壕減多少傷害（百分比）。本專案的規則。 */
    const val ENTRENCH_REDUCTION_PER_LEVEL = 5

    private const val DEFENCE_WEIGHT = 16
    private const val DEFENCE_BASE = 1000

    // ------------------------------------------------------------------
    // 公式的零件
    // ------------------------------------------------------------------

    /**
     * 攻擊%：士氣 × 編制 × 殘血門檻 × 科技與光環 × 補給 × 浮渡。
     *
     * [hp] 分開傳是為了反擊：守方先挨了一擊，反擊時的殘血門檻要看挨打之後的 HP，
     * 而 AI 推演時不能真的去扣它的血。
     */
    fun attackPercent(unit: ArmyUnit, hp: Int, morale: Int, tech: Int, aura: Int, atSea: Boolean): Float {
        var pct = moralePercent(morale).toFloat()
        if (pct <= 0f) return 0f
        pct = pct * ArmyUnit.attackPercent(unit.size) / 100f
        if (unit.maxHp > 0 && hp * 100 < unit.maxHp * WEAKENED_HP_PERCENT) pct = pct * WEAKENED_ATTACK_PERCENT / 100f
        pct *= 1f + (tech + aura) / 100f
        pct *= unit.supplyFactor
        if (atSea) pct = pct * AT_SEA_ATTACK_PERCENT / 100f
        return pct
    }

    /** 等級給的固定攻擊，加在擲骰之外，不受士氣與殘血放大。 */
    fun flatAttack(unit: ArmyUnit): Int = ArmyUnit.LEVEL_ATTACK * (unit.level - 1)

    /**
     * 有效防禦：兵種防禦 + 等級防禦，再乘科技與補給。浮渡中歸零。
     *
     * 參考遊戲的科技是加固定點數；本專案的科技是百分比，放在這裡的效果差不多：
     * 防禦 10 的戰車點滿科技變成 14。
     */
    fun effectiveDefence(unit: ArmyUnit, tech: Int, atSea: Boolean): Int {
        if (atSea) return 0
        val base = unit.kind.defence + ArmyUnit.LEVEL_DEFENCE * (unit.level - 1)
        // 補給只影響一半的防禦：斷補給的守軍會變脆，但不會直接被輾平。
        val supplied = 0.5f + 0.5f * unit.supplyFactor
        return (base * (1f + tech / 100f) * supplied).toInt()
    }

    /** 攻方是裝甲或火炮時，守方所在地形扣掉的比例；其他兵種不受地形影響。 */
    fun terrainFactor(attacker: ArmyUnit, defenderTerrain: Terrain, attackerAtSea: Boolean): Float {
        if (attackerAtSea) return 1f
        val penalty = when (attacker.kind.branch) {
            TechBranch.ARMOUR -> defenderTerrain.armourPenalty
            TechBranch.ARTILLERY -> defenderTerrain.artilleryPenalty
            else -> 0
        }
        return 1f - penalty / 100f
    }

    /** 守方的減傷（百分比）：築壕與防守型指揮官。本專案的規則。 */
    fun damageReduction(defender: ArmyUnit): Int {
        var r = defender.entrenchment * ENTRENCH_REDUCTION_PER_LEVEL
        val cmd = defender.commander
        if (cmd != null) {
            if (cmd.has(CommanderSkill.DEFENSIVE)) r += 13
            if (cmd.has(CommanderSkill.FORTRESS) && defender.entrenchment >= 2) r += 17
        }
        return r.coerceIn(0, 90)
    }

    /** 攻擊值 × 1000 / (防禦 × 16 + 1000)，整數除法。 */
    fun defended(attackValue: Int, defence: Int): Int =
        attackValue * DEFENCE_BASE / (defence.coerceAtLeast(0) * DEFENCE_WEIGHT + DEFENCE_BASE)

    // ------------------------------------------------------------------
    // 一擊
    // ------------------------------------------------------------------

    /** 一擊打在部隊與城防上各是多少。 */
    class Hit(val toUnit: Int, val toCity: Int)

    private val NO_HIT = Hit(0, 0)

    /**
     * 用給定的擲骰值 [roll] 算一擊。攻方的 HP 由 [attackerHp] 指定。
     *
     * [exchange] = 這一擊屬於一次會還手的交火：城防只承受七成。
     */
    fun hit(ctx: CombatContext, roll: Int, attackerHp: Int, exchange: Boolean): Hit {
        val a = ctx.attacker
        val d = ctx.defender
        val versus = a.kind.versus(d.kind.targetClass)
        if (versus <= 0) return NO_HIT
        val pct = attackPercent(a, attackerHp, ctx.attackerMorale, ctx.attackerTech, ctx.attackerAura, ctx.attackerAtSea)
        if (pct <= 0f) return NO_HIT
        val attackValue = flatAttack(a) + (pct / 100f * roll).toInt()
        val bonus = versus / 100f * commanderAttackFactor(a, d)

        var dmg = defended(attackValue, effectiveDefence(d, ctx.defenderTech, ctx.defenderAtSea))
        dmg = (dmg * bonus).toInt()
        val placement = when {
            ctx.defenderAtSea -> AT_SEA_DAMAGE_TAKEN_PERCENT / 100f
            ctx.defenderInCity -> UNIT_DAMAGE_IN_CITY_PERCENT / 100f
            else -> terrainFactor(a, ctx.defenderTerrain, ctx.attackerAtSea)
        }
        dmg = (dmg * placement).toInt()
        dmg = dmg * (100 - damageReduction(d)) / 100
        dmg = dmg.coerceAtLeast(1)

        var city = 0
        if (ctx.defenderInCity) {
            // 城防沒有防禦，也沒有地形：同一擲骰直接打在牆上，再看是不是交火。
            city = (defended(attackValue, 0) * cityFactor(a)).toInt()
            if (exchange) city = city * CITY_SHARE_IN_EXCHANGE_PERCENT / 100
            city = city.coerceAtLeast(1)
        }
        return Hit(dmg, city)
    }

    /**
     * 打城牆的效果百分比：取「對步兵」與「對裝甲」兩欄較高的那一個。
     *
     * 參考遊戲的城防是防禦 0 的目標，陸軍打它不打折；本專案有兵種相剋表，
     * 取兩個陸上欄位的較高者，讓步兵、戰車、火炮都能拆牆，潛艇與防空炮不行。
     */
    fun versusCity(kind: UnitKind): Int =
        maxOf(kind.versus(TargetClass.SOFT), kind.versus(TargetClass.ARMOURED))

    private fun cityFactor(attacker: ArmyUnit): Float = versusCity(attacker.kind) / 100f

    /** 擲一次攻擊骰。 */
    fun roll(kind: UnitKind, rng: Rng): Int = rng.range(kind.attackMin, kind.attackMax)

    /** 平均擲骰，給預測用。 */
    private fun meanRoll(kind: UnitKind): Int = (kind.attackMin + kind.attackMax) / 2

    // ------------------------------------------------------------------
    // 城市
    // ------------------------------------------------------------------

    /**
     * 直接轟擊一座沒有守軍的城市。
     *
     * 城防在參考遊戲裡是一支防禦 0 的「部隊」：不還手、沒有地形，所以傷害就是攻擊值本身。
     */
    fun cityStrike(attacker: ArmyUnit, techBonus: Int, auraBonus: Int, morale: Int, rng: Rng): Int =
        cityDamage(attacker, roll(attacker.kind, rng), techBonus, auraBonus, morale)

    fun previewCityStrike(attacker: ArmyUnit, techBonus: Int, auraBonus: Int, morale: Int): Int =
        cityDamage(attacker, meanRoll(attacker.kind), techBonus, auraBonus, morale)

    private fun cityDamage(attacker: ArmyUnit, roll: Int, techBonus: Int, auraBonus: Int, morale: Int): Int {
        if (versusCity(attacker.kind) <= 0) return 0
        val pct = attackPercent(attacker, attacker.hp, morale, techBonus, auraBonus, false)
        if (pct <= 0f) return 0
        val attackValue = flatAttack(attacker) + (pct / 100f * roll).toInt()
        return (defended(attackValue, 0) * cityFactor(attacker)).toInt().coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // 空中打擊
    // ------------------------------------------------------------------

    /**
     * 防空把打擊削掉多少。
     *
     * 「火力 / (火力 + 防空)」的比值：一門滿血防空炮（約 48）讓打擊只剩
     * 約三分之二，兩門剩一半 —— 永遠不會歸零，但防空密集的地方就是不划算。
     */
    fun flakFactor(flak: Int): Float = if (flak <= 0) 1f else 100f / (100f + flak)

    /**
     * 空中打擊打在部隊上：同一條防禦公式，沒有地形懲罰（飛機不是裝甲也不是火炮），
     * 城防還在時一樣只吃一半、城防整份承受（守方還不了手）。
     *
     * [powerPercent] 是打擊的攻擊%（科技、空戰專家、防空削減都已乘進去）。
     */
    fun airHit(
        roll: Int,
        powerPercent: Float,
        versus: Int,
        defender: ArmyUnit,
        defenderTech: Int,
        defenderAtSea: Boolean,
        defenderInCity: Boolean
    ): Hit {
        if (versus <= 0 || powerPercent <= 0f) return NO_HIT
        val attackValue = (powerPercent / 100f * roll).toInt()
        var dmg = defended(attackValue, effectiveDefence(defender, defenderTech, defenderAtSea))
        dmg = (dmg * versus / 100f).toInt()
        if (defenderAtSea) dmg = dmg * AT_SEA_DAMAGE_TAKEN_PERCENT / 100
        else if (defenderInCity) dmg = dmg * UNIT_DAMAGE_IN_CITY_PERCENT / 100
        dmg = (dmg * (100 - damageReduction(defender)) / 100).coerceAtLeast(1)
        // 城牆沒有防禦，跟打部隊用同一欄。
        val city = if (defenderInCity) (attackValue * versus / 100).coerceAtLeast(1) else 0
        return Hit(dmg, city)
    }

    /** 轟炸一座空城。 */
    fun airStrikeCity(roll: Int, powerPercent: Float, versus: Int): Int {
        if (versus <= 0 || powerPercent <= 0f) return 0
        return ((powerPercent / 100f * roll).toInt() * versus / 100).coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // 交戰
    // ------------------------------------------------------------------

    fun canReach(attacker: ArmyUnit, distance: Int): Boolean =
        distance >= attacker.kind.minRange.coerceAtLeast(1) && distance <= attacker.kind.maxRange

    /**
     * 守方會不會還手。全部要成立：
     * 還活著、貼身戰（遠程轟擊無法反擊）、守方的射程涵蓋這個距離、沒有混亂。
     */
    fun canRetaliate(ctx: CombatContext): Boolean {
        val d = ctx.defender
        if (!d.isAlive) return false
        if (ctx.distance > 1) return false
        if (d.kind.minRange > 1) return false
        if (!d.kind.canAttack) return false
        if (ctx.defenderMorale <= ArmyUnit.MIN_MORALE) return false
        // 浮渡中的陸軍還不了手。
        if (ctx.defenderAtSea) return false
        // 魚雷：潛艇出手時對方來不及反應。潛艇本體很脆，全靠這一條活著。
        if (ctx.attacker.kind.isStealth) return false
        return d.kind.versus(ctx.attacker.kind.targetClass) > 0
    }

    /** 不改狀態的預測：用平均擲骰算這一擊打掉守方多少 HP。AI 與 UI 用。 */
    fun previewDamage(ctx: CombatContext): Int =
        hit(ctx, meanRoll(ctx.attacker.kind), ctx.attacker.hp, canRetaliate(ctx)).toUnit

    /**
     * 不改狀態的預測：守方挨完 [dealt] 之後的反擊會打掉攻方多少。
     * 守方被打死、或根本不能還手時是 0。
     */
    fun previewRetaliation(ctx: CombatContext, dealt: Int): Int {
        if (!canRetaliate(ctx)) return 0
        val left = ctx.defender.hp - dealt
        if (left <= 0) return 0
        return hit(ctx.swapped(), meanRoll(ctx.defender.kind), left, true).toUnit
    }

    /**
     * 一次交戰：攻方打一擊；守方活著而且能還手，就以挨打後的 HP 全力反擊一擊。
     * 反擊不打折 —— 參考遊戲的反擊跟主動攻擊走同一條公式。
     */
    fun resolve(ctx: CombatContext, rng: Rng): CombatResult {
        val exchange = canRetaliate(ctx)
        val first = hit(ctx, roll(ctx.attacker.kind, rng), ctx.attacker.hp, exchange)

        val defenderBefore = ctx.defender.hp
        ctx.defender.damage(first.toUnit)
        val defenderLost = defenderBefore - ctx.defender.hp
        val defenderDead = !ctx.defender.isAlive

        var attackerLost = 0
        var toAttackerCity = 0
        if (!defenderDead && exchange) {
            val counter = hit(ctx.swapped(), roll(ctx.defender.kind, rng), ctx.defender.hp, true)
            toAttackerCity = counter.toCity
            val attackerBefore = ctx.attacker.hp
            ctx.attacker.damage(counter.toUnit)
            attackerLost = attackerBefore - ctx.attacker.hp
        }

        val attackerDead = !ctx.attacker.isAlive
        val attackerLevelled = ctx.attacker.gainExp(expFor(defenderLost, ctx.defender.maxHp, defenderDead))
        val defenderLevelled =
            if (!defenderDead) ctx.defender.gainExp(expFor(attackerLost, ctx.attacker.maxHp, attackerDead)) else false

        return CombatResult(
            damageToDefender = defenderLost,
            damageToAttacker = attackerLost,
            defenderDestroyed = defenderDead,
            attackerDestroyed = attackerDead,
            damageToDefenderCity = first.toCity,
            damageToAttackerCity = toAttackerCity,
            attackerLevelled = attackerLevelled,
            defenderLevelled = defenderLevelled
        )
    }

    /** 經驗跟著打掉對方幾成 HP 走，擊毀另有一筆獎勵。本專案的規則。 */
    private fun expFor(damage: Int, victimMaxHp: Int, killed: Boolean): Int {
        val percent = if (victimMaxHp <= 0) 0 else damage * 100 / victimMaxHp
        return percent / 3 + if (killed) 25 else 0
    }

    /** 攻方指揮官的傷害倍率。本專案的規則，位置相當於參考遊戲的將領技能倍率。 */
    private fun commanderAttackFactor(unit: ArmyUnit, target: ArmyUnit): Float {
        val cmd = unit.commander ?: return 1f
        var f = 1f
        if (cmd.has(CommanderSkill.OFFENSIVE)) f *= 1.12f
        if (cmd.has(CommanderSkill.ARMOUR_EXPERT) && unit.kind.branch == TechBranch.ARMOUR) f *= 1.20f
        if (cmd.has(CommanderSkill.NAVAL_EXPERT) && unit.kind.branch == TechBranch.NAVY) f *= 1.20f
        if (cmd.has(CommanderSkill.ARTILLERY_EXPERT) && unit.kind.branch == TechBranch.ARTILLERY) f *= 1.20f
        // 攻城專家看目標的築壕值 —— 守得越久，攻城專家的價值越高。
        if (cmd.has(CommanderSkill.SIEGE) && target.entrenchment > 0) f *= 1.25f
        return f
    }
}
