// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

import io.github.acidefluorhydrique.mapconquer.core.Rng
import kotlin.math.roundToInt

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
    /** 守方所在地形 + 城市的防禦加成總和（百分比）。 */
    val defenderTerrainBonus: Int,
    /** 攻方所在地形的防禦加成，反擊時才用得到。 */
    val attackerTerrainBonus: Int,
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
     * 下了海的陸軍等於擠在運輸駁船上：防禦幾乎歸零、火力大打折扣、
     * 兵種技能全部失效。這讓「制海權」變成真的有意義 ——
     * 沒有護航就渡海，是把整支部隊送給對方的艦隊。
     */
    val attackerAtSea: Boolean = false,
    val defenderAtSea: Boolean = false,
    /** 士氣等級，由 Session 依當下態勢推導後傳進來（+1 高昂 … −3 混亂）。 */
    val attackerMorale: Int = 0,
    val defenderMorale: Int = 0,
    /**
     * 這一方是否站在城市格上。
     *
     * 站在城裡的部隊由城防替它挨一部分：部隊只吃 50%，城市另外吃 70%。
     * 兩者加起來大於 100 是刻意的 —— 城市不是把傷害轉走，是替駐軍多擋一層，
     * 代價是自己會被打垮。所以攻城的節奏是先磨掉城防，再解決守軍。
     */
    val attackerInCity: Boolean = false,
    val defenderInCity: Boolean = false
)

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
 * 戰鬥判定。
 *
 * 公式是「攻防比」而不是「攻減防」：
 *
 *     傷害 = 55 * A / (A + D)
 *
 * 選比值的理由是它天然有界。相減的公式在高攻低防時會爆炸（一擊抹平），
 * 在低攻高防時又會完全歸零（打不動，玩家只能乾等），
 * 兩端都會讓中後期的數值成長變成「開關」而不是「曲線」。
 * 比值公式讓 2:1 的優勢永遠是 2:1 的手感，不管雙方數字多大。
 *
 * 亂數只有 ±8%，而且走 [Rng] 這條決定性的路：
 * 玩家需要能預期結果，AI 需要能推演結果，重播需要能重現結果。
 */
object Combat {

    /**
     * 士氣對輸出的乘數。
     *
     * 高昂的加成小、低落的懲罰大 —— 挨打比佔便宜更有感，這樣「把對方圍起來」
     * 才會是划算的投資。混亂直接歸零：那一級的意思就是打不出去。
     */
    fun moraleFactor(level: Int): Float = when {
        level >= 1 -> 1.08f
        level == 0 -> 1f
        level == -1 -> 0.85f
        level == -2 -> 0.65f
        else -> 0f
    }

    /** 均勢對打時的單回合傷害基準。調它等於調整整場戰爭的節奏。 */
    private const val DAMAGE_SCALE = 55f

    /** 反擊只有正面攻擊的六成 —— 主動出手必須是划算的。 */
    private const val RETALIATION_SCALE = 0.6f

    /** 站在城裡的部隊實際吃到的傷害比例。 */
    const val UNIT_DAMAGE_IN_CITY_PERCENT = 50

    /** 同一次攻擊另外扣在城防上的比例。兩者相加大於 100 是刻意的。 */
    const val CITY_DAMAGE_PERCENT = 70

    /** 空城的等效防禦基準，再乘上該城的守備加成。 */
    private const val CITY_STRIKE_DEFENCE_BASE = 26f

    fun attackPower(ctx: CombatContext): Float {
        val u = ctx.attacker
        val base = u.kind.attackAgainst(ctx.defender.kind.targetClass).toFloat()
        if (base <= 0f) return 0f
        var p = base
        p *= 1f + 0.06f * (u.level - 1)
        p *= ArmyUnit.attackPercent(u.size) / 100f
        p *= 1f + ctx.attackerTech / 100f
        p *= 1f + ctx.attackerAura / 100f
        p *= commanderAttackFactor(u, ctx.defender)
        p *= u.supplyFactor
        p *= moraleFactor(ctx.attackerMorale)
        // 浮渡中的陸軍只剩象徵性的自衛火力。
        if (ctx.attackerAtSea) p *= 0.3f
        // 殘血部隊的輸出等比下降，但不會完全消失。
        p *= 0.35f + 0.65f * (u.hp / ArmyUnit.MAX_HP.toFloat())
        return p
    }

