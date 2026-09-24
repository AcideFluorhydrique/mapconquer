// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.acidefluorhydrique.mapconquer.game.AirMission
import io.github.acidefluorhydrique.mapconquer.units.UnitKind

/**
 * 兵種剪影。
 *
 * 第一版用的是軍事地圖的抽象記號（步兵是交叉、裝甲是橢圓、砲兵是實心圓）。
 * 那套語彙對兵棋玩家是母語，對其他人卻是一張得先背下來的對照表 ——
 * 而一輛側面的戰車、一門揚起砲管的榴彈砲，任何人第一眼就認得。
 *
 * 剪影仍然全用向量 Path 畫，不放任何點陣素材：
 *
 *  - **無語系。** 形狀不是文字，三個語系共用，也不會遇到缺字的豆腐方框。
 *  - **無素材。** APK 裡不必放幾十張圖 × 5 個密度，F-Droid 建置也不需要美術來源說明。
 *  - **無限縮放。** 世界地圖的縮放跨了七八倍，向量在兩端都清楚。
 *
 * 所有剪影面朝右、實心填色，座標以「記號半高」為 1：x 大約 ±1.35、y 在 ±1 之內。
 * 小尺寸下實心的輪廓比線條好認得多 —— 地圖上一枚徽章只有十幾 dp 寬。
 * Path 只在第一次用到時建一次，之後每次畫都只是平移與縮放。
 */
object UnitGlyphs {

    private val units: Array<Path> by lazy { Array(UnitKind.ALL.size) { buildUnit(UnitKind.ALL[it]) } }
    private val missions: Array<Path> by lazy { Array(AirMission.ALL.size) { buildMission(AirMission.ALL[it]) } }

    /** 在 ([cx], [cy]) 為中心、寬 [w] 高 [h] 的框裡畫出 [kind] 的剪影，顏色取自 [paint]。 */
    fun draw(canvas: Canvas, kind: UnitKind, cx: Float, cy: Float, w: Float, h: Float, paint: Paint) {
        drawShape(canvas, units[kind.ordinal], cx, cy, minOf(w / 2.9f, h * 0.4f), 0f, paint)
    }

    /** 空中任務的圖示：機頭朝上的俯視剪影，空降是一頂傘。 */
    fun drawMission(canvas: Canvas, mission: AirMission, cx: Float, cy: Float, w: Float, h: Float, paint: Paint) {
        drawShape(canvas, missions[mission.ordinal], cx, cy, minOf(w, h) * 0.4f, 0f, paint)
    }

    /**
     * 飛行中的飛機：[unit] 是記號半高的像素數，[heading] 是航向（度，0 = 朝右）。
     * 空降任務在路上畫的是轟炸機的機身 —— 傘要到目標上空才打開。
     */
    fun drawAircraft(
        canvas: Canvas, mission: AirMission, cx: Float, cy: Float, unit: Float, heading: Float, paint: Paint,
        outline: Int = 0
    ) {
        val shape = missions[(if (mission == AirMission.AIRDROP) AirMission.BOMBER else mission).ordinal]
        // 剪影機頭朝上（-90°），轉到航向要再加 90°。
        if (outline != 0) {
            // 描邊：淺色的機身飛過淺色的國土時，靠這一圈深色才看得見。
            val fill = paint.color
            val style = paint.style
            paint.color = outline
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Shape.GRID * 0.2f
            paint.strokeJoin = Paint.Join.ROUND
            drawShape(canvas, shape, cx, cy, unit, heading + 90f, paint, keepStyle = true)
            paint.color = fill
            paint.style = style
        }
        drawShape(canvas, shape, cx, cy, unit, heading + 90f, paint)
    }

    /** 傘兵的降落傘，給空降動畫用。 */
    fun drawParachute(canvas: Canvas, cx: Float, cy: Float, unit: Float, paint: Paint) {
        drawShape(canvas, missions[AirMission.AIRDROP.ordinal], cx, cy, unit, 0f, paint)
    }

