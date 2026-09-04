// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import io.github.acidefluorhydrique.mapconquer.R
import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.core.LocaleManager
import io.github.acidefluorhydrique.mapconquer.core.Strings
import io.github.acidefluorhydrique.mapconquer.core.Ui
import io.github.acidefluorhydrique.mapconquer.core.Widgets
import io.github.acidefluorhydrique.mapconquer.game.Difficulty
import io.github.acidefluorhydrique.mapconquer.game.Scenario
import io.github.acidefluorhydrique.mapconquer.game.ScenarioNation
import io.github.acidefluorhydrique.mapconquer.units.Commander

/**
 * 遊戲外的所有畫面：主選單、戰役關卡表、征服設定、指揮官、設定、說明。
 *
 * 全部共用同一套版面骨架（標題列 + 內容區 + 返回鈕），
 * 一方面省下大量重複的座標計算，一方面讓玩家在任何一頁都知道返回鈕在哪。
 */
class MenuRenderer {

    private val rect = RectF()
    private val inner = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lines = ArrayList<String>(12)

    /** 各頁面的捲動位移，由 GameView 更新。 */
    var scroll: Float = 0f
    var maxScroll: Float = 0f

    // ------------------------------------------------------------------

    fun drawBackground(canvas: Canvas) {
        paint.shader = LinearGradient(
            0f, 0f, 0f, Ui.screenHeight.toFloat(),
            Colors.of("#16283A"), Colors.of("#0B1219"), Shader.TileMode.CLAMP
        )
        paint.style = Paint.Style.FILL
        canvas.drawRect(0f, 0f, Ui.screenWidth.toFloat(), Ui.screenHeight.toFloat(), paint)
        paint.shader = null

        // 背景的六角紋理：不放圖檔，直接畫，同時也是本作的視覺識別。
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(0.8f)
        paint.color = Colors.of("#141F2D")
        val radius = Ui.dp(26f)
        val stepX = radius * 1.732f
        val stepY = radius * 1.5f
        var row = 0
        var y = -radius
        while (y < Ui.screenHeight + radius) {
            var x = if (row and 1 == 1) stepX / 2f else 0f
            while (x < Ui.screenWidth + stepX) {
                Widgets.hexBadge(canvas, x, y, radius, Colors.of("#00000000"), Colors.of("#182636"))
                x += stepX
            }
            y += stepY
            row++
        }
        paint.style = Paint.Style.FILL
    }

    private fun header(canvas: Canvas, buttons: ButtonLayer, titleRes: Int, showBack: Boolean = true): Float {
        val h = Ui.dp(42f)
        rect.set(0f, 0f, Ui.screenWidth.toFloat(), h)
        Widgets.fill(canvas, rect, Colors.of("#CC0B1219"))
        Widgets.centeredFit(
            canvas, Strings.get(titleRes), Ui.screenWidth / 2f, h * 0.63f,
            Ui.dp(16f), Ui.screenWidth * 0.5f, bold = true, color = Colors.of(Widgets.INK)
        )
        if (showBack) {
            inner.set(Ui.dp(10f), Ui.dp(7f), Ui.dp(78f), h - Ui.dp(7f))
            Widgets.button(
                canvas, inner, Strings.get(R.string.common_back),
                Widgets.GRAY_TOP, Widgets.GRAY_BOTTOM, textSize = Ui.dp(12f)
            )
            buttons.add(ID_BACK, inner)
        }
        return h
    }

    // ------------------------------------------------------------------
    // 主選單
    // ------------------------------------------------------------------