    /**
     * 直接轟擊一座沒有守軍的城市。
     *
     * 城市不還手、沒有士氣、也沒有兵種相剋 —— 它就是一個要被磨掉的數字，
     * 所以用同一支比值公式，把守方換成城防加成。攻擊力取「對裝甲」那一欄：
     * 城牆與碉堡是硬目標，反步兵的火力對它幫助有限。
     */
    fun cityStrike(
        attacker: ArmyUnit,
        cityDefence: Int,
        techBonus: Int,
        auraBonus: Int,
        morale: Int,
        rng: Rng
    ): Int {
        val base = attacker.kind.attackAgainst(TargetClass.ARMOURED).toFloat()
        if (base <= 0f) return 0
        var a = base
        a *= 1f + 0.06f * (attacker.level - 1)
        a *= ArmyUnit.attackPercent(attacker.size) / 100f
        a *= 1f + techBonus / 100f
        a *= 1f + auraBonus / 100f
        a *= attacker.supplyFactor
        a *= moraleFactor(morale)
        a *= 0.35f + 0.65f * (attacker.hp / ArmyUnit.MAX_HP.toFloat())
        if (a <= 0f) return 0
        val d = CITY_STRIKE_DEFENCE_BASE * (1f + cityDefence / 100f)
        return jitter(DAMAGE_SCALE * a / (a + d), rng).coerceAtLeast(1)
    }

    // ------------------------------------------------------------------
    // 空中打擊
    // ------------------------------------------------------------------

    /**
     * 防空把打擊削掉多少。
     *
     * 用「火力 / (火力 + 防空)」的同一種比值：一門滿血防空炮（52）讓打擊只剩
     * 約三分之二，兩門剩一半 —— 永遠不會歸零，但防空密集的地方就是不划算。
     */
    fun flakFactor(flak: Int): Float = if (flak <= 0) 1f else 100f / (100f + flak)

    /**
     * 挨空襲時的防禦。
     *
     * 跟 [defencePower] 同一組修正，只是沒有攻方可比：等級、科技、
     * 地形（由呼叫端先砍半）、築壕、補給與殘血。飛機打完就走，不會被還手。
     */
    fun airDefence(defender: ArmyUnit, techBonus: Int, coverBonus: Int, atSea: Boolean): Float {
        var p = defender.kind.defence.toFloat()
        p *= 1f + 0.05f * (defender.level - 1)
        p *= 1f + techBonus / 100f
        p *= 1f + (coverBonus + defender.entrenchBonus) / 100f
        p *= 0.5f + 0.5f * defender.supplyFactor
        p *= 0.45f + 0.55f * (defender.hp / ArmyUnit.MAX_HP.toFloat())
        if (atSea) p *= 0.15f
        return p.coerceAtLeast(1f)
    }

    fun previewAirStrike(power: Float, defence: Float): Int {
        if (power <= 0f) return 0
        return (DAMAGE_SCALE * power / (power + defence)).roundToInt().coerceIn(1, ArmyUnit.MAX_HP)
    }

    fun airStrike(power: Float, defence: Float, rng: Rng): Int {
        if (power <= 0f) return 0
        return jitter(DAMAGE_SCALE * power / (power + defence), rng).coerceIn(1, ArmyUnit.MAX_HP)
    }

