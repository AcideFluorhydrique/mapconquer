// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.TestAssets
import io.github.acidefluorhydrique.mapconquer.units.UnitKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 劇本檔與地圖檔之間的交叉驗證。
 *
 * 這些都是產生器產出的資料，最容易壞在「省份 id 對不上地圖」這種地方 ——
 * 而那種錯誤在遊戲裡的表現是靜悄悄地把一個國家生在海裡，很難靠玩發現。
 */
class ScenarioAssetTest {

    @Test
    fun `every shipped scenario parses and points at a real map`() {
        val ids = TestAssets.scenarioIds()
        assertTrue("assets/scenarios 是空的", ids.isNotEmpty())
        assertTrue("至少要有一個征服劇本", ids.any { it.startsWith("conquest") })
        assertTrue("至少要有一個戰役關卡", ids.any { it.startsWith("campaign") })

        for (id in ids) {
            val scenario = TestAssets.scenario(id)
            assertEquals(id, scenario.id)
            assertTrue("$id: 沒有國家", scenario.nations.isNotEmpty())
            assertTrue("$id: 地圖不存在", TestAssets.mapIds().contains(scenario.mapId))
        }
    }

    @Test
    fun `owners, capitals and units all land inside the map`() {
        for (id in TestAssets.scenarioIds()) {
            val scenario = TestAssets.scenario(id)
            val map = TestAssets.cachedMap(scenario.mapId)
            val codes = scenario.nations.map { it.code }.toSet()

            for (nation in scenario.nations) {
                assertTrue(
                    "$id/${nation.code}: 首都省份 ${nation.capitalProvince} 越界",
                    nation.capitalProvince in map.provinces.indices
                )
                assertEquals("$id/${nation.code}: 科技必須六項", 6, nation.tech.size)
                assertTrue("$id/${nation.code}: 開局資金", nation.funds > 0)
            }
            assertEquals("$id: 國家代碼重複", codes.size, scenario.nations.size)

            val claimed = HashSet<Int>()
            for ((code, provinces) in scenario.ownership) {
                assertTrue("$id: 未知國家 $code", codes.contains(code))
                for (pid in provinces) {
                    assertTrue("$id/$code: 省份 $pid 越界", pid in map.provinces.indices)
                    assertTrue("$id: 省份 $pid 被兩個國家宣稱", claimed.add(pid))
                }
            }

            for (unit in scenario.startingUnits) {
                assertTrue("$id: 未知國家 ${unit.nationCode}", codes.contains(unit.nationCode))
                assertNotNull("$id: 未知兵種 ${unit.kindName}", UnitKind.byName(unit.kindName))
                assertTrue(
                    "$id: 部隊座標 ${unit.col},${unit.row} 越界",
                    map.inBounds(unit.col, unit.row)
                )
                val kind = UnitKind.byName(unit.kindName)!!
                val tile = map.index(unit.col, unit.row)
                if (kind.isNaval) {
                    assertTrue("$id: 軍艦被放在陸地上 ${unit.col},${unit.row}", map.isWater(tile))
                } else {
                    assertTrue("$id: 陸軍被放在水上 ${unit.col},${unit.row}", map.isLand(tile))
                }
            }

            for (code in scenario.playable) {
                assertTrue("$id: 可選國家 $code 不存在", codes.contains(code))
            }
        }
    }