    fun drawMain(
        canvas: Canvas,
        buttons: ButtonLayer,
        hasSave: Boolean,
        saveSubtitle: String,
        medals: Int,
        stars: Int,
        cleared: Int,
        totalCampaigns: Int
    ) {
        drawBackground(canvas)

        val titleY = Ui.screenHeight * 0.22f
        Widgets.centeredFit(
            canvas, Strings.get(R.string.app_name), Ui.screenWidth / 2f, titleY,
            Ui.dp(34f), Ui.screenWidth * 0.8f, bold = true, color = Colors.of("#F2F6FA")
        )
        Widgets.centeredFit(
            canvas, Strings.get(R.string.menu_tagline), Ui.screenWidth / 2f, titleY + Ui.dp(20f),
            Ui.dp(12f), Ui.screenWidth * 0.7f, color = Colors.of("#8FA3B6")
        )

        val buttonWidth = Ui.dp(150f)
        val buttonHeight = Ui.dp(34f)
        val gap = Ui.dp(9f)
        val entries = ArrayList<Triple<String, Int, String?>>(6)
        if (hasSave) entries.add(Triple(ID_CONTINUE, R.string.menu_continue, saveSubtitle))
        entries.add(Triple(ID_CAMPAIGN, R.string.menu_campaign, null))
        entries.add(Triple(ID_CONQUEST, R.string.menu_conquest, null))
        entries.add(Triple(ID_COMMANDERS, R.string.menu_commanders, null))
        entries.add(Triple(ID_SETTINGS, R.string.menu_settings, null))
        entries.add(Triple(ID_HELP, R.string.menu_help, null))

        val totalHeight = entries.size * buttonHeight + (entries.size - 1) * gap
        var y = (Ui.screenHeight * 0.40f).coerceAtMost(Ui.screenHeight - totalHeight - Ui.dp(36f))
        for ((id, labelRes, subtitle) in entries) {
            inner.set(
                (Ui.screenWidth - buttonWidth) / 2f, y,
                (Ui.screenWidth + buttonWidth) / 2f, y + buttonHeight
            )
            val primary = id == ID_CONTINUE || (!hasSave && id == ID_CAMPAIGN)
            Widgets.button(
                canvas, inner, Strings.get(labelRes),
                if (primary) Widgets.GREEN_TOP else Widgets.STEEL_TOP,
                if (primary) Widgets.GREEN_BOTTOM else Widgets.STEEL_BOTTOM,
                textSize = Ui.dp(13f), subLabel = subtitle
            )
            buttons.add(id, inner)
            y += buttonHeight + gap
        }

        val footer = Strings.format(R.string.menu_progress, cleared, totalCampaigns, stars, medals)
        Widgets.centeredFit(
            canvas, footer, Ui.screenWidth / 2f, Ui.screenHeight - Ui.dp(12f),
            Ui.dp(10.5f), Ui.screenWidth * 0.9f, color = Colors.of("#7F93A6")
        )
    }

    // ------------------------------------------------------------------
    // 劇本列表
    // ------------------------------------------------------------------

