// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.hex

import kotlin.math.abs
import kotlin.math.max

/**
 * 六角網格座標。
 *
 * 本專案同時用兩套座標，用途分得很清楚：
 *
 *  - **軸向座標 (q, r)**：所有數學都在這裡做 —— 距離、鄰居、環、視線。
 *    六角格的距離在方格座標下沒有簡潔解，換成軸向就是三行 cube 運算。
 *  - **奇數列偏移 (col, row)**：只用於「地圖是一個矩形陣列」這件事。
 *    存檔、地圖檔、繪製剔除全部走它，因為它可以直接當陣列索引。
 *
 * 版面是**尖頂（pointy-top）**：格子上下各有一個頂點，左右是平邊。
 * 這個選擇是刻意的 —— 手機橫向時寬度遠大於高度，尖頂讓同樣的螢幕
 * 塞得下更多「橫向」的格子，世界地圖的東西向延伸看起來才不會被壓扁。
 */
data class Hex(val q: Int, val r: Int) {

    /** cube 座標的第三軸；三軸恆滿足 x + y + z = 0。 */
    val s: Int get() = -q - r

    operator fun plus(other: Hex) = Hex(q + other.q, r + other.r)
    operator fun minus(other: Hex) = Hex(q - other.q, r - other.r)

    fun distanceTo(other: Hex): Int = HexMath.distance(this, other)

    fun neighbor(direction: Int): Hex {
        val d = HexMath.DIRECTIONS[((direction % 6) + 6) % 6]
        return Hex(q + d.q, r + d.r)
    }

    /** 轉成奇數列偏移的欄。 */
    val col: Int get() = q + ((r - (r and 1)) shr 1)

    /** 轉成奇數列偏移的列。 */
    val row: Int get() = r

    override fun toString(): String = "$col,$row"
}

object HexMath {

    /**
     * 六個方向，順序固定為：東、東北、西北、西、西南、東南。
     * 很多地方（扇形射界、包圍判定、AI 展開）依賴這個順序是繞著走的。
     */
    val DIRECTIONS = arrayOf(
        Hex(1, 0), Hex(1, -1), Hex(0, -1),
        Hex(-1, 0), Hex(-1, 1), Hex(0, 1)
    )

    fun distance(a: Hex, b: Hex): Int {
        val dq = a.q - b.q
        val dr = a.r - b.r
        val ds = a.s - b.s
        return max(abs(dq), max(abs(dr), abs(ds)))
    }

    fun ofOffset(col: Int, row: Int): Hex = Hex(col - ((row - (row and 1)) shr 1), row)

    /** 圓心為 [center]、半徑恰為 [radius] 的一圈格子。radius = 0 時回傳圓心本身。 */
    fun ring(center: Hex, radius: Int, out: MutableList<Hex>) {
        out.clear()
        if (radius <= 0) {
            out.add(center)
            return
        }
        // 從「西南方向走 radius 步」的角落出發，再沿六個方向各走 radius 步繞一圈。
        var current = Hex(center.q + DIRECTIONS[4].q * radius, center.r + DIRECTIONS[4].r * radius)
        for (dir in 0 until 6) {
            for (step in 0 until radius) {
                out.add(current)
                current = current.neighbor(dir)
            }
        }
    }

    /** 半徑 [radius] 以內的所有格子（含圓心）。 */
    fun spiral(center: Hex, radius: Int, out: MutableList<Hex>) {
        out.clear()
        out.add(center)
        val ringBuffer = ArrayList<Hex>(6 * max(radius, 1))
        for (k in 1..radius) {
            ring(center, k, ringBuffer)
            out.addAll(ringBuffer)
        }
    }

    /**
     * 兩格之間的直線（含頭尾），用於射界與視線遮蔽。
     *
     * 內插後要 round 回整數格；直接對 q、r 各自四捨五入會在邊界上跑掉，
     * 必須用 cube round —— 把三軸都捨入後，丟掉誤差最大的那一軸重算。
     */
    fun line(a: Hex, b: Hex, out: MutableList<Hex>) {
        out.clear()
        val n = distance(a, b)
        if (n == 0) {
            out.add(a)
            return
        }
        val step = 1.0 / n
        for (i in 0..n) {
            val t = step * i
            val q = a.q + (b.q - a.q) * t
            val r = a.r + (b.r - a.r) * t
            out.add(round(q, r))
        }
    }

    fun round(qf: Double, rf: Double): Hex {
        val sf = -qf - rf
        var q = Math.round(qf).toInt()
        var r = Math.round(rf).toInt()
        val s = Math.round(sf).toInt()
        val dq = abs(q - qf)
        val dr = abs(r - rf)
        val ds = abs(s - sf)
        if (dq > dr && dq > ds) q = -r - s else if (dr > ds) r = -q - s
        return Hex(q, r)
    }
}
