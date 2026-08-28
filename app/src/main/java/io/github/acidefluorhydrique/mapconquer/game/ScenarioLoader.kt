// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import android.content.Context
import java.io.BufferedReader

/**
 * 劇本檔（assets/scenarios 底下的 .scn）的解析器。
 *
 * 與地圖檔同一套風格：分節、每節逐行、`|` 分欄。
 * 省份 id 支援 `10-24,31,40` 這種寫法 —— 開局領土動輒幾十個省，
 * 逐一列出會讓檔案沒法看，範圍寫法讓「德國佔了中歐一整塊」在檔案裡也一目瞭然。
 */
object ScenarioLoader {

    class ScenarioFormatException(message: String) : RuntimeException(message)

    fun list(context: Context): List<String> =
        (context.assets.list("scenarios") ?: emptyArray())
            .filter { it.endsWith(".scn") }
            .map { it.removeSuffix(".scn") }
            .sorted()

    fun load(context: Context, scenarioId: String): Scenario =
        context.assets.open("scenarios/$scenarioId.scn").bufferedReader().use {
            parse(it, scenarioId)
        }

    /** 一次讀進所有劇本，選單需要它們的名稱與排序。 */
    fun loadAll(context: Context): List<Scenario> =
        list(context).mapNotNull { runCatching { load(context, it) }.getOrNull() }
            .sortedWith(compareBy({ it.mode.ordinal }, { it.order }, { it.id }))

    fun parse(reader: BufferedReader, fallbackId: String): Scenario {
        var id = fallbackId
        var mapId = "world"
        var mode = GameMode.CONQUEST
        var nameKey = "scenario_$fallbackId"
        var descKey = "scenario_${fallbackId}_desc"
        var order = 0
        var turnLimit = 0
        var startYear = 1939
        var startMonth = 9
        var starTurns = intArrayOf(0, 0)

        val nations = ArrayList<ScenarioNation>()
        val relations = ArrayList<Triple<String, String, Relation>>()
        val ownership = LinkedHashMap<String, IntArray>()
        val units = ArrayList<ScenarioUnit>()
        val playable = ArrayList<String>()
        val objectives = ArrayList<Objective>()

        var section = ""
        reader.forEachLine { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("[") -> section = line.lowercase()
                section.isEmpty() -> {
                    val sep = line.indexOf(' ')
                    if (sep > 0) {
                        val key = line.substring(0, sep)
                        val value = line.substring(sep + 1).trim()
                        when (key) {
                            "id" -> id = value
                            "map" -> mapId = value
                            "mode" -> mode = runCatching {
                                GameMode.valueOf(value.uppercase())
                            }.getOrDefault(GameMode.CONQUEST)
                            "nameKey" -> nameKey = value
                            "descKey" -> descKey = value
                            "order" -> order = value.toIntOrNull() ?: 0
                            "turnLimit" -> turnLimit = value.toIntOrNull() ?: 0
                            "startYear" -> startYear = value.toIntOrNull() ?: startYear
                            "startMonth" -> startMonth = value.toIntOrNull() ?: startMonth
                            "starTurns" -> starTurns = value.split(',')
                                .mapNotNull { it.trim().toIntOrNull() }
                                .toIntArray()
                        }
                    }
                }
                section == "[nations]" -> parseNation(line)?.let(nations::add)
                section == "[relations]" -> parseRelation(line)?.let(relations::add)
                section == "[owners]" -> parseOwners(line, ownership)
                section == "[units]" -> parseUnit(line)?.let(units::add)
                section == "[playable]" -> line.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach(playable::add)
                section == "[objectives]" -> parseObjective(line)?.let(objectives::add)
            }
        }

        if (nations.isEmpty()) throw ScenarioFormatException("scenario '$id' has no nations")
        if (starTurns.size < 2) starTurns = intArrayOf(turnLimit / 2, (turnLimit * 3) / 4)

