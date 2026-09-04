// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.TestAssets
import io.github.acidefluorhydrique.mapconquer.ai.AiPlayer
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Domain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拿正式的地圖與劇本跑完整的回合，讓 AI 自己打。
 *
 * 這是整套測試裡最有價值的一支：它同時壓到地圖解析、劇本展開、補給、
 * 尋路、戰鬥、佔領、生產、研發、外交與存亡判定。單元測試能驗規則，
 * 只有把它們全部接起來跑，才驗得到「一百多個國家同時行動」時的狀態一致性。
 */
class ConquestSmokeTest {

    /** 每一步之後都必須成立的不變量。違反的話代表某條路徑漏了同步。 */
    private fun assertInvariants(session: Session, where: String) {
        val map = session.map
        val seen = HashMap<Int, Int>()

        for (unit in session.units) {
            assertTrue("$where: 死掉的部隊還留在場上", unit.isAlive)
            assertTrue("$where: 座標越界 ${unit.tile}", unit.tile in 0 until map.tileCount)
            assertTrue("$where: hp 越界 ${unit.hp}", unit.hp in 1..ArmyUnit.MAX_HP)
            assertTrue("$where: 補給越界 ${unit.supply}", unit.supply in 0..ArmyUnit.MAX_SUPPLY)
            assertTrue("$where: 等級越界 ${unit.level}", unit.level in 1..ArmyUnit.MAX_LEVEL)
            assertTrue("$where: 國家越界", unit.nationId in session.nations.indices)
            assertTrue(
                "$where: 築壕越界 ${unit.entrenchment}",
                unit.entrenchment in 0..Session.MAX_ENTRENCHMENT
            )

            assertTrue("$where: 謠言層數越界 ${unit.rumour}", unit.rumour in 0..ArmyUnit.MAX_RUMOUR)
            assertTrue(
                "$where: 士氣越界 ${session.moraleOf(unit)}",
                session.moraleOf(unit) in ArmyUnit.MIN_MORALE..ArmyUnit.MAX_MORALE
            )

            when (unit.kind.domain) {
                // 陸軍站在水上是合法的 —— 那是浮渡。但它必須佔海層，
                // 否則就會跟軍艦疊在同一格。
                Domain.LAND -> if (!unit.isLoaded && map.isWater(unit.tile)) {
                    assertEquals(
                        "$where: 浮渡的陸軍該佔海層",
                        Domain.SEA,
                        session.layerOf(unit)
                    )
                }
                Domain.SEA -> assertTrue("$where: 軍艦擱淺 ${unit.kind}", map.isWater(unit.tile))
                Domain.AIR -> Unit
            }

            if (unit.isLoaded) {
                val transport = session.unitById(unit.transportId)
                assertTrue("$where: 乘客的載具不見了", transport != null)
                assertTrue("$where: 載具沒把乘客記在身上", transport!!.cargo.contains(unit.id))
                assertEquals("$where: 乘客沒跟著載具", transport.tile, unit.tile)
            } else {
                // 同一層、同一格只能有一支。
                val key = session.layerOf(unit).ordinal * map.tileCount + unit.tile
                val previous = seen.put(key, unit.id)
                assertTrue("$where: 兩支部隊疊在同一格 $previous / ${unit.id}", previous == null)
                assertEquals(
                    "$where: 佔位表跟部隊對不上",
                    unit,
                    session.unitAt(unit.tile, session.layerOf(unit))
                )
            }
        }

        for (owner in session.provinceOwner) {
            assertTrue("$where: 省份主人越界 $owner", owner == -1 || owner in session.nations.indices)
        }
    }

    /** 把一整個世界回合跑完（包含玩家那一國，玩家什麼也不做）。 */
    private fun playOneTurn(session: Session) {
        val startTurn = session.turn
        var guard = 0
        while (session.turn == startTurn && session.status == SessionStatus.PLAYING) {
            val ai = AiPlayer(session, session.activeNationId)
            var steps = 0
            while (ai.step() && steps < 4000) steps++
            assertTrue("AI 沒有在合理步數內結束回合", steps < 4000)
            session.advanceToNextNation()
            guard++
            assertTrue("回合沒有前進", guard <= session.nations.size + 2)
        }
    }

