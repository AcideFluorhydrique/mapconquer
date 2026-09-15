// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import io.github.acidefluorhydrique.mapconquer.R
import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.core.Strings
import io.github.acidefluorhydrique.mapconquer.core.Ui
import io.github.acidefluorhydrique.mapconquer.core.Widgets
import io.github.acidefluorhydrique.mapconquer.game.Orders
import io.github.acidefluorhydrique.mapconquer.game.Session
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit

/**
 * 遊戲中的常駐介面：上方國情列、下方選取資訊列、事件跑馬燈。
 *
 * 版面規則只有一條 —— **地圖永遠是主角**。
 * 所以 HUD 只佔上下兩條窄帶，中間完全留給地圖；
 * 需要大面積的東西（生產、科技、目標）一律做成可關閉的面板，
 * 而不是常駐的側欄。手機橫向的寬度太寶貴，側欄會把戰場切掉三分之一。
 */
class HudRenderer(private val session: Session) {

    private val rect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 已經按過一次、等待確認的宣戰對象；-1 代表沒有。由 GameView 設定。 */
    var armedWarTarget: Int = -1

    fun draw(
        canvas: Canvas,
        buttons: ButtonLayer,
        overlay: MapOverlay,
        aiThinking: Boolean,
        canUndo: Boolean
    ) {
        drawTopBar(canvas, buttons)
        drawSideToggles(canvas, buttons, overlay)
        drawEvents(canvas)
        drawBottomBar(canvas, buttons, overlay, canUndo)
        if (aiThinking) drawTurnBanner(canvas)
    }

    // ------------------------------------------------------------------

    private fun drawTopBar(canvas: Canvas, buttons: ButtonLayer) {
        val w = Ui.screenWidth.toFloat()
        val h = Ui.topBarHeight
        rect.set(0f, 0f, w, h)
        Widgets.fill(canvas, rect, Colors.of("#E60F1822"))
        canvas.drawLine(0f, h, w, h, paint.apply {
            color = Colors.of("#553E5A72")
            strokeWidth = Ui.dp(1f)
        })

        val nation = session.playerNation
        val pad = Ui.dp(8f)
        val textY = h * 0.5f + Ui.dp(4.5f)

        // 國旗 + 國色塊：emoji 認國家，色塊對應地圖上的領土色，兩者互補。
        var x = pad
        if (nation.flag.isNotEmpty()) {
            Widgets.left(canvas, nation.flag, x, h * 0.68f, Ui.dp(15f), Colors.of(Widgets.INK))
            x += Widgets.measure(nation.flag, Ui.dp(15f)) + Ui.dp(5f)
        }
        rect.set(x, h * 0.28f, x + Ui.dp(5f), h * 0.72f)
        Widgets.fill(canvas, rect, Palette.nationColour(session, nation.id), Ui.dp(2f))
        x += Ui.dp(11f)
        Widgets.left(
            canvas, Strings.byName(nation.nameKey), x, textY, Ui.dp(13f),
            Colors.of(Widgets.INK), bold = true
        )
        x += Widgets.measure(Strings.byName(nation.nameKey), Ui.dp(13f), true) + Ui.dp(12f)

        Widgets.left(
            canvas, Strings.format(R.string.hud_turn, session.turn), x, textY,
            Ui.dp(12f), Colors.of(Widgets.INK_DIM)
        )
        x += Ui.dp(58f)

        Widgets.left(
            canvas, Strings.format(R.string.hud_funds, nation.funds), x, textY,
            Ui.dp(12f), Colors.of("#F2D08A"), bold = true
        )
        x += Ui.dp(78f)

        val net = nation.lastIncome - nation.lastUpkeep
        Widgets.left(
            canvas,
            (if (net >= 0) "+" else "") + net.toString(),
            x, textY, Ui.dp(11f),
            if (net >= 0) Colors.of("#7FC98F") else Colors.of("#D07A66")
        )
        x += Ui.dp(42f)

        Widgets.left(
            canvas, Strings.format(R.string.hud_provinces, session.provincesOf(nation.id)),
            x, textY, Ui.dp(11f), Colors.of(Widgets.INK_DIM)
        )

        // 右側按鈕，由右往左排。
        var right = w - pad
        val buttonWidth = Ui.dp(74f)
        val buttonHeight = h - Ui.dp(10f)
        val top = Ui.dp(5f)

        rect.set(right - buttonWidth, top, right, top + buttonHeight)
        val canEnd = session.isPlayerTurn
        Widgets.button(
            canvas, rect, Strings.get(R.string.hud_end_turn),
            Widgets.GREEN_TOP, Widgets.GREEN_BOTTOM, enabled = canEnd, textSize = Ui.dp(12f)
        )
        buttons.add(ID_END_TURN, rect, isEnabled = canEnd)
        right -= buttonWidth + Ui.dp(6f)

        val small = Ui.dp(52f)
        for ((id, labelRes) in TOP_BUTTONS) {
            rect.set(right - small, top, right, top + buttonHeight)
            Widgets.button(
                canvas, rect, Strings.get(labelRes),
                Widgets.STEEL_TOP, Widgets.STEEL_BOTTOM, textSize = Ui.dp(11f)
            )
            buttons.add(id, rect)
            right -= small + Ui.dp(5f)
        }
    }

