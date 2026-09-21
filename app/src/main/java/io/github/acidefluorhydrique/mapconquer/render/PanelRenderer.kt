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
import io.github.acidefluorhydrique.mapconquer.game.AirMission
import io.github.acidefluorhydrique.mapconquer.game.AirOps
import io.github.acidefluorhydrique.mapconquer.game.GameMode
import io.github.acidefluorhydrique.mapconquer.game.Nation
import io.github.acidefluorhydrique.mapconquer.game.Objective
import io.github.acidefluorhydrique.mapconquer.game.Orders
import io.github.acidefluorhydrique.mapconquer.game.Session
import io.github.acidefluorhydrique.mapconquer.game.SessionStatus
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.TargetClass
import io.github.acidefluorhydrique.mapconquer.units.TechBranch
import io.github.acidefluorhydrique.mapconquer.units.UnitKind

/** 目前疊在地圖上的面板。同時只會有一個。 */
enum class Panel { NONE, PRODUCTION, AIR, TECH, OBJECTIVES, PAUSE, UNIT_DETAIL, RESULT }

/**
 * 蓋在地圖上的各種面板。
 *
 * 每個面板都遵守同一組規則：置中、留出四周的地圖、右上角有關閉鈕、
 * 背景加一層暗幕。一致的形狀讓玩家不必重新學每一個畫面該怎麼關掉。
 */
class PanelRenderer(private val session: Session) {

    private val rect = RectF()
    private val inner = RectF()

    /**
     * 面板外框自己一份 RectF。
     * 各個 drawXxx 都會把外框留在區域變數裡用到最後，
     * 如果它跟繪製過程中反覆改寫的 rect 是同一個物件，版面就會在中途被踩掉。
     */
    private val frameRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines = ArrayList<String>(8)

    /** 生產面板的捲動位移，由 GameView 維護。 */
    var productionScroll: Float = 0f
    var productionMaxScroll: Float = 0f

    /** 生產面板上選定的編制數。徵召時就決定，之後不能再併。 */
    var productionSize: Int = 1
        set(value) { field = value.coerceIn(1, ArmyUnit.MAX_SIZE) }

    fun draw(
        canvas: Canvas,
        buttons: ButtonLayer,
        panel: Panel,
        selectedProvince: Int,
        selectedUnit: ArmyUnit?
    ) {
        if (panel == Panel.NONE) return
        Widgets.scrim(canvas, Ui.screenWidth, Ui.screenHeight, "#B3050A10")
        when (panel) {
            Panel.PRODUCTION -> drawProduction(canvas, buttons, selectedProvince)
            Panel.AIR -> drawAir(canvas, buttons)
            Panel.TECH -> drawTech(canvas, buttons)
            Panel.OBJECTIVES -> drawObjectives(canvas, buttons)
            Panel.PAUSE -> drawPause(canvas, buttons)
            Panel.UNIT_DETAIL -> selectedUnit?.let { drawUnitDetail(canvas, buttons, it) }
            Panel.RESULT -> drawResult(canvas, buttons)
            Panel.NONE -> Unit
        }
    }

    private fun frame(canvas: Canvas, buttons: ButtonLayer, titleRes: Int, widthDp: Float, heightDp: Float): RectF {
        val w = Ui.dp(widthDp).coerceAtMost(Ui.screenWidth - Ui.dp(24f))
        val h = Ui.dp(heightDp).coerceAtMost(Ui.screenHeight - Ui.dp(24f))
        frameRect.set(
            (Ui.screenWidth - w) / 2f,
            (Ui.screenHeight - h) / 2f,
            (Ui.screenWidth + w) / 2f,
            (Ui.screenHeight + h) / 2f
        )
        Widgets.panel(canvas, frameRect)
        Widgets.centeredFit(
            canvas, Strings.get(titleRes), frameRect.centerX(), frameRect.top + Ui.dp(22f),
            Ui.dp(15f), w - Ui.dp(80f), bold = true, color = Colors.of(Widgets.INK)
        )
        rect.set(
            frameRect.right - Ui.dp(38f), frameRect.top + Ui.dp(8f),
            frameRect.right - Ui.dp(8f), frameRect.top + Ui.dp(30f)
        )
        Widgets.button(canvas, rect, "\u00D7", Widgets.GRAY_TOP, Widgets.GRAY_BOTTOM, textSize = Ui.dp(14f))
        buttons.add(ID_CLOSE, rect)
        return frameRect
    }

