// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import io.github.acidefluorhydrique.mapconquer.ai.AiPlayer
import io.github.acidefluorhydrique.mapconquer.core.Colors
import io.github.acidefluorhydrique.mapconquer.core.LocaleManager
import io.github.acidefluorhydrique.mapconquer.core.Prefs
import io.github.acidefluorhydrique.mapconquer.core.Rng
import io.github.acidefluorhydrique.mapconquer.core.Strings
import io.github.acidefluorhydrique.mapconquer.core.Ui
import io.github.acidefluorhydrique.mapconquer.core.Widgets
import io.github.acidefluorhydrique.mapconquer.game.Difficulty
import io.github.acidefluorhydrique.mapconquer.game.GameMode
import io.github.acidefluorhydrique.mapconquer.game.Orders
import io.github.acidefluorhydrique.mapconquer.game.Progress
import io.github.acidefluorhydrique.mapconquer.game.SaveGame
import io.github.acidefluorhydrique.mapconquer.game.Scenario
import io.github.acidefluorhydrique.mapconquer.game.ScenarioLoader
import io.github.acidefluorhydrique.mapconquer.game.Session
import io.github.acidefluorhydrique.mapconquer.game.SessionStatus
import io.github.acidefluorhydrique.mapconquer.render.ButtonLayer
import io.github.acidefluorhydrique.mapconquer.render.Camera
import io.github.acidefluorhydrique.mapconquer.render.HudRenderer
import io.github.acidefluorhydrique.mapconquer.render.MapOverlay
import io.github.acidefluorhydrique.mapconquer.render.MapRenderer
import io.github.acidefluorhydrique.mapconquer.render.MenuRenderer
import io.github.acidefluorhydrique.mapconquer.render.Panel
import io.github.acidefluorhydrique.mapconquer.render.PanelRenderer
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.Commander
import io.github.acidefluorhydrique.mapconquer.units.TechBranch
import io.github.acidefluorhydrique.mapconquer.units.UnitKind
import io.github.acidefluorhydrique.mapconquer.world.MapLoader
import kotlin.math.hypot

/** app 的畫面狀態。遊戲外的每一頁都是這裡的一個值。 */
enum class Screen { MAIN_MENU, CAMPAIGN_LIST, CONQUEST_LIST, SETUP, COMMANDERS, SETTINGS, HELP, GAME }

