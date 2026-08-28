// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.hex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexMathTest {

    @Test
    fun `offset and axial round trip`() {
        for (row in -8..40) {
            for (col in -8..40) {
                val hex = HexMath.ofOffset(col, row)
                assertEquals("col at ($col,$row)", col, hex.col)
                assertEquals("row at ($col,$row)", row, hex.row)
            }
        }
    }

    @Test
    fun `every neighbour is exactly one step away`() {
        val centre = Hex(3, -2)
        for (direction in 0 until 6) {
            assertEquals(1, centre.distanceTo(centre.neighbor(direction)))
        }
        // 六個鄰居必須互不相同。
        val seen = (0 until 6).map { centre.neighbor(it) }.toSet()
        assertEquals(6, seen.size)
    }

    @Test
    fun `distance is symmetric and obeys the triangle inequality`() {
        val points = listOf(Hex(0, 0), Hex(4, -2), Hex(-3, 5), Hex(7, 7), Hex(-6, -1))
        for (a in points) {
            for (b in points) {
                assertEquals(a.distanceTo(b), b.distanceTo(a))
                for (c in points) {
                    assertTrue(
                        "triangle inequality $a $b $c",
                        a.distanceTo(c) <= a.distanceTo(b) + b.distanceTo(c)
                    )
                }
            }
        }
    }

    @Test
    fun `ring has six times the radius, spiral is the closed disc`() {
        val out = ArrayList<Hex>()
        val centre = Hex(2, 3)
        for (radius in 1..5) {
            HexMath.ring(centre, radius, out)
            assertEquals("ring size at r=$radius", 6 * radius, out.size)
            assertTrue(out.all { it.distanceTo(centre) == radius })
            assertEquals("ring has no duplicates", out.size, out.toSet().size)
        }
        for (radius in 0..5) {
            HexMath.spiral(centre, radius, out)
            assertEquals("disc size at r=$radius", 1 + 3 * radius * (radius + 1), out.size)
            assertTrue(out.all { it.distanceTo(centre) <= radius })
        }
    }

    @Test
    fun `line connects the endpoints with unit steps`() {
        val out = ArrayList<Hex>()
        val a = Hex(-4, 2)
        val b = Hex(5, -3)
        HexMath.line(a, b, out)
        assertEquals(a, out.first())
        assertEquals(b, out.last())
        assertEquals(a.distanceTo(b) + 1, out.size)
        for (i in 0 until out.size - 1) {
            assertEquals("step $i", 1, out[i].distanceTo(out[i + 1]))
        }
    }

    @Test
    fun `pixel round trip lands back on the same hex`() {
        val layout = HexLayout(24f)
        for (row in 0 until 30) {
            for (col in 0 until 30) {
                val hex = HexMath.ofOffset(col, row)
                val back = layout.hexAt(layout.centerX(hex), layout.centerY(hex))
                assertEquals("hex at ($col,$row)", hex, back)
            }
        }
    }

    @Test
    fun `offset pixel helpers agree with the axial ones`() {
        val layout = HexLayout(18f)
        for (row in 0 until 20) {
            for (col in 0 until 20) {
                val hex = HexMath.ofOffset(col, row)
                assertEquals(layout.centerX(hex), layout.centerXOffset(col, row), 0.001f)
                assertEquals(layout.centerY(hex), layout.centerYOffset(row), 0.001f)
            }
        }
    }
}