    // ------------------------------------------------------------------
    // 生產
    // ------------------------------------------------------------------

    /**
     * 生產面板。
     *
     * 造不出來的兵種照樣列出來，只是變灰並標示原因（工業不足、不臨海、錢不夠）。
     * 這是刻意的：玩家需要看得到「升級這座城市之後可以造什麼」，
     * 把不可用的選項藏起來會讓城市等級這條成長線變得不可見。
     */
    private fun drawProduction(canvas: Canvas, buttons: ButtonLayer, provinceId: Int) {
        val panel = frame(canvas, buttons, R.string.panel_production, 480f, 292f)
        if (provinceId < 0 || provinceId >= session.map.provinces.size) return
        val province = session.map.provinces[provinceId]
        val nation = session.playerNation

        Widgets.centeredFit(
            canvas,
            Strings.format(
                R.string.panel_production_subtitle,
                Strings.byName(province.nameKey),
                province.industry,
                nation.funds
            ),
            panel.centerX(), panel.top + Ui.dp(40f), Ui.dp(11f), panel.width() - Ui.dp(40f),
            color = Colors.of(Widgets.INK_DIM)
        )

        // 編制選擇：一排 ×1..×4。價格與「錢夠不夠」都照選定的編制算。
        val sizeTop = panel.top + Ui.dp(52f)
        Widgets.leftFit(
            canvas, Strings.get(R.string.panel_production_size), panel.left + Ui.dp(14f),
            sizeTop + Ui.dp(16f), Ui.dp(11f), Ui.dp(90f), Colors.of(Widgets.INK_DIM)
        )
        val chipWidth = Ui.dp(46f)
        val chipGap = Ui.dp(6f)
        val chipsLeft = panel.left + Ui.dp(104f)
        for (n in 1..ArmyUnit.MAX_SIZE) {
            val x = chipsLeft + (n - 1) * (chipWidth + chipGap)
            inner.set(x, sizeTop, x + chipWidth, sizeTop + Ui.dp(24f))
            val chosen = n == productionSize
            Widgets.button(
                canvas, inner, "\u00D7$n",
                if (chosen) Widgets.AMBER_TOP else Widgets.GRAY_TOP,
                if (chosen) Widgets.AMBER_BOTTOM else Widgets.GRAY_BOTTOM,
                selected = chosen, textSize = Ui.dp(12f)
            )
            buttons.add(ID_BUILD_SIZE, inner, payload = n)
        }

        val listTop = panel.top + Ui.dp(84f)
        val listBottom = panel.bottom - Ui.dp(10f)
        val rowHeight = Ui.dp(34f)
        val columns = 2
        val columnWidth = (panel.width() - Ui.dp(24f)) / columns
        val kinds = UnitKind.ALL

        val rows = (kinds.size + columns - 1) / columns
        productionMaxScroll = (rows * rowHeight - (listBottom - listTop)).coerceAtLeast(0f)
        productionScroll = productionScroll.coerceIn(0f, productionMaxScroll)

        canvas.save()
        canvas.clipRect(panel.left, listTop, panel.right, listBottom)
        for (i in kinds.indices) {
            val kind = kinds[i]
            val row = i / columns
            val column = i % columns
            val y = listTop + row * rowHeight - productionScroll
            if (y + rowHeight < listTop || y > listBottom) continue
            val x = panel.left + Ui.dp(12f) + column * columnWidth
            inner.set(x, y + Ui.dp(2f), x + columnWidth - Ui.dp(8f), y + rowHeight - Ui.dp(2f))

            val blocker = Orders.buildBlocker(session, nation.id, provinceId, kind, productionSize)
            val enabled = blocker == Orders.BuildBlocker.NONE
            drawProductionRow(canvas, inner, kind, blocker, enabled)
            buttons.add(ID_BUILD_KIND, inner, payload = kind.ordinal, isEnabled = enabled)
        }
        canvas.restore()

        if (productionMaxScroll > 0f) {
            Widgets.centered(
                canvas, "▲ ▼", panel.right - Ui.dp(20f), panel.bottom - Ui.dp(6f),
                Ui.dp(9f), color = Colors.of("#66FFFFFF")
            )
        }
    }

