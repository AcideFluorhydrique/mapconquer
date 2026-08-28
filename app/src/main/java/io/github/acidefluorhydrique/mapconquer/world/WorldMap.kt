// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

import io.github.acidefluorhydrique.mapconquer.hex.Hex
import io.github.acidefluorhydrique.mapconquer.hex.HexMath

/**
 * 一張地圖的靜態資料：地形、省界、省份表。
 *
 * 這裡不存任何會隨戰局改變的東西（誰佔了哪、單位在哪、戰爭迷霧）。
 * 那些全部在 [io.github.acidefluorhydrique.mapconquer.game.Session]，
 * 因為同一張地圖會被多個劇本共用 —— 世界地圖只讀進記憶體一次，
 * 1939、1943、1950 三個劇本共享同一份地形陣列。
 *
 * 格子一律用「陣列索引」定址（index = row * cols + col），
 * 而不是傳 Hex 物件：尋路與 AI 每回合會走過幾十萬個格子，
 * 在那條路徑上配置物件是負擔不起的。
 */
class WorldMap(
    val id: String,
    val cols: Int,
    val rows: Int,
    /** 每格的 [Terrain.ordinal]。 */
    val terrain: ByteArray,
    /** 每格所屬省份 id；-1 代表不屬於任何省（大洋）。 */
    val provinceOf: IntArray,
    val provinces: List<Province>
) {

    val tileCount: Int get() = cols * rows

    fun index(col: Int, row: Int): Int = row * cols + col

    fun colOf(index: Int): Int = index % cols

    fun rowOf(index: Int): Int = index / cols

    fun inBounds(col: Int, row: Int): Boolean = col in 0 until cols && row in 0 until rows

    fun terrainAt(index: Int): Terrain = Terrain.ALL[terrain[index].toInt()]

    fun isLand(index: Int): Boolean = terrainAt(index).isLand

    fun isWater(index: Int): Boolean = terrainAt(index).isWater

    fun provinceAt(index: Int): Province? {
        val p = provinceOf[index]
        return if (p >= 0) provinces[p] else null
    }

    fun hexOf(index: Int): Hex = HexMath.ofOffset(colOf(index), rowOf(index))

    fun distance(a: Int, b: Int): Int = HexMath.distance(hexOf(a), hexOf(b))

    /**
     * 把 [index] 的六個鄰居寫進 [out]（長度必須 >= 6），回傳實際數量。
     * 地圖邊緣的格子鄰居會少於六個。
     *
     * 順序與 [HexMath.DIRECTIONS] 一致（東、東北、西北、西、西南、東南），
     * 有些呼叫端（省界描邊）依賴這個順序。
     */
    fun neighbours(index: Int, out: IntArray): Int {
        val col = index % cols
        val row = index / cols
        val odd = row and 1
        var n = 0
        // 東
        if (col + 1 < cols) out[n++] = index + 1
        // 東北
        run {
            val c = if (odd == 1) col + 1 else col
            val r = row - 1
            if (r >= 0 && c in 0 until cols) out[n++] = r * cols + c
        }
        // 西北
        run {
            val c = if (odd == 1) col else col - 1
            val r = row - 1
            if (r >= 0 && c in 0 until cols) out[n++] = r * cols + c
        }
        // 西
        if (col - 1 >= 0) out[n++] = index - 1
        // 西南
        run {
            val c = if (odd == 1) col else col - 1
            val r = row + 1
            if (r < rows && c in 0 until cols) out[n++] = r * cols + c
        }
        // 東南
        run {
            val c = if (odd == 1) col + 1 else col
            val r = row + 1
            if (r < rows && c in 0 until cols) out[n++] = r * cols + c
        }
        return n
    }

    /** 鄰居中有沒有水域 —— 判斷「臨海」用。只在載入時跑，配置一個小陣列不心疼。 */
    fun isCoastal(index: Int, buf: IntArray = IntArray(6)): Boolean {
        val n = neighbours(index, buf)
        for (i in 0 until n) if (isWater(buf[i])) return true
        return false
    }

    /** 半徑 [radius] 內的所有合法格，寫進 [out]。 */
    fun collectWithin(index: Int, radius: Int, out: MutableList<Int>) {
        out.clear()
        val center = hexOf(index)
        val buf = ArrayList<Hex>(3 * radius * (radius + 1) + 1)
        HexMath.spiral(center, radius, buf)
        for (h in buf) {
            if (inBounds(h.col, h.row)) out.add(index(h.col, h.row))
        }
    }
}
