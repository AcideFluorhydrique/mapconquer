// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

import android.content.Context
import java.io.BufferedReader

/**
 * 地圖檔（assets/maps/*.map）的解析器。
 *
 * 格式刻意做成純文字而不是二進位或 JSON：
 *  - 地形那一段直接就是一張 ASCII 圖，在 diff 裡看得出改了哪一塊；
 *  - 沒有 JSON 解析器要相依，也不必為了幾千個整數扛一整包函式庫；
 *  - `tools/genworld.py` 產出的東西人眼可讀，出問題時肉眼就能對。
 *
 * 檔案結構：
 * ```
 *   format 1
 *   id world
 *   cols 96
 *   rows 60
 *
 *   [terrain]
 *   ~~~~....^^^...          <- 每列 cols 個字元，共 rows 列
 *
 *   [provinces]
 *   96:-1                   <- 每列一行，RLE：「連續幾格:省份 id」，-1 = 無主水域
 *   12:-1 6:0 78:-1
 *
 *   [meta]
 *   0|prov_london|3|18,12   <- id|名稱鍵|城市等級|省會欄,列
 * ```
 */
object MapLoader {

    private const val SECTION_TERRAIN = "[terrain]"
    private const val SECTION_PROVINCES = "[provinces]"
    private const val SECTION_META = "[meta]"

    class MapFormatException(message: String) : RuntimeException(message)

    fun load(context: Context, mapId: String): WorldMap =
        context.assets.open("maps/$mapId.map").bufferedReader().use { parse(it, mapId) }

    fun parse(reader: BufferedReader, fallbackId: String): WorldMap {
        var id = fallbackId
        var cols = 0
        var rows = 0
        var section = ""
        val terrainRows = ArrayList<String>()
        val provinceRows = ArrayList<String>()
        val metaRows = ArrayList<String>()

        reader.forEachLine { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Unit
                line.startsWith("#") -> Unit
                line.startsWith("[") -> section = line.trim().lowercase()
                section == SECTION_TERRAIN -> terrainRows.add(line)
                section == SECTION_PROVINCES -> provinceRows.add(line.trim())
                section == SECTION_META -> metaRows.add(line.trim())
                else -> {
                    // 標頭區：`key value`
                    val sep = line.indexOf(' ')
                    if (sep > 0) {
                        val key = line.substring(0, sep).trim()
                        val value = line.substring(sep + 1).trim()
                        when (key) {
                            "id" -> id = value
                            "cols" -> cols = value.toIntOrNull() ?: 0
                            "rows" -> rows = value.toIntOrNull() ?: 0
                        }
                    }
                }
            }
        }

        if (cols <= 0 || rows <= 0) throw MapFormatException("map '$id': cols/rows missing")
        if (terrainRows.size != rows) {
            throw MapFormatException("map '$id': expected $rows terrain rows, got ${terrainRows.size}")
        }

        val count = cols * rows
        val terrain = ByteArray(count)
        for (row in 0 until rows) {
            val text = terrainRows[row]
            if (text.length < cols) {
                throw MapFormatException("map '$id': terrain row $row has ${text.length} chars, need $cols")
            }
            val base = row * cols
            for (col in 0 until cols) {
                terrain[base + col] = Terrain.ofCode(text[col]).ordinal.toByte()
            }
        }

        val provinceOf = IntArray(count) { -1 }
        for (row in provinceRows.indices) {
            if (row >= rows) break
            decodeRunLength(provinceRows[row], provinceOf, row * cols, cols, id, row)
        }

