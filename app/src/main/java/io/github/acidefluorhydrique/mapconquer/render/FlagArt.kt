// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.core.Widgets
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 國旗：emoji 交給系統字型，emoji 裡沒有的由這裡自己畫。
 *
 * 劇本檔的國旗欄位是一段 emoji，或是一個以 `@` 開頭的代號（見 tools/places.py
 * 的 flag_for）。代號對應的是 emoji 字型裡不存在的歷史國旗：蘇聯、東德、
 * 以及用鐵十字代替的納粹德國。
 *
 * 自己畫的國旗要跟旁邊的 emoji 國旗放在一起而不顯突兀，所以模仿的是 Android
 * 預設 emoji 字型的樣子：同樣的外框大小、上下緣一道波浪、一層淡淡的光影與
 * 一圈半透明的邊。尺寸不是寫死的，而是拿一面真的 emoji 國旗量出來 ——
 * 字級改了、裝置換了，兩者仍然一樣大。
 *
 * 所有呼叫端都走這裡而不是直接畫文字，介面刻意跟 [Widgets] 的文字函式對齊。
 *
 * 畫好的旗幟依像素尺寸快取成點陣圖：地圖上一個畫面就有上百枚城市徽章，
 * 每一幀重畫波浪與光影負擔不起。尺寸先量化再快取，縮放地圖時才不會
 * 每一幀都產生新的點陣圖。
 */
object FlagArt {

    /** 量尺寸用的 emoji 國旗。任何一面都行，emoji 字型裡的國旗外框全部一樣大。 */
    private const val REFERENCE = "🇩🇪"

    private const val SOVIET = "@su"
    private const val REICH = "@reich"
    private const val GDR = "@gdr"

    /** 旗面的長寬比。預設 emoji 字型把每一面國旗都塞進同一個外框。 */
    private const val ASPECT = 0.68f

