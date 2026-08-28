// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

import io.github.acidefluorhydrique.mapconquer.core.Rng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatTest {

    private var nextId = 1

    private fun unit(kind: UnitKind, nation: Int = 0, hp: Int = 100, level: Int = 1): ArmyUnit {
        val u = ArmyUnit(nextId++, kind, nation, 0)
        u.hp = hp
        u.level = level
        return u
    }

    private fun context(
        attacker: ArmyUnit,
        defender: ArmyUnit,
        distance: Int = 1,
        defenderTerrain: Int = 0
    ) = CombatContext(
        attacker, defender,
        defenderTerrainBonus = defenderTerrain,
        attackerTerrainBonus = 0,
        distance = distance,
        attackerTech = 0, defenderTech = 0,
        attackerAura = 0, defenderAura = 0
    )

    @Test
    fun `damage stays inside sane bounds`() {
        val rng = Rng(42)
        for (attackerKind in UnitKind.ALL) {
            for (defenderKind in UnitKind.ALL) {
                val ctx = context(unit(attackerKind), unit(defenderKind, nation = 1))
                val preview = Combat.previewDamage(ctx)
                assertTrue("$attackerKind -> $defenderKind = $preview", preview in 0..ArmyUnit.MAX_HP)
                val result = Combat.resolve(ctx, rng)
                assertTrue(result.damageToDefender in 0..ArmyUnit.MAX_HP)
                assertTrue(result.damageToAttacker in 0..ArmyUnit.MAX_HP)
            }
        }
    }

    @Test
    fun `the counter matters more than the raw attack value`() {
        // 反坦克炮打裝甲應該遠勝於打步兵，這是整個兵種相剋的基礎。
        val versusArmour = Combat.previewDamage(
            context(unit(UnitKind.ANTI_TANK), unit(UnitKind.ARMOUR, nation = 1))
        )
        val versusInfantry = Combat.previewDamage(
            context(unit(UnitKind.ANTI_TANK), unit(UnitKind.INFANTRY, nation = 1))
        )
        assertTrue("AT $versusArmour vs $versusInfantry", versusArmour > versusInfantry * 2)

        // 戰鬥機打飛機應該遠勝於打地面。
        val airToAir = Combat.previewDamage(
            context(unit(UnitKind.FIGHTER), unit(UnitKind.BOMBER, nation = 1))
        )
        val airToGround = Combat.previewDamage(
            context(unit(UnitKind.FIGHTER), unit(UnitKind.INFANTRY, nation = 1))
        )
        assertTrue("fighter $airToAir vs $airToGround", airToAir > airToGround)
    }

    @Test
    fun `terrain and entrenchment protect the defender`() {
        val open = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1))
        )
        val inMountains = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1), defenderTerrain = 45)
        )
        assertTrue("$open should exceed $inMountains", open > inMountains)

        val dug = unit(UnitKind.INFANTRY, nation = 1).apply { entrenchment = 3 }
        val versusDug = Combat.previewDamage(context(unit(UnitKind.INFANTRY), dug))
        assertTrue("$open should exceed $versusDug", open > versusDug)
    }

    @Test
    fun `artillery is not shot back at when firing from range`() {
        val gun = unit(UnitKind.ARTILLERY)
        val target = unit(UnitKind.INFANTRY, nation = 1)
        assertFalse(Combat.canRetaliate(context(gun, target, distance = 3)))
        assertTrue(Combat.canReach(gun, 3))
        // 但砲兵自己不能近戰。
        assertFalse(Combat.canReach(gun, 1))

        val result = Combat.resolve(context(gun, target, distance = 3), Rng(7))
        assertEquals(0, result.damageToAttacker)
    }

    @Test
    fun `artillery caught in melee defends badly`() {
        val versusGun = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.ARTILLERY, nation = 1))
        )
        val versusInfantry = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1))
        )
        // 被貼身的砲兵要明顯比一般步兵更好打，否則「保護砲兵」就不是個真的決策。
        assertTrue("gun $versusGun vs infantry $versusInfantry", versusGun > versusInfantry * 3 / 2)
    }

    @Test
    fun `the counter triangle closes`() {
        fun hit(attacker: UnitKind, defender: UnitKind) =
            Combat.previewDamage(context(unit(attacker), unit(defender, nation = 1)))

        // 反坦克炮的正確目標是裝甲，不是步兵。
        assertTrue(
            "AT armour=${hit(UnitKind.ANTI_TANK, UnitKind.ARMOUR)} infantry=${hit(UnitKind.ANTI_TANK, UnitKind.INFANTRY)}",
            hit(UnitKind.ANTI_TANK, UnitKind.ARMOUR) > hit(UnitKind.ANTI_TANK, UnitKind.INFANTRY) * 2
        )
        // 裝甲輾步兵，步兵拿裝甲沒轍。
        assertTrue(
            "armour->inf=${hit(UnitKind.ARMOUR, UnitKind.INFANTRY)} inf->armour=${hit(UnitKind.INFANTRY, UnitKind.ARMOUR)}",
            hit(UnitKind.ARMOUR, UnitKind.INFANTRY) > hit(UnitKind.INFANTRY, UnitKind.ARMOUR) * 2
        )
        // 而步兵是反坦克炮的剋星 —— 這條邊讓反坦克不能單獨守在最前面。
        assertTrue(
            "inf->AT=${hit(UnitKind.INFANTRY, UnitKind.ANTI_TANK)} AT->inf=${hit(UnitKind.ANTI_TANK, UnitKind.INFANTRY)}",
            hit(UnitKind.INFANTRY, UnitKind.ANTI_TANK) > hit(UnitKind.ANTI_TANK, UnitKind.INFANTRY) * 3 / 2
        )
    }

    @Test
    fun `veterancy and damage both move the needle in the right direction`() {
        val fresh = Combat.previewDamage(
            context(unit(UnitKind.ARMOUR), unit(UnitKind.INFANTRY, nation = 1))
        )
        val veteran = Combat.previewDamage(
            context(unit(UnitKind.ARMOUR, level = 5), unit(UnitKind.INFANTRY, nation = 1))
        )
        assertTrue("veteran $veteran should beat fresh $fresh", veteran > fresh)

        val hurt = Combat.previewDamage(
            context(unit(UnitKind.ARMOUR, hp = 30), unit(UnitKind.INFANTRY, nation = 1))
        )
        assertTrue("wounded $hurt should be worse than fresh $fresh", hurt < fresh)
    }

    @Test
    fun `low supply degrades a unit without disabling it`() {
        val attacker = unit(UnitKind.ARMOUR)
        val full = Combat.previewDamage(context(attacker, unit(UnitKind.INFANTRY, nation = 1)))
        attacker.supply = 0
        val starving = Combat.previewDamage(context(attacker, unit(UnitKind.INFANTRY, nation = 1)))
        assertTrue(starving < full)
        assertTrue("斷補給不該完全打不動", starving > 0)
    }

    @Test
    fun `experience accumulates into levels`() {
        val u = unit(UnitKind.INFANTRY)
        assertEquals(1, u.level)
        assertTrue(u.gainExp(40))
        assertEquals(2, u.level)
        u.gainExp(1000)
        assertEquals(ArmyUnit.MAX_LEVEL, u.level)
        assertFalse("滿級之後不該再升", u.gainExp(1000))
    }

    @Test
    fun `unit roster is internally consistent`() {
        for (kind in UnitKind.ALL) {
            assertEquals("$kind 攻擊值必須有四項", 4, kind.attack.size)
            assertTrue("$kind 防禦必須為正", kind.defence > 0)
            assertTrue("$kind 移動必須為正", kind.movement > 0)
            assertTrue("$kind 成本必須為正", kind.cost > 0)
            assertTrue("$kind 射程下限不得大於上限", kind.minRange <= kind.maxRange)
            if (kind.canAttack) assertTrue("$kind 能攻擊就要有射程", kind.maxRange >= 1)
            if (kind.isBombard) assertTrue("$kind 砲兵射程下限應大於 1", kind.minRange > 1)
            if (kind.canCapture) assertEquals("$kind 只有陸軍能佔領", Domain.LAND, kind.domain)
            assertTrue("$kind 名稱鍵格式", kind.key.startsWith("unit_"))
        }
        assertEquals("兵種鍵不得重複", UnitKind.ALL.size, UnitKind.ALL.map { it.key }.toSet().size)
        for (kind in UnitKind.ALL) {
            assertEquals("byName 應該找得回自己", kind, UnitKind.byName(kind.name))
            assertEquals("byName 也接受字串鍵", kind, UnitKind.byName(kind.key))
        }
    }

    @Test
    fun `commanders reference real skills and unique ids`() {
        val ids = Commander.ALL.map { it.id }
        assertEquals("指揮官 id 不得重複", ids.size, ids.toSet().size)
        for (commander in Commander.ALL) {
            assertTrue("${commander.id} 至少要有一個技能", commander.skills.isNotEmpty())
            assertTrue("${commander.id} 階級 1..5", commander.rank in 1..5)
            assertEquals("${commander.id} 應該查得到", commander, Commander.byId(commander.id))
        }
        assertEquals("開局指揮官應該免費", 0, Commander.starter.medalCost)
    }
}