        val provinces = buildProvinces(metaRows, provinceOf, cols, rows, terrain, id)
        return WorldMap(id, cols, rows, terrain, provinceOf, provinces)
    }

    /** `12:-1 6:0 78:-1` → 依序把 12 格填 -1、6 格填 0、78 格填 -1。 */
    private fun decodeRunLength(
        line: String,
        target: IntArray,
        base: Int,
        cols: Int,
        mapId: String,
        row: Int
    ) {
        var written = 0
        var i = 0
        while (i < line.length) {
            while (i < line.length && line[i] == ' ') i++
            if (i >= line.length) break
            val end = line.indexOf(' ', i).let { if (it < 0) line.length else it }
            val token = line.substring(i, end)
            i = end
            val colon = token.indexOf(':')
            if (colon <= 0) continue
            val runLength = token.substring(0, colon).toIntOrNull() ?: continue
            val value = token.substring(colon + 1).toIntOrNull() ?: continue
            var k = 0
            while (k < runLength && written < cols) {
                target[base + written] = value
                written++
                k++
            }
        }
        if (written != cols) {
            throw MapFormatException("map '$mapId': province row $row covers $written of $cols tiles")
        }
    }

    /**
     * 由 [meta] 段與逐格省份編號組出 [Province] 表。
     *
     * 鄰接關係在這裡算完就固定下來：AI 每回合都要問「這省跟誰接壤」，
     * 現場掃描整張地圖是純浪費，而地形在一局裡不會變。
     */
    private fun buildProvinces(
        metaRows: List<String>,
        provinceOf: IntArray,
        cols: Int,
        rows: Int,
        terrain: ByteArray,
        mapId: String
    ): List<Province> {
        if (metaRows.isEmpty()) return emptyList()

        val nameKeys = HashMap<Int, String>()
        val tiers = HashMap<Int, Int>()
        val capitals = HashMap<Int, Int>()
        var maxId = -1

        for (line in metaRows) {
            val parts = line.split('|')
            if (parts.size < 4) continue
            val pid = parts[0].trim().toIntOrNull() ?: continue
            nameKeys[pid] = parts[1].trim()
            tiers[pid] = parts[2].trim().toIntOrNull() ?: 0
            val coords = parts[3].split(',')
            if (coords.size == 2) {
                val c = coords[0].trim().toIntOrNull() ?: 0
                val r = coords[1].trim().toIntOrNull() ?: 0
                capitals[pid] = r * cols + c
            }
            if (pid > maxId) maxId = pid
        }
        if (maxId < 0) return emptyList()

        val tileLists = Array(maxId + 1) { ArrayList<Int>() }
        for (i in provinceOf.indices) {
            val pid = provinceOf[i]
            if (pid in 0..maxId) tileLists[pid].add(i)
        }

        val neighbourSets = Array(maxId + 1) { HashSet<Int>() }
        val coastal = BooleanArray(maxId + 1)
        val buf = IntArray(6)
        val scratchMap = WorldMap(mapId, cols, rows, terrain, provinceOf, emptyList())
        for (i in provinceOf.indices) {
            val pid = provinceOf[i]
            if (pid < 0 || pid > maxId) continue
            val n = scratchMap.neighbours(i, buf)
            for (k in 0 until n) {
                val other = provinceOf[buf[k]]
                if (other == pid) continue
                if (other in 0..maxId) neighbourSets[pid].add(other)
                if (other < 0 && Terrain.ALL[terrain[buf[k]].toInt()].isWater) coastal[pid] = true
            }
        }

        return (0..maxId).map { pid ->
            val tiles = tileLists[pid]
            val capital = capitals[pid]
                ?: tiles.firstOrNull()
                ?: 0
            Province(
                id = pid,
                nameKey = nameKeys[pid] ?: "prov_unknown",
                cityTier = tiers[pid] ?: 0,
                capitalTile = capital,
                tiles = tiles.toIntArray(),
                neighbours = neighbourSets[pid].toIntArray().sortedArray(),
                coastal = coastal[pid]
            )
        }
    }

    /** 列出 assets/maps 底下所有地圖 id。 */
    fun availableMaps(context: Context): List<String> =
        (context.assets.list("maps") ?: emptyArray())
            .filter { it.endsWith(".map") }
            .map { it.removeSuffix(".map") }
            .sorted()
}