    @Test
    fun `the 1939 world runs for several full turns`() {
        val scenario = TestAssets.scenario("conquest_1939")
        val map = TestAssets.map(scenario.mapId)
        val session = Session(map, scenario, Difficulty.OFFICER, "USA", 20260828L)

        assertTrue("國家太少", session.nations.size > 40)
        assertTrue("開局部隊太少", session.units.size > 50)
        assertEquals("USA", session.nations[session.playerNationId].code)
        assertInvariants(session, "turn 0")

        repeat(4) {
            playOneTurn(session)
            assertInvariants(session, "turn ${session.turn}")
        }
        assertTrue("回合沒有推進", session.turn >= 5)
        assertTrue("四回合就有人統一世界，平衡出了問題", session.status == SessionStatus.PLAYING)
    }

    /**
     * 旁觀國不動。它們有城市、有守軍，但沒有陣營也沒有交戰對象 ——
     * 整局下來不該多出一支部隊，也不該少掉一塊地。
     */
    @Test
    fun `bystanders neither build nor lose ground`() {
        val scenario = TestAssets.scenario("campaign_ww2_01_poland")
        val map = TestAssets.map(scenario.mapId)
        val session = Session(map, scenario, Difficulty.MARSHAL, "POL", 7L)

        val quiet = session.nations.filter { session.isBystander(it.id) }
        assertTrue("這一關應該有旁觀國", quiet.isNotEmpty())
        val before = quiet.associate {
            it.id to Pair(session.unitsOf(it.id).size, session.provincesOf(it.id))
        }

        assertInvariants(session, "start")
        repeat(4) {
            playOneTurn(session)
            assertInvariants(session, "turn ${session.turn}")
        }

        for (nation in quiet) {
            val (units, provinces) = before.getValue(nation.id)
            assertEquals(
                "${nation.code}: 中立國多造了兵",
                units, session.unitsOf(nation.id).size
            )
            assertEquals(
                "${nation.code}: 中立國掉了省份，有人在沒宣戰的情況下打它",
                provinces, session.provincesOf(nation.id)
            )
        }
    }

    @Test
    fun `every campaign mission is playable and starts undecided`() {
        for (id in TestAssets.scenarioIds().filter { it.startsWith("campaign") }) {
            val scenario = TestAssets.scenario(id)
            val map = TestAssets.map(scenario.mapId)
            val player = scenario.playable.first()
            val session = Session(map, scenario, Difficulty.OFFICER, player, 99L)

            assertEquals("$id: 玩家陣營沒選中", player, session.nations[session.playerNationId].code)
            assertEquals("$id: 開局就分出勝負了", SessionStatus.PLAYING, session.status)
            assertTrue("$id: 玩家開局沒有部隊", session.unitsOf(session.playerNationId).isNotEmpty())
            assertTrue("$id: 玩家開局沒有省份", session.provincesOf(session.playerNationId) > 0)
            assertInvariants(session, "$id start")

            repeat(3) {
                playOneTurn(session)
                assertInvariants(session, "$id turn ${session.turn}")
            }
        }
    }

    @Test
    fun `the AI actually does something with its turn`() {
        val scenario = TestAssets.scenario("campaign_ww2_03_desert")
        val map = TestAssets.map(scenario.mapId)
        val session = Session(map, scenario, Difficulty.OFFICER, "GBR", 5L)

        val enemy = session.nationByCode("DEU")!!
        val before = session.unitsOf(enemy.id).map { it.tile }.toSet()
        val fundsBefore = enemy.funds

        repeat(6) { playOneTurn(session) }

        val after = session.unitsOf(enemy.id).map { it.tile }.toSet()
        val moved = after != before
        val built = session.unitsOf(enemy.id).size > before.size
        val spent = enemy.funds != fundsBefore
        assertTrue("AI 六回合下來完全沒有動作", moved || built || spent)
    }

    @Test
    fun `supply reaches a nation's own cities and runs out far away`() {
        val scenario = TestAssets.scenario("conquest_1939")
        val map = TestAssets.map(scenario.mapId)
        val session = Session(map, scenario, Difficulty.OFFICER, "USA", 3L)

        // 回合開始時算的是當前行動國（開局是列表第一國）的補給覆蓋，
        // 所以這裡直接檢查它的城市有補給、而它管不到的遠方沒有。
        val active = session.activeNation
        val ownCities = map.provinces.filter {
            session.provinceOwner[it.id] == active.id && it.hasCity
        }
        assertTrue("行動國沒有城市可驗", ownCities.isNotEmpty())
        for (province in ownCities) {
            assertTrue(
                "自己的城市 ${province.nameKey} 竟然沒有補給",
                session.isSupplied(province.capitalTile)
            )
        }
        val supplied = (0 until map.tileCount).count { session.isSupplied(it) }
        assertTrue("補給覆蓋整張地圖，範圍限制沒有生效", supplied < map.tileCount / 2)
    }
}
