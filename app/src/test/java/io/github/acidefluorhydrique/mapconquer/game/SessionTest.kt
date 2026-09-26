// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.TestAssets
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Domain
import io.github.acidefluorhydrique.mapconquer.units.UnitKind
import io.github.acidefluorhydrique.mapconquer.world.MapLoader
import io.github.acidefluorhydrique.mapconquer.world.WorldMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合引擎與指令層。
 *
 * 用一張手寫的小地圖，才能把每一條規則單獨挑出來驗；
 * 真實地圖的整局壓力測試在 [ConquestSmokeTest]。
 */
class SessionTest {

    /**
     * 8x6 的測試地圖：左半是 A 省，右半是 B 省，中間夾一排山，
     * 最下面一列是海（給軍艦與登陸用）。
     */
    private fun testMap(): WorldMap {
        val text = """
            format 1
            id test
            cols 8
            rows 6

            [terrain]
            ....^...
            ....^...
            ........
            ....^...
            ........
            ~~~~~~~~

            [provinces]
            4:0 4:1
            4:0 4:1
            4:0 4:1
            4:0 4:1
            4:0 4:1
            8:-1

            [meta]
            0|prov_a|3|1,1
            1|prov_b|3|6,1
        """.trimIndent()
        return MapLoader.parse(text.reader().buffered(), "test")
    }

    private fun scenario(
        units: List<ScenarioUnit> = emptyList(),
        objectives: List<Objective> = emptyList(),
        relations: List<Triple<String, String, Relation>> = listOf(Triple("AAA", "BBB", Relation.WAR))
    ) = Scenario(
        id = "test", mapId = "test", mode = GameMode.CAMPAIGN,
        nameKey = "n", descKey = "d", order = 1, turnLimit = 50,
        startYear = 2026, startMonth = 1,
        nations = listOf(
            ScenarioNation("AAA", "nation_aaa", "#FF0000", 0, AiProfile.BALANCED, 5000, IntArray(6)),
            ScenarioNation("BBB", "nation_bbb", "#0000FF", 1, AiProfile.BALANCED, 5000, IntArray(6))
        ),
        relations = relations,
        ownership = mapOf("AAA" to intArrayOf(0), "BBB" to intArrayOf(1)),
        startingUnits = units,
        playable = listOf("AAA"),
        objectives = objectives,
        starTurns = intArrayOf(10, 20)
    )

    private fun session(
        units: List<ScenarioUnit> = emptyList(),
        objectives: List<Objective> = emptyList(),
        relations: List<Triple<String, String, Relation>> = listOf(Triple("AAA", "BBB", Relation.WAR))
    ) = Session(testMap(), scenario(units, objectives, relations), Difficulty.OFFICER, "AAA", 1234L)

    // ------------------------------------------------------------------

    @Test
    fun `the test map itself is what the other tests assume`() {
        val map = testMap()
        assertEquals(8, map.cols)
        assertEquals(6, map.rows)
        assertEquals(2, map.provinces.size)
        assertEquals(map.index(1, 1), map.provinces[0].capitalTile)
        assertEquals(map.index(6, 1), map.provinces[1].capitalTile)
        assertTrue(map.isWater(map.index(3, 5)))
        assertTrue(map.provinces[0].coastal)
    }