    /**
     * 戰役與征服共用同一份列表繪製。
     *
     * 差別只有兩個：戰役顯示星等並有解鎖門檻，征服顯示年代並全部開放。
     * 把它們寫成同一支而不是兩支，是因為兩邊的版面必須一致 ——
     * 玩家在兩個模式之間切換時，不該覺得自己換了一款遊戲。
     */
    fun drawScenarioList(
        canvas: Canvas,
        buttons: ButtonLayer,
        titleRes: Int,
        scenarios: List<Scenario>,
        starsOf: (Scenario) -> Int,
        unlockedAt: (Int) -> Boolean,
        showStars: Boolean
    ) {
        drawBackground(canvas)
        val top = header(canvas, buttons, titleRes)

        val listTop = top + Ui.dp(8f)
        val listBottom = Ui.screenHeight - Ui.dp(8f)
        val rowHeight = Ui.dp(46f)
        val width = (Ui.screenWidth - Ui.dp(40f)).coerceAtMost(Ui.dp(520f))
        val left = (Ui.screenWidth - width) / 2f

        maxScroll = (scenarios.size * rowHeight - (listBottom - listTop)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(0f, listTop, Ui.screenWidth.toFloat(), listBottom)
        for (i in scenarios.indices) {
            val scenario = scenarios[i]
            val y = listTop + i * rowHeight - scroll
            if (y + rowHeight < listTop || y > listBottom) continue
            inner.set(left, y + Ui.dp(3f), left + width, y + rowHeight - Ui.dp(3f))

            val unlocked = unlockedAt(i)
            Widgets.fill(
                canvas, inner,
                if (unlocked) Colors.of("#B31C2C3C") else Colors.of("#8C141C26"),
                Ui.dp(7f)
            )
            Widgets.outline(canvas, inner, Colors.of("#3D9FC4E8"), Ui.dp(1f), Ui.dp(7f))

            val ink = if (unlocked) Colors.of(Widgets.INK) else Colors.of("#66788799")
            Widgets.leftFit(
                canvas, Strings.byName(scenario.nameKey), inner.left + Ui.dp(12f),
                inner.top + Ui.dp(17f), Ui.dp(13f), width - Ui.dp(140f), ink, bold = true
            )
            Widgets.leftFit(
                canvas, Strings.byName(scenario.descKey), inner.left + Ui.dp(12f),
                inner.top + Ui.dp(32f), Ui.dp(10f), width - Ui.dp(140f),
                if (unlocked) Colors.of(Widgets.INK_DIM) else Colors.of("#556677")
            )

            if (showStars) {
                Widgets.stars(canvas, inner.right - Ui.dp(48f), inner.centerY() + Ui.dp(5f), starsOf(scenario), Ui.dp(13f))
            } else {
                Widgets.right(
                    canvas, scenario.startYear.toString(), inner.right - Ui.dp(14f),
                    inner.centerY() + Ui.dp(5f), Ui.dp(15f), Colors.of("#F2D08A"), bold = true
                )
            }
            if (!unlocked) {
                Widgets.right(
                    canvas, Strings.get(R.string.campaign_locked), inner.right - Ui.dp(14f),
                    inner.bottom - Ui.dp(8f), Ui.dp(9.5f), Colors.of("#7A8794")
                )
            }
            buttons.add(ID_SCENARIO, inner, payload = i, isEnabled = unlocked)
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // 選國家與難度
    // ------------------------------------------------------------------

    fun drawNationSelect(
        canvas: Canvas,
        buttons: ButtonLayer,
        scenario: Scenario,
        options: List<ScenarioNation>,
        selectedNation: Int,
        difficulty: Difficulty
    ) {
        drawBackground(canvas)
        val top = header(canvas, buttons, R.string.setup_title)

        Widgets.centeredFit(
            canvas, Strings.byName(scenario.nameKey), Ui.screenWidth / 2f, top + Ui.dp(16f),
            Ui.dp(13f), Ui.screenWidth * 0.7f, bold = true, color = Colors.of(Widgets.INK)
        )

        // 底部先留給難度與開始鈕，剩下的空間才是國家卡片區 ——
        // 征服模式有上百個可選國家，卡片區必須可捲動，
        // 但「開始」永遠要在同一個地方，不能跟著捲走。
        val footerHeight = Ui.dp(76f)
        val gridTop = top + Ui.dp(24f)
        val gridBottom = Ui.screenHeight - footerHeight

        val cardWidth = Ui.dp(94f)
        val cardHeight = Ui.dp(68f)
        val gap = Ui.dp(7f)
        val perRow = ((Ui.screenWidth - Ui.dp(20f)) / (cardWidth + gap)).toInt().coerceAtLeast(1)
        val rows = (options.size + perRow - 1) / perRow
        val gridWidth = perRow * cardWidth + (perRow - 1) * gap
        val startX = (Ui.screenWidth - gridWidth) / 2f

        maxScroll = (rows * (cardHeight + gap) - (gridBottom - gridTop)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(0f, gridTop, Ui.screenWidth.toFloat(), gridBottom)
        for (i in options.indices) {
            val nation = options[i]
            val column = i % perRow
            val row = i / perRow
            val x = startX + column * (cardWidth + gap)
            val cardY = gridTop + row * (cardHeight + gap) - scroll
            if (cardY + cardHeight < gridTop || cardY > gridBottom) continue
            inner.set(x, cardY, x + cardWidth, cardY + cardHeight)

            val selected = i == selectedNation
            Widgets.fill(canvas, inner, Colors.of(if (selected) "#CC22384E" else "#A6151F2B"), Ui.dp(6f))
            Widgets.outline(
                canvas, inner,
                if (selected) Colors.of("#FFD98A") else Colors.of("#33FFFFFF"),
                Ui.dp(if (selected) 1.8f else 1f), Ui.dp(6f)
            )

            // 國旗擺在卡片最上面當主視覺，底下留一條國色 —— 那條色帶
            // 對應地圖上的領土色，讓玩家在選完之後認得出自己是哪一片。
            if (nation.flag.isNotEmpty()) {
                Widgets.centered(
                    canvas, nation.flag, inner.centerX(), inner.top + Ui.dp(20f),
                    Ui.dp(17f), color = Colors.of(Widgets.INK)
                )
            }
            rect.set(inner.left + Ui.dp(14f), inner.top + Ui.dp(24f), inner.right - Ui.dp(14f), inner.top + Ui.dp(27f))
            Widgets.fill(canvas, rect, Colors.of(nation.colour), Ui.dp(1.5f))

            Widgets.centeredFit(
                canvas, Strings.byName(nation.nameKey), inner.centerX(), inner.top + Ui.dp(40f),
                Ui.dp(11.5f), cardWidth - Ui.dp(10f), bold = true, color = Colors.of(Widgets.INK)
            )
            Widgets.centeredFit(
                canvas, Strings.format(R.string.setup_funds, nation.funds),
                inner.centerX(), inner.top + Ui.dp(48f), Ui.dp(9.5f), cardWidth - Ui.dp(10f),
                color = Colors.of(Widgets.INK_DIM)
            )
            // 陣營而不是 AI 性格。玩家要決定的是「我站哪一邊」，
            // 「侵略」「穩健」這種詞描述的是對手怎麼打，那是打起來之後的事。
            Widgets.centeredFit(
                canvas, Strings.byName(Palette.blocNameKey(nation.bloc)),
                inner.centerX(), inner.top + Ui.dp(60f), Ui.dp(9f), cardWidth - Ui.dp(10f),
                bold = true, color = Palette.blocColour(nation.bloc)
            )
            buttons.add(ID_NATION, inner, payload = i)
        }
        canvas.restore()

        // 難度：一排四個，固定在底部。
        val diffWidth = Ui.dp(84f)
        val diffs = Difficulty.ALL
        val diffTotal = diffs.size * diffWidth + (diffs.size - 1) * gap
        var dx = (Ui.screenWidth - diffTotal) / 2f
        val diffY = Ui.screenHeight - footerHeight + Ui.dp(4f)
        for (option in diffs) {
            inner.set(dx, diffY, dx + diffWidth, diffY + Ui.dp(26f))
            val selected = option == difficulty
            Widgets.button(
                canvas, inner, Strings.byName(option.key),
                if (selected) Widgets.AMBER_TOP else Widgets.STEEL_TOP,
                if (selected) Widgets.AMBER_BOTTOM else Widgets.STEEL_BOTTOM,
                selected = selected, textSize = Ui.dp(11f)
            )
            buttons.add(ID_DIFFICULTY, inner, payload = option.ordinal)
            dx += diffWidth + gap
        }

        val startWidth = Ui.dp(170f)
        inner.set(
            (Ui.screenWidth - startWidth) / 2f, Ui.screenHeight - Ui.dp(38f),
            (Ui.screenWidth + startWidth) / 2f, Ui.screenHeight - Ui.dp(8f)
        )
        val ready = selectedNation in options.indices
        Widgets.button(
            canvas, inner, Strings.get(R.string.setup_start),
            Widgets.GREEN_TOP, Widgets.GREEN_BOTTOM,
            enabled = ready, textSize = Ui.dp(14f)
        )
        buttons.add(ID_START, inner, isEnabled = ready)
    }

    // ------------------------------------------------------------------
    // 指揮官
    // ------------------------------------------------------------------

    fun drawCommanders(
        canvas: Canvas,
        buttons: ButtonLayer,
        medals: Int,
        unlocked: Set<String>
    ) {
        drawBackground(canvas)
        val top = header(canvas, buttons, R.string.menu_commanders)

        Widgets.right(
            canvas, Strings.format(R.string.commander_medals, medals),
            Ui.screenWidth - Ui.dp(12f), Ui.dp(27f), Ui.dp(12f), Colors.of("#F2D08A"), bold = true
        )

        val listTop = top + Ui.dp(8f)
        val listBottom = Ui.screenHeight - Ui.dp(8f)
        val rowHeight = Ui.dp(50f)
        val width = (Ui.screenWidth - Ui.dp(40f)).coerceAtMost(Ui.dp(540f))
        val left = (Ui.screenWidth - width) / 2f
        val all = Commander.ALL

        maxScroll = (all.size * rowHeight - (listBottom - listTop)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(0f, listTop, Ui.screenWidth.toFloat(), listBottom)
        for (i in all.indices) {
            val commander = all[i]
            val y = listTop + i * rowHeight - scroll
            if (y + rowHeight < listTop || y > listBottom) continue
            inner.set(left, y + Ui.dp(3f), left + width, y + rowHeight - Ui.dp(3f))

            val owned = unlocked.contains(commander.id)
            Widgets.fill(canvas, inner, Colors.of(if (owned) "#B31C2C3C" else "#8C141C26"), Ui.dp(7f))

            // 頭像：色塊 + 譯名首字。沒有點陣素材，也就沒有素材授權問題。
            val name = Strings.byName(commander.nameKey)
            rect.set(inner.left + Ui.dp(8f), inner.top + Ui.dp(6f), inner.left + Ui.dp(40f), inner.bottom - Ui.dp(6f))
            Widgets.fill(canvas, rect, Colors.of(commander.colour), Ui.dp(5f))
            Widgets.centeredFit(
                canvas, commander.initial(name), rect.centerX(), rect.centerY() + Ui.dp(6f),
                Ui.dp(16f), rect.width() - Ui.dp(4f), bold = true, color = Colors.of("#F5F8FB")
            )

            val textX = inner.left + Ui.dp(48f)
            Widgets.leftFit(
                canvas, name, textX, inner.top + Ui.dp(18f), Ui.dp(12.5f),
                width - Ui.dp(180f), Colors.of(if (owned) Widgets.INK else "#8496A8"), bold = true
            )
            Widgets.stars(
                canvas, textX + Ui.dp(150f), inner.top + Ui.dp(18f),
                commander.rank, Ui.dp(10f), total = 5
            )

            val skills = commander.skills.joinToString("  ") { Strings.byName(it.key) }
            Widgets.leftFit(
                canvas, skills, textX, inner.top + Ui.dp(34f), Ui.dp(9.5f),
                width - Ui.dp(180f), Colors.of("#8FA3B6")
            )

            val actionRect = RectF(
                inner.right - Ui.dp(90f), inner.top + Ui.dp(9f),
                inner.right - Ui.dp(10f), inner.bottom - Ui.dp(9f)
            )
            if (owned) {
                Widgets.badge(
                    canvas, actionRect, Strings.get(R.string.commander_recruited),
                    Colors.of("#33A0D8A8"), Colors.of("#A0D8A8"), Ui.dp(10.5f)
                )
            } else {
                val affordable = medals >= commander.medalCost
                Widgets.button(
                    canvas, actionRect,
                    Strings.format(R.string.commander_recruit, commander.medalCost),
                    Widgets.PURPLE_TOP, Widgets.PURPLE_BOTTOM,
                    enabled = affordable, textSize = Ui.dp(10.5f)
                )
                buttons.add(ID_RECRUIT, actionRect, payload = i, isEnabled = affordable)
            }
        }
        canvas.restore()
    }

    // ------------------------------------------------------------------
    // 設定與說明
    // ------------------------------------------------------------------

    fun drawSettings(
        canvas: Canvas,
        buttons: ButtonLayer,
        language: String,
        sound: Boolean,
        animations: Boolean
    ) {
        drawBackground(canvas)
        val top = header(canvas, buttons, R.string.settings_title)

        var y = top + Ui.dp(20f)
        val width = Ui.dp(300f).coerceAtMost(Ui.screenWidth - Ui.dp(40f))
        val left = (Ui.screenWidth - width) / 2f

        Widgets.left(
            canvas, Strings.get(R.string.settings_language), left, y,
            Ui.dp(12f), Colors.of(Widgets.INK_DIM)
        )
        y += Ui.dp(10f)
        val optionWidth = width / LocaleManager.options.size - Ui.dp(4f)
        var x = left
        for (tag in LocaleManager.options) {
            inner.set(x, y, x + optionWidth, y + Ui.dp(28f))
            val selected = tag == language
            Widgets.button(
                canvas, inner, LocaleManager.displayName(tag),
                if (selected) Widgets.GREEN_TOP else Widgets.STEEL_TOP,
                if (selected) Widgets.GREEN_BOTTOM else Widgets.STEEL_BOTTOM,
                selected = selected, textSize = Ui.dp(10.5f)
            )
            buttons.add(ID_LANGUAGE, inner, payload = LocaleManager.options.indexOf(tag))
            x += optionWidth + Ui.dp(4f)
        }
        y += Ui.dp(42f)

        val toggles = arrayOf(
            Triple(ID_SOUND, R.string.settings_sound, sound),
            Triple(ID_ANIMATIONS, R.string.settings_animations, animations)
        )
        for ((id, labelRes, value) in toggles) {
            inner.set(left, y, left + width, y + Ui.dp(30f))
            Widgets.fill(canvas, inner, Colors.of("#8C151F2B"), Ui.dp(6f))
            Widgets.left(
                canvas, Strings.get(labelRes), inner.left + Ui.dp(12f), inner.centerY() + Ui.dp(4f),
                Ui.dp(12f), Colors.of(Widgets.INK)
            )
            rect.set(inner.right - Ui.dp(72f), inner.top + Ui.dp(4f), inner.right - Ui.dp(8f), inner.bottom - Ui.dp(4f))
            Widgets.button(
                canvas, rect,
                Strings.get(if (value) R.string.settings_on else R.string.settings_off),
                if (value) Widgets.GREEN_TOP else Widgets.GRAY_TOP,
                if (value) Widgets.GREEN_BOTTOM else Widgets.GRAY_BOTTOM,
                textSize = Ui.dp(10.5f)
            )
            buttons.add(id, inner)
            y += Ui.dp(38f)
        }

        Widgets.centeredFit(
            canvas, Strings.get(R.string.settings_licence), Ui.screenWidth / 2f,
            Ui.screenHeight - Ui.dp(12f), Ui.dp(10f), Ui.screenWidth * 0.9f,
            color = Colors.of("#6E8296")
        )
    }

    fun drawHelp(canvas: Canvas, buttons: ButtonLayer) {
        drawBackground(canvas)
        val top = header(canvas, buttons, R.string.menu_help)

        val width = (Ui.screenWidth - Ui.dp(48f)).coerceAtMost(Ui.dp(560f))
        val left = (Ui.screenWidth - width) / 2f
        val listTop = top + Ui.dp(10f)
        val listBottom = Ui.screenHeight - Ui.dp(8f)

        val sections = arrayOf(
            R.string.help_basics to R.string.help_basics_body,
            R.string.help_combat to R.string.help_combat_body,
            R.string.help_morale to R.string.help_morale_body,
            R.string.help_supply to R.string.help_supply_body,
            R.string.help_cities to R.string.help_cities_body,
            R.string.help_victory to R.string.help_victory_body
        )

        // 先量一次總高度，捲動範圍才會正確。
        var total = 0f
        for ((_, bodyRes) in sections) {
            Widgets.wrap(Strings.get(bodyRes), Ui.dp(11f), width, lines)
            total += Ui.dp(20f) + lines.size * Ui.dp(15f) + Ui.dp(10f)
        }
        maxScroll = (total - (listBottom - listTop)).coerceAtLeast(0f)
        scroll = scroll.coerceIn(0f, maxScroll)

        canvas.save()
        canvas.clipRect(0f, listTop, Ui.screenWidth.toFloat(), listBottom)
        var y = listTop + Ui.dp(14f) - scroll
        for ((titleRes, bodyRes) in sections) {
            Widgets.left(
                canvas, Strings.get(titleRes), left, y, Ui.dp(13f),
                Colors.of("#F2D08A"), bold = true
            )
            y += Ui.dp(20f)
            Widgets.wrap(Strings.get(bodyRes), Ui.dp(11f), width, lines)
            for (line in lines) {
                Widgets.left(canvas, line, left, y, Ui.dp(11f), Colors.of("#BFD0DE"))
                y += Ui.dp(15f)
            }
            y += Ui.dp(10f)
        }
        canvas.restore()
    }

    companion object {
        const val ID_BACK = "back"
        const val ID_CONTINUE = "mm_continue"
        const val ID_CAMPAIGN = "mm_campaign"
        const val ID_CONQUEST = "mm_conquest"
        const val ID_COMMANDERS = "mm_commanders"
        const val ID_SETTINGS = "mm_settings"
        const val ID_HELP = "mm_help"
        const val ID_SCENARIO = "scenario"
        const val ID_NATION = "nation"
        const val ID_DIFFICULTY = "difficulty"
        const val ID_START = "start"
        const val ID_RECRUIT = "recruit"
        const val ID_LANGUAGE = "language"
        const val ID_SOUND = "sound"
        const val ID_ANIMATIONS = "animations"
    }
}