    private fun drawProductionRow(
        canvas: Canvas,
        row: RectF,
        kind: UnitKind,
        blocker: Orders.BuildBlocker,
        enabled: Boolean
    ) {
        Widgets.fill(
            canvas, row,
            if (enabled) Colors.of("#3325384A") else Colors.of("#221C1F26"),
            Ui.dp(5f)
        )
        val ink = if (enabled) Colors.of(Widgets.INK) else Colors.of("#7A8794")
        val dim = if (enabled) Colors.of(Widgets.INK_DIM) else Colors.of("#5F6B78")

        paint.color = if (enabled) Palette.domainAccent(kind.domain) else Colors.of("#5F6B78")
        UnitGlyphs.draw(
            canvas, kind, row.left + Ui.dp(15f), row.centerY(),
            Ui.dp(22f), Ui.dp(20f), paint
        )

        val textX = row.left + Ui.dp(30f)
        Widgets.leftFit(
            canvas, Strings.byName(kind.key), textX, row.centerY() - Ui.dp(1f),
            Ui.dp(11.5f), row.width() - Ui.dp(80f), ink, bold = true
        )

        val detail = if (enabled) {
            Strings.format(
                R.string.panel_production_stats,
                kind.attackAgainst(TargetClass.SOFT),
                kind.defence,
                kind.movement
            )
        } else {
            Strings.get(blockerLabel(blocker))
        }
        Widgets.leftFit(
            canvas, detail, textX, row.centerY() + Ui.dp(11f),
            Ui.dp(9.5f), row.width() - Ui.dp(80f), dim
        )

        Widgets.right(
            canvas, Orders.buildCost(kind, productionSize).toString(), row.right - Ui.dp(8f), row.centerY() + Ui.dp(4f),
            Ui.dp(12f), if (enabled) Colors.of("#F2D08A") else Colors.of("#7A6B54"), bold = true
        )
    }

    // ------------------------------------------------------------------
    // 空中任務
    // ------------------------------------------------------------------

    /** 三種出擊，一列一種。選了之後回到地圖挑目標。 */
    private fun drawAir(canvas: Canvas, buttons: ButtonLayer) {
        val panel = frame(canvas, buttons, R.string.panel_air, 440f, 230f)
        val nation = session.playerNation
        Widgets.centeredFit(
            canvas, Strings.format(R.string.panel_air_subtitle, nation.funds),
            panel.centerX(), panel.top + Ui.dp(40f), Ui.dp(11f), panel.width() - Ui.dp(40f),
            color = Colors.of(Widgets.INK_DIM)
        )
        val rowHeight = Ui.dp(50f)
        var y = panel.top + Ui.dp(52f)
        for (mission in AirMission.ALL) {
            inner.set(panel.left + Ui.dp(12f), y + Ui.dp(2f), panel.right - Ui.dp(12f), y + rowHeight - Ui.dp(2f))
            val blocker = AirOps.blocker(session, nation.id, mission)
            val enabled = session.isPlayerTurn && blocker == AirOps.Blocker.NONE
            drawAirRow(canvas, inner, mission, blocker, enabled)
            buttons.add(ID_AIR_MISSION, inner, payload = mission.ordinal, isEnabled = enabled)
            y += rowHeight
        }
    }