    private fun drawShape(
        canvas: Canvas, path: Path, cx: Float, cy: Float, unit: Float, rotation: Float, paint: Paint,
        keepStyle: Boolean = false
    ) {
        val previous = paint.style
        if (!keepStyle) paint.style = Paint.Style.FILL
        canvas.save()
        canvas.translate(cx, cy)
        if (rotation != 0f) canvas.rotate(rotation)
        canvas.scale(unit / Shape.GRID, unit / Shape.GRID)
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = previous
    }

    // ------------------------------------------------------------------

    private fun buildUnit(kind: UnitKind): Path = Shape().apply {
        when (kind) {
            UnitKind.INFANTRY -> soldier(0f)

            UnitKind.MOUNTAIN_INFANTRY -> {
                poly(-1.35f, 0.95f, -0.78f, -0.25f, -0.22f, 0.95f)
                cut { rect(0.05f, -1f, 1f, 1f) }
                soldier(0.4f)
            }

            UnitKind.MARINE -> {
                anchor(-0.78f)
                soldier(0.4f)
            }

            UnitKind.RECON -> {
                poly(-1.25f, 0.45f, -1.25f, -0.05f, -0.62f, -0.12f, -0.36f, -0.52f, 0.5f, -0.52f, 0.78f, -0.1f, 1.3f, 0f, 1.3f, 0.45f)
                cut { poly(-0.2f, -0.4f, 0.35f, -0.4f, 0.52f, -0.14f, -0.28f, -0.14f) }
                bar(0.2f, -0.52f, 1.05f, -0.62f, 0.1f)
                wheels(0.52f, 0.34f, -0.72f, 0.78f)
            }

            UnitKind.ARMOUR -> {
                round(-1.3f, 0.28f, 1.3f, 0.95f, 0.33f)
                cut { for (x in floatArrayOf(-0.9f, -0.32f, 0.26f, 0.84f)) circle(x, 0.62f, 0.2f) }
                poly(-1.2f, 0.28f, -0.98f, -0.1f, 1.02f, -0.1f, 1.3f, 0.28f)
                poly(-0.62f, -0.1f, -0.46f, -0.6f, 0.36f, -0.6f, 0.52f, -0.1f)
                rect(0.3f, -0.46f, 1.42f, -0.3f)
            }

            // 反坦克炮：矮、砲管平而長、前面一面防盾 —— 跟揚起砲管的榴彈砲一眼分得開。
            UnitKind.ANTI_TANK -> {
                bar(-0.35f, 0.35f, -1.3f, 0.92f, 0.16f)
                poly(-0.6f, -0.4f, 0.05f, -0.4f, 0.12f, 0.3f, -0.66f, 0.3f)
                rect(0f, -0.16f, 1.3f, -0.02f)
                rect(1.12f, -0.24f, 1.4f, 0.06f)
                wheels(0.55f, 0.4f, -0.22f)
            }

            UnitKind.ARTILLERY -> {
                bar(-0.3f, 0.4f, -1.3f, 0.95f, 0.18f)
                bar(-0.5f, 0.25f, 1.15f, -0.85f, 0.26f)
                bar(-0.55f, 0.3f, 0.1f, -0.1f, 0.3f)
                wheels(0.5f, 0.45f, -0.12f)
            }

            UnitKind.ROCKET -> {
                rect(-1.3f, 0.18f, 0.5f, 0.4f)
                cab()
                bar(-1.2f, -0.02f, 0.4f, -0.8f, 0.2f)
                bar(-1.2f, -0.4f, 0f, -1f, 0.16f)
                bar(-0.4f, 0.2f, -0.3f, -0.4f, 0.14f)
                wheels(0.58f, 0.28f, -0.85f, -0.25f, 0.92f)
            }

            UnitKind.ANTI_AIR -> {
                bar(-1.25f, 0.95f, 0f, 0.45f, 0.18f)
                bar(1.25f, 0.95f, 0f, 0.45f, 0.18f)
                circle(0f, 0.35f, 0.36f)
                poly(-0.5f, 0.3f, -0.3f, -0.2f, 0.35f, -0.2f, 0.5f, 0.3f)
                bar(-0.05f, 0f, 0.6f, -0.98f, 0.14f)
                bar(0.22f, 0.05f, 0.95f, -0.88f, 0.14f)
            }

            UnitKind.SUPPLY_TRUCK -> {
                round(-1.3f, -0.6f, 0.36f, 0.32f, 0.06f)
                cab()
                rect(-1.3f, 0.28f, 1.3f, 0.44f)
                wheels(0.58f, 0.3f, -0.8f, 0.9f)
            }

            // 司令部：一頂帳篷加一面旗。
            UnitKind.HEADQUARTERS -> {
                poly(-1.3f, 0.95f, -0.4f, -0.35f, 0.5f, 0.95f)
                cut { poly(-0.4f, 0.95f, -0.4f, 0.2f, -0.05f, 0.95f) }
                bar(0.9f, 0.95f, 0.9f, -0.95f, 0.12f)
                poly(0.95f, -0.98f, 1.42f, -0.72f, 0.95f, -0.46f)
            }

            // 船艦靠長度與上層結構分辨：運輸艦是貨艙、驅逐艦一座煙囪、
            // 巡洋艦兩座煙囪加前後砲塔、戰艦一座高聳的艦橋。
            UnitKind.TRANSPORT_SHIP -> {
                poly(-1.3f, 0.2f, 1.38f, 0.2f, 1.12f, 0.75f, -1.15f, 0.75f)
                rect(-1.05f, -0.42f, -0.4f, 0.2f)
                rect(-0.88f, -0.78f, -0.62f, -0.42f)
                rect(-0.22f, -0.18f, 0.36f, 0.2f)
                rect(0.46f, -0.18f, 1.04f, 0.2f)
                bar(0.4f, -0.18f, 0.4f, -0.85f, 0.08f)
            }

            UnitKind.DESTROYER -> {
                poly(-1.35f, 0.28f, 1.42f, 0.12f, 1.12f, 0.7f, -1.18f, 0.7f)
                rect(0.2f, -0.32f, 0.72f, 0.28f)
                poly(-0.28f, 0.28f, -0.2f, -0.5f, 0.08f, -0.5f, 0.06f, 0.28f)
                round(0.86f, -0.02f, 1.14f, 0.2f, 0.08f)
                bar(1f, 0.06f, 1.4f, -0.06f, 0.07f)
                bar(0.5f, -0.32f, 0.5f, -0.9f, 0.07f)
                round(-1.1f, 0.04f, -0.82f, 0.28f, 0.08f)
            }

            UnitKind.CRUISER -> {
                poly(-1.38f, 0.26f, 1.42f, 0.12f, 1.14f, 0.7f, -1.2f, 0.7f)
                rect(-0.3f, -0.24f, 0.62f, 0.26f)
                rect(0.2f, -0.52f, 0.56f, -0.24f)
                rect(-0.2f, -0.62f, 0.02f, -0.24f)
                rect(0.08f, -0.58f, 0.16f, -0.24f)
                round(0.8f, -0.04f, 1.1f, 0.2f, 0.08f)
                bar(0.95f, 0.04f, 1.4f, -0.1f, 0.07f)
                round(-1.12f, -0.04f, -0.82f, 0.2f, 0.08f)
                bar(-0.97f, 0.04f, -1.4f, -0.1f, 0.07f)
                bar(0.38f, -0.52f, 0.38f, -0.98f, 0.07f)
            }

            UnitKind.BATTLESHIP -> {
                poly(-1.4f, 0.22f, 1.44f, 0.1f, 1.16f, 0.72f, -1.22f, 0.72f)
                rect(-0.45f, -0.16f, 0.55f, 0.22f)
                poly(-0.05f, -0.16f, 0f, -0.72f, 0.34f, -0.72f, 0.4f, -0.16f)
                rect(0.06f, -0.95f, 0.28f, -0.72f)
                rect(-0.38f, -0.5f, -0.14f, -0.16f)
                round(0.66f, -0.12f, 1f, 0.14f, 0.08f)
                bar(0.84f, -0.02f, 1.42f, -0.14f, 0.08f)
                round(-0.98f, -0.12f, -0.64f, 0.14f, 0.08f)
                bar(-0.8f, -0.02f, -1.38f, -0.14f, 0.08f)
            }

            UnitKind.SUBMARINE -> {
                round(-1.35f, 0.2f, 1.35f, 0.66f, 0.23f)
                poly(-0.34f, 0.22f, -0.24f, -0.36f, 0.34f, -0.36f, 0.46f, 0.22f)
                bar(0.12f, -0.36f, 0.12f, -0.86f, 0.07f)
                bar(0.12f, -0.83f, 0.32f, -0.83f, 0.07f)
                poly(-1.35f, 0.43f, -1.5f, 0.12f, -1.5f, 0.74f)
            }

            UnitKind.CARRIER -> {
                poly(-1.2f, 0.2f, 1.3f, 0.2f, 1.06f, 0.72f, -1.06f, 0.72f)
                rect(-1.42f, 0.02f, 1.42f, 0.2f)
                rect(0.38f, -0.55f, 0.78f, 0.02f)
                bar(0.58f, -0.55f, 0.58f, -0.95f, 0.07f)
            }
        }
    }.path

