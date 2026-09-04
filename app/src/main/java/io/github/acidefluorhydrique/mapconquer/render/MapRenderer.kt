// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.core.Strings
import io.github.acidefluorhydrique.mapconquer.core.Ui
import io.github.acidefluorhydrique.mapconquer.core.Widgets
import io.github.acidefluorhydrique.mapconquer.game.Session
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Domain

/**
 * 地圖繪製。
 *
 * 效能上有兩個關鍵決定：
 *
 * 1. **六角形的 Path 只建一次。** 每格重建一個 Path 會在每影格產生上百個
 *    短命物件；這裡把一個以原點為中心的單位六角形快取起來，
 *    畫每一格時改用 canvas.translate 移動座標系。
 * 2. **視野剔除走欄列範圍而不是逐格判斷。** 攝影機直接算得出可見的
 *    col/row 區間，於是永遠只碰到畫面上的那幾百格，
 *    地圖再大都不影響影格時間。
 */
class MapRenderer(private val session: Session) {

    private val map = session.map
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hexPath = Path()
    private var hexPathSize = -1f
    private val bounds = IntArray(4)
    private val neighbourBuf = IntArray(6)
    private val rect = RectF()
    private val corners = FloatArray(12)

    /** visibleCol 的「不在畫面上」哨兵值。 */
    private val OFFSCREEN = Int.MIN_VALUE

    fun draw(canvas: Canvas, camera: Camera, overlay: MapOverlay) {
        ensureHexPath(camera.hexSize)
        camera.visibleBounds(bounds)

        drawTerrain(canvas, camera, overlay)
        drawCoastline(canvas, camera)
        drawBorders(canvas, camera)
        drawOverlayTiles(canvas, camera, overlay)
        drawCities(canvas, camera)
        drawUnits(canvas, camera, overlay)
        drawPath(canvas, camera, overlay)
    }

    // ------------------------------------------------------------------

    private fun ensureHexPath(size: Float) {
        if (size == hexPathSize) return
        hexPathSize = size
        hexPath.rewind()
        for (i in 0 until 6) {
            val angle = Math.toRadians(60.0 * i - 90.0)
            val x = (size * Math.cos(angle)).toFloat()
            val y = (size * Math.sin(angle)).toFloat()
            if (i == 0) hexPath.moveTo(x, y) else hexPath.lineTo(x, y)
        }
        hexPath.close()
    }

    /**
     * 走過所有可見格。
     *
     * 環繞地圖上，欄號可能是負的或超過 cols —— **格子索引要折回去，
     * 螢幕位置要用原始欄號算**。這兩件事分開，接縫才會無縫：
     * 太平洋左右兩側畫的是同一批格子，只是世界座標差了一整圈。
     */
    private inline fun forEachVisibleTile(camera: Camera, action: (tile: Int, cx: Float, cy: Float) -> Unit) {
        val layout = camera.layout
        for (row in bounds[1]..bounds[3]) {
            val cy = camera.screenY(layout.centerYOffset(row))
            for (col in bounds[0]..bounds[2]) {
                val cx = camera.screenX(layout.centerXOffset(col, row))
                action(map.indexWrapped(col, row), cx, cy)
            }
        }
    }

    /**
     * 把某一格的欄號搬到目前可見範圍內。回傳 [OFFSCREEN] 代表它不在畫面上。
     *
     * 城市、部隊、行軍路線都是「已知某一格，要問它畫在哪」，跟逐格掃描的
     * 方向相反，所以需要這個反查。
     */
    private fun visibleCol(col: Int): Int {
        if (!map.wrapX) return if (col in bounds[0]..bounds[2]) col else OFFSCREEN
        val shifted = ((col - bounds[0]) % map.cols + map.cols) % map.cols + bounds[0]
        return if (shifted <= bounds[2]) shifted else OFFSCREEN
    }

