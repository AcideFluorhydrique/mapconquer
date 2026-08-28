// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader

/**
 * 共用的畫面元件，讓主選單、HUD、面板維持一致的視覺語言。
 * 所有繪製都在遊戲執行緒進行，因此共用一支 Paint 是安全的。
 */
object Widgets {

    const val STEEL_TOP = "#37506B"
    const val STEEL_BOTTOM = "#22374D"
    const val GREEN_TOP = "#3E7D5A"
    const val GREEN_BOTTOM = "#2A5B41"
    const val AMBER_TOP = "#C9922F"
    const val AMBER_BOTTOM = "#95681C"
    const val RED_TOP = "#A8452F"
    const val RED_BOTTOM = "#7A2E20"
    const val GRAY_TOP = "#4A5666"
    const val GRAY_BOTTOM = "#333C49"
    const val PURPLE_TOP = "#6A4E8C"
    const val PURPLE_BOTTOM = "#4A3563"

    const val INK = "#F2F6FA"
    const val INK_DIM = "#A9B8C7"
    const val PANEL_TOP = "#F21C2C3C"
    const val PANEL_BOTTOM = "#EE111B27"

    private val paint = Paint().apply { isAntiAlias = true }
    private val scratch = RectF()
    private val path = Path()

    fun panel(
        canvas: Canvas,
        rect: RectF,
        radius: Float = Ui.dp(10f),
        topColor: String = PANEL_TOP,
        bottomColor: String = PANEL_BOTTOM,
        borderColor: String = "#4D9FC4E8"
    ) {
        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = Colors.of("#77000000")
        scratch.set(rect.left, rect.top + Ui.dp(3f), rect.right, rect.bottom + Ui.dp(3f))
        canvas.drawRoundRect(scratch, radius, radius, paint)
        paint.shader = LinearGradient(
            rect.left, rect.top, rect.left, rect.bottom,
            Colors.of(topColor), Colors.of(bottomColor), Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(1.1f)
        paint.color = Colors.of(borderColor)
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.style = Paint.Style.FILL
    }

    fun fill(canvas: Canvas, rect: RectF, color: Int, radius: Float = 0f) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = color
        if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint)
        else canvas.drawRect(rect, paint)
    }

    fun outline(canvas: Canvas, rect: RectF, color: Int, width: Float, radius: Float = 0f) {
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = width
        paint.color = color
        if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint)
        else canvas.drawRect(rect, paint)
        paint.style = Paint.Style.FILL
    }

    fun button(
        canvas: Canvas,
        rect: RectF,
        label: String,
        topColor: String = STEEL_TOP,
        bottomColor: String = STEEL_BOTTOM,
        enabled: Boolean = true,
        selected: Boolean = false,
        textSize: Float = Ui.dp(14f),
        subLabel: String? = null
    ) {
        val radius = Ui.dp(8f)
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Colors.of("#66000000")
        scratch.set(rect.left, rect.top + Ui.dp(2.5f), rect.right, rect.bottom + Ui.dp(2.5f))
        canvas.drawRoundRect(scratch, radius, radius, paint)

        if (enabled) {
            paint.shader = LinearGradient(
                rect.left, rect.top, rect.left, rect.bottom,
                Colors.of(topColor), Colors.of(bottomColor), Shader.TileMode.CLAMP
            )
        } else {
            paint.color = Colors.of("#772F3945")
        }
        canvas.drawRoundRect(rect, radius, radius, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = if (selected) Ui.dp(2f) else Ui.dp(1.1f)
        paint.color = when {
            selected -> Colors.of("#FFD98A")
            enabled -> Colors.of("#59FFFFFF")
            else -> Colors.of("#33FFFFFF")
        }
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = if (enabled) Colors.of(INK) else Colors.of("#8C9AA8B4")
        val inner = rect.width() - Ui.dp(8f)
        if (subLabel == null) {
            val size = fitSize(label, textSize, inner, bold = true)
            centered(canvas, label, rect.centerX(), rect.centerY() + size * 0.36f, size, bold = true)
        } else {
            val mainSize = fitSize(label, textSize, inner, bold = true)
            centered(canvas, label, rect.centerX(), rect.centerY() - textSize * 0.04f, mainSize, bold = true)
            paint.color = if (enabled) Colors.of("#CCD6E4F0") else Colors.of("#779AA8B4")
            val subSize = fitSize(subLabel, textSize * 0.68f, inner)
            centered(canvas, subLabel, rect.centerX(), rect.centerY() + textSize * 1.0f, subSize, bold = false)
        }
    }

    /**
     * 把字級縮到剛好塞得下 maxWidth。
     * 翻譯後的長度差異很大（英文普遍比中文長），會被翻譯的文字都該走這條。
     */
    fun fitSize(text: String, desired: Float, maxWidth: Float, bold: Boolean = false): Float {
        if (maxWidth <= 0f || text.isEmpty()) return desired
        val width = measure(text, desired, bold)
        if (width <= maxWidth) return desired
        return (desired * (maxWidth / width)).coerceAtLeast(desired * 0.45f)
    }

    fun centeredFit(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        size: Float,
        maxWidth: Float,
        bold: Boolean = false,
        color: Int? = null
    ) = centered(canvas, text, centerX, baselineY, fitSize(text, size, maxWidth, bold), bold, color)

    fun leftFit(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        size: Float,
        maxWidth: Float,
        color: Int,
        bold: Boolean = false
    ) = left(canvas, text, x, baselineY, fitSize(text, size, maxWidth, bold), color, bold)

    fun centered(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baselineY: Float,
        size: Float,
        bold: Boolean = false,
        color: Int? = null
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.isFakeBoldText = bold
        if (color != null) paint.color = color
        canvas.drawText(text, centerX - paint.measureText(text) / 2f, baselineY, paint)
        paint.isFakeBoldText = false
    }

    fun left(
        canvas: Canvas,
        text: String,
        x: Float,
        baselineY: Float,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.isFakeBoldText = bold
        paint.color = color
        canvas.drawText(text, x, baselineY, paint)
        paint.isFakeBoldText = false
    }

    fun right(
        canvas: Canvas,
        text: String,
        rightX: Float,
        baselineY: Float,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.textSize = size
        paint.isFakeBoldText = bold
        paint.color = color
        canvas.drawText(text, rightX - paint.measureText(text), baselineY, paint)
        paint.isFakeBoldText = false
    }

    /**
     * 從 [start] 起算，寬度上限內最多放得下幾個字元。
     *
     * 走 Paint.breakText 而不是逐字元 substring 再量測：後者每試一個位置就配置
     * 一個新字串，而這是每幀都會跑的繪製路徑。breakText 不配置任何東西。
     */
    fun fitChars(text: String, start: Int, size: Float, maxWidth: Float): Int {
        paint.textSize = size
        paint.isFakeBoldText = false
        return paint.breakText(text, start, text.length, true, maxWidth, null)
    }

    /** 把一段長文字斷成數行，回傳每行的結束索引（不配置中繼字串）。 */
    fun wrap(text: String, size: Float, maxWidth: Float, out: MutableList<String>) {
        out.clear()
        if (text.isEmpty() || maxWidth <= 0f) return
        var i = 0
        while (i < text.length) {
            var n = fitChars(text, i, size, maxWidth)
            if (n <= 0) n = 1
            var end = i + n
            if (end < text.length) {
                // 英文盡量斷在空白處；中日文沒有空白，直接硬斷。
                val space = text.lastIndexOf(' ', end - 1)
                if (space > i) end = space + 1
            }
            out.add(text.substring(i, end).trim())
            i = end
        }
    }

    fun measure(text: String, size: Float, bold: Boolean = false): Float {
        paint.textSize = size
        paint.isFakeBoldText = bold
        val w = paint.measureText(text)
        paint.isFakeBoldText = false
        return w
    }

    /** 條狀量表：血量、補給、經驗、研發進度都走這支。 */
    fun bar(
        canvas: Canvas,
        rect: RectF,
        ratio: Float,
        fillColor: Int,
        trackColor: Int = Colors.of("#66000000")
    ) {
        val radius = rect.height() / 2f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = trackColor
        canvas.drawRoundRect(rect, radius, radius, paint)
        val k = ratio.coerceIn(0f, 1f)
        if (k <= 0f) return
        scratch.set(rect.left, rect.top, rect.left + rect.width() * k, rect.bottom)
        paint.color = fillColor
        canvas.drawRoundRect(scratch, radius, radius, paint)
    }

    /** 星等顯示：實心＋空心。 */
    fun stars(canvas: Canvas, centerX: Float, baselineY: Float, earned: Int, size: Float, total: Int = 3) {
        val glyph = "★"
        val gap = size * 1.1f
        val startX = centerX - gap * (total - 1) / 2f
        for (i in 0 until total) {
            paint.shader = null
            paint.style = Paint.Style.FILL
            paint.textSize = size
            paint.isFakeBoldText = true
            paint.color = if (i < earned) Colors.of("#FFD98A") else Colors.of("#40FFFFFF")
            val w = paint.measureText(glyph)
            canvas.drawText(glyph, startX + gap * i - w / 2f, baselineY, paint)
        }
        paint.isFakeBoldText = false
    }

    fun scrim(canvas: Canvas, w: Int, h: Int, color: String) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Colors.of(color)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    fun badge(canvas: Canvas, rect: RectF, text: String, background: Int, textColor: Int, size: Float) {
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = background
        canvas.drawRoundRect(rect, rect.height() / 2f, rect.height() / 2f, paint)
        centeredFit(
            canvas, text, rect.centerX(), rect.centerY() + size * 0.36f,
            size, rect.width() - Ui.dp(6f), bold = true, color = textColor
        )
    }

    /** 小型正六邊形（尖頂），選單與圖例用來預覽地形色。 */
    fun hexBadge(canvas: Canvas, cx: Float, cy: Float, radius: Float, fill: Int, stroke: Int) {
        path.rewind()
        for (i in 0 until 6) {
            val a = Math.toRadians((60.0 * i - 30.0)).toFloat()
            val x = cx + radius * kotlin.math.cos(a)
            val y = cy + radius * kotlin.math.sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = fill
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(1f)
        paint.color = stroke
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.FILL
    }
}