    /** 轟炸一座空城：跟 [cityStrike] 同一個守方基準。 */
    fun airStrikeCity(power: Float, cityDefence: Int, rng: Rng): Int {
        if (power <= 0f) return 0
        val d = CITY_STRIKE_DEFENCE_BASE * (1f + cityDefence / 100f)
        return jitter(DAMAGE_SCALE * power / (power + d), rng).coerceAtLeast(1)
    }

    fun defencePower(ctx: CombatContext): Float {
        val u = ctx.defender
        var p = u.kind.defence.toFloat()
        p *= 1f + 0.05f * (u.level - 1)
        p *= 1f + ctx.defenderTech / 100f
        p *= 1f + ctx.defenderAura / 100f
        p *= commanderDefenceFactor(u)
        p *= 1f + (ctx.defenderTerrainBonus + u.entrenchBonus) / 100f
        // 補給只影響一半的防禦：斷補給的守軍會變脆，但不會直接被輾平。
        p *= 0.5f + 0.5f * u.supplyFactor
        p *= 0.45f + 0.55f * (u.hp / ArmyUnit.MAX_HP.toFloat())
        // 砲兵被貼身時幾乎沒有自衛能力，這是它必須被保護的原因。
        if (u.kind.isBombard && ctx.distance <= 1) p *= 0.55f
        // 浮渡中的陸軍防禦幾乎歸零 —— 這正是它不該單獨出海的原因。
        if (ctx.defenderAtSea) p *= 0.15f
        return p.coerceAtLeast(1f)
    }

    /** 只算傷害不改狀態，供 AI 與 UI 的預測面板使用。 */
    fun previewDamage(ctx: CombatContext): Int {
        val a = attackPower(ctx)
        if (a <= 0f) return 0
        val d = defencePower(ctx)
        return (DAMAGE_SCALE * a / (a + d)).roundToInt().coerceIn(1, ArmyUnit.MAX_HP)
    }

    fun canReach(attacker: ArmyUnit, distance: Int): Boolean =
        distance >= attacker.kind.minRange.coerceAtLeast(1) && distance <= attacker.kind.maxRange

    /**
     * 守方會不會還手。三個條件全部要成立：
     * 還活著、貼身戰（遠程轟擊無法反擊）、而且守方的射程涵蓋這個距離。
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
        return d.kind.attackAgainst(ctx.attacker.kind.targetClass) > 0
    }

    /** 站在城裡的部隊只吃這一部分的傷害。 */
    private fun shielded(damage: Int, inCity: Boolean): Int =
        if (inCity) (damage * UNIT_DAMAGE_IN_CITY_PERCENT / 100).coerceAtLeast(1) else damage

    /** 城市替駐軍多擋的那一層，扣在城防上。 */
    private fun cityShare(damage: Int, inCity: Boolean): Int =
        if (inCity) damage * CITY_DAMAGE_PERCENT / 100 else 0