    private fun drawTerrain(canvas: Canvas, camera: Camera, overlay: MapOverlay) {
        paint.style = Paint.Style.FILL
        forEachVisibleTile(camera) { tile, cx, cy ->
            canvas.save()
            canvas.translate(cx, cy)
            paint.shader = null
            paint.color = Palette.terrainColour(map.terrainAt(tile), tile)
            canvas.drawPath(hexPath, paint)

            val owner = session.ownerOfTile(tile)
            if (owner >= 0) {
                paint.color = Palette.ownershipTint(session, owner)
                canvas.drawPath(hexPath, paint)
            }
            if (overlay.showSupply && session.isSupplied(tile)) {
                paint.color = Palette.SUPPLY_HINT
                canvas.drawPath(hexPath, paint)
            }
            canvas.restore()
        }

        if (overlay.showGrid && camera.hexSize >= Ui.dp(11f)) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Ui.dp(0.6f)
            paint.color = Palette.GRID
            forEachVisibleTile(camera) { _, cx, cy ->
                canvas.save()
                canvas.translate(cx, cy)
                canvas.drawPath(hexPath, paint)
                canvas.restore()
            }
            paint.style = Paint.Style.FILL
        }
    }

    /**
     * 海岸線。
     *
     * 只畫「陸地格朝向水域的那一條邊」。這是整張地圖上最重要的一條線 ——
     * 六角格的地形色再怎麼調，遠看都會糊成一片，而一道亮邊可以讓
     * 海陸關係在任何縮放下都是瞬間可讀的。
     */
    private fun drawCoastline(canvas: Canvas, camera: Camera) {
        val layout = camera.layout
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = Ui.dp(1.1f)
        paint.color = Palette.COASTLINE

        for (row in bounds[1]..bounds[3]) {
            for (col in bounds[0]..bounds[2]) {
                val tile = map.indexWrapped(col, row)
                if (!map.isLand(tile)) continue
                val cx = camera.screenX(layout.centerXOffset(col, row))
                val cy = camera.screenY(layout.centerYOffset(row))
                layout.corners(cx, cy, corners)
                val n = map.neighbours(tile, neighbourBuf)
                for (i in 0 until n) {
                    if (map.isWater(neighbourBuf[i])) drawEdge(canvas, directionToEdge(i))
                }
                // 地圖邊緣沒有鄰居的那幾條邊不畫，免得整張圖被框起來。
            }
        }
        paint.style = Paint.Style.FILL
    }

    /**
     * 國界。
     *
     * 只畫「兩邊歸屬不同」的那條邊，而不是把每個省都描一圈 ——
     * 後者會讓同一國內部的省界跟對外的國界一樣粗，地圖上讀不出勢力範圍。
     * 省界細而暗，國界粗而亮，這是玩家判斷戰線的主要視覺線索。
     */
    private fun drawBorders(canvas: Canvas, camera: Camera) {
        val layout = camera.layout
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND

        for (row in bounds[1]..bounds[3]) {
            for (col in bounds[0]..bounds[2]) {
                val tile = map.indexWrapped(col, row)
                val province = map.provinceOf[tile]
                if (province < 0) continue
                val owner = session.ownerOfTile(tile)
                val cx = camera.screenX(layout.centerXOffset(col, row))
                val cy = camera.screenY(layout.centerYOffset(row))
                layout.corners(cx, cy, corners)

                val n = map.neighbours(tile, neighbourBuf)
                for (i in 0 until n) {
                    val other = neighbourBuf[i]
                    val otherProvince = map.provinceOf[other]
                    if (otherProvince == province) continue
                    val otherOwner = session.ownerOfTile(other)
                    val national = otherOwner != owner
                    // 只讓索引小的那一格畫，避免每條邊被畫兩次。
                    if (!national && other < tile) continue
                    if (national && otherOwner >= 0 && owner >= 0 && other < tile) continue

                    if (national) {
                        paint.strokeWidth = Ui.dp(1.6f)
                        paint.color = if (owner >= 0) {
                            Colors.alpha(Colors.scale(Palette.nationColour(session, owner), 1.6f), 0xE0)
                        } else {
                            Colors.of("#66FFFFFF")
                        }
                    } else {
                        if (camera.hexSize < Ui.dp(9f)) continue
                        paint.strokeWidth = Ui.dp(0.7f)
                        paint.color = Palette.PROVINCE_BORDER
                    }
                    drawEdge(canvas, directionToEdge(i))
                }
            }
        }
        paint.style = Paint.Style.FILL
    }

    /**
     * 方向索引 → 六角形的哪一條邊。
     *
     * 頂點順序從正上方（-90°）開始順時針；鄰居順序是東、東北、西北、西、西南、東南。
     * 這張對照表就是兩者的接合處，改動任一邊都要同步改這裡。
     */
    private fun directionToEdge(direction: Int): Int = when (direction) {
        0 -> 1 // 東
        1 -> 0 // 東北
        2 -> 5 // 西北
        3 -> 4 // 西
        4 -> 3 // 西南
        else -> 2 // 東南
    }

    private fun drawEdge(canvas: Canvas, edge: Int) {
        val a = edge * 2
        val b = ((edge + 1) % 6) * 2
        canvas.drawLine(corners[a], corners[a + 1], corners[b], corners[b + 1], paint)
    }

    private fun drawOverlayTiles(canvas: Canvas, camera: Camera, overlay: MapOverlay) {
        if (overlay.selectedUnit == null) return
        val layout = camera.layout
        paint.style = Paint.Style.FILL
        for (row in bounds[1]..bounds[3]) {
            for (col in bounds[0]..bounds[2]) {
                val tile = map.indexWrapped(col, row)
                val movable = overlay.movable[tile]
                val attackable = overlay.attackable[tile]
                if (!movable && !attackable) continue
                canvas.save()
                canvas.translate(
                    camera.screenX(layout.centerXOffset(col, row)),
                    camera.screenY(layout.centerYOffset(row))
                )
                paint.color = if (attackable) Palette.ATTACK_RANGE else Palette.MOVE_RANGE
                canvas.drawPath(hexPath, paint)
                canvas.restore()
            }
        }

        // 選取框最後畫，才不會被範圍色蓋掉。
        val selected = overlay.selectedTile
        val selectedCol = if (selected >= 0) visibleCol(map.colOf(selected)) else OFFSCREEN
        if (selectedCol != OFFSCREEN) {
            canvas.save()
            canvas.translate(
                camera.screenX(layout.centerXOffset(selectedCol, map.rowOf(selected))),
                camera.screenY(layout.centerYOffset(map.rowOf(selected)))
            )
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Ui.dp(2.2f)
            paint.color = Palette.SELECTION
            canvas.drawPath(hexPath, paint)
            paint.style = Paint.Style.FILL
            canvas.restore()
        }
    }

    /**
     * 城市。
     *
     * 一枚國旗徽章加上等級點。旗幟是 emoji，由系統字型畫，APK 裡不必放素材
     * （F-Droid 上這是加分的），而且在任何縮放下都清楚。等級用點的數量表達，
     * 玩家不必記圖示對應表。
     */
    private fun drawCities(canvas: Canvas, camera: Camera) {
        val layout = camera.layout
        val size = camera.hexSize
        val showNames = size >= Ui.dp(15f)
        for (province in map.provinces) {
            if (!province.hasCity) continue
            val tile = province.capitalTile
            val row = map.rowOf(tile)
            if (row < bounds[1] || row > bounds[3]) continue
            val col = visibleCol(map.colOf(tile))
            if (col == OFFSCREEN) continue

            val cx = camera.screenX(layout.centerXOffset(col, row))
            val cy = camera.screenY(layout.centerYOffset(row))
            val owner = session.provinceOwner[province.id]
            val plate = if (owner >= 0) Palette.nationColour(session, owner) else Colors.of("#8A94A0")
            val flag = if (owner < 0) "" else session.nations.getOrNull(owner)?.flag.orEmpty()

            // 城市是一枚旗幟徽章。顏色本身認不了人 —— 一張世界地圖上有一百多個
            // 國家，色相根本不夠分，玩家不該被迫先把顏色背回國名。所以底色只當
            // 襯底，識別交給旗幟。做成扁的圓角矩形而不是圓形，是因為旗幟 emoji
            // 本來就是長方的，塞進圓裡會左右溢出。
            val plateW = size * 0.76f
            val plateH = size * 0.54f
            val corner = plateH * 0.24f
            rect.set(cx - plateW / 2f, cy - plateH / 2f, cx + plateW / 2f, cy + plateH / 2f)
            paint.style = Paint.Style.FILL
            paint.color = Colors.of("#B3000000")
            rect.offset(0f, size * 0.035f)
            canvas.drawRoundRect(rect, corner, corner, paint)
            rect.offset(0f, -size * 0.035f)
            paint.color = Colors.scale(plate, 1.15f)
            canvas.drawRoundRect(rect, corner, corner, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = size * 0.045f
            paint.color = Colors.of("#E6F2F6FA")
            canvas.drawRoundRect(rect, corner, corner, paint)
            paint.style = Paint.Style.FILL

            if (flag.isNotEmpty() && plateH >= Ui.dp(7f)) {
                Widgets.centeredFit(
                    canvas, flag, cx, cy + plateH * 0.36f,
                    plateH * 0.94f, plateW * 0.86f,
                    color = Colors.of("#FFF2F6FA")
                )
            }

            // 城防：只在破損時畫。滿血的城市不需要佔用視覺注意力，
            // 而一條掉了一半的血條就是「這裡正在被攻」最直接的說法。
            val maxHp = province.maxCityHp
            val hp = session.cityHp[province.id]
            if (maxHp > 0 && hp < maxHp && size >= Ui.dp(9f)) {
                rect.set(
                    cx - plateW / 2f, cy - plateH / 2f - size * 0.18f,
                    cx + plateW / 2f, cy - plateH / 2f - size * 0.06f
                )
                Widgets.bar(canvas, rect, hp / maxHp.toFloat(), Palette.cityHealthColour(hp, maxHp))
            }

            // 城市等級：徽章下方的小點，不跟旗幟搶位置。
            if (size >= Ui.dp(9f)) {
                paint.color = Colors.of("#E6F5F8FB")
                val pip = size * 0.055f
                val gap = pip * 2.6f
                val startX = cx - gap * (province.cityTier - 1) / 2f
                val pipY = cy + plateH / 2f + pip * 2.1f
                for (i in 0 until province.cityTier) {
                    canvas.drawCircle(startX + gap * i, pipY, pip, paint)
                }
            }

            if (showNames) {
                val label = Strings.byName(province.nameKey)
                val textSize = Ui.dp(8f)
                Widgets.centered(
                    canvas, label, cx, cy + size * 0.92f, textSize,
                    bold = true, color = Colors.of("#CC000000")
                )
                Widgets.centered(
                    canvas, label, cx, cy + size * 0.90f, textSize,
                    bold = true, color = Colors.of("#F2F6FA")
                )
            }
        }
    }

    // ------------------------------------------------------------------

    private fun drawUnits(canvas: Canvas, camera: Camera, overlay: MapOverlay) {
        val layout = camera.layout
        val size = camera.hexSize
        if (size < Ui.dp(7f)) return

        for (unit in session.units) {
            if (!unit.isAlive || unit.isLoaded) continue
            if (!session.isUnitVisibleToPlayer(unit)) continue
            val row = map.rowOf(unit.tile)
            if (row < bounds[1] - 1 || row > bounds[3] + 1) continue
            val col = visibleCol(map.colOf(unit.tile))
            if (col == OFFSCREEN) continue

            var cx = camera.screenX(layout.centerXOffset(col, row))
            var cy = camera.screenY(layout.centerYOffset(row))

            // 移動動畫：把這支部隊畫在起點與終點之間。
            if (overlay.animUnit === unit && overlay.animFrom >= 0 && overlay.animTo >= 0) {
                val fromCol = visibleCol(map.colOf(overlay.animFrom))
                val fromX = camera.screenX(
                    layout.centerXOffset(if (fromCol == OFFSCREEN) map.colOf(overlay.animFrom) else fromCol, map.rowOf(overlay.animFrom))
                )
                val fromY = camera.screenY(layout.centerYOffset(map.rowOf(overlay.animFrom)))
                val t = overlay.animProgress
                cx = fromX + (cx - fromX) * t
                cy = fromY + (cy - fromY) * t
            }

            drawUnitBadge(canvas, unit, cx, cy, size)
        }
    }

    private fun drawUnitBadge(canvas: Canvas, unit: ArmyUnit, cx: Float, cy: Float, size: Float) {
        val w = size * 0.86f
        val h = size * 0.66f
        // 空中單位往上偏、海上單位往下偏：同一格有兩層時仍然分得開。
        val yShift = when (unit.kind.domain) {
            Domain.AIR -> -size * 0.34f
            Domain.SEA -> size * 0.10f
            Domain.LAND -> 0f
        }
        val top = cy - h / 2f + yShift
        rect.set(cx - w / 2f, top, cx + w / 2f, top + h)

        paint.style = Paint.Style.FILL
        paint.color = Colors.of("#77000000")
        rect.offset(0f, size * 0.05f)
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, paint)
        rect.offset(0f, -size * 0.05f)

        paint.color = Palette.unitPlate(session, unit.nationId)
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, paint)

        // 外框是敵我，填色是國別。一百多個國家的顏色一定有相近的，
        // 但「這支是不是我的」不能靠分辨色差。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(1.6f)
        val outline = Palette.relationOutline(session, unit.nationId)
        paint.color = if (unit.isSpent && unit.nationId == session.playerNationId) {
            Colors.alpha(outline, 0x66)
        } else {
            outline
        }
        canvas.drawRoundRect(rect, h * 0.25f, h * 0.25f, paint)
        paint.style = Paint.Style.FILL

        if (size >= Ui.dp(10f)) {
            paint.color = Palette.domainAccent(unit.kind.domain)
            UnitGlyphs.draw(canvas, unit.kind, rect.centerX(), rect.centerY(), w, h, paint)
        }

        // 血條。滿血就不畫 —— 地圖上該只有「出事了」的部隊會吸引注意力。
        if (unit.hp < ArmyUnit.MAX_HP && size >= Ui.dp(9f)) {
            val ratio = unit.hp / ArmyUnit.MAX_HP.toFloat()
            rect.set(cx - w / 2f, top + h + size * 0.04f, cx + w / 2f, top + h + size * 0.16f)
            Widgets.bar(canvas, rect, ratio, Palette.healthColour(ratio))
        }

        // 等級：右上角的小星點。
        if (unit.level > 1 && size >= Ui.dp(13f)) {
            paint.color = Colors.of("#FFD98A")
            val r = size * 0.055f
            for (i in 0 until unit.level - 1) {
                canvas.drawCircle(cx + w / 2f - r - i * r * 2.4f, top + r + size * 0.02f, r, paint)
            }
        }

        // 國旗：左上角，只在放得夠大時才畫。emoji 由系統字型負責，
        // 缺字型的裝置會退化成兩個字母，仍然認得出國別。
        val flag = session.nations.getOrNull(unit.nationId)?.flag.orEmpty()
        if (flag.isNotEmpty() && size >= Ui.dp(11f)) {
            Widgets.centered(
                canvas, flag, cx - w / 2f + size * 0.13f, top + size * 0.2f,
                size * 0.30f, color = Colors.of("#FFFFFFFF")
            )
        }

        // 補給告急的紅點：這是玩家最需要一眼看到的異常狀態。
        // 放右下角，避開左上的國旗。
        if (unit.supply < ArmyUnit.SUPPLY_STRAINED && size >= Ui.dp(10f)) {
            paint.color = Palette.supplyColour(unit.supply / ArmyUnit.MAX_SUPPLY.toFloat())
            canvas.drawCircle(cx + w / 2f - size * 0.09f, top + h - size * 0.09f, size * 0.07f, paint)
        }
    }

    /** 行軍路線的虛線與終點箭頭。 */
    private fun drawPath(canvas: Canvas, camera: Camera, overlay: MapOverlay) {
        if (overlay.path.size < 2) return
        val layout = camera.layout
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(2f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = Colors.of("#CCFFD98A")
        for (i in 0 until overlay.path.size - 1) {
            val a = overlay.path[i]
            val b = overlay.path[i + 1]
            val ca = visibleCol(map.colOf(a))
            val cb = visibleCol(map.colOf(b))
            if (ca == OFFSCREEN || cb == OFFSCREEN) continue
            canvas.drawLine(
                camera.screenX(layout.centerXOffset(ca, map.rowOf(a))),
                camera.screenY(layout.centerYOffset(map.rowOf(a))),
                camera.screenX(layout.centerXOffset(cb, map.rowOf(b))),
                camera.screenY(layout.centerYOffset(map.rowOf(b))),
                paint
            )
        }
        paint.style = Paint.Style.FILL
        val last = overlay.path.last()
        val lastCol = visibleCol(map.colOf(last))
        if (lastCol != OFFSCREEN) {
            canvas.drawCircle(
                camera.screenX(layout.centerXOffset(lastCol, map.rowOf(last))),
                camera.screenY(layout.centerYOffset(map.rowOf(last))),
                Ui.dp(3.2f), paint
            )
        }
    }
}
