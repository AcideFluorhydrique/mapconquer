// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.world.Terrain

/**
 * 地形符號。
 *
 * 格子的底色屬於國家，地形就不能再靠色相說話 —— 兩者疊在一起的結果是
 * 一片既看不出是誰的、也看不出是山還是平原的泥色。所以地形改用印在
 * 國色上的小圖案，借用紙本地圖的慣例：樹是森林、三角是山、弧線是丘陵、
 * 點與沙丘是沙漠。
 *
 * 符號一律用半透明的黑墨，而不是各自的顏色：這樣不論底下是哪一國的色，
 * 墨色只會把它壓暗一點，永遠讀得出來，也不會跟國色搶。例外只有兩個 ——
 * 山頂的雪與河谷裡的水，少了這兩點顏色，山跟河就不像山跟河了。
 *
 * 座標以六角形外接圓半徑為 1，圖案收在半徑約 0.6 的範圍內，留出邊緣
 * 給國界與海岸線。Path 跟 [MapRenderer] 的六角形一樣，只在縮放改變時重建。
 */
class TerrainGlyphs {

    /** 一個圖層：同一種顏色、同一種畫法的所有筆畫。[stroke] 為 0 表示填色。 */
    private class Layer(val colour: String, val stroke: Float, val build: Path.(Float) -> Unit) {
        val path = Path()
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private var builtSize = -1f

    private val glyphs: Array<Array<Layer>> = Array(Terrain.ALL.size) { i ->
        when (Terrain.ALL[i]) {
            Terrain.FARMLAND -> arrayOf(Layer(INK, 0.05f) { s -> farmland(s) })
            Terrain.FOREST -> arrayOf(Layer(INK, 0f) { s -> forest(s) })
            Terrain.JUNGLE -> arrayOf(Layer(INK, 0f) { s -> jungle(s) })
            Terrain.HILLS -> arrayOf(Layer(INK, 0.075f) { s -> hills(s) })
            Terrain.MOUNTAIN -> arrayOf(
                Layer(INK_HEAVY, 0f) { s -> peaks(s) },
                Layer(SNOW, 0f) { s -> snowcaps(s) }
            )
            Terrain.DESERT -> arrayOf(
                Layer(INK, 0.06f) { s -> dunes(s) },
                Layer(INK, 0f) { s -> sand(s) }
            )
            Terrain.SWAMP -> arrayOf(Layer(INK, 0.055f) { s -> marsh(s) })
            Terrain.TUNDRA -> arrayOf(Layer(INK, 0.05f) { s -> tundra(s) })
            Terrain.ICE -> arrayOf(Layer(INK, 0.045f) { s -> cracks(s) })
            Terrain.RIVER -> arrayOf(
                Layer(RIVER, 0.11f) { s -> stream(s) },
                Layer(INK, 0.045f) { s -> banks(s) }
            )
            // 平原刻意留白：它是最常見的地形，空著反而讓其他符號跳出來。
            else -> emptyArray()
        }
    }

    /**
     * 在已平移到格子中心的 canvas 上畫一格的地形符號。
     *
     * [mirror] 讓相鄰的同種地形左右翻面，大片森林或山脈才不會像壁紙一樣
     * 整齊重複；[fade] 是縮放淡入用的整體透明度。
     */
    fun draw(canvas: Canvas, terrain: Terrain, size: Float, mirror: Boolean, fade: Float) {
        val layers = glyphs[terrain.ordinal]
        if (layers.isEmpty()) return
        ensureBuilt(size)
        if (mirror) {
            canvas.save()
            canvas.scale(-1f, 1f)
        }
        for (layer in layers) {
            val colour = Colors.of(layer.colour)
            paint.color = Colors.alpha(colour, ((colour ushr 24) * fade).toInt())
            if (layer.stroke > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = layer.stroke * size
            } else {
                paint.style = Paint.Style.FILL
            }
            canvas.drawPath(layer.path, paint)
        }
        if (mirror) canvas.restore()
    }

    private fun ensureBuilt(size: Float) {
        if (size == builtSize) return
        builtSize = size
        for (layers in glyphs) {
            for (layer in layers) {
                layer.path.rewind()
                layer.build(layer.path, size)
            }
        }
    }

    // ------------------------------------------------------------------
    // 各地形的圖案。s 是六角形外接圓半徑，所有座標都寫成 s 的倍數。