    private fun drawAirRow(canvas: Canvas, row: RectF, mission: AirMission, blocker: AirOps.Blocker, enabled: Boolean) {
        Widgets.fill(
            canvas, row,
            if (enabled) Colors.of("#3325384A") else Colors.of("#221C1F26"),
            Ui.dp(5f)
        )
        val ink = if (enabled) Colors.of(Widgets.INK) else Colors.of("#7A8794")
        val dim = if (enabled) Colors.of(Widgets.INK_DIM) else Colors.of("#5F6B78")
        paint.color = if (enabled) Palette.AIR_ACCENT else Colors.of("#5F6B78")
        UnitGlyphs.drawMission(canvas, mission, row.left + Ui.dp(18f), row.centerY(), Ui.dp(26f), Ui.dp(24f), paint)

        val textX = row.left + Ui.dp(38f)
        val textWidth = row.width() - Ui.dp(100f)
        Widgets.leftFit(
            canvas, Strings.byName(mission.key), textX, row.centerY() - Ui.dp(4f),
            Ui.dp(12.5f), textWidth, ink, bold = true
        )
        val detail = when (blocker) {
            AirOps.Blocker.NO_FUNDS -> Strings.get(R.string.build_blocked_funds)
            AirOps.Blocker.NO_BASE -> Strings.get(R.string.air_blocked_base)
            AirOps.Blocker.NONE -> Strings.byName(mission.descKey)
        }
        Widgets.leftFit(
            canvas, detail, textX, row.centerY() + Ui.dp(12f),
            Ui.dp(9.5f), textWidth, dim
        )
        Widgets.right(
            canvas, mission.totalCost.toString(), row.right - Ui.dp(10f), row.centerY() + Ui.dp(4f),
            Ui.dp(13f), if (enabled) Colors.of("#F2D08A") else Colors.of("#7A6B54"), bold = true
        )
    }

    private fun blockerLabel(blocker: Orders.BuildBlocker): Int = when (blocker) {
        Orders.BuildBlocker.LOW_INDUSTRY -> R.string.build_blocked_industry
        Orders.BuildBlocker.NOT_COASTAL -> R.string.build_blocked_coastal
        Orders.BuildBlocker.NO_ROOM -> R.string.build_blocked_room
        Orders.BuildBlocker.NO_FUNDS -> R.string.build_blocked_funds
        Orders.BuildBlocker.NO_CITY -> R.string.build_blocked_city
        else -> R.string.build_blocked_owner
    }

    // ------------------------------------------------------------------
    // 科技
    // ------------------------------------------------------------------

    private fun drawTech(canvas: Canvas, buttons: ButtonLayer) {
        val panel = frame(canvas, buttons, R.string.panel_tech, 420f, 250f)
        val nation = session.playerNation
        Widgets.centeredFit(
            canvas, Strings.format(R.string.hud_funds, nation.funds),
            panel.centerX(), panel.top + Ui.dp(40f), Ui.dp(11f), panel.width() - Ui.dp(40f),
            color = Colors.of("#F2D08A")
        )

        val branches = TechBranch.values()
        val rowHeight = Ui.dp(30f)
        var y = panel.top + Ui.dp(50f)
        for (branch in branches) {
            inner.set(panel.left + Ui.dp(12f), y, panel.right - Ui.dp(12f), y + rowHeight - Ui.dp(4f))
            val level = nation.techLevel(branch)
            val canBuy = Orders.canResearch(session, nation.id, branch)
            Widgets.fill(canvas, inner, Colors.of("#3325384A"), Ui.dp(5f))

            Widgets.leftFit(
                canvas, Strings.byName("tech_" + branch.name.lowercase()),
                inner.left + Ui.dp(8f), inner.centerY() + Ui.dp(4f),
                Ui.dp(11.5f), Ui.dp(110f), Colors.of(Widgets.INK), bold = true
            )

            // 等級用點而不是數字：一眼看得出還剩幾級可以點。
            val pipX = inner.left + Ui.dp(126f)
            for (i in 0 until Nation.MAX_TECH_LEVEL) {
                paint.color = if (i < level) Colors.of("#7FC98F") else Colors.of("#33FFFFFF")
                canvas.drawCircle(pipX + i * Ui.dp(11f), inner.centerY(), Ui.dp(3.6f), paint)
            }

            Widgets.left(
                canvas, "+${nation.techBonus(branch)}%",
                pipX + Ui.dp(64f), inner.centerY() + Ui.dp(4f), Ui.dp(10.5f),
                Colors.of(Widgets.INK_DIM)
            )

            val buttonWidth = Ui.dp(84f)
            val buttonRect = RectF(
                inner.right - buttonWidth - Ui.dp(4f), inner.top + Ui.dp(2f),
                inner.right - Ui.dp(4f), inner.bottom - Ui.dp(2f)
            )
            val label = if (level >= Nation.MAX_TECH_LEVEL) {
                Strings.get(R.string.tech_maxed)
            } else {
                nation.techCost(branch).toString()
            }
            Widgets.button(
                canvas, buttonRect, label,
                Widgets.STEEL_TOP, Widgets.STEEL_BOTTOM,
                enabled = canBuy, textSize = Ui.dp(11f)
            )
            buttons.add(ID_RESEARCH, buttonRect, payload = branch.ordinal, isEnabled = canBuy)
            y += rowHeight
        }
    }