    /** 左側的小開關：格線與補給視圖。放在地圖上而不是選單裡，因為會被反覆切換。 */
    private fun drawSideToggles(canvas: Canvas, buttons: ButtonLayer, overlay: MapOverlay) {
        val size = Ui.dp(30f)
        val pad = Ui.dp(8f)
        var y = Ui.topBarHeight + pad
        val toggles = arrayOf(
            Triple(ID_TOGGLE_GRID, R.string.hud_toggle_grid, overlay.showGrid),
            Triple(ID_TOGGLE_SUPPLY, R.string.hud_toggle_supply, overlay.showSupply)
        )
        for ((id, labelRes, active) in toggles) {
            rect.set(pad, y, pad + size, y + size)
            Widgets.button(
                canvas, rect, Strings.get(labelRes),
                if (active) Widgets.GREEN_TOP else Widgets.GRAY_TOP,
                if (active) Widgets.GREEN_BOTTOM else Widgets.GRAY_BOTTOM,
                selected = active, textSize = Ui.dp(9f)
            )
            buttons.add(id, rect)
            y += size + Ui.dp(6f)
        }
    }

    /** 最近幾則事件。刻意只留三則 —— 戰報應該是提示，不是閱讀材料。 */
    private fun drawEvents(canvas: Canvas) {
        if (session.events.isEmpty()) return
        val shown = session.events.takeLast(3)
        val pad = Ui.dp(8f)
        val lineHeight = Ui.dp(15f)
        val width = Ui.dp(230f)
        val x = Ui.screenWidth - width - pad
        var y = Ui.topBarHeight + pad + lineHeight

        for (event in shown) {
            val text = describeEvent(event.key, event.args)
            if (text.isEmpty()) continue
            Widgets.right(
                canvas, text, x + width, y, Ui.dp(10.5f),
                Colors.of("#B3D6E4F0")
            )
            y += lineHeight
        }
    }

    private fun describeEvent(key: String, args: List<Any>): String {
        val translatedArgs = args.map { if (it is String) Strings.byName(it) else it }
        val template = Strings.byName(key)
        if (template == key) return ""
        return runCatching { String.format(template, *translatedArgs.toTypedArray()) }
            .getOrDefault(template)
    }

    // ------------------------------------------------------------------

    private fun drawBottomBar(
        canvas: Canvas,
        buttons: ButtonLayer,
        overlay: MapOverlay,
        canUndo: Boolean
    ) {
        val tile = overlay.selectedTile
        if (tile < 0) return

        val w = Ui.screenWidth.toFloat()
        val h = Ui.bottomBarHeight
        val top = Ui.screenHeight - h
        rect.set(0f, top, w, Ui.screenHeight.toFloat())
        Widgets.fill(canvas, rect, Colors.of("#E60F1822"))

        val pad = Ui.dp(10f)
        drawTileInfo(canvas, tile, pad, top)

        val unit = overlay.selectedUnit
        if (unit != null) drawUnitInfo(canvas, unit, Ui.dp(150f), top)
        drawContextActions(canvas, buttons, overlay, top, h, canUndo)
    }

    private fun drawTileInfo(canvas: Canvas, tile: Int, x: Float, top: Float) {
        val map = session.map
        val terrain = map.terrainAt(tile)
        val province = map.provinceAt(tile)
        val ink = Colors.of(Widgets.INK)
        val dim = Colors.of(Widgets.INK_DIM)

        val title = province?.let { Strings.byName(it.nameKey) } ?: Strings.byName(terrain.key)
        Widgets.leftFit(canvas, title, x, top + Ui.dp(16f), Ui.dp(12.5f), Ui.dp(130f), ink, bold = true)

        val terrainLine = Strings.format(
            R.string.hud_terrain_line,
            Strings.byName(terrain.key),
            terrain.defenceBonus
        )
        Widgets.leftFit(canvas, terrainLine, x, top + Ui.dp(31f), Ui.dp(10.5f), Ui.dp(130f), dim)

        if (province != null) {
            val owner = session.provinceOwner[province.id]
            val ownerName = if (owner >= 0) {
                val n = session.nations[owner]
                (if (n.flag.isEmpty()) "" else n.flag + " ") + Strings.byName(n.nameKey)
            } else {
                Strings.get(R.string.hud_neutral)
            }
            Widgets.leftFit(canvas, ownerName, x, top + Ui.dp(45f), Ui.dp(10.5f), Ui.dp(130f),
                if (owner >= 0) Palette.nationColour(session, owner) else dim)

            // 城防：玩家得看得到還要磨幾回合，不然「打不下來」會像是壞掉。
            if (province.hasCity) {
                val hp = session.cityHp[province.id]
                val max = province.maxCityHp
                Widgets.leftFit(
                    canvas, Strings.format(R.string.hud_city_defence, hp, max),
                    x, top + Ui.dp(59f), Ui.dp(10.5f), Ui.dp(130f),
                    Palette.cityHealthColour(hp, max)
                )
            }
        }
    }