    private fun buildMission(mission: AirMission): Path = Shape().apply {
        when (mission) {
            // 單發戰鬥機：窄機身、直翼。
            AirMission.FIGHTER -> {
                round(-0.13f, -1f, 0.13f, 0.95f, 0.13f)
                poly(-0.1f, -0.25f, -1.05f, 0.08f, -1.05f, 0.25f, -0.1f, 0.15f)
                poly(0.1f, -0.25f, 1.05f, 0.08f, 1.05f, 0.25f, 0.1f, 0.15f)
                tailplane(0.68f, 0.45f)
            }
            // 轟炸機：翼展寬得多，四具發動機伸出前緣。
            AirMission.BOMBER -> {
                round(-0.15f, -0.98f, 0.15f, 0.98f, 0.15f)
                poly(-0.12f, -0.22f, -1.3f, -0.05f, -1.3f, 0.12f, -0.12f, 0.14f)
                poly(0.12f, -0.22f, 1.3f, -0.05f, 1.3f, 0.12f, 0.12f, 0.14f)
                for (x in floatArrayOf(-0.9f, -0.48f, 0.48f, 0.9f)) round(x - 0.09f, -0.42f, x + 0.09f, 0.02f, 0.07f)
                tailplane(0.66f, 0.55f)
            }
            AirMission.AIRDROP -> {
                dome(0f, -0.3f, 0.95f)
                cut {
                    dome(-0.63f, -0.3f, 0.32f)
                    dome(0f, -0.3f, 0.32f)
                    dome(0.63f, -0.3f, 0.32f)
                }
                bar(-0.9f, -0.3f, -0.06f, 0.5f, 0.07f)
                bar(0.9f, -0.3f, 0.06f, 0.5f, 0.07f)
                bar(0f, -0.3f, 0f, 0.5f, 0.07f)
                circle(0f, 0.52f, 0.13f)
                round(-0.13f, 0.6f, 0.13f, 0.98f, 0.08f)
            }
        }
    }.path