    // ------------------------------------------------------------------
    // 目標與局勢
    // ------------------------------------------------------------------

    private fun drawObjectives(canvas: Canvas, buttons: ButtonLayer) {
        val panel = frame(canvas, buttons, R.string.panel_objectives, 440f, 250f)
        var y = panel.top + Ui.dp(48f)
        val ink = Colors.of(Widgets.INK)
        val dim = Colors.of(Widgets.INK_DIM)

        val objectives = session.scenario.objectives
        if (objectives.isEmpty()) {
            Widgets.leftFit(
                canvas,
                if (session.blocEnemiesOf(session.playerNationId).isEmpty()) {
                    Strings.format(R.string.objective_conquest_solo, Session.CONQUEST_VICTORY_PERCENT)
                } else {
                    Strings.get(R.string.objective_conquest)
                },
                panel.left + Ui.dp(16f), y, Ui.dp(11.5f), panel.width() - Ui.dp(32f), ink
            )
            y += Ui.dp(20f)
        } else {
            for (objective in objectives) {
                val (done, total) = session.objectiveProgress(objective)
                val met = session.isObjectiveMet(objective)
                Widgets.leftFit(
                    canvas, describeObjective(objective),
                    panel.left + Ui.dp(16f), y, Ui.dp(11.5f), panel.width() - Ui.dp(90f),
                    if (met) Colors.of("#7FC98F") else ink
                )
                Widgets.right(
                    canvas, "$done / $total", panel.right - Ui.dp(16f), y, Ui.dp(11.5f),
                    if (met) Colors.of("#7FC98F") else dim, bold = true
                )
                y += Ui.dp(19f)
            }
        }

        y += Ui.dp(6f)
        Widgets.leftFit(
            canvas, Strings.get(R.string.panel_standings),
            panel.left + Ui.dp(16f), y, Ui.dp(12f), panel.width() - Ui.dp(32f), ink, bold = true
        )
        y += Ui.dp(16f)

        // 局勢：依省份數排名，只列前幾名加上玩家自己。
        val ranked = session.nations
            .filter { !it.eliminated }
            .sortedByDescending { session.provincesOf(it.id) }
        val shown = ranked.take(6).toMutableList()
        if (shown.none { it.id == session.playerNationId }) shown.add(session.playerNation)

        val barLeft = panel.left + Ui.dp(120f)
        val barWidth = panel.width() - Ui.dp(180f)
        val topCount = ranked.firstOrNull()?.let { session.provincesOf(it.id) }?.coerceAtLeast(1) ?: 1
        for (nation in shown) {
            val count = session.provincesOf(nation.id)
            val label = (if (nation.flag.isEmpty()) "" else nation.flag + " ") +
                Strings.byName(nation.nameKey)
            Widgets.leftFit(
                canvas, label, panel.left + Ui.dp(16f), y,
                Ui.dp(10.5f), Ui.dp(100f),
                if (nation.id == session.playerNationId) Colors.of("#F2D08A") else dim,
                bold = nation.id == session.playerNationId
            )
            inner.set(barLeft, y - Ui.dp(8f), barLeft + barWidth, y - Ui.dp(1f))
            Widgets.bar(canvas, inner, count / topCount.toFloat(), Palette.nationColour(session, nation.id))
            Widgets.right(canvas, count.toString(), panel.right - Ui.dp(16f), y, Ui.dp(10.5f), dim)
            y += Ui.dp(16f)
            if (y > panel.bottom - Ui.dp(14f)) break
        }
    }