    private fun drawUnitInfo(canvas: Canvas, unit: ArmyUnit, x: Float, top: Float) {
        val ink = Colors.of(Widgets.INK)
        val dim = Colors.of(Widgets.INK_DIM)

        // 兵種記號 + 名稱。
        paint.color = Palette.domainAccent(unit.kind.domain)
        UnitGlyphs.draw(canvas, unit.kind, x + Ui.dp(12f), top + Ui.dp(24f), Ui.dp(26f), Ui.dp(24f), paint)

        val nameX = x + Ui.dp(30f)
        Widgets.leftFit(
            canvas, Strings.byName(unit.kind.key) + if (unit.size > 1) " ×${unit.size}" else "",
            nameX, top + Ui.dp(16f),
            Ui.dp(12.5f), Ui.dp(120f), ink, bold = true
        )
        Widgets.leftFit(
            canvas,
            Strings.format(R.string.hud_unit_level, unit.level, unit.movesLeft, session.movementFor(unit)),
            nameX, top + Ui.dp(30f), Ui.dp(10f), Ui.dp(120f), dim
        )

        val commander = unit.commander
        if (commander != null) {
            Widgets.leftFit(
                canvas, Strings.byName(commander.nameKey), nameX, top + Ui.dp(44f),
                Ui.dp(10f), Ui.dp(120f), Colors.of("#F2D08A")
            )
        }

        // 血條與補給條。
        val barX = nameX + Ui.dp(126f)
        val barWidth = Ui.dp(84f)
        rect.set(barX, top + Ui.dp(12f), barX + barWidth, top + Ui.dp(19f))
        val hpRatio = unit.hp / ArmyUnit.MAX_HP.toFloat()
        Widgets.bar(canvas, rect, hpRatio, Palette.healthColour(hpRatio))
        Widgets.left(canvas, "${unit.hp}", barX + barWidth + Ui.dp(5f), top + Ui.dp(19f), Ui.dp(10f), dim)

        rect.set(barX, top + Ui.dp(25f), barX + barWidth, top + Ui.dp(32f))
        val supplyRatio = unit.supply / ArmyUnit.MAX_SUPPLY.toFloat()
        Widgets.bar(canvas, rect, supplyRatio, Palette.supplyColour(supplyRatio))
        Widgets.left(canvas, "${unit.supply}", barX + barWidth + Ui.dp(5f), top + Ui.dp(32f), Ui.dp(10f), dim)

        // 士氣是位置決定的，玩家必須看得到它 —— 否則「為什麼我打不出去」
        // 會變成一個沒有線索的謎題。
        val morale = session.moraleOf(unit)
        if (morale != 0) {
            Widgets.left(
                canvas, Strings.format(R.string.hud_morale, moraleLabel(morale)),
                barX, top + Ui.dp(44f), Ui.dp(10f), moraleColour(morale)
            )
        } else if (unit.entrenchment > 0) {
            Widgets.left(
                canvas, Strings.format(R.string.hud_entrenched, unit.entrenchment),
                barX, top + Ui.dp(44f), Ui.dp(10f), Colors.of("#8FBFA0")
            )
        }
    }