        return Scenario(
            id, mapId, mode, nameKey, descKey, order, turnLimit, startYear, startMonth,
            nations, relations, ownership, units, playable, objectives, starTurns
        )
    }

    /**
     * `GER|nation_ger|#6E7A86|12|AGGRESSIVE|900|1,1,1,1,0,1|🇩🇪`
     *
     * 最後兩欄（科技、國旗）都可以省略，舊劇本不必改。
     */
    private fun parseNation(line: String): ScenarioNation? {
        val p = line.split('|')
        if (p.size < 6) return null
        val tech = if (p.size >= 7) {
            p[6].split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
        } else IntArray(0)
        return ScenarioNation(
            code = p[0].trim(),
            nameKey = p[1].trim(),
            colour = p[2].trim(),
            capitalProvince = p[3].trim().toIntOrNull() ?: -1,
            aiProfile = runCatching { AiProfile.valueOf(p[4].trim().uppercase()) }
                .getOrDefault(AiProfile.BALANCED),
            funds = p[5].trim().toIntOrNull() ?: 500,
            tech = tech,
            flag = if (p.size >= 8) p[7].trim() else ""
        )
    }

    /** `GER SOV WAR` */
    private fun parseRelation(line: String): Triple<String, String, Relation>? {
        val p = line.split(Regex("\\s+"))
        if (p.size < 3) return null
        val rel = runCatching { Relation.valueOf(p[2].uppercase()) }.getOrNull() ?: return null
        return Triple(p[0], p[1], rel)
    }

    /** `GER: 10-24,31,40` */
    private fun parseOwners(line: String, into: MutableMap<String, IntArray>) {
        val colon = line.indexOf(':')
        if (colon <= 0) return
        val code = line.substring(0, colon).trim()
        val ids = expandRanges(line.substring(colon + 1))
        if (ids.isEmpty()) return
        val existing = into[code]
        into[code] = if (existing == null) ids else existing + ids
    }

    /** `GER|40,14|ARMOUR|2|cmd_ashby` —— 等級與指揮官可省略。 */
    private fun parseUnit(line: String): ScenarioUnit? {
        val p = line.split('|')
        if (p.size < 3) return null
        val coords = p[1].split(',')
        if (coords.size < 2) return null
        val col = coords[0].trim().toIntOrNull() ?: return null
        val row = coords[1].trim().toIntOrNull() ?: return null
        return ScenarioUnit(
            nationCode = p[0].trim(),
            col = col,
            row = row,
            kindName = p[2].trim(),
            level = if (p.size >= 4) (p[3].trim().toIntOrNull() ?: 1) else 1,
            commanderId = if (p.size >= 5) p[4].trim() else ""
        )
    }

    /**
     * `CAPTURE_PROVINCES|12,13,14`
     * `SURVIVE_TURNS||20`
     * `ELIMINATE_NATION|SOV`
     * `CONTROL_COUNT||90`
     */
    private fun parseObjective(line: String): Objective? {
        val p = line.split('|')
        val type = runCatching { ObjectiveType.valueOf(p[0].trim().uppercase()) }.getOrNull()
            ?: return null
        val arg1 = if (p.size >= 2) p[1].trim() else ""
        val arg2 = if (p.size >= 3) p[2].trim() else ""
        return when (type) {
            ObjectiveType.CAPTURE_PROVINCES, ObjectiveType.HOLD_PROVINCES -> Objective(
                type = type,
                provinces = expandRanges(arg1),
                turn = arg2.toIntOrNull() ?: 0
            )
            ObjectiveType.ELIMINATE_NATION -> Objective(type = type, nationCode = arg1)
            ObjectiveType.SURVIVE_TURNS -> Objective(
                type = type,
                turn = (arg2.ifEmpty { arg1 }).toIntOrNull() ?: 0
            )
            ObjectiveType.CONTROL_COUNT -> Objective(
                type = type,
                amount = (arg2.ifEmpty { arg1 }).toIntOrNull() ?: 0
            )
        }
    }

    /** `10-24,31,40` → [10,11,...,24,31,40] */
    fun expandRanges(text: String): IntArray {
        if (text.isBlank()) return IntArray(0)
        val out = ArrayList<Int>()
        for (chunk in text.split(',')) {
            val token = chunk.trim()
            if (token.isEmpty()) continue
            val dash = token.indexOf('-', 1)
            if (dash > 0) {
                val lo = token.substring(0, dash).trim().toIntOrNull()
                val hi = token.substring(dash + 1).trim().toIntOrNull()
                if (lo != null && hi != null && hi >= lo) for (v in lo..hi) out.add(v)
            } else {
                token.toIntOrNull()?.let(out::add)
            }
        }
        return out.toIntArray()
    }
}