    private fun describeObjective(objective: Objective): String {
        val template = Strings.byName(objective.key)
        return runCatching {
            String.format(
                template,
                objective.amount.coerceAtLeast(objective.provinces.size),
                objective.turn,
                Strings.byName(
                    session.nationByCode(objective.nationCode)?.nameKey ?: objective.nationCode
                )
            )
        }.getOrDefault(template)
    }

    // ------------------------------------------------------------------
    // 暫停與結算
    // ------------------------------------------------------------------

    private fun drawPause(canvas: Canvas, buttons: ButtonLayer) {
        val panel = frame(canvas, buttons, R.string.panel_paused, 260f, 220f)
        val entries = arrayOf(
            ID_RESUME to R.string.pause_resume,
            ID_SAVE to R.string.pause_save,
            ID_SETTINGS to R.string.menu_settings,
            ID_QUIT to R.string.pause_quit
        )
        var y = panel.top + Ui.dp(48f)
        val width = panel.width() - Ui.dp(40f)
        for ((id, labelRes) in entries) {
            inner.set(panel.centerX() - width / 2f, y, panel.centerX() + width / 2f, y + Ui.dp(30f))
            val danger = id == ID_QUIT
            Widgets.button(
                canvas, inner, Strings.get(labelRes),
                if (danger) Widgets.RED_TOP else Widgets.STEEL_TOP,
                if (danger) Widgets.RED_BOTTOM else Widgets.STEEL_BOTTOM,
                textSize = Ui.dp(12.5f)
            )
            buttons.add(id, inner)
            y += Ui.dp(36f)
        }
    }

    private fun drawResult(canvas: Canvas, buttons: ButtonLayer) {
        val victory = session.status == SessionStatus.VICTORY
        val panel = frame(
            canvas, buttons,
            if (victory) R.string.result_victory else R.string.result_defeat,
            340f, 220f
        )
        val nation = session.playerNation
        var y = panel.top + Ui.dp(54f)

        if (victory && session.scenario.mode == GameMode.CAMPAIGN) {
            Widgets.stars(canvas, panel.centerX(), y, session.scenario.starsFor(session.turn), Ui.dp(20f))
            y += Ui.dp(24f)
        }

        val rows = arrayOf(
            R.string.result_turns to session.turn.toString(),
            R.string.result_provinces to session.provincesOf(nation.id).toString(),
            R.string.result_killed to nation.unitsKilled.toString(),
            R.string.result_lost to nation.unitsLost.toString()
        )
        for ((labelRes, value) in rows) {
            Widgets.leftFit(
                canvas, Strings.get(labelRes), panel.left + Ui.dp(24f), y,
                Ui.dp(11.5f), panel.width() - Ui.dp(120f), Colors.of(Widgets.INK_DIM)
            )
            Widgets.right(
                canvas, value, panel.right - Ui.dp(24f), y, Ui.dp(12f),
                Colors.of(Widgets.INK), bold = true
            )
            y += Ui.dp(18f)
        }

        inner.set(
            panel.centerX() - Ui.dp(60f), panel.bottom - Ui.dp(38f),
            panel.centerX() + Ui.dp(60f), panel.bottom - Ui.dp(10f)
        )
        Widgets.button(
            canvas, inner, Strings.get(R.string.result_continue),
            Widgets.GREEN_TOP, Widgets.GREEN_BOTTOM, textSize = Ui.dp(12.5f)
        )
        buttons.add(ID_RESULT_OK, inner)
    }

    // ------------------------------------------------------------------
    // 單位詳情
    // ------------------------------------------------------------------

