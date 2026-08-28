// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

/** 兩大模式。 */
enum class GameMode {
    /** 戰役：固定劇本、固定目標、有星等評價。 */
    CAMPAIGN,

    /** 征服：整張世界地圖，選一國打到底。 */
    CONQUEST
}

enum class ObjectiveType {
    /** 佔領指定的所有省份。 */
    CAPTURE_PROVINCES,

    /** 撐到第 N 回合仍持有指定省份。 */
    HOLD_PROVINCES,

    /** 消滅指定國家。 */
    ELIMINATE_NATION,

    /** 撐過 N 回合。 */
    SURVIVE_TURNS,

    /** 持有至少 N 個省份（征服模式的統一條件）。 */
    CONTROL_COUNT
}

/**
 * 一條勝利條件。
 *
 * 目標寫在劇本檔而不是程式碼裡，是為了讓新增戰役關卡完全不必動 Kotlin ——
 * 想加一關就丟一個 .scn 進 assets。
 */
class Objective(
    val type: ObjectiveType,
    val provinces: IntArray = IntArray(0),
    val nationCode: String = "",
    val amount: Int = 0,
    val turn: Int = 0
) {
    /** 給 UI 顯示的字串鍵。 */
    val key: String
        get() = when (type) {
            ObjectiveType.CAPTURE_PROVINCES -> "objective_capture"
            ObjectiveType.HOLD_PROVINCES -> "objective_hold"
            ObjectiveType.ELIMINATE_NATION -> "objective_eliminate"
            ObjectiveType.SURVIVE_TURNS -> "objective_survive"
            ObjectiveType.CONTROL_COUNT -> "objective_control"
        }
}

class ScenarioNation(
    val code: String,
    val nameKey: String,
    val colour: String,
    val capitalProvince: Int,
    val aiProfile: AiProfile,
    val funds: Int,
    val tech: IntArray
)

class ScenarioUnit(
    val nationCode: String,
    val col: Int,
    val row: Int,
    val kindName: String,
    val level: Int,
    val commanderId: String
)

/**
 * 一份劇本：地圖 + 開局態勢 + 勝利條件。
 *
 * 劇本不持有任何可變狀態。開一局遊戲＝把劇本「展開」成一個
 * [Session]，同一份劇本可以重開無數次而互不干擾。
 */
class Scenario(
    val id: String,
    val mapId: String,
    val mode: GameMode,
    val nameKey: String,
    val descKey: String,
    /** 戰役關卡的排序；征服劇本用它排年代。 */
    val order: Int,
    val turnLimit: Int,
    val startYear: Int,
    val startMonth: Int,
    val nations: List<ScenarioNation>,
    /** (國家 A, 國家 B, 關係)。沒列到的一律和平。 */
    val relations: List<Triple<String, String, Relation>>,
    /** 國家代碼 → 開局持有的省份 id。 */
    val ownership: Map<String, IntArray>,
    val startingUnits: List<ScenarioUnit>,
    /** 玩家可以選的國家代碼；空的代表全部可選。 */
    val playable: List<String>,
    val objectives: List<Objective>,
    /** 三星／二星的回合門檻。戰役專用。 */
    val starTurns: IntArray
) {
    fun nationByCode(code: String): ScenarioNation? = nations.firstOrNull { it.code == code }

    /** 玩家可選的國家；劇本沒指定時就是全部。 */
    fun playableNations(): List<ScenarioNation> =
        if (playable.isEmpty()) nations else nations.filter { playable.contains(it.code) }

    /** 依完成回合數換算星等。 */
    fun starsFor(turnsUsed: Int): Int = when {
        starTurns.size < 2 -> 3
        turnsUsed <= starTurns[0] -> 3
        turnsUsed <= starTurns[1] -> 2
        else -> 1
    }
}
