// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.acidefluorhydrique.mapconquer.units.UnitKind

/**
 * 兵種記號。
 *
 * 全部用線條畫出來，不放任何點陣素材。三個理由：
 *
 *  - **無語系。** 記號是形狀不是文字，正體、簡體、English 三個語系共用同一張圖，
 *    也不會遇到某些字型缺字變成豆腐方框的問題。
 *  - **無素材。** APK 裡不必放 20 張圖 × 5 個密度，F-Droid 的建置也就不需要
 *    任何美術資產的來源說明。
 *  - **無限縮放。** 世界地圖從「看得到整個歐亞」拉到「看得到單格」跨了七八倍，
 *    向量在兩端都清楚。
 *
 * 造形上借用軍事地圖長年通用的抽象語彙（步兵是交叉、砲兵是實心圓、
 * 裝甲是橢圓），這些是描述性的圖形慣例，不屬於任何人。
 */
object UnitGlyphs {

    private val path = Path()
    private val oval = RectF()

    /**
     * 在 ([cx], [cy]) 為中心、寬 [w] 高 [h] 的框裡畫出 [kind] 的記號。
     */
    fun draw(canvas: Canvas, kind: UnitKind, cx: Float, cy: Float, w: Float, h: Float, paint: Paint) {
        val hw = w * 0.34f
        val hh = h * 0.34f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (h * 0.11f).coerceAtLeast(1f)
        paint.strokeCap = Paint.Cap.ROUND

        when (kind) {
            UnitKind.INFANTRY -> cross(canvas, cx, cy, hw, hh, paint)

            UnitKind.MOUNTAIN_INFANTRY -> {
                cross(canvas, cx, cy + hh * 0.15f, hw, hh * 0.8f, paint)
                peak(canvas, cx, cy - hh * 0.85f, hw * 0.55f, hh * 0.4f, paint)
            }

            UnitKind.MARINE -> {
                cross(canvas, cx, cy - hh * 0.15f, hw, hh * 0.8f, paint)
                wave(canvas, cx, cy + hh * 0.85f, hw, paint)
            }

            UnitKind.RECON -> canvas.drawLine(cx - hw, cy + hh, cx + hw, cy - hh, paint)

            UnitKind.ARMOUR -> ellipse(canvas, cx, cy, hw, hh * 0.8f, paint)

            UnitKind.ANTI_TANK -> {
                ellipse(canvas, cx, cy, hw, hh * 0.8f, paint)
                canvas.drawLine(cx - hw, cy + hh * 0.8f, cx + hw, cy - hh * 0.8f, paint)
            }

            UnitKind.ARTILLERY -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy, hh * 0.72f, paint)
            }

