// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import android.content.Context
import io.github.acidefluorhydrique.mapconquer.units.ArmyUnit
import io.github.acidefluorhydrique.mapconquer.units.UnitKind
import io.github.acidefluorhydrique.mapconquer.world.MapLoader
import java.io.File

/**
 * 存檔。
 *
 * 自己寫一套行導向的文字格式，理由跟地圖檔一樣：不想為了存檔背一整包 JSON 相依，
 * 而且純文字的存檔在出問題時可以直接打開來看是哪一行壞了。
 *
 * 存檔不放 SharedPreferences：那是整檔重寫的，每回合塞進去一份幾十 KB 的戰局
 * 會在低階機上卡住主執行緒。這裡寫到 app 私有目錄的一個檔案。
 *
 * 復原的作法是「先照劇本開一局，再把存檔蓋上去」而不是從零反序列化 ——
 * 這樣地圖、省界、國家表這些唯讀資料只有一條建構路徑，
 * 存檔格式也就不必描述它們。
 */
object SaveGame {

    private const val FILE_NAME = "session.save"
    private const val VERSION = 1

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).let { it.exists() && it.length() > 0 }

    fun delete(context: Context) {
        runCatching { file(context).delete() }
    }

    // ------------------------------------------------------------------
    // 寫入
    // ------------------------------------------------------------------

    fun save(context: Context, session: Session): Boolean = runCatching {
        val sb = StringBuilder(8 * 1024)
        sb.append("v ").append(VERSION).append('\n')
        sb.append("scenario ").append(session.scenario.id).append('\n')
        sb.append("difficulty ").append(session.difficulty.name).append('\n')
        sb.append("player ").append(session.nations[session.playerNationId].code).append('\n')
        sb.append("turn ").append(session.turn).append('\n')
        sb.append("active ").append(session.activeNationId).append('\n')
        sb.append("status ").append(session.status.name).append('\n')
        sb.append("rng ").append(session.rng.state).append('\n')
        sb.append("diplomacy ").append(session.diplomacy.encode()).append('\n')

        sb.append("owners ")
        for (i in session.provinceOwner.indices) {
            if (i > 0) sb.append(',')
            sb.append(session.provinceOwner[i])
        }
        sb.append('\n')

        // 城防是局面狀態，跟省份歸屬一樣要存 —— 不然讀檔之後每座城都回滿血，
        // 存檔前打了三回合的攻城就白打了。
        sb.append("cityhp ")
        for (i in session.cityHp.indices) {
            if (i > 0) sb.append(',')
            sb.append(session.cityHp[i])
        }
        sb.append('\n')

        for (nation in session.nations) {
            sb.append("nation ")
                .append(nation.code).append(' ')
                .append(nation.funds).append(' ')
                .append(nation.tech.joinToString(",")).append(' ')
                .append(if (nation.eliminated) 1 else 0).append(' ')
                .append(nation.unitsKilled).append(' ')
                .append(nation.unitsLost).append(' ')
                .append(nation.provincesTaken).append('\n')
        }

        for (unit in session.units) {
            if (!unit.isAlive) continue
            sb.append("unit ")
                .append(unit.id).append(' ')
                .append(unit.kind.name).append(' ')
                .append(unit.nationId).append(' ')
                .append(unit.tile).append(' ')
                .append(unit.hp).append(' ')
                .append(unit.level).append(' ')
                .append(unit.exp).append(' ')
                .append(unit.supply).append(' ')
                .append(unit.movesLeft).append(' ')
                .append(if (unit.hasAttacked) 1 else 0).append(' ')
                .append(unit.entrenchment).append(' ')
                .append(unit.rumour).append(' ')
                .append(unit.transportId).append(' ')
                .append(unit.commanderId.ifEmpty { "-" }).append('\n')
        }

        file(context).writeText(sb.toString())
        true
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // 讀取
    // ------------------------------------------------------------------

    /** 只讀標頭，供主選單顯示「繼續遊戲」的摘要。 */
    class Summary(
        val scenarioId: String,
        val playerCode: String,
        val difficulty: Difficulty,
        val turn: Int
    )

    fun peek(context: Context): Summary? = runCatching {
        var scenarioId = ""
        var playerCode = ""
        var difficulty = Difficulty.OFFICER
        var turn = 1
        file(context).bufferedReader().use { reader ->
            for (line in reader.lineSequence()) {
                val sep = line.indexOf(' ')
                if (sep <= 0) continue
                when (line.substring(0, sep)) {
                    "scenario" -> scenarioId = line.substring(sep + 1).trim()
                    "player" -> playerCode = line.substring(sep + 1).trim()
                    "difficulty" -> difficulty = Difficulty.byName(line.substring(sep + 1).trim())
                    "turn" -> turn = line.substring(sep + 1).trim().toIntOrNull() ?: 1
                    "unit" -> return@use
                }
            }
        }
        if (scenarioId.isEmpty()) null else Summary(scenarioId, playerCode, difficulty, turn)
    }.getOrNull()

    fun load(context: Context): Session? = runCatching {
        val lines = file(context).readLines()
        if (lines.isEmpty()) return null

        var scenarioId = ""
        var playerCode = ""
        var difficulty = Difficulty.OFFICER
        var turn = 1
        var active = 0
        var status = SessionStatus.PLAYING
        var rngState = 1L
        var diplomacy = ""
        var owners = ""
        var cityHp = ""
        val nationLines = ArrayList<String>()
        val unitLines = ArrayList<String>()

        for (line in lines) {
            val sep = line.indexOf(' ')
            if (sep <= 0) continue
            val key = line.substring(0, sep)
            val value = line.substring(sep + 1).trim()
            when (key) {
                "scenario" -> scenarioId = value
                "player" -> playerCode = value
                "difficulty" -> difficulty = Difficulty.byName(value)
                "turn" -> turn = value.toIntOrNull() ?: 1
                "active" -> active = value.toIntOrNull() ?: 0
                "status" -> status = runCatching { SessionStatus.valueOf(value) }
                    .getOrDefault(SessionStatus.PLAYING)
                "rng" -> rngState = value.toLongOrNull() ?: 1L
                "diplomacy" -> diplomacy = value
                "owners" -> owners = value
                "cityhp" -> cityHp = value
                "nation" -> nationLines.add(value)
                "unit" -> unitLines.add(value)
            }
        }
        if (scenarioId.isEmpty()) return null

        val scenario = ScenarioLoader.load(context, scenarioId)
        val map = MapLoader.load(context, scenario.mapId)
        val session = Session(map, scenario, difficulty, playerCode, rngState)

        session.turn = turn
        session.activeNationId = active.coerceIn(0, session.nations.size - 1)
        session.status = status
        if (diplomacy.isNotEmpty()) session.diplomacy.decode(diplomacy)

        if (owners.isNotEmpty()) {
            val parts = owners.split(',')
            for (i in parts.indices) {
                if (i >= session.provinceOwner.size) break
                session.provinceOwner[i] = parts[i].trim().toIntOrNull() ?: -1
            }
        }

        // 舊存檔沒有這一行，那就維持劇本的初始城防。
        if (cityHp.isNotEmpty()) {
            val parts = cityHp.split(',')
            for (i in parts.indices) {
                if (i >= session.cityHp.size) break
                val max = session.map.provinces[i].maxCityHp
                session.cityHp[i] = (parts[i].trim().toIntOrNull() ?: max).coerceIn(0, max)
            }
        }

        for (line in nationLines) {
            val p = line.split(' ')
            if (p.size < 7) continue
            val nation = session.nationByCode(p[0]) ?: continue
            nation.funds = p[1].toIntOrNull() ?: nation.funds
            p[2].split(',').forEachIndexed { i, v ->
                if (i < nation.tech.size) nation.tech[i] = v.toIntOrNull() ?: 0
            }
            nation.eliminated = p[3] == "1"
            nation.unitsKilled = p[4].toIntOrNull() ?: 0
            nation.unitsLost = p[5].toIntOrNull() ?: 0
            nation.provincesTaken = p[6].toIntOrNull() ?: 0
        }

        session.clearAllUnits()
        for (line in unitLines) {
            val p = line.split(' ')
            if (p.size < 14) continue
            val kind = UnitKind.byName(p[1]) ?: continue
            val id = p[0].toIntOrNull() ?: continue
            val nationId = p[2].toIntOrNull() ?: continue
            val tile = p[3].toIntOrNull() ?: continue
            if (tile !in 0 until map.tileCount) continue
            val unit = ArmyUnit(id, kind, nationId, tile)
            unit.hp = p[4].toIntOrNull() ?: ArmyUnit.MAX_HP
            unit.level = (p[5].toIntOrNull() ?: 1).coerceIn(1, ArmyUnit.MAX_LEVEL)
            unit.exp = p[6].toIntOrNull() ?: 0
            unit.supply = p[7].toIntOrNull() ?: ArmyUnit.MAX_SUPPLY
            unit.movesLeft = p[8].toIntOrNull() ?: 0
            unit.hasAttacked = p[9] == "1"
            unit.entrenchment = p[10].toIntOrNull() ?: 0
            unit.rumour = (p[11].toIntOrNull() ?: 0).coerceIn(0, ArmyUnit.MAX_RUMOUR)
            unit.transportId = p[12].toIntOrNull() ?: -1
            unit.commanderId = if (p[13] == "-") "" else p[13]
            session.restoreUnit(unit)
        }
        session.rebuildCargoLinks()
        session.refreshSupplyView()
        session
    }.getOrNull()
}
