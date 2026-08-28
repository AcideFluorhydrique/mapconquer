// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

import io.github.acidefluorhydrique.mapconquer.TestAssets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 直接拿正式的 assets 來驗。
 *
 * 地圖是工具產生的，最怕的就是「產生器改了、遊戲端沒跟上」——
 * 這組測試把地圖檔與 [MapLoader] 的約定釘住：只要任一邊變了而另一邊沒變，
 * CI 就會紅。
 */
class MapAssetTest {

    @Test
    fun `every shipped map parses`() {
        val ids = TestAssets.mapIds()
        assertTrue("assets/maps 是空的", ids.isNotEmpty())
        for (id in ids) {
            val map = TestAssets.cachedMap(id)
            assertEquals(id, map.id)
            assertTrue("$id: 尺寸不合理", map.cols > 8 && map.rows > 8)
            assertEquals("$id: 地形陣列長度", map.tileCount, map.terrain.size)
            assertEquals("$id: 省份陣列長度", map.tileCount, map.provinceOf.size)
            assertTrue("$id: 沒有省份", map.provinces.isNotEmpty())
        }
    }

    @Test
    fun `neighbour relation is symmetric and never leaves the grid`() {
        for (id in TestAssets.mapIds()) {
            val map = TestAssets.cachedMap(id)
            val mine = IntArray(6)
            val theirs = IntArray(6)
            for (tile in 0 until map.tileCount) {
                val count = map.neighbours(tile, mine)
                assertTrue("$id: 鄰居數 $count", count in 2..6)
                for (i in 0 until count) {
                    val other = mine[i]
                    assertTrue("$id: 鄰居越界", other in 0 until map.tileCount)
                    val back = map.neighbours(other, theirs)
                    var found = false
                    for (j in 0 until back) if (theirs[j] == tile) found = true
                    assertTrue("$id: $tile -> $other 不對稱", found)
                }
                // 鄰居一定恰好一格遠。
                for (i in 0 until count) {
                    assertEquals("$id: 距離不是 1", 1, map.distance(tile, mine[i]))
                }
            }
        }
    }

    @Test
    fun `provinces are land, self consistent and hold their capital`() {
        for (id in TestAssets.mapIds()) {
            val map = TestAssets.cachedMap(id)
            for (province in map.provinces) {
                assertTrue("$id/${province.nameKey}: 沒有任何格子", province.tiles.isNotEmpty())
                assertTrue(
                    "$id/${province.nameKey}: 省會不在自己的省裡",
                    map.provinceOf[province.capitalTile] == province.id
                )
                assertTrue(
                    "$id/${province.nameKey}: 省會在水上",
                    map.isLand(province.capitalTile)
                )
                for (tile in province.tiles) {
                    assertEquals("$id: 逐格省份對不上", province.id, map.provinceOf[tile])
                    assertTrue("$id/${province.nameKey}: 省份含水格", map.isLand(tile))
                }
                assertFalse("$id: 省份把自己列成鄰居", province.neighbours.contains(province.id))
                for (n in province.neighbours) {
                    assertTrue("$id: 鄰省 id 越界", n in map.provinces.indices)
                    assertTrue(
                        "$id: 鄰接不對稱 ${province.id} <-> $n",
                        map.provinces[n].neighbours.contains(province.id)
                    )
                }
            }
        }
    }

    @Test
    fun `every land tile belongs to a province and every water tile does not`() {
        for (id in TestAssets.mapIds()) {
            val map = TestAssets.cachedMap(id)
            for (tile in 0 until map.tileCount) {
                if (map.isLand(tile)) {
                    assertTrue("$id: 陸地無主 tile=$tile", map.provinceOf[tile] >= 0)
                } else {
                    assertEquals("$id: 水域被劃進省份 tile=$tile", -1, map.provinceOf[tile])
                }
            }
        }
    }

    @Test
    fun `coastal flag matches the terrain`() {
        for (id in TestAssets.mapIds()) {
            val map = TestAssets.cachedMap(id)
            val buf = IntArray(6)
            for (province in map.provinces) {
                val touchesWater = province.tiles.any { map.isCoastal(it, buf) }
                assertEquals("$id/${province.nameKey}: coastal 旗標不符", touchesWater, province.coastal)
            }
        }
    }

    @Test
    fun `the world map is recognisably a world`() {
        val world = TestAssets.cachedMap("world")
        val land = (0 until world.tileCount).count { world.isLand(it) }
        val ratio = land * 100 / world.tileCount
        // 排除南極與高緯度之後，陸地大約佔三成。這是防止產生器徹底壞掉的護欄。
        assertTrue("陸地比例 $ratio%% 不合理", ratio in 20..45)
        assertTrue("城市太少", world.provinces.count { it.hasCity } > 100)
        assertTrue("首都級城市太少", world.provinces.count { it.cityTier >= 4 } >= 8)
    }
}
