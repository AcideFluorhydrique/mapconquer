// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PathfinderTest {

    /** 一張全是平原的空地圖，用來單獨檢驗圖論部分。 */
    private fun flatMap(cols: Int, rows: Int): WorldMap = WorldMap(
        id = "flat",
        cols = cols,
        rows = rows,
        terrain = ByteArray(cols * rows) { Terrain.PLAIN.ordinal.toByte() },
        provinceOf = IntArray(cols * rows) { -1 },
        provinces = emptyList()
    )

    private fun uniformRules(cost: Int = 1) = object : MoveRules {
        override fun enterCost(from: Int, to: Int) = cost
    }

    @Test
    fun `reachable set on open ground is the hex disc`() {
        val map = flatMap(21, 21)
        val finder = Pathfinder(map)
        val start = map.index(10, 10)
        val budget = 4
        finder.explore(start, budget, uniformRules())

        for (tile in 0 until map.tileCount) {
            val distance = map.distance(start, tile)
            if (distance <= budget) {
                assertEquals("cost to $tile", distance, finder.costTo(tile))
            } else {
                assertFalse("$tile should be out of range", finder.isReachable(tile))
            }
        }
    }

    @Test
    fun `path is contiguous and ends where asked`() {
        val map = flatMap(21, 21)
        val finder = Pathfinder(map)
        val start = map.index(3, 4)
        val target = map.index(9, 11)
        val path = ArrayList<Int>()
        val cost = finder.routeTo(start, target, uniformRules(), path)

        assertEquals(map.distance(start, target), cost)
        assertEquals(start, path.first())
        assertEquals(target, path.last())
        for (i in 0 until path.size - 1) {
            assertEquals("step $i", 1, map.distance(path[i], path[i + 1]))
        }
    }

    @Test
    fun `an expensive wall is walked around, not through`() {
        // 一整排高成本的格子橫在中間，只有第 0 欄留了缺口。
        val cols = 15
        val rows = 9
        val map = flatMap(cols, rows)
        val finder = Pathfinder(map)
        val wallRow = 4
        val gate = map.index(0, wallRow)
        val rules = object : MoveRules {
            override fun enterCost(from: Int, to: Int): Int =
                if (map.rowOf(to) == wallRow && to != gate) 60 else 1
        }

        val start = map.index(7, 1)
        val target = map.index(7, 7)
        val path = ArrayList<Int>()
        val cost = finder.routeTo(start, target, rules, path)

        assertTrue("target unreachable", cost > 0)
        val crossings = path.filter { map.rowOf(it) == wallRow }
        assertEquals("應該只穿過缺口一次", 1, crossings.size)
        assertEquals("而且必須是那個缺口", gate, crossings.first())
        assertTrue("繞路的總成本應該遠低於硬闖", cost < 60)
    }

    @Test
    fun `impassable tiles are never entered`() {
        val map = flatMap(11, 11)
        val finder = Pathfinder(map)
        val blocked = map.index(5, 4)
        val rules = object : MoveRules {
            override fun enterCost(from: Int, to: Int) = if (to == blocked) -1 else 1
        }
        finder.explore(map.index(5, 5), 6, rules)
        assertFalse(finder.isReachable(blocked))
    }

    @Test
    fun `zone of control stops movement without blocking the tile itself`() {
        // 一條寬一格的走廊，中間那格是控制區：不進去到不了另一頭。
        val cols = 11
        val rows = 3
        val map = flatMap(cols, rows)
        val finder = Pathfinder(map)
        val corridorRow = 1
        val gate = map.index(5, corridorRow)
        val rules = object : MoveRules {
            override fun enterCost(from: Int, to: Int): Int =
                if (map.rowOf(to) == corridorRow) 1 else -1

            override fun stopsAt(tile: Int) = tile == gate
        }

        val start = map.index(2, corridorRow)
        finder.explore(start, 8, rules)

        assertTrue("控制區那一格本身應該站得上去", finder.isReachable(gate))
        assertEquals(3, finder.costTo(gate))
        assertFalse("不該穿過控制區繼續前進", finder.isReachable(map.index(6, corridorRow)))
        assertFalse(finder.isReachable(map.index(8, corridorRow)))
        assertTrue("控制區之前的格子不受影響", finder.isReachable(map.index(4, corridorRow)))
    }

    @Test
    fun `repeated searches do not leak state into each other`() {
        val map = flatMap(15, 15)
        val finder = Pathfinder(map)
        finder.explore(map.index(1, 1), 10, uniformRules())
        val far = map.index(12, 12)
        assertFalse(finder.isReachable(far))

        finder.explore(map.index(12, 12), 2, uniformRules())
        assertTrue(finder.isReachable(far))
        assertFalse("上一次搜尋的結果不該殘留", finder.isReachable(map.index(1, 1)))
    }
}