    fun resolve(ctx: CombatContext, rng: Rng): CombatResult {
        val a = attackPower(ctx)
        val d = defencePower(ctx)
        val rawDamage = if (a <= 0f) 0f else DAMAGE_SCALE * a / (a + d)
        val landed = jitter(rawDamage, rng).coerceIn(if (a > 0f) 1 else 0, ArmyUnit.MAX_HP)
        val toDefender = shielded(landed, ctx.defenderInCity)
        val toDefenderCity = cityShare(landed, ctx.defenderInCity)

        val defenderBefore = ctx.defender.hp
        ctx.defender.damage(toDefender)
        // 回報的是實際掉了幾成而不是原始傷害 —— 大編制吃同一發會掉得比較少，
        // 戰報與經驗值都該照實際的算。
        val defenderLost = defenderBefore - ctx.defender.hp
        val defenderDead = !ctx.defender.isAlive

        var toAttacker = 0
        var toAttackerCity = 0
        if (!defenderDead && canRetaliate(ctx)) {
            // 反擊 = 角色互換之後的同一支公式，再乘上折扣。
            val counter = CombatContext(
                attacker = ctx.defender,
                defender = ctx.attacker,
                attackerAtSea = ctx.defenderAtSea,
                defenderAtSea = ctx.attackerAtSea,
                attackerMorale = ctx.defenderMorale,
                defenderMorale = ctx.attackerMorale,
                defenderTerrainBonus = ctx.attackerTerrainBonus,
                attackerTerrainBonus = ctx.defenderTerrainBonus,
                distance = ctx.distance,
                attackerTech = ctx.defenderTech,
                defenderTech = ctx.attackerTech,
                attackerAura = ctx.defenderAura,
                defenderAura = ctx.attackerAura,
                attackerInCity = ctx.defenderInCity,
                defenderInCity = ctx.attackerInCity
            )
            val ca = attackPower(counter)
            val cd = defencePower(counter)
            if (ca > 0f) {
                val raw = DAMAGE_SCALE * RETALIATION_SCALE * ca / (ca + cd)
                val counterLanded = jitter(raw, rng).coerceIn(1, ArmyUnit.MAX_HP)
                toAttacker = shielded(counterLanded, ctx.attackerInCity)
                toAttackerCity = cityShare(counterLanded, ctx.attackerInCity)
                val attackerBefore = ctx.attacker.hp
                ctx.attacker.damage(toAttacker)
                toAttacker = attackerBefore - ctx.attacker.hp
            }
        }

        val attackerDead = !ctx.attacker.isAlive
        val attackerLevelled = ctx.attacker.gainExp(expFor(defenderLost, defenderDead))
        val defenderLevelled =
            if (!defenderDead) ctx.defender.gainExp(expFor(toAttacker, attackerDead)) else false

        return CombatResult(
            damageToDefender = defenderLost,
            damageToAttacker = toAttacker,
            defenderDestroyed = defenderDead,
            attackerDestroyed = attackerDead,
            damageToDefenderCity = toDefenderCity,
            damageToAttackerCity = toAttackerCity,
            attackerLevelled = attackerLevelled,
            defenderLevelled = defenderLevelled
        )
    }

    /** 經驗跟著實際打出的傷害走，擊毀另有一筆獎勵。 */
    private fun expFor(damage: Int, killed: Boolean): Int =
        (damage / 3) + if (killed) 25 else 0

    private fun jitter(value: Float, rng: Rng): Int {
        if (value <= 0f) return 0
        val factor = 0.92f + rng.nextFloat() * 0.16f
        return (value * factor).roundToInt()
    }

    private fun commanderAttackFactor(unit: ArmyUnit, target: ArmyUnit): Float {
        val cmd = unit.commander ?: return 1f
        var f = 1f
        if (cmd.has(CommanderSkill.OFFENSIVE)) f *= 1.12f
        if (cmd.has(CommanderSkill.ARMOUR_EXPERT) && unit.kind.branch == TechBranch.ARMOUR) f *= 1.20f
        if (cmd.has(CommanderSkill.NAVAL_EXPERT) && unit.kind.branch == TechBranch.NAVY) f *= 1.20f
        if (cmd.has(CommanderSkill.ARTILLERY_EXPERT) && unit.kind.branch == TechBranch.ARTILLERY) f *= 1.20f
        // 攻城：對方縮在城裡才有加成，由呼叫端把城市加成寫進 entrenchment 以外的地方判斷不了，
        // 因此改看目標的築壕值 —— 守得越久，攻城專家的價值越高。
        if (cmd.has(CommanderSkill.SIEGE) && target.entrenchment > 0) f *= 1.25f
        return f
    }

    private fun commanderDefenceFactor(unit: ArmyUnit): Float {
        val cmd = unit.commander ?: return 1f
        var f = 1f
        if (cmd.has(CommanderSkill.DEFENSIVE)) f *= 1.15f
        if (cmd.has(CommanderSkill.FORTRESS) && unit.entrenchment >= 2) f *= 1.20f
        return f
    }
}