    /**
     * 陣營是關係表的來源，不是關係表的註解。
     *
     * 交戰關係由陣營推導出來（見 tools/genworld.py 的 bloc_wars），所以這裡
     * 反過來驗：同陣營之間不該有任何 WAR，不同陣營之間每一對都要有。
     * 手寫關係表最典型的漏就是「對法國宣戰了但忘了對比利時宣戰」。
     */
    @Test
    fun `blocs and declared wars agree with each other`() {
        for (id in TestAssets.scenarioIds()) {
            val scenario = TestAssets.scenario(id)
            val bloc = scenario.nations.associate { it.code to it.bloc }
            val atWar = HashSet<String>()
            for ((a, b, relation) in scenario.relations) {
                if (relation != Relation.WAR) continue
                atWar.add(if (a < b) "$a|$b" else "$b|$a")
                val blocA = bloc[a].orEmpty()
                val blocB = bloc[b].orEmpty()
                assertTrue(
                    "$id: $a 與 $b 同屬 $blocA 卻在交戰",
                    blocA.isEmpty() || blocA != blocB
                )
            }
            val aligned = scenario.nations.filter { it.bloc.isNotEmpty() }
            for (x in aligned) {
                for (y in aligned) {
                    if (x.code >= y.code || x.bloc == y.bloc) continue
                    // 雙方都在第 1 回合參戰才該開局就在打；有一方晚參戰就該是和平。
                    val fromStart = x.warTurn <= 1 && y.warTurn <= 1
                    assertEquals(
                        "$id: ${x.code}(${x.bloc}, 第 ${x.warTurn} 回合) 與 " +
                            "${y.code}(${y.bloc}, 第 ${y.warTurn} 回合) 的開局關係不對",
                        fromStart, atWar.contains("${x.code}|${y.code}")
                    )
                }
            }
        }
    }

    @Test
    fun `campaign missions declare objectives and a turn limit`() {
        for (id in TestAssets.scenarioIds().filter { it.startsWith("campaign") }) {
            val scenario = TestAssets.scenario(id)
            assertEquals("$id: 應該是戰役模式", GameMode.CAMPAIGN, scenario.mode)
            assertTrue("$id: 戰役必須有目標", scenario.objectives.isNotEmpty())
            assertTrue("$id: 戰役必須有回合上限", scenario.turnLimit > 0)
            assertEquals("$id: 星等門檻要有兩個", 2, scenario.starTurns.size)
            assertTrue("$id: 三星門檻應嚴於二星", scenario.starTurns[0] < scenario.starTurns[1])
            assertEquals("$id: 戰役只給一個可選陣營", 1, scenario.playable.size)

            val map = TestAssets.cachedMap(scenario.mapId)
            for (objective in scenario.objectives) {
                for (pid in objective.provinces) {
                    assertTrue("$id: 目標省份 $pid 越界", pid in map.provinces.indices)
                }
            }
            // 目標不能是自己開局就持有的省份，否則第一回合就通關了。
            // orEmpty() 沒有 IntArray? 的多載，所以老實地展開。
            val owned = scenario.ownership[scenario.playable.first()]?.toSet() ?: emptySet()
            for (objective in scenario.objectives) {
                if (objective.type != ObjectiveType.CAPTURE_PROVINCES) continue
                for (pid in objective.provinces) {
                    assertTrue("$id: 目標省份 $pid 開局就是自己的", !owned.contains(pid))
                }
            }
        }
    }

    @Test
    fun `star thresholds are reachable within the turn limit`() {
        for (id in TestAssets.scenarioIds().filter { it.startsWith("campaign") }) {
            val scenario = TestAssets.scenario(id)
            assertTrue("$id: 二星門檻超過回合上限", scenario.starTurns[1] <= scenario.turnLimit)
            assertEquals(3, scenario.starsFor(scenario.starTurns[0]))
            assertEquals(2, scenario.starsFor(scenario.starTurns[1]))
            assertEquals(1, scenario.starsFor(scenario.turnLimit))
        }
    }

    @Test
    fun `range syntax expands the way the files assume`() {
        assertTrue(ScenarioLoader.expandRanges("").isEmpty())
        assertEquals(listOf(3), ScenarioLoader.expandRanges("3").toList())
        assertEquals(listOf(1, 2, 3, 7, 9, 10), ScenarioLoader.expandRanges("1-3,7,9-10").toList())
        assertEquals(listOf(5), ScenarioLoader.expandRanges(" 5 , x ").toList())
    }
}