            UnitKind.ROCKET -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + hh * 0.28f, hh * 0.55f, paint)
                paint.style = Paint.Style.STROKE
                arrowUp(canvas, cx, cy - hh * 0.35f, hw * 0.6f, hh * 0.65f, paint)
            }

            UnitKind.ANTI_AIR -> {
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + hh * 0.35f, hh * 0.45f, paint)
                paint.style = Paint.Style.STROKE
                chevron(canvas, cx, cy - hh * 0.45f, hw * 0.85f, hh * 0.55f, paint)
            }

            UnitKind.SUPPLY_TRUCK -> {
                canvas.drawLine(cx - hw, cy, cx + hw, cy, paint)
                canvas.drawLine(cx - hw * 0.4f, cy - hh * 0.7f, cx - hw * 0.4f, cy + hh * 0.7f, paint)
            }

            UnitKind.HEADQUARTERS -> {
                canvas.drawLine(cx - hw * 0.55f, cy - hh, cx - hw * 0.55f, cy + hh, paint)
                paint.style = Paint.Style.FILL
                oval.set(cx - hw * 0.55f, cy - hh, cx + hw * 0.9f, cy - hh * 0.15f)
                canvas.drawRect(oval, paint)
            }

            UnitKind.FIGHTER -> delta(canvas, cx, cy, hw, hh, filled = false, paint = paint)

            UnitKind.BOMBER -> {
                delta(canvas, cx, cy - hh * 0.1f, hw, hh * 0.9f, filled = true, paint = paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(cx, cy + hh * 0.75f, hh * 0.22f, paint)
            }

            UnitKind.AIR_TRANSPORT -> {
                delta(canvas, cx, cy - hh * 0.15f, hw, hh * 0.8f, filled = false, paint = paint)
                canvas.drawLine(cx - hw * 0.8f, cy + hh * 0.8f, cx + hw * 0.8f, cy + hh * 0.8f, paint)
            }

            UnitKind.TRANSPORT_SHIP -> {
                hull(canvas, cx, cy + hh * 0.35f, hw, hh * 0.7f, paint)
                canvas.drawLine(cx - hw * 0.5f, cy - hh * 0.55f, cx + hw * 0.5f, cy - hh * 0.55f, paint)
            }

            UnitKind.DESTROYER -> {
                hull(canvas, cx, cy + hh * 0.35f, hw, hh * 0.7f, paint)
                mast(canvas, cx, cy - hh * 0.15f, hh * 0.7f, paint)
            }

            UnitKind.CRUISER -> {
                hull(canvas, cx, cy + hh * 0.35f, hw, hh * 0.7f, paint)
                mast(canvas, cx - hw * 0.35f, cy - hh * 0.15f, hh * 0.7f, paint)
                mast(canvas, cx + hw * 0.35f, cy - hh * 0.15f, hh * 0.7f, paint)
            }

            UnitKind.BATTLESHIP -> {
                hull(canvas, cx, cy + hh * 0.35f, hw, hh * 0.7f, paint)
                mast(canvas, cx - hw * 0.55f, cy - hh * 0.15f, hh * 0.7f, paint)
                mast(canvas, cx, cy - hh * 0.3f, hh * 0.9f, paint)
                mast(canvas, cx + hw * 0.55f, cy - hh * 0.15f, hh * 0.7f, paint)
            }

            UnitKind.SUBMARINE -> {
                ellipse(canvas, cx, cy + hh * 0.25f, hw, hh * 0.45f, paint)
                canvas.drawLine(cx, cy - hh * 0.6f, cx, cy + hh * 0.05f, paint)
            }

            UnitKind.CARRIER -> {
                hull(canvas, cx, cy + hh * 0.35f, hw, hh * 0.7f, paint)
                canvas.drawLine(cx - hw, cy - hh * 0.35f, cx + hw, cy - hh * 0.35f, paint)
            }
        }
        paint.style = Paint.Style.FILL
    }

    private fun cross(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, paint: Paint) {
        canvas.drawLine(cx - hw, cy - hh, cx + hw, cy + hh, paint)
        canvas.drawLine(cx - hw, cy + hh, cx + hw, cy - hh, paint)
    }

    private fun ellipse(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, paint: Paint) {
        oval.set(cx - hw, cy - hh, cx + hw, cy + hh)
        canvas.drawOval(oval, paint)
    }

    private fun peak(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, paint: Paint) {
        path.rewind()
        path.moveTo(cx - hw, cy + hh)
        path.lineTo(cx, cy - hh)
        path.lineTo(cx + hw, cy + hh)
        canvas.drawPath(path, paint)
    }

    private fun wave(canvas: Canvas, cx: Float, cy: Float, hw: Float, paint: Paint) {
        path.rewind()
        path.moveTo(cx - hw, cy)
        path.quadTo(cx - hw * 0.5f, cy - hw * 0.4f, cx, cy)
        path.quadTo(cx + hw * 0.5f, cy + hw * 0.4f, cx + hw, cy)
        canvas.drawPath(path, paint)
    }

    private fun delta(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, filled: Boolean, paint: Paint) {
        path.rewind()
        path.moveTo(cx, cy - hh)
        path.lineTo(cx + hw, cy + hh)
        path.lineTo(cx - hw, cy + hh)
        path.close()
        val previous = paint.style
        paint.style = if (filled) Paint.Style.FILL else Paint.Style.STROKE
        canvas.drawPath(path, paint)
        paint.style = previous
    }

    private fun arrowUp(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, paint: Paint) {
        canvas.drawLine(cx, cy + hh, cx, cy - hh, paint)
        canvas.drawLine(cx - hw, cy - hh * 0.25f, cx, cy - hh, paint)
        canvas.drawLine(cx + hw, cy - hh * 0.25f, cx, cy - hh, paint)
    }

    private fun chevron(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, paint: Paint) {
        canvas.drawLine(cx - hw, cy + hh, cx, cy - hh, paint)
        canvas.drawLine(cx + hw, cy + hh, cx, cy - hh, paint)
    }

    private fun hull(canvas: Canvas, cx: Float, cy: Float, hw: Float, hh: Float, paint: Paint) {
        path.rewind()
        path.moveTo(cx - hw, cy - hh * 0.5f)
        path.lineTo(cx + hw, cy - hh * 0.5f)
        path.lineTo(cx + hw * 0.62f, cy + hh * 0.5f)
        path.lineTo(cx - hw * 0.62f, cy + hh * 0.5f)
        path.close()
        canvas.drawPath(path, paint)
    }

    private fun mast(canvas: Canvas, cx: Float, cy: Float, height: Float, paint: Paint) {
        canvas.drawLine(cx, cy, cx, cy - height, paint)
    }
}
