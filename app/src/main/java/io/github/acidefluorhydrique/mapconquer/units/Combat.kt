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
    val defenderAtSea: Boolean = false
)

/** 一次交戰的結果。UI 用它播動畫，AI 用它評估要不要打。 */
class CombatResult(
    val damageToDefender: Int,
    val damageToAttacker: Int,
    val defenderDestroyed: Boolean,
    val attackerDestroyed: Boolean,
    val attackerLevelled: Boolean,
    val defenderLevelled: Boolean
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

    /** 均勢對打時的單回合傷害基準。調它等於調整整場戰爭的節奏。 */
    private const val DAMAGE_SCALE = 55f

    /** 反擊只有正面攻擊的六成 —— 主動出手必須是划算的。 */
    private const val RETALIATION_SCALE = 0.6f

    fun attackPower(ctx: CombatContext): Float {
        val u = ctx.attacker
        val base = u.kind.attackAgainst(ctx.defender.kind.targetClass).toFloat()
        if (base <= 0f) return 0f
        var p = base
        p *= 1f + 0.06f * (u.level - 1)
        p *= 1f + ctx.attackerTech / 100f
        p *= 1f + ctx.attackerAura / 100f
        p *= commanderAttackFactor(u, ctx.defender)
        p *= u.supplyFactor
        p *= u.moraleFactor
        // 浮渡中的陸軍只剩象徵性的自衛火力。
        if (ctx.attackerAtSea) p *= 0.3f
        // 殘血部隊的輸出等比下降，但不會完全消失。
        p *= 0.35f + 0.65f * (u.hp / ArmyUnit.MAX_HP.toFloat())
        return p
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
        if (d.isDisrupted) return false
        // 浮渡中的陸軍還不了手。
        if (ctx.defenderAtSea) return false
        // 魚雷：潛艇出手時對方來不及反應。潛艇本體很脆，全靠這一條活著。
        if (ctx.attacker.kind.isStealth) return false
        return d.kind.attackAgainst(ctx.attacker.kind.targetClass) > 0
    }

    fun resolve(ctx: CombatContext, rng: Rng): CombatResult {
        val a = attackPower(ctx)
        val d = defencePower(ctx)
        val rawDamage = if (a <= 0f) 0f else DAMAGE_SCALE * a / (a + d)
        val toDefender = jitter(rawDamage, rng).coerceIn(if (a > 0f) 1 else 0, ArmyUnit.MAX_HP)

        ctx.defender.damage(toDefender)
        val defenderDead = !ctx.defender.isAlive

        var toAttacker = 0
        if (!defenderDead && canRetaliate(ctx)) {
            // 反擊 = 角色互換之後的同一支公式，再乘上折扣。
            val counter = CombatContext(
                attacker = ctx.defender,
                defender = ctx.attacker,
                attackerAtSea = ctx.defenderAtSea,
                defenderAtSea = ctx.attackerAtSea,
                defenderTerrainBonus = ctx.attackerTerrainBonus,
                attackerTerrainBonus = ctx.defenderTerrainBonus,
                distance = ctx.distance,
                attackerTech = ctx.defenderTech,
                defenderTech = ctx.attackerTech,
                attackerAura = ctx.defenderAura,
                defenderAura = ctx.attackerAura
            )
            val ca = attackPower(counter)
            val cd = defencePower(counter)
            if (ca > 0f) {
                val raw = DAMAGE_SCALE * RETALIATION_SCALE * ca / (ca + cd)
                toAttacker = jitter(raw, rng).coerceIn(1, ArmyUnit.MAX_HP)
                ctx.attacker.damage(toAttacker)
            }
        }

        val attackerDead = !ctx.attacker.isAlive
        val attackerLevelled = ctx.attacker.gainExp(expFor(toDefender, defenderDead))
        val defenderLevelled =
            if (!defenderDead) ctx.defender.gainExp(expFor(toAttacker, attackerDead)) else false

        return CombatResult(
            damageToDefender = toDefender,
            damageToAttacker = toAttacker,
            defenderDestroyed = defenderDead,
            attackerDestroyed = attackerDead,
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
        if (cmd.has(CommanderSkill.AIR_EXPERT) && unit.kind.branch == TechBranch.AIR) f *= 1.20f
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
