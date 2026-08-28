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

    fun draw(canvas: Canvas, camera: Camera, overlay: MapOverlay, animateFog: Boolean) {
        ensureHexPath(camera.hexSize)
        camera.visibleBounds(bounds)

        drawTerrain(canvas, camera, overlay, animateFog)
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

    private inline fun forEachVisibleTile(camera: Camera, action: (tile: Int, cx: Float, cy: Float) -> Unit) {
        val layout = camera.layout
        for (row in bounds[1]..bounds[3]) {
            val cy = camera.screenY(layout.centerYOffset(row))
            val base = row * map.cols
            for (col in bounds[0]..bounds[2]) {
                val cx = camera.screenX(layout.centerXOffset(col, row))
                action(base + col, cx, cy)
            }
        }
    }

    private fun drawTerrain(canvas: Canvas, camera: Camera, overlay: MapOverlay, dimUnknown: Boolean) {
        paint.style = Paint.Style.FILL
        forEachVisibleTile(camera) { tile, cx, cy ->
            val explored = !dimUnknown || session.isExplored(tile)
            canvas.save()
            canvas.translate(cx, cy)
            if (!explored) {
                paint.shader = null
                paint.color = Palette.UNEXPLORED
                canvas.drawPath(hexPath, paint)
            } else {
                val terrain = map.terrainAt(tile)
                var colour = Palette.terrainColour(terrain, tile)
                val visible = !dimUnknown || session.isVisible(tile)
                if (!visible) colour = Palette.fogged(colour)
                paint.color = colour
                canvas.drawPath(hexPath, paint)

                val owner = session.ownerOfTile(tile)
                if (owner >= 0) {
                    var tint = Palette.ownershipTint(session, owner)
                    if (!visible) tint = Colors.alpha(tint, 0x48)
                    paint.color = tint
                    canvas.drawPath(hexPath, paint)
                }
                if (overlay.showSupply && session.isSupplied(tile)) {
                    paint.color = Palette.SUPPLY_HINT
                    canvas.drawPath(hexPath, paint)
                }
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
                val tile = row * map.cols + col
                if (!session.isExplored(tile)) continue
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
                val tile = row * map.cols + col
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
        if (selected >= 0 && map.inBounds(map.colOf(selected), map.rowOf(selected))) {
            canvas.save()
            canvas.translate(
                camera.screenX(layout.centerXOffset(map.colOf(selected), map.rowOf(selected))),
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
     * 用幾何圖形而不是點陣圖示：一來 APK 裡不必放素材（F-Droid 上這是加分的），
     * 二來向量在任何縮放下都清楚，三來城市等級可以直接用「幾個方塊」表達，
     * 玩家不必記圖示對應表。
     */
    private fun drawCities(canvas: Canvas, camera: Camera) {
        val layout = camera.layout
        val size = camera.hexSize
        val showNames = size >= Ui.dp(15f)
        for (province in map.provinces) {
            if (!province.hasCity) continue
            val tile = province.capitalTile
            val col = map.colOf(tile)
            val row = map.rowOf(tile)
            if (col < bounds[0] || col > bounds[2] || row < bounds[1] || row > bounds[3]) continue
            if (!session.isExplored(tile)) continue

            val cx = camera.screenX(layout.centerXOffset(col, row))
            val cy = camera.screenY(layout.centerYOffset(row))
            val owner = session.provinceOwner[province.id]
            val plate = if (owner >= 0) Palette.nationColour(session, owner) else Colors.of("#8A94A0")

            val half = size * 0.30f
            rect.set(cx - half, cy - half, cx + half, cy + half)
            paint.style = Paint.Style.FILL
            paint.color = Colors.of("#B3000000")
            canvas.drawRoundRect(rect, half * 0.35f, half * 0.35f, paint)
            paint.color = Colors.scale(plate, 1.15f)
            rect.inset(size * 0.055f, size * 0.055f)
            canvas.drawRoundRect(rect, half * 0.3f, half * 0.3f, paint)

            // 城市等級：中央疊上等級數量的小方塊。
            paint.color = Colors.of("#F5F8FB")
            val pip = size * 0.075f
            val gap = pip * 2.2f
            val startX = cx - gap * (province.cityTier - 1) / 2f
            val pipY = cy
            for (i in 0 until province.cityTier) {
                rect.set(startX + gap * i - pip, pipY - pip, startX + gap * i + pip, pipY + pip)
                canvas.drawRect(rect, paint)
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
            val col = map.colOf(unit.tile)
            val row = map.rowOf(unit.tile)
            if (col < bounds[0] - 1 || col > bounds[2] + 1 || row < bounds[1] - 1 || row > bounds[3] + 1) continue

            var cx = camera.screenX(layout.centerXOffset(col, row))
            var cy = camera.screenY(layout.centerYOffset(row))

            // 移動動畫：把這支部隊畫在起點與終點之間。
            if (overlay.animUnit === unit && overlay.animFrom >= 0 && overlay.animTo >= 0) {
                val fromX = camera.screenX(layout.centerXOffset(map.colOf(overlay.animFrom), map.rowOf(overlay.animFrom)))
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

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(1f)
        paint.color = if (unit.isSpent && unit.nationId == session.playerNationId) {
            Colors.of("#66FFFFFF")
        } else {
            Palette.unitOutline(session, unit.nationId)
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

        // 補給告急的紅點：這是玩家最需要一眼看到的異常狀態。
        if (unit.supply < ArmyUnit.SUPPLY_STRAINED && size >= Ui.dp(10f)) {
            paint.color = Palette.supplyColour(unit.supply / ArmyUnit.MAX_SUPPLY.toFloat())
            canvas.drawCircle(cx - w / 2f + size * 0.09f, top + size * 0.09f, size * 0.07f, paint)
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
            canvas.drawLine(
                camera.screenX(layout.centerXOffset(map.colOf(a), map.rowOf(a))),
                camera.screenY(layout.centerYOffset(map.rowOf(a))),
                camera.screenX(layout.centerXOffset(map.colOf(b), map.rowOf(b))),
                camera.screenY(layout.centerYOffset(map.rowOf(b))),
                paint
            )
        }
        paint.style = Paint.Style.FILL
        val last = overlay.path.last()
        canvas.drawCircle(
            camera.screenX(layout.centerXOffset(map.colOf(last), map.rowOf(last))),
            camera.screenY(layout.centerYOffset(map.rowOf(last))),
            Ui.dp(3.2f), paint
        )
    }
}