    private const val CACHE_LIMIT = 32

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = Rect()
    private val dst = RectF()
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val cache = object : LinkedHashMap<String, Bitmap>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
            size > CACHE_LIMIT
    }

    fun isDrawn(flag: String): Boolean = flag.startsWith("@")

    /** 對應 [Widgets.measure]：這面旗在 [size] 字級下佔的寬度。 */
    fun measure(flag: String, size: Float): Float =
        Widgets.measure(if (isDrawn(flag)) REFERENCE else flag, size)

    /** 對應 [Widgets.left]。 */
    fun left(canvas: Canvas, flag: String, x: Float, baselineY: Float, size: Float, color: Int = INK) {
        if (isDrawn(flag)) draw(canvas, flag, x, baselineY, size)
        else Widgets.left(canvas, flag, x, baselineY, size, color)
    }

    /** 對應 [Widgets.centered]。 */
    fun centered(canvas: Canvas, flag: String, centerX: Float, baselineY: Float, size: Float, color: Int = INK) {
        if (isDrawn(flag)) draw(canvas, flag, centerX - measure(flag, size) / 2f, baselineY, size)
        else Widgets.centered(canvas, flag, centerX, baselineY, size, color = color)
    }

    /** 對應 [Widgets.centeredFit]：放不下 [maxWidth] 就縮小字級。 */
    fun centeredFit(
        canvas: Canvas,
        flag: String,
        centerX: Float,
        baselineY: Float,
        size: Float,
        maxWidth: Float,
        color: Int = INK
    ) {
        val width = measure(flag, size)
        val fitted = if (width > maxWidth && width > 0f) size * maxWidth / width else size
        centered(canvas, flag, centerX, baselineY, fitted, color)
    }

    private val INK: Int get() = Colors.of(Widgets.INK)

    // ------------------------------------------------------------------
    // 定位與快取
    // ------------------------------------------------------------------

    /** 把旗幟畫在一個 emoji 字元會佔的位置上：[left] 是字元左緣，[baselineY] 是基線。 */
    private fun draw(canvas: Canvas, flag: String, left: Float, baselineY: Float, size: Float) {
        if (size <= 0f) return
        measurePaint.textSize = size
        measurePaint.getTextBounds(REFERENCE, 0, REFERENCE.length, bounds)
        val advance = measure(flag, size)
        // emoji 的字框比旗面大一圈：旗面左右各留一點邊，垂直置中在字框裡。
        val width = advance * 0.92f
        val pixelWidth = max(8, (ceil(width / 4f) * 4f).toInt())
        val bitmap = composed(flag, pixelWidth) ?: return
        val height = width * bitmap.height / bitmap.width
        val centerY = baselineY + bounds.exactCenterY()
        val x = left + (advance - width) / 2f
        dst.set(x, centerY - height / 2f, x + width, centerY + height / 2f)
        canvas.drawBitmap(bitmap, null, dst, blit)
    }

    private fun composed(flag: String, pixelWidth: Int): Bitmap? {
        val key = "$flag:$pixelWidth"
        cache[key]?.let { return it }
        val design = designFor(flag) ?: return null
        val bitmap = wave(pixelWidth, design)
        cache[key] = bitmap
        return bitmap
    }

    // ------------------------------------------------------------------
    // 波浪、光影與邊框
    // ------------------------------------------------------------------

    /**
     * 先把平的旗面畫好，再用網格把它貼成一道波浪。波浪的振幅、光影與邊框
     * 都是照預設 emoji 國旗的樣子估的：上下緣起伏一次、光從左上來、
     * 邊緣有一圈很淡的深色。
     */
    private fun wave(width: Int, design: (Canvas, Float, Float) -> Unit): Bitmap {
        val body = max(6, (width * ASPECT).roundToInt())
        val amp = max(1f, body * 0.055f)
        val height = body + (amp * 2f).roundToInt() + 2

        val flat = Bitmap.createBitmap(width, body, Bitmap.Config.ARGB_8888)
        design(Canvas(flat), width.toFloat(), body.toFloat())

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val columns = 16
        val verts = FloatArray((columns + 1) * 2 * 2)
        val top = FloatArray(columns + 1)
        for (i in 0..columns) {
            val t = i / columns.toFloat()
            val x = t * width
            val y = 1f + amp + amp * sin((t * 1.6f - 0.3f) * PI.toFloat())
            top[i] = y
            verts[i * 2] = x
            verts[i * 2 + 1] = y
            verts[(columns + 1 + i) * 2] = x
            verts[(columns + 1 + i) * 2 + 1] = y + body
        }
        canvas.drawBitmapMesh(flat, columns, 1, verts, 0, null, 0, blit)
        flat.recycle()

        // 光影：沿著波浪明暗交替，只蓋在旗面上（SRC_ATOP）。
        val shade = Paint(Paint.ANTI_ALIAS_FLAG)
        shade.shader = LinearGradient(
            0f, 0f, width.toFloat(), 0f,
            intArrayOf(
                Colors.of("#26FFFFFF"), Colors.of("#00FFFFFF"), Colors.of("#1F000000"),
                Colors.of("#00000000"), Colors.of("#1AFFFFFF"), Colors.of("#14000000")
            ),
            floatArrayOf(0f, 0.22f, 0.45f, 0.62f, 0.8f, 1f),
            Shader.TileMode.CLAMP
        )
        shade.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)

        val edge = Path()
        edge.moveTo(0f, top[0])
        for (i in 1..columns) edge.lineTo(verts[i * 2], top[i])
        for (i in columns downTo 0) edge.lineTo(verts[i * 2], top[i] + body)
        edge.close()
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
        stroke.style = Paint.Style.STROKE
        stroke.strokeWidth = max(1f, width / 40f)
        stroke.strokeJoin = Paint.Join.ROUND
        stroke.color = Colors.of("#40000000")
        canvas.drawPath(edge, stroke)
        return out
    }

    // ------------------------------------------------------------------
    // 旗面（平的，寬 w、高 h）
    // ------------------------------------------------------------------

    private fun designFor(flag: String): ((Canvas, Float, Float) -> Unit)? = when (flag) {
        SOVIET -> this::soviet
        REICH -> this::ironCross
        GDR -> this::gdr
        else -> null
    }

    private val GOLD = "#FFD24A"

    /** 蘇聯：紅底，左上角一顆描邊的星、底下交叉的鐮刀與錘子。 */
    private fun soviet(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Colors.of("#D22B20")
        canvas.drawRect(0f, 0f, w, h, paint)

        paint.color = Colors.of(GOLD)
        paint.style = Paint.Style.STROKE
        paint.strokeJoin = Paint.Join.MITER
        paint.strokeWidth = h * 0.028f
        canvas.drawPath(star(w * 0.2f, h * 0.17f, h * 0.085f), paint)

        val cx = w * 0.2f
        val cy = h * 0.47f
        val s = h * 0.34f
        paint.strokeCap = Paint.Cap.ROUND
        // 鐮刀：大半個圓弧，開口朝右上，底端接一小段刀柄。
        paint.strokeWidth = s * 0.13f
        val r = s * 0.36f
        val arc = RectF(cx - r, cy - r - s * 0.04f, cx + r, cy + r - s * 0.04f)
        canvas.drawArc(arc, -40f, 250f, false, paint)
        val endAngle = Math.toRadians(210.0)
        val ex = cx + r * cos(endAngle).toFloat()
        val ey = cy - s * 0.04f + r * sin(endAngle).toFloat()
        canvas.drawLine(ex, ey, ex - s * 0.12f, ey + s * 0.34f, paint)
        // 錘子：柄從左下斜向右上，頭垂直於柄。
        paint.strokeWidth = s * 0.11f
        val hx0 = cx - s * 0.32f
        val hy0 = cy + s * 0.38f
        val hx1 = cx + s * 0.2f
        val hy1 = cy - s * 0.16f
        canvas.drawLine(hx0, hy0, hx1, hy1, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = s * 0.17f
        val dx = (hx1 - hx0)
        val dy = (hy1 - hy0)
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        val px = -dy / len * s * 0.2f
        val py = dx / len * s * 0.2f
        canvas.drawLine(hx1 - px, hy1 - py, hx1 + px, hy1 + py, paint)
    }

    /**
     * 納粹德國：史實是卐字旗。這裡刻意改用德軍的鐵十字 —— 原野灰底、
     * 黑色的十字四臂向外張開，外面一圈白邊。底色要夠深，白邊才看得見；
     * 白底的版本在地圖徽章的尺寸下會糊成兩個括號。
     */
    private fun ironCross(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Colors.of("#6E7562")
        canvas.drawRect(0f, 0f, w, h, paint)

        val cx = w / 2f
        val cy = h / 2f
        val size = h * 0.84f
        paint.color = Colors.of("#F4F4F2")
        canvas.drawPath(crossPattee(cx, cy, size * 1.13f), paint)
        paint.color = Colors.of("#141414")
        canvas.drawPath(crossPattee(cx, cy, size * 0.9f), paint)
    }

    /**
     * 東德（1959 年起）：黑紅金三色，中間是麥穗環圍著錘子與圓規。
     * 1959 年以前的東德國旗跟西德一樣，那一段直接用 emoji（見 places.flag_for）。
     */
    private fun gdr(canvas: Canvas, w: Float, h: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val band = h / 3f
        paint.color = Colors.of("#1A1A1A")
        canvas.drawRect(0f, 0f, w, band, paint)
        paint.color = Colors.of("#DD0000")
        canvas.drawRect(0f, band, w, band * 2f, paint)
        paint.color = Colors.of("#FFCE00")
        canvas.drawRect(0f, band * 2f, w, h, paint)

        val cx = w / 2f
        val cy = h / 2f
        val r = h * 0.3f
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        // 麥穗環：一圈底部開口的金色弧線，外側再描一圈深色讓它在金色帶上也看得見。
        val ring = RectF(cx - r, cy - r, cx + r, cy + r)
        paint.strokeWidth = r * 0.26f
        paint.color = Colors.of("#8A6A00")
        canvas.drawArc(ring, 120f, 300f, false, paint)
        paint.strokeWidth = r * 0.18f
        paint.color = Colors.of("#F2C200")
        canvas.drawArc(ring, 120f, 300f, false, paint)
        // 圓規：倒 V，頂端一個小圓。
        paint.strokeWidth = r * 0.12f
        paint.color = Colors.of("#FFD84D")
        canvas.drawLine(cx, cy - r * 0.55f, cx - r * 0.4f, cy + r * 0.5f, paint)
        canvas.drawLine(cx, cy - r * 0.55f, cx + r * 0.4f, cy + r * 0.5f, paint)
        // 錘子：直立的柄，頂端一塊橫的錘頭。
        paint.strokeWidth = r * 0.12f
        canvas.drawLine(cx, cy - r * 0.35f, cx, cy + r * 0.55f, paint)
        paint.strokeCap = Paint.Cap.BUTT
        paint.strokeWidth = r * 0.2f
        canvas.drawLine(cx - r * 0.28f, cy - r * 0.42f, cx + r * 0.28f, cy - r * 0.42f, paint)
    }

    // ------------------------------------------------------------------
    // 幾何
    // ------------------------------------------------------------------

    private fun star(cx: Float, cy: Float, r: Float): Path {
        val path = Path()
        for (i in 0 until 10) {
            val radius = if (i % 2 == 0) r else r * 0.42f
            val angle = -PI / 2 + i * PI / 5
            val x = cx + radius * cos(angle).toFloat()
            val y = cy + radius * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    /** 四臂向外張開的十字（鐵十字的形狀），外接正方形邊長 [size]。 */
    private fun crossPattee(cx: Float, cy: Float, size: Float): Path {
        val half = size / 2f
        val inner = size * 0.13f
        val outer = size * 0.27f
        val path = Path()
        // 從上臂右側開始，順時針繞一圈；每一臂在中心窄、在外端寬。
        path.moveTo(cx + inner, cy - inner)
        path.lineTo(cx + outer, cy - half)
        path.lineTo(cx - outer, cy - half)
        path.lineTo(cx - inner, cy - inner)
        path.lineTo(cx - half, cy - outer)
        path.lineTo(cx - half, cy + outer)
        path.lineTo(cx - inner, cy + inner)
        path.lineTo(cx - outer, cy + half)
        path.lineTo(cx + outer, cy + half)
        path.lineTo(cx + inner, cy + inner)
        path.lineTo(cx + half, cy + outer)
        path.lineTo(cx + half, cy - outer)
        path.close()
        return path
    }
}