    /** 持槍步行的士兵，[x] 是身體中線。 */
    private fun Shape.soldier(x: Float) {
        dome(x + 0.02f, -0.58f, 0.32f)
        rect(x - 0.42f, -0.6f, x + 0.46f, -0.5f)
        circle(x + 0.04f, -0.4f, 0.16f)
        poly(x - 0.28f, -0.28f, x + 0.3f, -0.28f, x + 0.34f, 0.28f, x - 0.3f, 0.28f)
        bar(x - 0.14f, 0.2f, x - 0.42f, 0.95f, 0.22f)
        bar(x + 0.14f, 0.2f, x + 0.4f, 0.95f, 0.22f)
        bar(x - 0.62f, 0.3f, x + 0.58f, -0.95f, 0.11f)
    }

    private fun Shape.anchor(x: Float) {
        circle(x, -0.62f, 0.2f)
        cut { circle(x, -0.62f, 0.09f) }
        bar(x, -0.45f, x, 0.9f, 0.14f)
        bar(x - 0.34f, -0.2f, x + 0.34f, -0.2f, 0.12f)
        curve {
            moveTo(x - 0.47f, 0.35f)
            quadTo(x - 0.37f, 0.98f, x, 0.95f)
            quadTo(x + 0.37f, 0.98f, x + 0.47f, 0.35f)
            lineTo(x + 0.35f, 0.42f)
            quadTo(x + 0.28f, 0.8f, x, 0.8f)
            quadTo(x - 0.28f, 0.8f, x - 0.35f, 0.42f)
            close()
        }
    }

