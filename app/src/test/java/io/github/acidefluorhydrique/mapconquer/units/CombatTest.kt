// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.units

import io.github.acidefluorhydrique.mapconquer.core.Rng
import io.github.acidefluorhydrique.mapconquer.world.Terrain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombatTest {

    private var nextId = 1

    /** [hp] 省略時是滿血。等級會按比例縮放 HP，所以先設等級再設 HP。 */
    private fun unit(kind: UnitKind, nation: Int = 0, hp: Int = -1, level: Int = 1): ArmyUnit {
        val u = ArmyUnit(nextId++, kind, nation, 0)
        u.level = level
        if (hp >= 0) u.hp = hp
        return u
    }

    private fun context(
        attacker: ArmyUnit,
        defender: ArmyUnit,
        distance: Int = 1,
        defenderTerrain: Terrain = Terrain.PLAIN,
        defenderInCity: Boolean = false,
        attackerAtSea: Boolean = false,
        defenderAtSea: Boolean = false,
        attackerMorale: Int = 0,
        defenderMorale: Int = 0
    ) = CombatContext(
        attacker, defender,
        defenderTerrain = defenderTerrain,
        distance = distance,
        attackerTech = 0, defenderTech = 0,
        attackerAura = 0, defenderAura = 0,
        attackerAtSea = attackerAtSea,
        defenderAtSea = defenderAtSea,
        attackerMorale = attackerMorale,
        defenderMorale = defenderMorale,
        defenderInCity = defenderInCity
    )

    @Test
    fun `damage stays inside sane bounds`() {
        val rng = Rng(42)
        for (attackerKind in UnitKind.ALL) {
            for (defenderKind in UnitKind.ALL) {
                val attacker = unit(attackerKind)
                val defender = unit(defenderKind, nation = 1)
                val ctx = context(attacker, defender)
                val preview = Combat.previewDamage(ctx)
                assertTrue("$attackerKind -> $defenderKind = $preview", preview in 0..defender.maxHp)
                val result = Combat.resolve(ctx, rng)
                assertTrue(result.damageToDefender in 0..defender.maxHp)
                assertTrue(result.damageToAttacker in 0..attacker.maxHp)
                assertTrue(defender.hp in 0..defender.maxHp && attacker.hp in 0..attacker.maxHp)
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
    }

    @Test
    fun `flak blunts an air strike but never stops it`() {
        assertEquals(1f, Combat.flakFactor(0), 0.0001f)
        val one = Combat.flakFactor(UnitKind.ANTI_AIR.attackAgainst(TargetClass.AIRCRAFT))
        val two = Combat.flakFactor(UnitKind.ANTI_AIR.attackAgainst(TargetClass.AIRCRAFT) * 2)
        assertTrue("一門防空就該有感 $one", one < 0.75f)
        assertTrue("越多越痛 $two", two < one)
        assertTrue("但不會歸零 $two", two > 0f)
    }

    @Test
    fun `terrain hampers tanks and guns attacking into it, not infantry`() {
        fun hit(attacker: UnitKind, terrain: Terrain, distance: Int = 1) = Combat.previewDamage(
            context(unit(attacker), unit(UnitKind.INFANTRY, nation = 1), distance = distance, defenderTerrain = terrain)
        )
        assertTrue(hit(UnitKind.ARMOUR, Terrain.MOUNTAIN) < hit(UnitKind.ARMOUR, Terrain.PLAIN))
        assertTrue(hit(UnitKind.ARTILLERY, Terrain.JUNGLE, 2) < hit(UnitKind.ARTILLERY, Terrain.PLAIN, 2))
        assertEquals("步兵不受地形影響", hit(UnitKind.INFANTRY, Terrain.PLAIN), hit(UnitKind.INFANTRY, Terrain.MOUNTAIN))
        assertEquals("軍艦不受地形影響", hit(UnitKind.DESTROYER, Terrain.PLAIN), hit(UnitKind.DESTROYER, Terrain.FOREST))
    }

    @Test
    fun `entrenchment protects the defender`() {
        val open = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1))
        )
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
    fun `artillery caught in melee cannot fire back`() {
        // 被貼身的砲兵還不了手，所以「保護砲兵」是個真的決策。
        val ctx = context(unit(UnitKind.INFANTRY), unit(UnitKind.ARTILLERY, nation = 1))
        assertFalse(Combat.canRetaliate(ctx))
        assertEquals(0, Combat.resolve(ctx, Rng(3)).damageToAttacker)
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
            context(unit(UnitKind.ARMOUR, hp = 60), unit(UnitKind.INFANTRY, nation = 1))
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
    fun `a submarine is never shot back at`() {
        val sub = unit(UnitKind.SUBMARINE)
        val destroyer = unit(UnitKind.DESTROYER, nation = 1)
        assertFalse("魚雷：潛艇出手時對方來不及反應", Combat.canRetaliate(context(sub, destroyer)))
        // 反過來就會被還手 —— 潛艇很脆，這是它必須先手的原因。
        assertTrue(Combat.canRetaliate(context(destroyer, sub)))

        val result = Combat.resolve(context(sub, destroyer), Rng(11))
        assertEquals(0, result.damageToAttacker)
    }

    @Test
    fun `a disrupted unit cannot shoot back`() {
        val attacker = unit(UnitKind.INFANTRY)
        val defender = unit(UnitKind.INFANTRY, nation = 1)
        assertTrue(Combat.canRetaliate(context(attacker, defender)))
        assertFalse(
            "陷入混亂就打不出去了",
            Combat.canRetaliate(context(attacker, defender, defenderMorale = ArmyUnit.MIN_MORALE))
        )
    }

    @Test
    fun `the morale ladder scales output the way it reads`() {
        fun hit(morale: Int) = Combat.previewDamage(
            context(unit(UnitKind.ARMOUR), unit(UnitKind.INFANTRY, nation = 1), attackerMorale = morale)
        )

        val elevated = hit(1)
        val steady = hit(0)
        val shaken = hit(-1)
        val broken = hit(-2)
        assertTrue("士氣高昂 $elevated > 正常 $steady", elevated > steady)
        assertTrue("正常 $steady > 士氣下降 $shaken", steady > shaken)
        assertTrue("士氣下降 $shaken > 士氣嚴重下降 $broken", shaken > broken)
        assertEquals("混亂完全打不出傷害", 0, hit(ArmyUnit.MIN_MORALE))
    }

    @Test
    fun `land units at sea are helpless`() {
        val ashore = Combat.previewDamage(
            context(unit(UnitKind.DESTROYER), unit(UnitKind.INFANTRY, nation = 1))
        )
        val afloat = Combat.previewDamage(
            context(unit(UnitKind.DESTROYER), unit(UnitKind.INFANTRY, nation = 1), defenderAtSea = true)
        )
        // 浮渡中沒有防禦、另外多挨五成；真正致命的是下面那條：完全無法還手。
        assertTrue("浮渡的陸軍應該明顯好打 $afloat vs $ashore", afloat > ashore * 7 / 5)
        assertFalse(
            "浮渡的陸軍還不了手",
            Combat.canRetaliate(
                context(unit(UnitKind.DESTROYER), unit(UnitKind.INFANTRY, nation = 1), defenderAtSea = true)
            )
        )

        val firingAshore = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1))
        )
        val firingAfloat = Combat.previewDamage(
            context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1), attackerAtSea = true)
        )
        assertTrue("浮渡中的火力大打折扣 $firingAfloat vs $firingAshore", firingAfloat < firingAshore / 2)
    }

    @Test
    fun `only tanks get the follow-up attack`() {
        assertTrue(UnitKind.ARMOUR.isAssault)
        assertTrue(UnitKind.RECON.isAssault)
        assertFalse(UnitKind.INFANTRY.isAssault)
        assertFalse(UnitKind.ARTILLERY.isAssault)
        for (kind in UnitKind.ALL) {
            if (kind.isAssault) {
                assertEquals("$kind: 突擊是陸軍裝甲的專利", Domain.LAND, kind.domain)
            }
        }
    }

    @Test
    fun `unit roster is internally consistent`() {
        for (kind in UnitKind.ALL) {
            assertEquals("$kind 相剋表必須有四項", 4, kind.versus.size)
            assertTrue("$kind 攻擊下限不得大於上限", kind.attackMin <= kind.attackMax)
            assertEquals("$kind 有攻擊擲骰就要打得到某類目標", kind.attackMax > 0, kind.canAttack)
            assertTrue("$kind 生命必須為正", kind.hp > 0)
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

    // ------------------------------------------------------------------
    // 參考公式的數值（docs/original-behavior.md 第 6 節）
    // ------------------------------------------------------------------

    @Test
    fun `each point of defence widens the divisor by 1_6 percent`() {
        assertEquals(100, Combat.defended(100, 0))
        assertEquals("防禦 1 → 1000/1016", 21, Combat.defended(22, 1))
        assertEquals("防禦 125 剩三分之一", 333, Combat.defended(1000, 125))
        assertEquals("防禦 10 → 1000/1160", 34, Combat.defended(40, 10))
    }

    @Test
    fun `one hit follows the reference formula step by step`() {
        fun hit(ctx: CombatContext, roll: Int) = Combat.hit(ctx, roll, ctx.attacker.hp, exchange = false)

        // 步兵打步兵，平原、滿血、士氣正常：22 × 1000 / 1016 = 21.65 → 21。
        val plain = context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1))
        assertEquals(21, hit(plain, 22).toUnit)

        // HP 不到一半，攻擊% 減半：⌊0.5 × 22⌋ = 11 → 11000/1016 = 10。
        val wounded = unit(UnitKind.INFANTRY).apply { hp = maxHp / 2 - 1 }
        assertEquals(10, hit(context(wounded, unit(UnitKind.INFANTRY, nation = 1)), 22).toUnit)
        // 剛好一半不算殘血。
        val half = unit(UnitKind.INFANTRY).apply { hp = maxHp / 2 }
        assertEquals(21, hit(context(half, unit(UnitKind.INFANTRY, nation = 1)), 22).toUnit)

        // 等級的固定攻擊不受士氣放大：3 級 +8，士氣 −2 → 8 + ⌊0.5 × 20⌋ = 18 → 18000/1016 = 17。
        val veteran = context(
            unit(UnitKind.INFANTRY, level = 3), unit(UnitKind.INFANTRY, nation = 1), attackerMorale = -2
        )
        assertEquals(17, hit(veteran, 20).toUnit)

        // 戰車打山上的步兵：40 × 1000 / 1016 = 39，再 × 0.8 = 31.2 → 31。
        val uphill = context(unit(UnitKind.ARMOUR), unit(UnitKind.INFANTRY, nation = 1), defenderTerrain = Terrain.MOUNTAIN)
        assertEquals(31, hit(uphill, 40).toUnit)

        // 傷害至少 1。
        val feeble = context(unit(UnitKind.SUBMARINE), unit(UnitKind.BATTLESHIP, nation = 1).apply { level = 5 })
        assertTrue(hit(feeble, 1).toUnit >= 1)
    }

    @Test
    fun `a garrison with walls takes half and the walls take the roll undefended`() {
        val ctx = context(unit(UnitKind.INFANTRY), unit(UnitKind.INFANTRY, nation = 1), defenderInCity = true)
        // 部隊：20 → 19，× 0.5 = 9。城牆沒有防禦：20；有還手的交火 × 0.7 = 14。
        val exchange = Combat.hit(ctx, 20, ctx.attacker.hp, exchange = true)
        assertEquals(9, exchange.toUnit)
        assertEquals(14, exchange.toCity)
        assertEquals(20, Combat.hit(ctx, 20, ctx.attacker.hp, exchange = false).toCity)
    }

    @Test
    fun `the counter is full strength but uses the defender's hp after the hit`() {
        val attacker = unit(UnitKind.INFANTRY)
        val defender = unit(UnitKind.INFANTRY, nation = 1)
        val ctx = context(attacker, defender)
        val untouched = Combat.previewRetaliation(ctx, 0)
        val asFirstStrike = Combat.previewDamage(context(defender, attacker))
        assertEquals("反擊不打折", asFirstStrike, untouched)
        val crippled = Combat.previewRetaliation(ctx, defender.maxHp / 2 + 1)
        assertTrue("挨打到半血以下，反擊減半 $crippled vs $untouched", crippled < untouched * 3 / 4)
        assertEquals("被打死就沒有反擊", 0, Combat.previewRetaliation(ctx, defender.maxHp))
    }

    @Test
    fun `attack rolls are uniform integers between min and max`() {
        val rng = Rng(99)
        val seen = HashSet<Int>()
        repeat(2_000) {
            val r = Combat.roll(UnitKind.INFANTRY, rng)
            assertTrue(r in UnitKind.INFANTRY.attackMin..UnitKind.INFANTRY.attackMax)
            seen.add(r)
        }
        assertEquals("每個值都擲得到", UnitKind.INFANTRY.attackMax - UnitKind.INFANTRY.attackMin + 1, seen.size)
    }

    @Test
    fun `max hp comes from kind, formation and level`() {
        assertEquals(80, ArmyUnit.maxHpFor(UnitKind.INFANTRY, 1, 1))
        assertEquals("編制 3 = 210%", 168, ArmyUnit.maxHpFor(UnitKind.INFANTRY, 3, 1))
        assertEquals("180 × 160% + 2 級 × 20", 328, ArmyUnit.maxHpFor(UnitKind.ARMOUR, 2, 3))

        // 改編制或等級時 HP 依比例縮放。
        val u = ArmyUnit(1, UnitKind.ARMOUR, 0, 0)
        u.hp = 90
        u.size = 2
        assertEquals(144, u.hp)
        u.level = 2
        assertEquals(154, u.hp)
        assertEquals(308, u.maxHp)
    }

    @Test
    fun `healing and starvation work in shares of max hp`() {
        val u = ArmyUnit(1, UnitKind.ARMOUR, 0, 0)
        u.hp = 100
        u.healPercent(22)
        assertEquals(139, u.hp)
        u.losePercent(8)
        assertEquals(125, u.hp)
        u.healPercent(100)
        assertEquals(u.maxHp, u.hp)
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
