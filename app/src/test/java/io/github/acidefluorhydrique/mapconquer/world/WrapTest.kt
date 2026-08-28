// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

import io.github.acidefluorhydrique.mapconquer.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 東西環繞。
 *
 * 接縫是最容易靜默壞掉的地方：鄰接算錯只會讓艦隊在太平洋中間停住，
 * 距離算錯會讓 AI 覺得堪察加到阿拉斯加要橫跨整個歐亞大陸 ——
 * 兩者都不會拋例外，只會讓遊戲變得莫名其妙。
 */
class WrapTest {

    private fun cylinder(cols: Int, rows: Int) = WorldMap(
        id = "cylinder",
        cols = cols,
        rows = rows,
        terrain = ByteArray(cols * rows) { Terrain.PLAIN.ordinal.toByte() },
        provinceOf = IntArray(cols * rows) { -1 },
        provinces = emptyList(),
        wrapX = true
    )

    private fun flat(cols: Int, rows: Int) = WorldMap(
        "flat", cols, rows,
        ByteArray(cols * rows) { Terrain.PLAIN.ordinal.toByte() },
        IntArray(cols * rows) { -1 }, emptyList(), wrapX = false
    )

    @Test
    fun `only the world map wraps`() {
        assertTrue("世界地圖應該環繞", TestAssets.cachedMap("world").wrapX)
        for (id in TestAssets.mapIds().filter { it != "world" }) {
            assertFalse("$id 是區域地圖，不該環繞", TestAssets.cachedMap(id).wrapX)
        }
    }

    @Test
    fun `the seam columns are neighbours`() {
        val map = cylinder(20, 10)
        val buf = IntArray(6)
        val west = map.index(0, 4)
        val east = map.index(19, 4)

        val n = map.neighbours(west, buf)
        var found = false
        for (i in 0 until n) if (buf[i] == east) found = true
        assertTrue("第 0 欄的西鄰應該是最後一欄", found)
        assertEquals("跨接縫的距離應該是 1", 1, map.distance(west, east))
    }

    @Test
    fun `a flat map has no seam`() {
        val map = flat(20, 10)
        val buf = IntArray(6)
        val n = map.neighbours(map.index(0, 4), buf)
        for (i in 0 until n) {
            assertTrue("平面地圖不該繞到另一端", map.colOf(buf[i]) <= 1)
        }
        assertEquals(19, map.distance(map.index(0, 4), map.index(19, 4)))
    }

    @Test
    fun `distance takes the short way round`() {
        val map = cylinder(96, 60)
        // 第 2 欄往西四步就到第 94 欄，繞另一邊要 92 步。
        assertEquals(4, map.distance(map.index(2, 20), map.index(94, 20)))
        // 半圈是最遠的距離，再遠就從另一邊繞回來了。
        val half = map.distance(map.index(0, 20), map.index(48, 20))
        for (col in 0 until 96) {
            assertTrue(
                "沒有任何一對格子該比半圈更遠 (col=$col)",
                map.distance(map.index(0, 20), map.index(col, 20)) <= half
            )
        }
    }

    @Test
    fun `neighbours stay symmetric across the seam`() {
        val map = cylinder(24, 12)
        val mine = IntArray(6)
        val theirs = IntArray(6)
        for (tile in 0 until map.tileCount) {
            val n = map.neighbours(tile, mine)
            assertTrue("環繞之後每一格都該有鄰居", n in 2..6)
            for (i in 0 until n) {
                val back = map.neighbours(mine[i], theirs)
                var found = false
                for (j in 0 until back) if (theirs[j] == tile) found = true
                assertTrue("$tile -> ${mine[i]} 不對稱", found)
            }
        }
    }

    @Test
    fun `a fleet can sail right around the world`() {
        val map = cylinder(40, 9)
        val finder = Pathfinder(map)
        val rules = object : MoveRules {
            override fun enterCost(from: Int, to: Int) = 1
        }
        val start = map.index(0, 4)
        // 往西走十步，應該落在第 30 欄而不是撞在地圖邊上。
        finder.explore(start, 10, rules)
        assertTrue(finder.isReachable(map.index(30, 4)))
        assertEquals(10, finder.costTo(map.index(30, 4)))

        val path = ArrayList<Int>()
        finder.buildPath(map.index(30, 4), path)
        assertEquals(11, path.size)
        for (i in 0 until path.size - 1) {
            assertEquals("每一步都該是相鄰格", 1, map.distance(path[i], path[i + 1]))
        }
    }

    @Test
    fun `wrapping does not change the world map's province structure`() {
        val world = TestAssets.cachedMap("world")
        for (province in world.provinces) {
            assertTrue(province.tiles.isNotEmpty())
            for (n in province.neighbours) {
                assertTrue(
                    "接縫兩側的鄰接也要對稱",
                    world.provinces[n].neighbours.contains(province.id)
                )
            }
        }
    }
}