    @Test
    fun `scenario ownership and units are applied on construction`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 2, "")))
        assertEquals(0, s.playerNationId)
        assertEquals(1, s.provincesOf(0))
        assertEquals(1, s.provincesOf(1))
        assertEquals(1, s.units.size)
        val unit = s.units.first()
        assertEquals(UnitKind.INFANTRY, unit.kind)
        assertEquals(2, unit.level)
        assertTrue("開局部隊要能立刻行動", unit.movesLeft > 0)
        assertEquals(unit, s.unitAt(s.map.index(1, 1), Domain.LAND))
        assertTrue(s.isHostile(0, 1))
    }

    @Test
    fun `moving a unit updates occupancy and spends movement`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        val from = unit.tile
        val target = s.map.index(3, 1)

        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, unit, reachable)
        assertTrue("目標應在移動範圍內", reachable.contains(target))
        assertFalse("山地在四點移動之外", reachable.contains(s.map.index(7, 1)))

        val before = unit.movesLeft
        val path = ArrayList<Int>()
        assertEquals(target, Orders.move(s, unit, target, path))
        assertEquals(target, unit.tile)
        assertTrue(unit.movesLeft < before)
        assertNull("舊格子要被清空", s.unitAt(from, Domain.LAND))
        assertEquals(unit, s.unitAt(target, Domain.LAND))
        assertEquals("走過就不算築壕", 0, unit.entrenchment)
    }

    @Test
    fun `vehicles cannot climb mountains but infantry can`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 3, 0, "ARMOUR", 1, ""),
                ScenarioUnit("AAA", 3, 1, "INFANTRY", 1, "")
            )
        )
        val mountain = s.map.index(4, 0)
        val armour = s.units.first { it.kind == UnitKind.ARMOUR }
        val infantry = s.units.first { it.kind == UnitKind.INFANTRY }

        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, armour, reachable)
        assertFalse("裝甲不該進得了山地", reachable.contains(mountain))

        Orders.computeReachable(s, infantry, reachable)
        assertTrue("步兵應該進得了山地", reachable.contains(s.map.index(4, 1)))
    }

    @Test
    fun `capturing the enemy capital flips the whole province`() {
        val s = session(listOf(ScenarioUnit("AAA", 5, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        val enemyCapital = s.map.provinces[1].capitalTile
        assertEquals(1, s.provinceOwner[1])
        // 城防先打光，這個測試看的是「城破之後整省易主」那一步。
        s.cityHp[1] = 0

        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, unit, reachable)
        assertTrue(reachable.contains(enemyCapital))
        Orders.move(s, unit, enemyCapital, ArrayList())

        assertEquals("整省易主", 0, s.provinceOwner[1])
        assertEquals(2, s.provincesOf(0))
        for (tile in s.map.provinces[1].tiles) assertEquals(0, s.ownerOfTile(tile))
        assertTrue("接手的該是殘城", s.cityHp[1] in 1 until s.map.provinces[1].maxCityHp)
    }

    @Test
    fun `a city with defence left is shut until it is worn down, then taken by walking in`() {
        val s = session(listOf(ScenarioUnit("AAA", 5, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        val enemyCapital = s.map.provinces[1].capitalTile
        assertTrue("測試地圖的 B 省要有城", s.cityHp[1] > 0)

        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, unit, reachable)
        assertFalse("還有城防的敵城走不進去", reachable.contains(enemyCapital))
        assertFalse(Orders.mayStandIn(s, unit, enemyCapital))
        assertTrue("但可以從旁邊打它的城防", Orders.canAttack(s, unit, enemyCapital))

        // 城防打光之後，城裡沒人，走進去的那一刻就易主。
        s.cityHp[1] = 0
        Orders.computeReachable(s, unit, reachable)
        assertTrue(reachable.contains(enemyCapital))
        Orders.move(s, unit, enemyCapital, ArrayList())
        assertEquals("城防歸零、城裡沒人，走進去就佔領", 0, s.provinceOwner[1])
    }

    @Test
    fun `nobody passes through a city that still has its defence`() {
        // 走路、空降都不能進還有城防的敵城；城防歸零之後空降才落得下去。
        val s = session(listOf(ScenarioUnit("AAA", 5, 1, "RECON", 1, "")))
        val unit = s.units.first()
        val enemyCapital = s.map.provinces[1].capitalTile
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, unit, reachable)
        assertFalse(reachable.contains(enemyCapital))
        assertFalse("空降也落不進還有城防的敵城", AirOps.canDropAt(s, 0, enemyCapital))
        s.cityHp[1] = 0
        assertTrue(AirOps.canDropAt(s, 0, enemyCapital))
    }

    /** 兩國各有一省的陣營劇本，參戰回合與初始關係自己指定。 */
    private fun blocSession(
        blocA: String, turnA: Int, blocB: String, turnB: Int,
        extra: List<ScenarioNation> = emptyList(),
        relations: List<Triple<String, String, Relation>> = emptyList()
    ): Session {
        val base = scenario(relations = relations)
        val nations = listOf(
            ScenarioNation("AAA", "nation_aaa", "#FF0000", 0, AiProfile.BALANCED, 5000, IntArray(6),
                bloc = blocA, warTurn = turnA),
            ScenarioNation("BBB", "nation_bbb", "#0000FF", 1, AiProfile.BALANCED, 5000, IntArray(6),
                bloc = blocB, warTurn = turnB)
        ) + extra
        val s = Scenario(
            id = base.id, mapId = base.mapId, mode = base.mode, nameKey = base.nameKey,
            descKey = base.descKey, order = base.order, turnLimit = base.turnLimit,
            startYear = base.startYear, startMonth = base.startMonth, nations = nations,
            relations = relations, ownership = base.ownership, startingUnits = emptyList(),
            playable = base.playable, objectives = base.objectives, starTurns = base.starTurns
        )
        return Session(testMap(), s, Difficulty.OFFICER, "AAA", 99L)
    }

    @Test
    fun `a nation joins its bloc's war on its war turn, not before`() {
        val s = blocSession("AXIS", 1, "ALLIES", 3)
        assertFalse("第 1 回合還不該開戰", s.diplomacy.isAtWar(0, 1))
        while (s.turn < 3) s.advanceToNextNation()
        assertTrue("第 3 回合該照表參戰", s.diplomacy.isAtWar(0, 1))
    }

    @Test
    fun `declaring war on a bystander makes it an enemy that stops sitting still`() {
        val s = blocSession("", 1, "", 1)
        assertTrue(s.isBystander(1))
        assertTrue(Orders.declareWar(s, 0, 1))
        assertTrue("宣戰之後要能互相攻擊", s.isHostile(0, 1))
        assertFalse("被宣戰的中立國不再是旁觀者", s.isBystander(1))
        assertFalse("已經在打就不能再宣一次", Orders.canDeclareWar(s, 0, 1))
    }

    @Test
    fun `nobody may declare war on their own bloc`() {
        val s = blocSession("AXIS", 1, "AXIS", 1)
        assertFalse(Orders.canDeclareWar(s, 0, 1))
        assertFalse(Orders.declareWar(s, 0, 1))
    }

    @Test
    fun `attacking a late entrant drags its whole bloc war forward`() {
        val ally = ScenarioNation("CCC", "nation_ccc", "#00FF00", -1, AiProfile.BALANCED, 500, IntArray(6),
            bloc = "AXIS", warTurn = 1)
        val s = blocSession("AXIS", 1, "ALLIES", 9, extra = listOf(ally))
        assertFalse("美國式的晚參戰國開局不在打", s.diplomacy.isAtWar(2, 1))

        assertTrue(Orders.declareWar(s, 0, 1))
        assertTrue("被打的那一國要跟整個敵對陣營開戰", s.diplomacy.isAtWar(2, 1))
    }

    @Test
    fun `a formation is chosen when it is built, and units never merge afterwards`() {
        val s = session(listOf(ScenarioUnit("AAA", 2, 1, "INFANTRY", 1, "")))
        val nation = s.nations[0]
        nation.funds = 10_000

        val built = Orders.build(s, 0, 0, UnitKind.INFANTRY, size = 3)
        assertNotNull(built)
        assertEquals(3, built!!.size)
        assertEquals("照編制數付錢", 10_000 - UnitKind.INFANTRY.cost * 3, nation.funds)

        nation.funds = UnitKind.INFANTRY.cost * 2
        s.relocate(built, s.map.index(1, 3))
        assertEquals(
            "錢不夠四個編制",
            Orders.BuildBlocker.NO_FUNDS,
            Orders.buildBlocker(s, 0, 0, UnitKind.INFANTRY, 4)
        )

        // 同兵種的友軍站著的格子，走不進去 —— 不會再變成併編。
        val single = s.units.first { it.size == 1 }
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, single, reachable)
        assertFalse(reachable.contains(built.tile))
        assertEquals(single.tile, Orders.move(s, single, built.tile, ArrayList()))
        assertEquals(2, s.unitsOf(0).size)
    }

    @Test
    fun `a bigger formation pays more upkeep`() {
        val s = session(listOf(ScenarioUnit("AAA", 2, 1, "INFANTRY", 1, "", size = 4)))
        repeat(s.nations.size) { s.advanceToNextNation() }
        assertEquals(UnitKind.INFANTRY.upkeep * 4, s.nations[0].lastUpkeep)
    }

    @Test
    fun `an enemy blocks the way through its hex`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, "")
            )
        )
        val infantry = s.units.first { it.nationId == 0 }
        val enemy = s.units.first { it.nationId == 1 }
        assertEquals(-1, UnitMoveRules(s, infantry).enterCost(infantry.tile, enemy.tile))
    }

    // ------------------------------------------------------------------
    // 空中任務
    // ------------------------------------------------------------------

    @Test
    fun `aircraft are missions, not units`() {
        for (kind in UnitKind.ALL) {
            assertTrue("$kind 不該是空軍部隊", kind.domain == Domain.LAND || kind.domain == Domain.SEA)
        }
    }

    @Test
    fun `fighters hit infantry hardest and bombers hit armour hardest`() {
        val s = session(
            listOf(
                ScenarioUnit("BBB", 5, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 5, 3, "ARMOUR", 1, "")
            )
        )
        val infantry = s.units.first { it.kind == UnitKind.INFANTRY }
        val armour = s.units.first { it.kind == UnitKind.ARMOUR }
        val fighterOnFoot = AirOps.previewDamage(s, 0, AirMission.FIGHTER, infantry)
        val fighterOnTanks = AirOps.previewDamage(s, 0, AirMission.FIGHTER, armour)
        val bomberOnFoot = AirOps.previewDamage(s, 0, AirMission.BOMBER, infantry)
        val bomberOnTanks = AirOps.previewDamage(s, 0, AirMission.BOMBER, armour)
        assertTrue("戰鬥機 $fighterOnFoot vs $fighterOnTanks", fighterOnFoot > fighterOnTanks * 2)
        assertTrue("轟炸機 $bomberOnTanks vs $bomberOnFoot", bomberOnTanks > bomberOnFoot)
        assertTrue("打步兵該找戰鬥機", fighterOnFoot > bomberOnFoot)
        assertTrue("打戰車該找轟炸機", bomberOnTanks > fighterOnTanks)
    }

    @Test
    fun `an air strike costs money, hits once, and grounds its airfield for the turn`() {
        val s = session(listOf(ScenarioUnit("BBB", 4, 2, "INFANTRY", 1, "")))
        val nation = s.nations[0]
        nation.funds = 1_000
        val target = s.units.first()
        assertTrue(AirOps.canFly(s, 0, AirMission.FIGHTER, target.tile))

        val result = AirOps.fly(s, 0, AirMission.FIGHTER, target.tile)
        assertNotNull(result)
        assertTrue("要打出傷害", result!!.damageToUnit > 0)
        assertTrue(target.hp < target.maxHp)
        assertEquals(1_000 - AirMission.FIGHTER.totalCost, nation.funds)
        assertEquals("飛機不會留在地圖上", 1, s.units.size)

        // A 國只有一座機場，這回合已經飛過了。
        assertFalse(AirOps.canFly(s, 0, AirMission.FIGHTER, target.tile))
        assertEquals(AirOps.Blocker.NO_BASE, AirOps.blocker(s, 0, AirMission.FIGHTER))
        repeat(s.nations.size) { s.advanceToNextNation() }
        assertTrue("下一回合機場又能飛", AirOps.canFly(s, 0, AirMission.FIGHTER, target.tile))
    }

    @Test
    fun `anti-air near the target blunts the strike`() {
        val bare = session(listOf(ScenarioUnit("BBB", 4, 2, "INFANTRY", 1, "")))
        val covered = session(
            listOf(
                ScenarioUnit("BBB", 4, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 5, 2, "ANTI_AIR", 1, "")
            )
        )
        val a = AirOps.previewDamage(bare, 0, AirMission.FIGHTER, bare.units.first())
        val b = AirOps.previewDamage(
            covered, 0, AirMission.FIGHTER, covered.units.first { it.kind == UnitKind.INFANTRY }
        )
        assertTrue("有防空要打得比較少 $b vs $a", b < a)
    }

    @Test
    fun `an airdrop puts a fresh infantry unit on an open land hex`() {
        val s = session(listOf(ScenarioUnit("BBB", 3, 3, "INFANTRY", 1, "")))
        val nation = s.nations[0]
        nation.funds = 1_000
        val drop = s.map.index(3, 2)
        val occupied = s.map.index(3, 3)
        val water = s.map.index(3, 5)

        assertFalse("不能落在有人的格子", AirOps.canFly(s, 0, AirMission.AIRDROP, occupied))
        assertFalse("不能落在海上", AirOps.canFly(s, 0, AirMission.AIRDROP, water))
        val result = AirOps.fly(s, 0, AirMission.AIRDROP, drop)
        val trooper = result?.dropped
        assertNotNull(trooper)
        assertEquals(drop, trooper!!.tile)
        assertEquals(AirOps.PARATROOPER, trooper.kind)
        assertEquals(0, trooper.nationId)
        assertEquals(1_000 - AirMission.AIRDROP.totalCost, nation.funds)
    }

    @Test
    fun `only a bomber can bomb an empty city`() {
        val s = session()
        s.nations[0].funds = 1_000
        val enemyCapital = s.map.provinces[1].capitalTile
        assertFalse(AirOps.canFly(s, 0, AirMission.FIGHTER, enemyCapital))
        assertTrue(AirOps.canFly(s, 0, AirMission.BOMBER, enemyCapital))
        val before = s.cityHp[1]
        assertNotNull(AirOps.fly(s, 0, AirMission.BOMBER, enemyCapital))
        assertTrue(s.cityHp[1] < before)
        assertEquals("炸城不會讓它易主", 1, s.provinceOwner[1])
    }

    @Test
    fun `a besieger is not shielded by the walls it is besieging`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 6, 1, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 6, 2, "INFANTRY", 1, "")
            )
        )
        val besieger = s.units.first { it.nationId == 0 }
        assertEquals(s.map.provinces[1].capitalTile, besieger.tile)
        assertFalse("敵城的城牆不護圍城的部隊", s.cityShields(besieger))

        // 城防在圍城期間被別的方式打光，下一個己方回合也要收下。
        s.cityHp[1] = 0
        repeat(s.nations.size) { s.advanceToNextNation() }
        assertEquals(0, s.provinceOwner[1])
    }

    @Test
    fun `a formation is stronger than one unit but weaker than the units it absorbed`() {
        for (n in 2..ArmyUnit.MAX_SIZE) {
            assertTrue(ArmyUnit.attackPercent(n) > ArmyUnit.attackPercent(n - 1))
            assertTrue(ArmyUnit.sizeHpPercent(n) > ArmyUnit.sizeHpPercent(n - 1))
            assertTrue("攻擊不該追上 $n 支分開的部隊", ArmyUnit.attackPercent(n) < 100 * n)
            assertTrue("生命不該追上 $n 支分開的部隊", ArmyUnit.sizeHpPercent(n) < 100 * n)
        }
        val small = ArmyUnit(1, UnitKind.INFANTRY, 0, 0)
        val big = ArmyUnit(2, UnitKind.INFANTRY, 0, 0).apply { size = 3 }
        assertEquals("三編制的生命是 210%", small.maxHp * 210 / 100, big.maxHp)
        small.damage(30)
        big.damage(30)
        assertTrue("同一發傷害，大編制掉的比例要比較少", big.hpRatio > small.hpRatio)
    }

    @Test
    fun `a garrison rebuilds the city, and the city heals the garrison`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")))
        val garrison = s.units.first()
        assertEquals("守軍該站在 A 省的城上", s.map.provinces[0].capitalTile, garrison.tile)

        s.cityHp[0] = 20
        garrison.damage(40)
        val cityBefore = s.cityHp[0]
        val hpBefore = garrison.hp

        repeat(s.nations.size) { s.advanceToNextNation() }

        assertTrue("有守軍就該補城防", s.cityHp[0] > cityBefore)
        assertTrue("城市也該替守軍回血", garrison.hp > hpBefore)
    }

    @Test
    fun `an empty city does not rebuild itself`() {
        val s = session(listOf(ScenarioUnit("AAA", 3, 2, "INFANTRY", 1, "")))
        val capital = s.map.provinces[0].capitalTile
        assertNull("這一關的城裡刻意不放守軍", s.primaryUnitAt(capital))

        s.cityHp[0] = 20
        repeat(s.nations.size) { s.advanceToNextNation() }

        assertEquals("空城不會自己長回城防", 20, s.cityHp[0])
    }

    @Test
    fun `a tile holds one unit, whatever its domain`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 1, "INFANTRY", 1, ""),
                ScenarioUnit("AAA", 2, 3, "ARTILLERY", 1, "")
            )
        )
        val infantry = s.units.first { it.kind == UnitKind.INFANTRY }
        val gun = s.units.first { it.kind == UnitKind.ARTILLERY }

        assertFalse("步兵佔著的格子放不下火炮", s.isTileFreeFor(gun, infantry.tile))
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, gun, reachable)
        assertFalse("可達清單不該包含被佔的格子", reachable.contains(infantry.tile))
        assertEquals(
            "移動到被佔的格子應該原地不動",
            gun.tile, Orders.move(s, gun, infantry.tile, ArrayList())
        )
    }

    @Test
    fun `an undefended city can be shelled, and shelling alone does not take it`() {
        val s = session(listOf(ScenarioUnit("AAA", 5, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        val enemyCapital = s.map.provinces[1].capitalTile
        assertNull("城裡不該有守軍", s.primaryUnitAt(enemyCapital))

        val before = s.cityHp[1]
        assertTrue("空城要打得到", Orders.canAttack(s, unit, enemyCapital))
        assertNotNull(Orders.attack(s, unit, enemyCapital))
        assertTrue("城防要掉", s.cityHp[1] < before)
        assertEquals("轟一發不會讓城市易主", 1, s.provinceOwner[1])
        assertTrue("開火之後這回合不能再動", unit.hasAttacked)
    }

    @Test
    fun `a garrison in a city takes half, and the city takes the rest`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 5, 1, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 6, 1, "INFANTRY", 1, "")
            )
        )
        val attacker = s.units.first { it.nationId == 0 }
        val defender = s.units.first { it.nationId == 1 }
        assertEquals("守軍應該就站在 B 省的城上", s.map.provinces[1].capitalTile, defender.tile)

        val hpBefore = defender.hp
        val cityBefore = s.cityHp[1]
        val result = Orders.attack(s, attacker, defender.tile)
        assertNotNull(result)

        val toUnit = hpBefore - defender.hp
        val toCity = cityBefore - s.cityHp[1]
        assertTrue("城市要跟著挨打", toCity > 0)
        assertTrue("城市挨的比部隊多", toCity > toUnit)
    }

    @Test
    fun `support units cannot capture`() {
        val s = session(listOf(ScenarioUnit("AAA", 5, 1, "ARTILLERY", 1, "")))
        val gun = s.units.first()
        Orders.computeReachable(s, gun, ArrayList())
        Orders.move(s, gun, s.map.provinces[1].capitalTile, ArrayList())
        assertEquals("火炮不該佔得下省份", 1, s.provinceOwner[1])
        assertTrue("火炮也不該停得進敵城", gun.tile != s.map.provinces[1].capitalTile)
    }

    @Test
    fun `attacking costs the attacker its turn and damages the defender`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, "")
            )
        )
        val attacker = s.units.first { it.nationId == 0 }
        val defender = s.units.first { it.nationId == 1 }
        val targets = ArrayList<Int>()
        Orders.collectTargets(s, attacker, targets)
        assertTrue("應該打得到隔壁的敵人", targets.contains(defender.tile))

        val result = Orders.attack(s, attacker, defender.tile)
        assertNotNull(result)
        assertTrue("守方應該受傷", defender.hp < defender.maxHp)
        assertTrue("步兵對步兵應該有反擊", result!!.damageToAttacker > 0)
        assertTrue(attacker.hasAttacked)
        assertEquals("開火之後不能再動", 0, attacker.movesLeft)
        assertNull("同一回合不能打第二次", Orders.attack(s, attacker, defender.tile))
    }

    @Test
    fun `artillery reaches further than it can be reached`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 1, 2, "ARTILLERY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, "")
            )
        )
        val gun = s.units.first { it.kind == UnitKind.ARTILLERY }
        val target = s.units.first { it.nationId == 1 }
        val result = Orders.attack(s, gun, target.tile)
        assertNotNull("兩格外應該打得到", result)
        assertEquals("遠射不該被反擊", 0, result!!.damageToAttacker)
    }

    @Test
    fun `zone of control stops a unit next to an enemy`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 0, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 2, 2, "INFANTRY", 1, "")
            )
        )
        val mover = s.units.first { it.nationId == 0 }
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, mover, reachable)
        assertTrue("可以走到敵人旁邊", reachable.contains(s.map.index(1, 2)))
        assertFalse("不該直接穿過敵人的控制區", reachable.contains(s.map.index(3, 2)))
    }

    @Test
    fun `armour breaks through zones of control`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 0, 2, "ARMOUR", 1, ""),
                ScenarioUnit("BBB", 2, 2, "INFANTRY", 1, "")
            )
        )
        val armour = s.units.first { it.nationId == 0 }
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, armour, reachable)
        assertTrue("裝甲應該推得過去", reachable.contains(s.map.index(3, 2)))
    }

    @Test
    fun `production needs the province, the industry and the money`() {
        val s = session()
        val nation = s.nations[0]
        nation.funds = 10_000

        assertTrue(Orders.canBuild(s, 0, 0, UnitKind.INFANTRY))
        assertEquals(
            "不是自己的省份",
            Orders.BuildBlocker.NOT_OWNED,
            Orders.buildBlocker(s, 0, 1, UnitKind.INFANTRY)
        )
        assertEquals(
            "大城的工業等級擋不下航艦",
            Orders.BuildBlocker.LOW_INDUSTRY,
            Orders.buildBlocker(s, 0, 0, UnitKind.CARRIER)
        )

        val built = Orders.build(s, 0, 0, UnitKind.INFANTRY)
        assertNotNull(built)
        assertEquals(10_000 - UnitKind.INFANTRY.cost, nation.funds)
        assertEquals("新兵應該站在省會", s.map.provinces[0].capitalTile, built!!.tile)
        assertEquals("新兵當回合不能動", 0, built.movesLeft)

        assertEquals(
            "省會被佔住了就沒地方部署",
            Orders.BuildBlocker.NO_ROOM,
            Orders.buildBlocker(s, 0, 0, UnitKind.INFANTRY)
        )

        nation.funds = 10
        assertEquals(
            Orders.BuildBlocker.NO_FUNDS,
            Orders.buildBlocker(s, 0, 0, UnitKind.ARMOUR)
        )
    }

    @Test
    fun `warships are launched into the water next to their port`() {
        val s = session()
        s.nations[0].funds = 10_000
        // A 省的省會在 (1,1)，離海還有距離，所以造不了船。
        assertEquals(
            Orders.BuildBlocker.NO_ROOM,
            Orders.buildBlocker(s, 0, 0, UnitKind.DESTROYER)
        )
    }

    @Test
    fun `research spends funds and raises the bonus`() {
        val s = session()
        val nation = s.nations[0]
        nation.funds = 10_000
        val before = nation.techBonus(io.github.acidefluorhydrique.mapconquer.units.TechBranch.ARMOUR)
        assertTrue(Orders.research(s, 0, io.github.acidefluorhydrique.mapconquer.units.TechBranch.ARMOUR))
        assertTrue(
            nation.techBonus(io.github.acidefluorhydrique.mapconquer.units.TechBranch.ARMOUR) > before
        )
        assertTrue(nation.funds < 10_000)
    }

    @Test
    fun `turn order cycles through every nation and increments the turn`() {
        val s = session()
        assertEquals(1, s.turn)
        assertEquals(0, s.activeNationId)
        assertTrue(s.isPlayerTurn)

        assertFalse("換到第二國還在同一回合", s.advanceToNextNation())
        assertEquals(1, s.activeNationId)
        assertEquals(1, s.turn)

        assertTrue("繞完一圈才進下一回合", s.advanceToNextNation())
        assertEquals(0, s.activeNationId)
        assertEquals(2, s.turn)
    }

    @Test
    fun `units entrench while standing still and refill in supply`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        unit.hp = 50
        unit.supply = 10
        repeat(4) {
            s.advanceToNextNation()
            s.advanceToNextNation()
        }
        assertTrue("待在城裡應該回血", unit.hp > 50)
        assertEquals("待在城裡應該補滿", ArmyUnit.MAX_SUPPLY, unit.supply)
        assertTrue("原地不動應該築壕", unit.entrenchment > 0)
        assertTrue(unit.entrenchment <= Session.MAX_ENTRENCHMENT)
    }

    @Test
    fun `objectives drive victory`() {
        val s = session(
            units = listOf(ScenarioUnit("AAA", 5, 1, "INFANTRY", 1, "")),
            objectives = listOf(
                Objective(ObjectiveType.CAPTURE_PROVINCES, provinces = intArrayOf(1))
            )
        )
        assertEquals(SessionStatus.PLAYING, s.status)
        val unit = s.units.first { it.nationId == 0 }
        s.cityHp[1] = 0
        Orders.computeReachable(s, unit, ArrayList())
        Orders.move(s, unit, s.map.provinces[1].capitalTile, ArrayList())
        assertEquals(SessionStatus.VICTORY, s.status)
    }

    // ------------------------------------------------------------------
    // 投降與征服的勝利條件（docs/original-behavior.md）
    // ------------------------------------------------------------------

    /**
     * 三省地圖：A 省（AAA 的城）、B 省（BBB 的城）、C 省（[cTier] 級，歸 [cOwner]）。
     * 最下面一列是海。
     */
    private fun threeProvinceSession(
        cTier: Int,
        cOwner: String,
        units: List<ScenarioUnit>,
        relations: List<Triple<String, String, Relation>>,
        turnLimit: Int = 50
    ): Session {
        val text = """
            format 1
            id test3
            cols 9
            rows 6

            [terrain]
            .........
            .........
            .........
            .........
            .........
            ~~~~~~~~~

            [provinces]
            3:0 3:1 3:2
            3:0 3:1 3:2
            3:0 3:1 3:2
            3:0 3:1 3:2
            3:0 3:1 3:2
            9:-1

            [meta]
            0|prov_a|3|1,1
            1|prov_b|3|4,1
            2|prov_c|$cTier|7,1
        """.trimIndent()
        val map = MapLoader.parse(text.reader().buffered(), "test3")
        val nations = listOf(
            ScenarioNation("AAA", "nation_aaa", "#FF0000", 0, AiProfile.BALANCED, 5000, IntArray(6)),
            ScenarioNation("BBB", "nation_bbb", "#0000FF", 1, AiProfile.BALANCED, 5000, IntArray(6)),
            ScenarioNation("CCC", "nation_ccc", "#00FF00", if (cOwner == "CCC") 2 else -1,
                AiProfile.BALANCED, 5000, IntArray(6))
        )
        val ownership = mutableMapOf("AAA" to intArrayOf(0), "BBB" to intArrayOf(1))
        ownership[cOwner] = (ownership[cOwner] ?: IntArray(0)) + 2
        val scenario = Scenario(
            id = "test3", mapId = "test3", mode = GameMode.CONQUEST,
            nameKey = "n", descKey = "d", order = 1, turnLimit = turnLimit,
            startYear = 2026, startMonth = 1, nations = nations, relations = relations,
            ownership = ownership, startingUnits = units, playable = listOf("AAA"),
            objectives = emptyList(), starTurns = intArrayOf(10, 20)
        )
        return Session(map, scenario, Difficulty.OFFICER, "AAA", 4321L)
    }

    /** 讓 AAA 在 (3,1) 的步兵走進 B 省的城，城防先歸零。 */
    private fun takeCityB(s: Session) {
        val unit = s.units.first { it.nationId == 0 && it.kind == UnitKind.INFANTRY }
        s.cityHp[1] = 0
        Orders.computeReachable(s, unit, ArrayList())
        Orders.move(s, unit, s.map.provinces[1].capitalTile, ArrayList())
        assertEquals("步兵該站上 B 城", s.map.provinces[1].capitalTile, unit.tile)
    }

    @Test
    fun `losing the last city is a surrender - units vanish, leftover land goes to the captor`() {
        val s = threeProvinceSession(
            cTier = 0, cOwner = "BBB",
            units = listOf(
                ScenarioUnit("AAA", 3, 1, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 7, 3, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 7, 5, "DESTROYER", 1, "")
            ),
            relations = listOf(Triple("AAA", "BBB", Relation.WAR))
        )
        assertFalse("C 省沒有城", s.map.provinces[2].hasCity)
        takeCityB(s)

        val bbb = s.nationByCode("BBB")!!
        assertTrue("最後一座城丟了就投降", bbb.eliminated)
        assertTrue("投降國的部隊全部消失，包括海上的", s.unitsOf(bbb.id).isEmpty())
        assertEquals("剩下的領土歸攻下最後一城的國家", 0, s.provinceOwner[2])
        assertTrue(s.events.any { it.key == "event_nation_surrendered" })
        assertEquals("唯一的敵國投降，征服完成", SessionStatus.VICTORY, s.status)
    }

    @Test
    fun `losing one of several cities is not a surrender`() {
        val s = threeProvinceSession(
            cTier = 2, cOwner = "BBB",
            units = listOf(
                ScenarioUnit("AAA", 3, 1, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 7, 3, "INFANTRY", 1, "")
            ),
            relations = listOf(Triple("AAA", "BBB", Relation.WAR))
        )
        takeCityB(s)

        val bbb = s.nationByCode("BBB")!!
        assertFalse("還有一座城就還在打", bbb.eliminated)
        assertEquals(1, s.unitsOf(bbb.id).size)
        assertEquals(bbb.id, s.provinceOwner[2])
        assertEquals(SessionStatus.PLAYING, s.status)
    }

    @Test
    fun `conquest is won without touching neutrals`() {
        val s = threeProvinceSession(
            cTier = 2, cOwner = "CCC",
            units = listOf(ScenarioUnit("AAA", 3, 1, "INFANTRY", 1, "")),
            relations = listOf(Triple("AAA", "BBB", Relation.WAR))
        )
        takeCityB(s)
        assertFalse("中立國不必打", s.nationByCode("CCC")!!.eliminated)
        assertEquals(SessionStatus.VICTORY, s.status)
    }

    @Test
    fun `a neutral you declared war on has to be conquered too`() {
        val s = threeProvinceSession(
            cTier = 2, cOwner = "CCC",
            units = listOf(ScenarioUnit("AAA", 3, 1, "INFANTRY", 1, "")),
            relations = listOf(Triple("AAA", "BBB", Relation.WAR))
        )
        assertTrue(Orders.declareWar(s, 0, 2))
        takeCityB(s)
        assertEquals("被宣戰的中立國就是敵國，還沒打下來", SessionStatus.PLAYING, s.status)
    }

    @Test
    fun `a conquest still undecided past its turn limit is lost`() {
        val s = threeProvinceSession(
            cTier = 2, cOwner = "BBB",
            units = listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")),
            relations = listOf(Triple("AAA", "BBB", Relation.WAR)),
            turnLimit = 2
        )
        while (s.turn <= 2) {
            assertEquals("上限之內還在打", SessionStatus.PLAYING, s.status)
            s.advanceToNextNation()
        }
        assertEquals("超過回合上限判負", SessionStatus.DEFEAT, s.status)
    }

    @Test
    fun `nobody to fight means no conquest victory`() {
        val s = threeProvinceSession(
            cTier = 2, cOwner = "CCC",
            units = listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")),
            relations = emptyList()
        )
        repeat(s.nations.size) { s.advanceToNextNation() }
        s.refreshOutcome()
        assertEquals(SessionStatus.PLAYING, s.status)
    }

    @Test
    fun `a new game starts on the player's turn, before any AI moves`() {
        val scenario = TestAssets.scenario("conquest_1939")
        val map = TestAssets.cachedMap(scenario.mapId)
        // 挑劇本檔裡排得最後面的可選國家：原本它要等前面所有 AI 跑完才輪到。
        val code = scenario.playable.last()
        val s = Session(map, scenario, Difficulty.OFFICER, code, 1L)
        assertEquals(code, s.playerNation.code)
        assertEquals(1, s.turn)
        assertTrue("開局第一個行動的就是玩家", s.isPlayerTurn)
    }

    @Test
    fun `losing everything ends the game`() {
        val s = session(
            units = listOf(ScenarioUnit("BBB", 5, 1, "INFANTRY", 1, "")),
            objectives = listOf(Objective(ObjectiveType.SURVIVE_TURNS, turn = 99))
        )
        // 直接把玩家的省份交出去，模擬被打光。
        s.captureProvince(0, 1)
        s.refreshOutcome()
        assertEquals(SessionStatus.DEFEAT, s.status)
    }

    @Test
    fun `a supply column beside your own city still reaches over the border`() {
        // 補給車就停在自家城市附近：城市的補給已經蓋到它腳下，但城市的補給過不了國界，
        // 伸進 B 國的那一圈只能靠補給車自己。
        val s = session(listOf(ScenarioUnit("AAA", 3, 2, "SUPPLY_TRUCK", 1, "")))
        assertTrue(s.isSupplied(s.map.index(5, 2)))
    }

    @Test
    fun `a fleet far from port thins out but never starves`() {
        val s = session(listOf(ScenarioUnit("AAA", 7, 5, "DESTROYER", 1, "")))
        val ship = s.units.first()
        assertFalse("這艘船要在補給範圍外，測試才有意義", s.isSupplied(ship.tile))

        repeat(20) { repeat(s.nations.size) { s.advanceToNextNation() } }
        assertEquals("補給滑到八成戰力的門檻就停住", ArmyUnit.SUPPLY_CRITICAL, ship.supply)
        assertEquals("海上不會餓死", ship.maxHp, ship.hp)
        assertTrue(ship.isAlive)
    }

    @Test
    fun `an army cut off from supply still starves`() {
        val s = session(listOf(ScenarioUnit("AAA", 7, 2, "INFANTRY", 1, "")))
        val soldier = s.units.first()
        assertFalse(s.isSupplied(soldier.tile))

        repeat(6) { repeat(s.nations.size) { s.advanceToNextNation() } }
        assertEquals("陸軍的補給會見底", 0, soldier.supply)
        assertTrue("見底之後開始失血", soldier.hp < soldier.maxHp)
    }

    @Test
    fun `a supply column keeps an offensive fed inside enemy territory`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 7, 2, "INFANTRY", 1, ""),
                ScenarioUnit("AAA", 7, 1, "SUPPLY_TRUCK", 1, "")
            )
        )
        // 兩支部隊都深在 B 國境內，城市的補給沿自己的領土走不過來。
        assertTrue("補給車自己站的那格有補給", s.isSupplied(s.map.index(7, 1)))
        assertTrue("旁邊的部隊也要吃得到", s.isSupplied(s.map.index(7, 2)))
        // 但補給圈只有五點預算：隔著一座山就到不了。
        assertFalse("補給車不是第二座城市", s.isSupplied(s.map.index(4, 1)))

        // 畫在地圖上的補給圈要跟實際生效的範圍一模一樣。
        val truck = s.units.first { it.kind == UnitKind.SUPPLY_TRUCK }
        val bubble = BooleanArray(s.map.tileCount)
        s.supplyBubble(truck, bubble)
        assertTrue(bubble[s.map.index(7, 2)])
        assertFalse("圈的形狀跟著地形走，山擋住就縮回來", bubble[s.map.index(4, 1)])
        for (tile in 0 until s.map.tileCount) {
            if (bubble[tile]) assertTrue("圈裡的格子 $tile 必須真的有補給", s.isSupplied(tile))
        }
    }

    @Test
    fun `transports carry land units over water`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 1, 4, "INFANTRY", 1, ""),
                ScenarioUnit("AAA", 1, 5, "TRANSPORT_SHIP", 1, "")
            )
        )
        val infantry = s.units.first { it.kind == UnitKind.INFANTRY }
        val ship = s.units.first { it.kind == UnitKind.TRANSPORT_SHIP }

        assertTrue(Orders.canLoad(s, infantry, ship))
        assertTrue(Orders.load(s, infantry, ship))
        assertTrue(infantry.isLoaded)
        assertEquals(1, ship.cargo.size)
        assertNull("上船之後不該再佔著陸地", s.unitAt(s.map.index(1, 4), Domain.LAND))

        // 把運輸艦開到另一段海岸再放下來。
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, ship, reachable)
        val destination = s.map.index(5, 5)
        assertTrue(reachable.contains(destination))
        Orders.move(s, ship, destination, ArrayList())
        assertEquals("乘客要跟著船走", destination, infantry.tile)

        val beach = s.map.index(5, 4)
        assertTrue(Orders.canUnload(s, infantry, beach))
        assertTrue(Orders.unload(s, infantry, beach))
        assertFalse(infantry.isLoaded)
        assertEquals(beach, infantry.tile)
        assertTrue("一般步兵登陸當回合不能打", infantry.hasAttacked)
    }

    @Test
    fun `marines can fight the turn they land`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 1, 4, "MARINE", 1, ""),
                ScenarioUnit("AAA", 1, 5, "TRANSPORT_SHIP", 1, "")
            )
        )
        val marine = s.units.first { it.kind == UnitKind.MARINE }
        val ship = s.units.first { it.kind == UnitKind.TRANSPORT_SHIP }
        Orders.load(s, marine, ship)
        assertTrue(Orders.unload(s, marine, s.map.index(2, 4)))
        assertFalse("陸戰隊下船就能打", marine.hasAttacked)
    }

    @Test
    fun `land units can put to sea, and it costs a whole turn`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 4, "INFANTRY", 1, "")))
        val infantry = s.units.first()
        val water = s.map.index(1, 5)
        assertTrue(s.map.isWater(water))

        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, infantry, reachable)
        assertTrue("陸軍應該下得了海", reachable.contains(water))

        // 入海這個「轉換」本身會中斷移動。直接問規則物件最不會誤判 ——
        // 水格本身仍然是可達的，不能達的是「下海之後繼續前進」。
        val rules = UnitMoveRules(s, infantry)
        assertTrue("入陸→海要停", rules.stopsAt(infantry.tile, water))
        assertFalse("海上繼續航行不必停", rules.stopsAt(water, s.map.index(2, 5)))
        assertTrue("海→陸登陸也要停", rules.stopsAt(water, s.map.index(1, 4)))

        Orders.move(s, infantry, water, ArrayList())
        assertEquals(water, infantry.tile)
        assertTrue("站在水上就是在浮渡", s.isEmbarked(infantry))
        assertEquals("浮渡的陸軍佔海層", Domain.SEA, s.layerOf(infantry))
        assertEquals(infantry, s.unitAt(water, Domain.SEA))
        assertNull("它不該還佔著陸層", s.unitAt(water, Domain.LAND))
    }

    @Test
    fun `a warship and an embarked unit cannot share a tile`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 1, 4, "INFANTRY", 1, ""),
                ScenarioUnit("AAA", 1, 5, "DESTROYER", 1, "")
            )
        )
        val infantry = s.units.first { it.kind == UnitKind.INFANTRY }
        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, infantry, reachable)
        assertFalse("驅逐艦佔著的水格，浮渡的陸軍進不去", reachable.contains(s.map.index(1, 5)))
    }

    @Test
    fun `landing also costs a turn`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 5, "INFANTRY", 1, "")))
        val infantry = s.units.first()
        assertTrue(s.isEmbarked(infantry))

        val reachable = ArrayList<Int>()
        Orders.computeReachable(s, infantry, reachable)
        val beach = s.map.index(1, 4)
        assertTrue("應該登得了陸", reachable.contains(beach))
        assertFalse("登陸之後不能再往內陸推進", reachable.contains(s.map.index(1, 2)))

        Orders.move(s, infantry, beach, ArrayList())
        assertFalse(s.isEmbarked(infantry))
        assertEquals(Domain.LAND, s.layerOf(infantry))
    }

    @Test
    fun `armour keeps attacking as long as it keeps killing`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "ARMOUR", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 2, 1, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 1, 2, "INFANTRY", 1, "")
            )
        )
        val armour = s.units.first { it.nationId == 0 }
        val victims = s.units.filter { it.nationId == 1 }
        // 前兩個一擊必殺，第三個滿血活得下來。
        victims[0].hp = 1
        victims[1].hp = 1

        val first = Orders.attack(s, armour, victims[0].tile)
        assertTrue("目標應該被擊毀", first!!.defenderDestroyed)
        assertFalse("突擊：擊毀之後還能再打", armour.hasAttacked)

        val second = Orders.attack(s, armour, victims[1].tile)
        assertTrue("連鎖應該打得到第二個目標", second!!.defenderDestroyed)
        assertFalse("連續擊毀就繼續連鎖", armour.hasAttacked)

        val third = Orders.attack(s, armour, victims[2].tile)
        assertNotNull(third)
        assertFalse("滿血的目標活了下來", third!!.defenderDestroyed)
        assertTrue("沒殺掉就停止連鎖", armour.hasAttacked)
        assertNull("停下來之後不能再打", Orders.attack(s, armour, victims[2].tile))
    }

    @Test
    fun `infantry gets no follow-up attack`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, "")
            )
        )
        val attacker = s.units.first { it.nationId == 0 }
        val victim = s.units.first { it.nationId == 1 }
        victim.hp = 1
        val result = Orders.attack(s, attacker, victim.tile)
        assertTrue(result!!.defenderDestroyed)
        assertTrue("步兵沒有突擊，打完就結束", attacker.hasAttacked)
    }

    /**
     * 士氣階梯。這是規格本身：
     *
     *   +1 高昂 / 0 正常 / −1 夾擊 / −2 包圍 / −3 混亂
     *
     * 而進入混亂剛好有三條等價的路徑，全部都是累計到 −3。
     */
    @Test
    fun `flanking costs one step of morale`() {
        // (2,2) 的東西兩格是一組對向。
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 1, 2, "INFANTRY", 1, "")
            )
        )
        val target = s.units.first { it.nationId == 0 }
        assertEquals("對向兩格有敵人就是夾擊", -1, s.moraleOf(target))
        assertFalse(s.isDisrupted(target))
    }

    @Test
    fun `two adjacent enemies on the same side are not a flank`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 2, 1, "INFANTRY", 1, "")
            )
        )
        val target = s.units.first { it.nationId == 0 }
        assertEquals("同一側的兩個敵人不構成夾擊", 0, s.moraleOf(target))
    }

    @Test
    fun `four neighbours is an encirclement`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 1, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 2, 1, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 2, 3, "INFANTRY", 1, "")
            )
        )
        val target = s.units.first { it.nationId == 0 }
        assertEquals("四面有敵人就是包圍", -2, s.moraleOf(target))
        assertFalse("光是包圍還不到混亂", s.isDisrupted(target))
    }

    @Test
    fun `all three routes into disruption reach the same place`() {
        fun trapped(enemies: List<Pair<Int, Int>>, rumour: Int): Session {
            val units = ArrayList<ScenarioUnit>()
            units.add(ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""))
            for ((c, r) in enemies) units.add(ScenarioUnit("BBB", c, r, "INFANTRY", 1, ""))
            val s = Session(testMap(), scenario(units), Difficulty.OFFICER, "AAA", 1L)
            s.units.first { it.nationId == 0 }.rumour = rumour
            return s
        }

        val encircleAndRumour = trapped(listOf(3 to 2, 1 to 2, 2 to 1, 2 to 3), 1)
        val flankAndTwoRumours = trapped(listOf(3 to 2, 1 to 2), 2)
        val threeRumours = trapped(emptyList(), 3)

        for ((name, s) in listOf(
            "包圍＋謠言×1" to encircleAndRumour,
            "夾擊＋謠言×2" to flankAndTwoRumours,
            "純謠言×3" to threeRumours
        )) {
            val unit = s.units.first { it.nationId == 0 }
            assertEquals("$name 應該進入混亂", ArmyUnit.MIN_MORALE, s.moraleOf(unit))
            assertTrue(name, s.isDisrupted(unit))
            assertNull("$name：混亂的部隊打不出去", Orders.attack(s, unit, s.map.index(3, 2)))
        }
    }

    @Test
    fun `morale is a read of the position, so it returns the moment the ring opens`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 2, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 3, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 1, 2, "INFANTRY", 1, "")
            )
        )
        val target = s.units.first { it.nationId == 0 }
        assertEquals(-1, s.moraleOf(target))

        // 拿掉其中一個，夾擊立刻解除 —— 不必等回合、不必慢慢爬回來。
        s.destroyUnit(s.units.first { it.nationId == 1 })
        assertEquals("包圍圈一鬆開，士氣立刻回來", 0, s.moraleOf(target))
    }

    @Test
    fun `standing unopposed in your own city lifts morale, but rumour cancels it`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        assertEquals("待在自己的城市裡且無人接觸", 1, s.moraleOf(unit))

        unit.rumour = 3
        assertEquals(
            "謠言會取消高昂，否則三次謠言在城裡就打不進混亂",
            ArmyUnit.MIN_MORALE,
            s.moraleOf(unit)
        )
    }

    @Test
    fun `rumour fades one stack per turn`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        unit.rumour = 3
        s.advanceToNextNation()
        s.advanceToNextNation()
        assertEquals("每回合散去一層", 2, unit.rumour)
    }

    // ------------------------------------------------------------------
    // 撤回
    // ------------------------------------------------------------------

    @Test
    fun `a move can be taken back while nothing else has happened`() {
        val s = session(listOf(ScenarioUnit("AAA", 1, 1, "INFANTRY", 1, "")))
        val unit = s.units.first()
        val record = Orders.snapshot(unit)
        val movesBefore = unit.movesLeft

        Orders.computeReachable(s, unit, ArrayList())
        Orders.move(s, unit, s.map.index(3, 1), ArrayList())
        assertTrue(unit.movesLeft < movesBefore)

        assertTrue(Orders.canUndo(s, record))
        assertTrue(Orders.undoMove(s, record))
        assertEquals("該回到原位", s.map.index(1, 1), unit.tile)
        assertEquals("移動點要還原", movesBefore, unit.movesLeft)
        assertEquals(unit, s.unitAt(s.map.index(1, 1), Domain.LAND))
        assertNull("新位置要清空", s.unitAt(s.map.index(3, 1), Domain.LAND))
    }

    @Test
    fun `a move cannot be taken back after the unit has fired`() {
        val s = session(
            listOf(
                ScenarioUnit("AAA", 1, 2, "INFANTRY", 1, ""),
                ScenarioUnit("BBB", 4, 2, "INFANTRY", 1, "")
            )
        )
        val unit = s.units.first { it.nationId == 0 }
        val record = Orders.snapshot(unit)
        Orders.computeReachable(s, unit, ArrayList())
        Orders.move(s, unit, s.map.index(3, 2), ArrayList())
        assertTrue(Orders.canUndo(s, record))

        Orders.attack(s, unit, s.map.index(4, 2))
        assertFalse("開過火就不能反悔了", Orders.canUndo(s, record))
        assertFalse(Orders.undoMove(s, record))
        assertEquals(s.map.index(3, 2), unit.tile)
    }

    @Test
    fun `save format survives a round trip through the parser`() {
        // SaveGame 需要 Context，這裡只驗外交關係的編解碼 —— 那是唯一自訂的編碼。
        val s = session()
        s.diplomacy.set(0, 1, Relation.ALLIED)
        val encoded = s.diplomacy.encode()
        val other = Diplomacy(2)
        other.decode(encoded)
        assertEquals(Relation.ALLIED, other.relation(0, 1))
        assertEquals(Relation.ALLIED, other.relation(1, 0))
    }

    @Test
    fun `truces block a fresh declaration until they expire`() {
        val diplomacy = Diplomacy(3)
        assertTrue(diplomacy.declareWar(0, 1))
        diplomacy.ceasefire(0, 1, 3)
        assertEquals(Relation.PEACE, diplomacy.relation(0, 1))
        assertFalse("停戰期內不得再宣戰", diplomacy.declareWar(0, 1))
        repeat(3) { diplomacy.tickTruces() }
        assertTrue(diplomacy.declareWar(0, 1))
    }
}