/**
 * 整個 app 的畫面與輸入。
 *
 * 只有一個 Activity、一個 SurfaceView，所有頁面都是這裡的一個狀態 ——
 * 沒有 Fragment、沒有導覽元件、沒有 View 樹。對一款所有內容都畫在
 * Canvas 上的遊戲來說，引入那些只會多一層要同步的狀態。
 *
 * 執行緒模型：繪製與更新在 [GameLoop] 的執行緒，觸控在 UI 執行緒，
 * 兩者用 surfaceHolder 這把鎖串起來。所有會改動遊戲狀態的路徑都在鎖內。
 */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private var loop: GameLoop? = null

    private val buttons = ButtonLayer()
    private val menuRenderer = MenuRenderer()

    private var screen = Screen.MAIN_MENU

    // ---- 遊戲中的狀態 ----
    private var session: Session? = null
    private var camera: Camera? = null
    private var mapRenderer: MapRenderer? = null
    private var hudRenderer: HudRenderer? = null
    private var panelRenderer: PanelRenderer? = null
    private var overlay: MapOverlay? = null
    private var ai: AiPlayer? = null
    private var panel = Panel.NONE

    // ---- 選單資料 ----
    private var allScenarios: List<Scenario> = emptyList()
    private var campaignScenarios: List<Scenario> = emptyList()
    private var conquestScenarios: List<Scenario> = emptyList()
    private var pendingScenario: Scenario? = null
    private var pendingNationIndex = 0
    private var pendingDifficulty = Difficulty.OFFICER
    private var pendingIsCampaign = true

    private var saveSummary: String = ""
    private var hasSave = false

    // ---- 輸入 ----
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var dragged = false
    private var pinchDistance = 0f
    private var pinching = false

    private val reachable = ArrayList<Int>(160)
    private val attackTargets = ArrayList<Int>(32)
    private val pathBuffer = ArrayList<Int>(32)

    private var soundEnabled = true
    private var animationsEnabled = true

    private var toastText: String = ""
    private var toastTimer = 0
    private val toastRect = RectF()

    init {
        holder.addCallback(this)
        isFocusable = true
        Strings.init(context)
        soundEnabled = Prefs.getBool(context, Prefs.KEY_SOUND, true)
        animationsEnabled = Prefs.getBool(context, Prefs.KEY_ANIMATIONS, true)
        Audio.setEnabled(soundEnabled)
        loadScenarios()
        refreshSaveState()
    }

    // ------------------------------------------------------------------
    // Surface 生命週期
    // ------------------------------------------------------------------

    override fun surfaceCreated(holder: SurfaceHolder) {
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Ui.onSurfaceChanged(width, height)
        camera?.let {
            it.insetTop = Ui.topBarHeight
            it.insetBottom = Ui.bottomBarHeight
            it.onResize(width, height)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        stopLoop()
    }

    private fun startLoop() {
        if (loop?.running == true) return
        loop = GameLoop(holder, this).also {
            it.running = true
            it.start()
        }
    }

    private fun stopLoop() {
        val current = loop ?: return
        current.running = false
        var retry = true
        while (retry) {
            try {
                current.join(500)
                retry = false
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                retry = false
            }
        }
        loop = null
    }

    fun onActivityResume() = startLoop()

    fun onActivityPause() {
        autoSave()
        stopLoop()
    }

    fun onActivityDestroy() = stopLoop()

    // ------------------------------------------------------------------
    // 每影格
    // ------------------------------------------------------------------

    fun update(deltaMs: Int) {
        if (toastTimer > 0) toastTimer -= deltaMs
        val active = session ?: return
        overlay?.tickAnimation(if (animationsEnabled) deltaMs / 180f else 1f)

        if (active.status != SessionStatus.PLAYING) {
            if (panel != Panel.RESULT) onGameFinished(active)
            return
        }
        if (active.isPlayerTurn) {
            ai = null
            return
        }
        driveAi(active)
    }

    /**
     * 推進 AI。
     *
     * 一影格只跑固定步數，讓畫面保持流暢；步數開得比「看得清楚」略高一點，
     * 因為在世界地圖上等三十個國家逐一表演會比看不清楚更難受。
     */
    private fun driveAi(active: Session) {
        val player = ai ?: AiPlayer(active, active.activeNationId).also { ai = it }
        var steps = AI_STEPS_PER_FRAME
        while (steps-- > 0) {
            if (!player.step()) {
                ai = null
                active.advanceToNextNation()
                if (active.isPlayerTurn) {
                    onPlayerTurnStarted(active)
                }
                return
            }
        }
    }

    private fun onPlayerTurnStarted(active: Session) {
        overlay?.clearSelection()
        Audio.play(Sfx.TURN)
        autoSave()
    }

    private fun onGameFinished(active: Session) {
        panel = Panel.RESULT
        Audio.play(if (active.status == SessionStatus.VICTORY) Sfx.VICTORY else Sfx.DEFEAT)
        if (active.status == SessionStatus.VICTORY && active.scenario.mode == GameMode.CAMPAIGN) {
            val stars = active.scenario.starsFor(active.turn)
            Progress.recordCampaignResult(context, active.scenario.id, stars)
        }
        SaveGame.delete(context)
        refreshSaveState()
    }

    fun render(canvas: Canvas) {
        buttons.clear()
        when (screen) {
            Screen.MAIN_MENU -> menuRenderer.drawMain(
                canvas, buttons, hasSave, saveSummary,
                Progress.medals(context), Progress.totalStars(context),
                Progress.clearedCount(context), campaignScenarios.size
            )
            Screen.CAMPAIGN_LIST -> menuRenderer.drawScenarioList(
                canvas, buttons, R.string.menu_campaign, campaignScenarios,
                starsOf = { Progress.starsFor(context, it.id) },
                unlockedAt = { Progress.isScenarioUnlocked(context, campaignScenarios, it) },
                showStars = true
            )
            Screen.CONQUEST_LIST -> menuRenderer.drawScenarioList(
                canvas, buttons, R.string.menu_conquest, conquestScenarios,
                starsOf = { 0 }, unlockedAt = { true }, showStars = false
            )
            Screen.SETUP -> pendingScenario?.let {
                menuRenderer.drawNationSelect(
                    canvas, buttons, it, it.playableNations(), pendingNationIndex, pendingDifficulty
                )
            }
            Screen.COMMANDERS -> menuRenderer.drawCommanders(
                canvas, buttons, Progress.medals(context), Progress.unlockedCommanders(context)
            )
            Screen.SETTINGS -> menuRenderer.drawSettings(
                canvas, buttons, LocaleManager.stored(context), soundEnabled, animationsEnabled
            )
            Screen.HELP -> menuRenderer.drawHelp(canvas, buttons)
            Screen.GAME -> renderGame(canvas)
        }
        drawToast(canvas)
    }

    private fun renderGame(canvas: Canvas) {
        val active = session ?: return
        val cam = camera ?: return
        val over = overlay ?: return
        canvas.drawColor(Colors.of("#070C12"))
        mapRenderer?.draw(canvas, cam, over, animateFog = true)
        hudRenderer?.draw(canvas, buttons, over, aiThinking = !active.isPlayerTurn)
        panelRenderer?.draw(
            canvas, buttons, panel,
            selectedProvince = over.selectedTile.takeIf { it >= 0 }
                ?.let { active.map.provinceOf[it] } ?: -1,
            selectedUnit = over.selectedUnit
        )
    }

    private fun drawToast(canvas: Canvas) {
        if (toastTimer <= 0 || toastText.isEmpty()) return
        val width = Ui.dp(260f)
        val height = Ui.dp(26f)
        val x = (Ui.screenWidth - width) / 2f
        val y = Ui.screenHeight - Ui.bottomBarHeight - height - Ui.dp(12f)
        toastRect.set(x, y, x + width, y + height)
        Widgets.panel(canvas, toastRect, Ui.dp(6f))
        Widgets.centeredFit(
            canvas, toastText, toastRect.centerX(), toastRect.centerY() + Ui.dp(4f),
            Ui.dp(11.5f), width - Ui.dp(16f), color = Colors.of("#F2F6FA")
        )
    }

    private fun toast(text: String) {
        toastText = text
        toastTimer = TOAST_MS
    }

    // ------------------------------------------------------------------
    // 觸控
    // ------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        synchronized(holder) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    lastX = event.x
                    lastY = event.y
                    dragged = false
                    pinching = false
                }

                MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount >= 2) {
                    pinching = true
                    dragged = true
                    pinchDistance = spacing(event)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (pinching && event.pointerCount >= 2) {
                        val distance = spacing(event)
                        if (pinchDistance > 1f && distance > 1f) {
                            camera?.zoomBy(
                                distance / pinchDistance,
                                (event.getX(0) + event.getX(1)) / 2f,
                                (event.getY(0) + event.getY(1)) / 2f
                            )
                        }
                        pinchDistance = distance
                    } else {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (!dragged && hypot(event.x - downX, event.y - downY) > Ui.touchSlop) {
                            dragged = true
                        }
                        if (dragged) onDrag(dx, dy)
                        lastX = event.x
                        lastY = event.y
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> if (event.pointerCount <= 2) pinching = false

                MotionEvent.ACTION_UP -> if (!dragged) onTap(event.x, event.y)

                MotionEvent.ACTION_CANCEL -> dragged = false
            }
        }
        return true
    }

    private fun spacing(event: MotionEvent): Float =
        hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))

    private fun onDrag(dx: Float, dy: Float) {
        when (screen) {
            Screen.GAME -> {
                if (panel == Panel.PRODUCTION) {
                    panelRenderer?.let {
                        it.productionScroll = (it.productionScroll - dy).coerceIn(0f, it.productionMaxScroll)
                    }
                } else if (panel == Panel.NONE) {
                    camera?.panBy(dx, dy)
                }
            }
            else -> menuRenderer.scroll = (menuRenderer.scroll - dy).coerceIn(0f, menuRenderer.maxScroll)
        }
    }

    private fun onTap(x: Float, y: Float) {
        val hit = buttons.hit(x, y)
        if (hit != null) {
            if (!hit.enabled) {
                Audio.play(Sfx.DENIED)
                return
            }
            Audio.play(Sfx.CLICK)
            handleButton(hit.id, hit.payload)
            return
        }
        if (screen == Screen.GAME && panel == Panel.NONE) onMapTap(x, y)
    }

    // ------------------------------------------------------------------
    // 按鈕
    // ------------------------------------------------------------------

    private fun handleButton(id: String, payload: Int) {
        when (id) {
            MenuRenderer.ID_BACK -> goBack()
            MenuRenderer.ID_CONTINUE -> continueGame()
            MenuRenderer.ID_CAMPAIGN -> openList(Screen.CAMPAIGN_LIST)
            MenuRenderer.ID_CONQUEST -> openList(Screen.CONQUEST_LIST)
            MenuRenderer.ID_COMMANDERS -> openList(Screen.COMMANDERS)
            MenuRenderer.ID_SETTINGS -> openList(Screen.SETTINGS)
            MenuRenderer.ID_HELP -> openList(Screen.HELP)

            MenuRenderer.ID_SCENARIO -> {
                val list = if (screen == Screen.CAMPAIGN_LIST) campaignScenarios else conquestScenarios
                pendingScenario = list.getOrNull(payload)
                pendingIsCampaign = screen == Screen.CAMPAIGN_LIST
                pendingNationIndex = 0
                if (pendingScenario != null) {
                    screen = Screen.SETUP
                    menuRenderer.scroll = 0f
                }
            }
            MenuRenderer.ID_NATION -> pendingNationIndex = payload
            MenuRenderer.ID_DIFFICULTY -> pendingDifficulty =
                Difficulty.ALL.getOrElse(payload) { Difficulty.OFFICER }
            MenuRenderer.ID_START -> startGame()

            MenuRenderer.ID_RECRUIT -> {
                val commander = Commander.ALL.getOrNull(payload) ?: return
                if (Progress.recruit(context, commander)) {
                    toast(Strings.format(R.string.commander_recruited_toast, Strings.byName(commander.nameKey)))
                } else {
                    Audio.play(Sfx.DENIED)
                }
            }
            MenuRenderer.ID_LANGUAGE -> {
                val tag = LocaleManager.options.getOrNull(payload) ?: return
                LocaleManager.store(context, tag)
                // 語系是整個 Activity 的設定，換了就重建，比逐處刷新可靠得多。
                (context as? MainActivity)?.recreate()
            }
            MenuRenderer.ID_SOUND -> {
                soundEnabled = !soundEnabled
                Prefs.putBool(context, Prefs.KEY_SOUND, soundEnabled)
                Audio.setEnabled(soundEnabled)
            }
            MenuRenderer.ID_ANIMATIONS -> {
                animationsEnabled = !animationsEnabled
                Prefs.putBool(context, Prefs.KEY_ANIMATIONS, animationsEnabled)
            }

            HudRenderer.ID_END_TURN -> endPlayerTurn()
            HudRenderer.ID_MENU -> panel = Panel.PAUSE
            HudRenderer.ID_TECH -> panel = Panel.TECH
            HudRenderer.ID_OBJECTIVES -> panel = Panel.OBJECTIVES
            HudRenderer.ID_BUILD -> panel = Panel.PRODUCTION
            HudRenderer.ID_WAIT -> skipSelectedUnit()
            HudRenderer.ID_REPAIR -> repairSelectedUnit()
            HudRenderer.ID_NEXT_UNIT -> selectNextIdleUnit()
            HudRenderer.ID_TOGGLE_GRID -> overlay?.let { it.showGrid = !it.showGrid }
            HudRenderer.ID_TOGGLE_SUPPLY -> overlay?.let { it.showSupply = !it.showSupply }

            PanelRenderer.ID_CLOSE, PanelRenderer.ID_RESUME -> panel = Panel.NONE
            PanelRenderer.ID_BUILD_KIND -> buildUnit(payload)
            PanelRenderer.ID_RESEARCH -> researchBranch(payload)
            PanelRenderer.ID_SAVE -> {
                autoSave()
                toast(Strings.get(R.string.toast_saved))
            }
            PanelRenderer.ID_SETTINGS -> {
                panel = Panel.NONE
                openList(Screen.SETTINGS)
            }
            PanelRenderer.ID_QUIT -> quitToMenu()
            PanelRenderer.ID_RESULT_OK -> quitToMenu()
            PanelRenderer.ID_ASSIGN_COMMANDER -> assignCommander()
        }
    }

    private fun openList(target: Screen) {
        screen = target
        menuRenderer.scroll = 0f
    }

    private fun goBack() {
        when (screen) {
            Screen.SETUP -> screen = if (pendingIsCampaign) Screen.CAMPAIGN_LIST else Screen.CONQUEST_LIST
            Screen.GAME -> panel = Panel.PAUSE
            else -> screen = Screen.MAIN_MENU
        }
        menuRenderer.scroll = 0f
    }

    /** 回傳 false 代表交還給系統（離開 app）。 */
    fun onBackPressed(): Boolean {
        synchronized(holder) {
            return when {
                screen == Screen.GAME && panel != Panel.NONE -> {
                    panel = Panel.NONE
                    true
                }
                screen == Screen.GAME -> {
                    panel = Panel.PAUSE
                    true
                }
                screen != Screen.MAIN_MENU -> {
                    goBack()
                    true
                }
                else -> false
            }
        }
    }

    // ------------------------------------------------------------------
    // 地圖操作
    // ------------------------------------------------------------------

    private fun onMapTap(x: Float, y: Float) {
        val active = session ?: return
        val cam = camera ?: return
        val over = overlay ?: return
        val tile = cam.tileAt(x, y)
        if (tile < 0) return
        if (!active.isPlayerTurn) {
            over.selectedTile = tile
            over.selectedUnit = active.primaryUnitAt(tile)
            return
        }

        val selected = over.selectedUnit
        if (selected != null && selected.nationId == active.playerNationId) {
            if (over.attackable[tile]) {
                performAttack(active, selected, tile)
                return
            }
            if (over.movable[tile]) {
                performMove(active, selected, tile)
                return
            }
        }

        // 沒有動作可做就是「選取」。同一格連點兩次會開詳情面板。
        val unit = active.primaryUnitAt(tile)
        if (unit != null && !active.isUnitVisibleToPlayer(unit)) {
            over.selectedUnit = null
            over.selectedTile = tile
            over.clearHighlights()
            return
        }
        if (over.selectedTile == tile && over.selectedUnit != null && unit === over.selectedUnit) {
            panel = Panel.UNIT_DETAIL
            return
        }
        over.selectedTile = tile
        over.selectedUnit = unit
        refreshHighlights(active, over)
    }

    private fun refreshHighlights(active: Session, over: MapOverlay) {
        over.clearHighlights()
        val unit = over.selectedUnit ?: return
        if (unit.nationId != active.playerNationId || !active.isPlayerTurn) return
        Orders.computeReachable(active, unit, reachable)
        over.setMovable(reachable)
        Orders.collectTargets(active, unit, attackTargets)
        over.setAttackable(attackTargets)
    }

    private fun performMove(active: Session, unit: ArmyUnit, target: Int) {
        val from = unit.tile
        val moved = Orders.move(active, unit, target, pathBuffer)
        if (moved == from) {
            Audio.play(Sfx.DENIED)
            return
        }
        Audio.play(Sfx.MOVE)
        overlay?.let {
            if (animationsEnabled) it.startMoveAnimation(unit, from, moved)
            refreshHighlights(active, it)
            it.selectedTile = moved
        }
    }

    private fun performAttack(active: Session, unit: ArmyUnit, target: Int) {
        val defender = Orders.findTarget(active, unit, target)
        val result = Orders.attack(active, unit, target)
        if (result == null) {
            Audio.play(Sfx.DENIED)
            return
        }
        Audio.play(Sfx.ATTACK)
        val defenderName = defender?.let { Strings.byName(it.kind.key) } ?: ""
        toast(
            Strings.format(
                R.string.toast_combat,
                defenderName, result.damageToDefender, result.damageToAttacker
            )
        )
        overlay?.let {
            if (!unit.isAlive) it.clearSelection() else refreshHighlights(active, it)
        }
    }

    private fun skipSelectedUnit() {
        val unit = overlay?.selectedUnit ?: return
        unit.movesLeft = 0
        unit.hasAttacked = true
        selectNextIdleUnit()
    }

    private fun repairSelectedUnit() {
        val active = session ?: return
        val unit = overlay?.selectedUnit ?: return
        if (Orders.repair(active, unit)) {
            Audio.play(Sfx.BUILD)
            overlay?.let { refreshHighlights(active, it) }
        } else {
            Audio.play(Sfx.DENIED)
        }
    }

    /** 跳到下一支還沒行動完的部隊，並把畫面帶過去。 */
    private fun selectNextIdleUnit() {
        val active = session ?: return
        val over = overlay ?: return
        val own = active.units.filter {
            it.nationId == active.playerNationId && it.isAlive && !it.isSpent && !it.isLoaded
        }
        if (own.isEmpty()) {
            toast(Strings.get(R.string.toast_no_idle_units))
            over.clearSelection()
            return
        }
        val currentIndex = own.indexOfFirst { it === over.selectedUnit }
        val next = own[(currentIndex + 1).coerceAtLeast(0) % own.size]
        over.selectedUnit = next
        over.selectedTile = next.tile
        refreshHighlights(active, over)
        camera?.centreOnTile(next.tile)
    }

    private fun buildUnit(kindOrdinal: Int) {
        val active = session ?: return
        val over = overlay ?: return
        val kind = UnitKind.ALL.getOrNull(kindOrdinal) ?: return
        val tile = over.selectedTile
        if (tile < 0) return
        val provinceId = active.map.provinceOf[tile]
        if (provinceId < 0) return
        val unit = Orders.build(active, active.playerNationId, provinceId, kind)
        if (unit == null) {
            Audio.play(Sfx.DENIED)
            return
        }
        Audio.play(Sfx.BUILD)
        toast(Strings.format(R.string.toast_built, Strings.byName(kind.key)))
    }

    private fun researchBranch(ordinal: Int) {
        val active = session ?: return
        val branch = TechBranch.values().getOrNull(ordinal) ?: return
        if (Orders.research(active, active.playerNationId, branch)) {
            Audio.play(Sfx.BUILD)
        } else {
            Audio.play(Sfx.DENIED)
        }
    }

    /**
     * 指派指揮官：從已解鎖且沒帶兵的人裡輪流換。
     * 做成「循環」而不是彈一個選單，是因為指揮官數量少，
     * 而反覆點一顆按鈕比開關一層面板快得多。
     */
    private fun assignCommander() {
        val active = session ?: return
        val unit = overlay?.selectedUnit ?: return
        if (unit.nationId != active.playerNationId) return
        val unlocked = Progress.unlockedCommanders(context)
        val available = Commander.ALL.filter {
            unlocked.contains(it.id) &&
                active.units.none { u -> u.id != unit.id && u.commanderId == it.id }
        }
        if (available.isEmpty()) {
            toast(Strings.get(R.string.toast_no_commanders))
            return
        }
        val currentIndex = available.indexOfFirst { it.id == unit.commanderId }
        val next = if (currentIndex < 0) available.first() else {
            if (currentIndex + 1 >= available.size) null else available[currentIndex + 1]
        }
        Orders.assignCommander(active, unit, next?.id ?: "")
        toast(
            if (next == null) Strings.get(R.string.toast_commander_cleared)
            else Strings.format(R.string.toast_commander_assigned, Strings.byName(next.nameKey))
        )
    }

    private fun endPlayerTurn() {
        val active = session ?: return
        if (!active.isPlayerTurn) return
        overlay?.clearSelection()
        panel = Panel.NONE
        active.advanceToNextNation()
        Audio.play(Sfx.TURN)
    }

    // ------------------------------------------------------------------
    // 開局／存檔
    // ------------------------------------------------------------------

    private fun loadScenarios() {
        allScenarios = runCatching { ScenarioLoader.loadAll(context) }.getOrDefault(emptyList())
        campaignScenarios = allScenarios.filter { it.mode == GameMode.CAMPAIGN }
        conquestScenarios = allScenarios.filter { it.mode == GameMode.CONQUEST }
    }

    private fun refreshSaveState() {
        hasSave = SaveGame.exists(context)
        saveSummary = if (!hasSave) "" else {
            val summary = SaveGame.peek(context)
            if (summary == null) "" else {
                val scenario = allScenarios.firstOrNull { it.id == summary.scenarioId }
                Strings.format(
                    R.string.menu_continue_summary,
                    scenario?.let { Strings.byName(it.nameKey) } ?: summary.scenarioId,
                    summary.turn
                )
            }
        }
    }

    private fun startGame() {
        val scenario = pendingScenario ?: return
        val nations = scenario.playableNations()
        val chosen = nations.getOrNull(pendingNationIndex) ?: return
        val map = runCatching { MapLoader.load(context, scenario.mapId) }.getOrNull()
        if (map == null) {
            toast(Strings.get(R.string.toast_map_missing))
            return
        }
        val seed = Rng.seedOf(scenario.id + chosen.code + System.currentTimeMillis())
        installSession(Session(map, scenario, pendingDifficulty, chosen.code, seed))
    }

    private fun continueGame() {
        val restored = SaveGame.load(context)
        if (restored == null) {
            toast(Strings.get(R.string.toast_save_broken))
            SaveGame.delete(context)
            refreshSaveState()
            return
        }
        installSession(restored)
    }

    private fun installSession(active: Session) {
        session = active
        overlay = MapOverlay(active.map.tileCount)
        mapRenderer = MapRenderer(active)
        hudRenderer = HudRenderer(active)
        panelRenderer = PanelRenderer(active)
        ai = null
        panel = Panel.NONE
        screen = Screen.GAME

        val cam = Camera(active.map)
        cam.insetTop = Ui.topBarHeight
        cam.insetBottom = Ui.bottomBarHeight
        cam.onResize(Ui.screenWidth, Ui.screenHeight)
        val home = active.nations[active.playerNationId].capitalProvince
            .takeIf { it in active.map.provinces.indices }
            ?.let { active.map.provinces[it].capitalTile }
            ?: active.units.firstOrNull { it.nationId == active.playerNationId }?.tile
            ?: 0
        cam.frameOn(home)
        camera = cam

        active.recomputeVisibility(active.playerNationId)
        Audio.play(Sfx.TURN)
    }

    private fun autoSave() {
        val active = session ?: return
        if (active.status != SessionStatus.PLAYING) return
        SaveGame.save(context, active)
        refreshSaveState()
    }

    private fun quitToMenu() {
        autoSave()
        session = null
        camera = null
        mapRenderer = null
        hudRenderer = null
        panelRenderer = null
        overlay = null
        ai = null
        panel = Panel.NONE
        screen = Screen.MAIN_MENU
        refreshSaveState()
    }

    companion object {
        /**
         * 一影格推進幾步 AI。
         *
         * 征服劇本有上百個國家，每個都要走完整的思考流程；步數開太小的話
         * 玩家會盯著「AI 回合中」看好幾秒。開到 8 之後一個世界回合大約一秒，
         * 又還看得到部隊一支一支移動。
         */
        private const val AI_STEPS_PER_FRAME = 8
        private const val TOAST_MS = 2200
    }
}