    /** 兩塊方向不同的田壟，加上一條斜的 —— 拼布一樣的農地。 */
    private fun Path.farmland(s: Float) {
        for (i in 0 until 4) {
            val y = (-0.42f + i * 0.14f) * s
            moveTo(-0.5f * s, y); lineTo(-0.02f * s, y)
        }
        for (i in 0 until 4) {
            val x = (0.12f + i * 0.13f) * s
            moveTo(x, -0.44f * s); lineTo(x, 0f)
        }
        for (i in 0 until 4) {
            val y = (0.14f + i * 0.13f) * s
            moveTo(-0.3f * s, y); lineTo(0.34f * s, y - 0.08f * s)
        }
    }

    /** 針葉樹：三角形樹冠加一截樹幹。 */
    private fun Path.forest(s: Float) {
        for (tree in FOREST_TREES) {
            val x = tree[0] * s
            val y = tree[1] * s
            val k = tree[2]
            triangle(x, y, 0.15f * s * k, 0.34f * s * k)
            addRect(x - 0.03f * s, y, x + 0.03f * s, y + 0.09f * s * k, Path.Direction.CW)
        }
    }

    /** 闊葉樹：圓樹冠，比森林密，一眼就跟針葉林分得開。 */
    private fun Path.jungle(s: Float) {
        for (tree in JUNGLE_TREES) {
            val x = tree[0] * s
            val y = tree[1] * s
            val r = tree[2] * s
            addCircle(x, y, r, Path.Direction.CW)
            addRect(x - 0.025f * s, y, x + 0.025f * s, y + r + 0.1f * s, Path.Direction.CW)
        }
    }

    private fun Path.hills(s: Float) {
        for (hill in HILLS) {
            val x = hill[0] * s
            val y = hill[1] * s
            val w = hill[2] * s
            moveTo(x - w, y)
            quadTo(x, y - w * 1.1f, x + w, y)
        }
    }

    private fun Path.peaks(s: Float) {
        triangle(-0.14f * s, 0.36f * s, 0.40f * s, 0.78f * s)
        triangle(0.30f * s, 0.36f * s, 0.28f * s, 0.52f * s)
    }

    private fun Path.snowcaps(s: Float) {
        snowcap(-0.14f * s, 0.36f * s, 0.40f * s, 0.78f * s)
        snowcap(0.30f * s, 0.36f * s, 0.28f * s, 0.52f * s)
    }

    /** 山頂的雪：沿兩側山坡往下三成，底緣做成鋸齒，才像雪線而不像一頂帽子。 */
    private fun Path.snowcap(x: Float, baseY: Float, w: Float, h: Float) {
        val top = baseY - h
        val k = 0.3f
        moveTo(x, top)
        lineTo(x + w * k, top + h * k)
        lineTo(x + w * k * 0.3f, top + h * k * 0.8f)
        lineTo(x - w * k * 0.2f, top + h * k * 1.05f)
        lineTo(x - w * k, top + h * k)
        close()
    }

    private fun Path.dunes(s: Float) {
        for (dune in DUNES) {
            val x = dune[0] * s
            val y = dune[1] * s
            val w = dune[2] * s
            moveTo(x - w, y)
            quadTo(x - w * 0.1f, y - w * 0.9f, x + w, y + w * 0.15f)
        }
    }

    private fun Path.sand(s: Float) {
        for (dot in SAND) addCircle(dot[0] * s, dot[1] * s, 0.05f * s, Path.Direction.CW)
    }

    /** 沼澤：水平的水線，上面長一叢蘆葦。 */
    private fun Path.marsh(s: Float) {
        for (m in MARSH) {
            val x = m[0] * s
            val y = m[1] * s
            val w = m[2] * s
            moveTo(x - w, y); lineTo(x + w, y)
            for (dx in REEDS) {
                moveTo(x + dx * s, y - 0.04f * s)
                lineTo(x + dx * 1.8f * s, y + (if (dx == 0f) -0.2f else -0.16f) * s)
            }
        }
    }

    /** 凍原：錯開的短橫線，稀疏得像地衣 —— 刻意不畫草叢，免得跟沼澤的蘆葦混在一起。 */
    private fun Path.tundra(s: Float) {
        for (dash in TUNDRA) {
            val x = dash[0] * s
            val y = dash[1] * s
            moveTo(x - 0.09f * s, y); lineTo(x + 0.09f * s, y)
        }
    }