    /**
     * 右下角的動作按鈕。只顯示「現在真的做得到」的動作 ——
     * 一整排灰掉的按鈕比沒有按鈕更難讀。
     */
    private fun drawContextActions(
        canvas: Canvas,
        buttons: ButtonLayer,
        overlay: MapOverlay,
        top: Float,
        height: Float,
        canUndo: Boolean
    ) {
        val unit = overlay.selectedUnit
        val tile = overlay.selectedTile
        val province = if (tile >= 0) session.map.provinceAt(tile) else null

        val buttonWidth = Ui.dp(66f)
        val buttonHeight = height - Ui.dp(16f)
        var right = Ui.screenWidth - Ui.dp(10f)
        val y = top + Ui.dp(8f)

        fun action(
            id: String,
            labelRes: Int,
            enabled: Boolean,
            topColour: String,
            bottomColour: String,
            payload: Int = 0
        ) {
            rect.set(right - buttonWidth, y, right, y + buttonHeight)
            Widgets.button(
                canvas, rect, Strings.get(labelRes), topColour, bottomColour,
                enabled = enabled, textSize = Ui.dp(11.5f)
            )
            buttons.add(id, rect, payload = payload, isEnabled = enabled)
            right -= buttonWidth + Ui.dp(6f)
        }

        if (session.isPlayerTurn) {
            action(ID_NEXT_UNIT, R.string.hud_next_unit, true, Widgets.STEEL_TOP, Widgets.STEEL_BOTTOM)

            // 撤回擺在最顯眼的位置，而且只在真的可以撤的時候出現 ——
            // 一排灰掉的按鈕比沒有按鈕更難讀。
            if (canUndo) {
                action(ID_UNDO, R.string.hud_undo, true, Widgets.PURPLE_TOP, Widgets.PURPLE_BOTTOM)
            }

            if (province != null &&
                session.provinceOwner[province.id] == session.playerNationId &&
                province.hasCity
            ) {
                action(ID_BUILD, R.string.hud_build, true, Widgets.AMBER_TOP, Widgets.AMBER_BOTTOM)
            }

            // 宣戰：點到別國的領土才出現。要按兩次 —— 宣戰收不回來，
            // 一次誤觸就把中立國拖進戰爭不該是可能的事。
            val owner = if (province != null) session.provinceOwner[province.id] else -1
            if (owner >= 0 && Orders.canDeclareWar(session, session.playerNationId, owner)) {
                action(
                    ID_DECLARE_WAR,
                    if (armedWarTarget == owner) R.string.hud_confirm_war else R.string.hud_declare_war,
                    true, Widgets.RED_TOP, Widgets.RED_BOTTOM, payload = owner
                )
            }
            if (unit != null && unit.nationId == session.playerNationId) {
                if (Orders.canRepair(session, unit)) {
                    action(
                        ID_REPAIR, R.string.hud_repair, true,
                        Widgets.GREEN_TOP, Widgets.GREEN_BOTTOM
                    )
                }
                action(ID_WAIT, R.string.hud_wait, !unit.isSpent, Widgets.GRAY_TOP, Widgets.GRAY_BOTTOM)
            }
        }
    }

    private fun moraleLabel(level: Int): String = Strings.get(
        when {
            level >= 1 -> R.string.morale_elevated
            level == -1 -> R.string.morale_shaken
            level == -2 -> R.string.morale_broken
            level <= -3 -> R.string.morale_disrupted
            else -> R.string.morale_steady
        }
    )

    private fun moraleColour(level: Int): Int = when {
        level >= 1 -> Colors.of("#8FE0B0")
        level == -1 -> Colors.of("#E0C87F")
        level == -2 -> Colors.of("#E09A6B")
        else -> Colors.of("#FF7B6B")
    }

    /** AI 回合時蓋在畫面中央上方的提示條。 */
    private fun drawTurnBanner(canvas: Canvas) {
        val nation = session.activeNation
        val w = Ui.dp(220f)
        val h = Ui.dp(30f)
        val x = (Ui.screenWidth - w) / 2f
        val y = Ui.topBarHeight + Ui.dp(12f)
        rect.set(x, y, x + w, y + h)
        Widgets.panel(canvas, rect, Ui.dp(6f))
        Widgets.centeredFit(
            canvas,
            Strings.format(R.string.hud_ai_turn, Strings.byName(nation.nameKey)),
            rect.centerX(), rect.centerY() + Ui.dp(4f), Ui.dp(12f), w - Ui.dp(16f),
            bold = true, color = Colors.of(Widgets.INK)
        )
    }

    companion object {
        const val ID_END_TURN = "end_turn"
        const val ID_MENU = "menu"
        const val ID_TECH = "tech"
        const val ID_OBJECTIVES = "objectives"
        const val ID_BUILD = "build"
        const val ID_REPAIR = "repair"
        const val ID_WAIT = "wait"
        const val ID_NEXT_UNIT = "next_unit"
        const val ID_UNDO = "undo"
        const val ID_DECLARE_WAR = "declare_war"
        const val ID_TOGGLE_GRID = "toggle_grid"
        const val ID_TOGGLE_SUPPLY = "toggle_supply"

        private val TOP_BUTTONS = arrayOf(
            ID_MENU to R.string.hud_menu,
            ID_TECH to R.string.hud_tech,
            ID_OBJECTIVES to R.string.hud_objectives
        )
    }
}