    private fun drawUnitDetail(canvas: Canvas, buttons: ButtonLayer, unit: ArmyUnit) {
        val panel = frame(canvas, buttons, R.string.panel_unit, 400f, 240f)
        val ink = Colors.of(Widgets.INK)
        val dim = Colors.of(Widgets.INK_DIM)

        paint.color = Palette.domainAccent(unit.kind.domain)
        UnitGlyphs.draw(
            canvas, unit.kind, panel.left + Ui.dp(36f), panel.top + Ui.dp(66f),
            Ui.dp(44f), Ui.dp(40f), paint
        )

        val textLeft = panel.left + Ui.dp(66f)
        Widgets.leftFit(
            canvas, Strings.byName(unit.kind.key), textLeft, panel.top + Ui.dp(56f),
            Ui.dp(14f), Ui.dp(180f), ink, bold = true
        )
        Widgets.leftFit(
            canvas, Strings.format(R.string.unit_level_line, unit.level, unit.exp),
            textLeft, panel.top + Ui.dp(72f), Ui.dp(10.5f), Ui.dp(180f), dim
        )

        // 對四類目標的攻擊力：這張表是玩家判斷「該派誰上」的核心資訊。
        var y = panel.top + Ui.dp(96f)
        val labels = arrayOf(
            R.string.target_soft, R.string.target_armoured,
            R.string.target_ship, R.string.target_aircraft
        )
        for (i in labels.indices) {
            Widgets.leftFit(
                canvas, Strings.get(labels[i]), panel.left + Ui.dp(24f), y,
                Ui.dp(10.5f), Ui.dp(90f), dim
            )
            val value = unit.kind.attack[i]
            inner.set(panel.left + Ui.dp(110f), y - Ui.dp(8f), panel.left + Ui.dp(230f), y - Ui.dp(1f))
            Widgets.bar(
                canvas, inner, value / 64f,
                if (value > 0) Colors.of("#C4553C") else Colors.of("#3A4450")
            )
            Widgets.left(canvas, value.toString(), panel.left + Ui.dp(236f), y, Ui.dp(10.5f), ink)
            y += Ui.dp(16f)
        }

        val stats = Strings.format(
            R.string.unit_stat_line,
            unit.kind.defence, session.movementFor(unit),
            unit.kind.minRange.coerceAtLeast(1), unit.kind.maxRange, unit.kind.vision
        )
        Widgets.leftFit(
            canvas, stats, panel.left + Ui.dp(24f), y + Ui.dp(4f),
            Ui.dp(10.5f), panel.width() - Ui.dp(48f), dim
        )

        Widgets.wrap(
            Strings.byName(unit.kind.descKey), Ui.dp(10f),
            panel.width() - Ui.dp(48f), lines
        )
        var descY = y + Ui.dp(22f)
        for (line in lines.take(3)) {
            Widgets.left(canvas, line, panel.left + Ui.dp(24f), descY, Ui.dp(10f), dim)
            descY += Ui.dp(13f)
        }

        // 指揮官指派。
        val commanderRect = RectF(
            panel.right - Ui.dp(120f), panel.bottom - Ui.dp(38f),
            panel.right - Ui.dp(16f), panel.bottom - Ui.dp(10f)
        )
        val commander = unit.commander
        Widgets.button(
            canvas, commanderRect,
            commander?.let { Strings.byName(it.nameKey) } ?: Strings.get(R.string.unit_assign_commander),
            Widgets.PURPLE_TOP, Widgets.PURPLE_BOTTOM,
            enabled = unit.nationId == session.playerNationId, textSize = Ui.dp(11f)
        )
        buttons.add(ID_ASSIGN_COMMANDER, commanderRect, isEnabled = unit.nationId == session.playerNationId)
    }

    companion object {
        const val ID_CLOSE = "panel_close"
        const val ID_BUILD_KIND = "build_kind"
        const val ID_BUILD_SIZE = "build_size"
        const val ID_AIR_MISSION = "air_mission"
        const val ID_RESEARCH = "research"
        const val ID_RESUME = "resume"
        const val ID_SAVE = "save"
        const val ID_SETTINGS = "pause_settings"
        const val ID_QUIT = "quit"
        const val ID_RESULT_OK = "result_ok"
        const val ID_ASSIGN_COMMANDER = "assign_commander"
    }
}