    private fun Path.cracks(s: Float) {
        moveTo(-0.5f * s, -0.2f * s); lineTo(-0.2f * s, -0.1f * s)
        lineTo(0f, -0.3f * s); lineTo(0.4f * s, -0.22f * s)
        moveTo(-0.2f * s, -0.1f * s); lineTo(-0.1f * s, 0.25f * s); lineTo(0.3f * s, 0.35f * s)
        moveTo(-0.1f * s, 0.25f * s); lineTo(-0.42f * s, 0.4f * s)
    }

    /** 河谷：一條橫過格子的蜿蜒水道。 */
    private fun Path.stream(s: Float) {
        moveTo(-0.62f * s, -0.12f * s)
        cubicTo(-0.25f * s, -0.5f * s, -0.05f * s, 0.34f * s, 0.2f * s, 0.04f * s)
        quadTo(0.4f * s, -0.2f * s, 0.62f * s, 0.16f * s)
    }

    private fun Path.banks(s: Float) {
        for (b in BANKS) {
            moveTo((b[0] - 0.12f) * s, b[1] * s); lineTo((b[0] + 0.12f) * s, b[1] * s)
        }
    }

    /** 以 (x, baseY) 為底邊中點、尖端朝上的三角形。 */
    private fun Path.triangle(x: Float, baseY: Float, halfWidth: Float, height: Float) {
        moveTo(x, baseY - height)
        lineTo(x + halfWidth, baseY)
        lineTo(x - halfWidth, baseY)
        close()
    }

    private companion object {
        const val INK = "#57000000"
        const val INK_HEAVY = "#6B000000"
        const val SNOW = "#8CFFFFFF"
        const val RIVER = "#FF6FB2D6"

        /** x, 底邊 y, 大小倍率。 */
        val FOREST_TREES = arrayOf(
            floatArrayOf(-0.34f, 0.12f, 1f), floatArrayOf(0.30f, 0.14f, 1f),
            floatArrayOf(-0.02f, -0.26f, 1.1f), floatArrayOf(0.02f, 0.46f, 0.8f)
        )

        /** x, y, 樹冠半徑。 */
        val JUNGLE_TREES = arrayOf(
            floatArrayOf(-0.34f, 0f, 0.15f), floatArrayOf(0.30f, 0.02f, 0.16f),
            floatArrayOf(-0.04f, -0.34f, 0.15f), floatArrayOf(0f, 0.36f, 0.16f),
            floatArrayOf(-0.45f, 0.38f, 0.10f), floatArrayOf(0.46f, -0.30f, 0.10f)
        )

        /** x, 底 y, 半寬。 */
        val HILLS = arrayOf(
            floatArrayOf(-0.24f, -0.02f, 0.26f), floatArrayOf(0.26f, 0.26f, 0.26f),
            floatArrayOf(0.22f, -0.34f, 0.18f), floatArrayOf(-0.30f, 0.42f, 0.16f)
        )

        val DUNES = arrayOf(floatArrayOf(-0.22f, -0.10f, 0.28f), floatArrayOf(0.24f, 0.30f, 0.26f))

        val SAND = arrayOf(
            floatArrayOf(0.30f, -0.30f), floatArrayOf(0.12f, -0.50f), floatArrayOf(-0.45f, 0.25f),
            floatArrayOf(-0.20f, 0.45f), floatArrayOf(0.48f, 0.02f), floatArrayOf(0f, 0.12f),
            floatArrayOf(-0.52f, -0.30f)
        )

        /** x, y, 水線半寬。 */
        val MARSH = arrayOf(
            floatArrayOf(-0.18f, -0.18f, 0.30f), floatArrayOf(0.24f, 0.20f, 0.28f),
            floatArrayOf(-0.22f, 0.44f, 0.22f)
        )
        val REEDS = floatArrayOf(-0.1f, 0f, 0.1f)

        val TUNDRA = arrayOf(
            floatArrayOf(-0.20f, -0.42f), floatArrayOf(0.22f, -0.40f),
            floatArrayOf(-0.42f, -0.14f), floatArrayOf(0.02f, -0.12f), floatArrayOf(0.44f, -0.16f),
            floatArrayOf(-0.22f, 0.14f), floatArrayOf(0.24f, 0.16f),
            floatArrayOf(-0.40f, 0.42f), floatArrayOf(0.04f, 0.44f), floatArrayOf(0.42f, 0.40f)
        )

        val BANKS = arrayOf(floatArrayOf(-0.30f, 0.34f), floatArrayOf(0.30f, 0.42f), floatArrayOf(0.02f, -0.42f))
    }
}
