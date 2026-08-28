// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer

import io.github.acidefluorhydrique.mapconquer.game.Scenario
import io.github.acidefluorhydrique.mapconquer.game.ScenarioLoader
import io.github.acidefluorhydrique.mapconquer.world.MapLoader
import io.github.acidefluorhydrique.mapconquer.world.WorldMap
import java.io.File

/**
 * 讓 JVM 測試讀得到 assets。
 *
 * 這是本專案能被有意義地測試的關鍵：模擬層（六角數學、尋路、地圖解析、
 * 戰鬥、回合引擎、AI）完全沒有碰 Android API，所以可以在一般的 JVM 上
 * 直接跑真實的地圖與劇本，而不是餵一堆假資料。
 *
 * Gradle 跑單元測試時的工作目錄是模組目錄，但被 IDE 或別的工具叫起來時不一定，
 * 所以這裡從幾個候選路徑往上找。
 */
object TestAssets {

    private val root: File by lazy {
        val candidates = listOf(
            File("src/main/assets"),
            File("app/src/main/assets"),
            File("../app/src/main/assets")
        )
        candidates.firstOrNull { it.isDirectory }
            ?: error("找不到 assets 目錄，工作目錄是 ${File(".").absolutePath}")
    }

    fun mapIds(): List<String> =
        File(root, "maps").listFiles()
            ?.filter { it.name.endsWith(".map") }
            ?.map { it.name.removeSuffix(".map") }
            ?.sorted()
            ?: emptyList()

    fun scenarioIds(): List<String> =
        File(root, "scenarios").listFiles()
            ?.filter { it.name.endsWith(".scn") }
            ?.map { it.name.removeSuffix(".scn") }
            ?.sorted()
            ?: emptyList()

    fun map(id: String): WorldMap =
        File(root, "maps/$id.map").bufferedReader().use { MapLoader.parse(it, id) }

    fun scenario(id: String): Scenario =
        File(root, "scenarios/$id.scn").bufferedReader().use { ScenarioLoader.parse(it, id) }

    private val mapCache = HashMap<String, WorldMap>()

    fun cachedMap(id: String): WorldMap = mapCache.getOrPut(id) { map(id) }
}