    /** 卡車的駕駛座，連同車窗。 */
    private fun Shape.cab() {
        poly(0.5f, 0.4f, 0.5f, -0.25f, 0.92f, -0.25f, 1.3f, 0.1f, 1.3f, 0.4f)
        cut { poly(0.62f, -0.14f, 0.87f, -0.14f, 1.08f, 0.08f, 0.62f, 0.08f) }
    }

    /** 車輪：先挖出一圈縫把輪子跟車身分開，再放輪子、挖輪轂。 */
    private fun Shape.wheels(y: Float, r: Float, vararg xs: Float) {
        cut { for (x in xs) circle(x, y, r + 0.1f) }
        for (x in xs) circle(x, y, r)
        cut { for (x in xs) circle(x, y, r * 0.35f) }
    }

    private fun Shape.tailplane(y: Float, halfSpan: Float) {
        poly(-0.08f, y, -halfSpan, y + 0.22f, -halfSpan, y + 0.3f, -0.05f, y + 0.27f)
        poly(0.08f, y, halfSpan, y + 0.22f, halfSpan, y + 0.3f, 0.05f, y + 0.27f)
    }

    /**
     * 蓋剪影用的小工具：每一筆形狀依序併進（或在 [cut] 裡挖掉）結果。
     *
     * 座標在內部放大 [GRID] 倍 —— Skia 的布林運算在個位數以下的座標上
     * 會有精度問題，畫的時候再縮回來。
     */
    private class Shape {
        val path = Path()
        private val piece = Path()
        private val oval = RectF()
        private var cutting = false

        fun cut(block: Shape.() -> Unit) {
            cutting = true
            block()
            cutting = false
        }

        fun poly(vararg xy: Float) {
            piece.rewind()
            piece.moveTo(xy[0] * GRID, xy[1] * GRID)
            for (i in 2 until xy.size step 2) piece.lineTo(xy[i] * GRID, xy[i + 1] * GRID)
            piece.close()
            commit()
        }

        fun rect(l: Float, t: Float, r: Float, b: Float) {
            piece.rewind()
            piece.addRect(l * GRID, t * GRID, r * GRID, b * GRID, Path.Direction.CW)
            commit()
        }

        fun round(l: Float, t: Float, r: Float, b: Float, radius: Float) {
            piece.rewind()
            piece.addRoundRect(l * GRID, t * GRID, r * GRID, b * GRID, radius * GRID, radius * GRID, Path.Direction.CW)
            commit()
        }

        fun circle(x: Float, y: Float, r: Float) {
            piece.rewind()
            piece.addCircle(x * GRID, y * GRID, r * GRID, Path.Direction.CW)
            commit()
        }

        /** 上半圓，底邊在 y。 */
        fun dome(x: Float, y: Float, r: Float) {
            piece.rewind()
            oval.set((x - r) * GRID, (y - r) * GRID, (x + r) * GRID, (y + r) * GRID)
            piece.arcTo(oval, 180f, 180f, true)
            piece.close()
            commit()
        }

        /** 一條有寬度的直線，兩端是方頭。 */
        fun bar(x0: Float, y0: Float, x1: Float, y1: Float, width: Float) {
            val dx = x1 - x0
            val dy = y1 - y0
            val len = kotlin.math.hypot(dx, dy)
            val nx = -dy / len * width / 2f
            val ny = dx / len * width / 2f
            poly(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny)
        }

        /** 任意曲線；座標照樣以半高為 1，這裡替它放大。 */
        fun curve(block: Path.() -> Unit) {
            piece.rewind()
            piece.block()
            piece.transform(SCALE)
            commit()
        }

        private fun commit() {
            path.op(piece, if (cutting) Path.Op.DIFFERENCE else Path.Op.UNION)
        }

        companion object {
            const val GRID = 100f
            private val SCALE = android.graphics.Matrix().apply { setScale(GRID, GRID) }
        }
    }
}
