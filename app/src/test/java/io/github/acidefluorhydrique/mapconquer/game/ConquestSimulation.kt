// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import io.github.acidefluorhydrique.mapconquer.TestAssets
import io.github.acidefluorhydrique.mapconquer.ai.AiPlayer
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 征服地圖的平衡模擬：每一個國家都交給 AI，連玩家那一國也是，看整局會打成什麼樣子。
 *
 * 這不是對錯的測試，是一份報告。它回答的是「這張地圖公不公平」：
 * 哪個陣營一面倒、哪些國家開局幾回合就滅亡、哪些國家一路滾雪球。
 * 地圖、劇本或 AI 改了之後再跑一次，就知道平衡往哪邊移了。
 *
 * 平常的 `./gradlew test` 會跳過它 —— 跑一整局要好幾分鐘。要跑的話：
 *
 *     ./gradlew testDebugUnitTest --tests '*ConquestSimulation*' \
 *         -Dmapconquer.simulate=true \
 *         -Dmapconquer.simulate.scenarios=conquest_1939,conquest_1950 \
 *         -Dmapconquer.simulate.turns=50 -Dmapconquer.simulate.seeds=2
 *
 * 或是在 GitHub Actions 手動觸發「Simulate conquest balance」（.github/workflows/simulate.yml）。
 * 報告寫在 app/build/reports/simulation/<劇本>.md。
 *
 * 難度用「軍官」：AI 與玩家的收入都是 100%，對誰都一樣，所以結果反映的是地圖本身。
 */
class ConquestSimulation {

    private fun property(key: String, default: String): String =
        System.getProperty("mapconquer.simulate.$key")?.takeIf { it.isNotBlank() } ?: default

    @Test
    fun `simulate every conquest with the AI playing all sides`() {
        assumeTrue("平衡模擬只在 -Dmapconquer.simulate=true 時跑", System.getProperty("mapconquer.simulate") == "true")

        val scenarios = property("scenarios", "conquest_1939,conquest_1943,conquest_1950,conquest_1980")
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val turns = property("turns", "50").toInt()
        val seeds = property("seeds", "2").toInt()
        val outDir = File("build/reports/simulation").apply { mkdirs() }

        for (id in scenarios) {
            val report = simulate(id, turns, seeds, File(outDir, "$id.md"))
            println(report)
        }
    }

    /** 某一刻的局勢：每個陣營手上有什麼。 */
    private class Snapshot(
        val turn: Int,
        val provinces: Map<String, Int>,
        val cities: Map<String, Int>,
        val units: Map<String, Int>,
        val alive: Map<String, Int>
    )

    private class Run(
        val seed: Long,
        val snapshots: List<Snapshot>,
        /** 國家代碼 → 滅亡的回合。 */
        val eliminated: Map<String, Int>,
        val startProvinces: Map<String, Int>,
        val endProvinces: Map<String, Int>,
        val millisPerTurn: Long
    )

    private fun blocOf(nation: Nation): String = nation.bloc.ifEmpty { NEUTRAL }

    /** [turn] 是這份快照代表「打完第幾回合」；開局是 0。 */
    private fun snapshot(session: Session, turn: Int): Snapshot {
        val provinces = HashMap<String, Int>()
        val cities = HashMap<String, Int>()
        val units = HashMap<String, Int>()
        val alive = HashMap<String, Int>()
        for (nation in session.nations) {
            val bloc = blocOf(nation)
            provinces.merge(bloc, session.provincesOf(nation.id), Int::plus)
            cities.merge(bloc, session.map.provinces.count { it.hasCity && session.provinceOwner[it.id] == nation.id }, Int::plus)
            units.merge(bloc, session.unitsOf(nation.id).size, Int::plus)
            alive.merge(bloc, if (nation.eliminated) 0 else 1, Int::plus)
        }
        return Snapshot(turn, provinces, cities, units, alive)
    }

    /**
     * 跑完每一局就先把報告寫出去：一局很長，萬一在 CI 上超時，已經跑完的那幾局不會白費。
     */
    private fun simulate(id: String, turns: Int, seeds: Int, out: File): String {
        val scenario = TestAssets.scenario(id)
        val map = TestAssets.cachedMap(scenario.mapId)
        val checkpoints = (listOf(1, 5, 10) + (25..turns step 25) + listOf(turns)).filter { it <= turns }.toSortedSet()

        val runs = ArrayList<Run>()
        for (seed in 1..seeds) {
            // 玩家那一國也交給 AI；選誰都一樣，選第一個可選的。
            val session = Session(map, scenario, Difficulty.OFFICER, scenario.playable.first(), seed * 7919L)
            val start = session.nations.associate { it.code to session.provincesOf(it.id) }
            val eliminated = LinkedHashMap<String, Int>()
            val snapshots = ArrayList<Snapshot>()
            snapshots.add(snapshot(session, 0))
            val began = System.currentTimeMillis()
            while (session.turn <= turns) {
                val turn = session.turn
                var guard = 0
                while (session.turn == turn && guard <= session.nations.size + 2) {
                    val ai = AiPlayer(session, session.activeNationId)
                    var steps = 0
                    while (ai.step() && steps < STEP_LIMIT) steps++
                    session.advanceToNextNation()
                    guard++
                }
                for (nation in session.nations) {
                    if (nation.eliminated && nation.code !in eliminated) eliminated[nation.code] = turn
                }
                if (turn in checkpoints) {
                    snapshots.add(snapshot(session, turn))
                    // 進度印在 CI 的 log 上：跑得慢的時候看得出是卡住了還是只是慢。
                    println(
                        "[$id seed $seed] turn $turn, ${(System.currentTimeMillis() - began) / 1000}s, " +
                            "${session.units.count { it.isAlive }} units, " +
                            "${session.nations.count { !it.eliminated }} nations left"
                    )
                    System.out.flush()
                }
            }
            val elapsed = System.currentTimeMillis() - began
            runs.add(
                Run(
                    seed.toLong(), snapshots, eliminated, start,
                    session.nations.associate { it.code to session.provincesOf(it.id) },
                    elapsed / turns.coerceAtLeast(1)
                )
            )
            out.writeText(render(id, scenario, map.provinces.size, turns, runs))
        }
        return render(id, scenario, map.provinces.size, turns, runs)
    }

    private fun render(id: String, scenario: Scenario, totalProvinces: Int, turns: Int, runs: List<Run>): String {
        val blocs = (scenario.nations.map { it.bloc.ifEmpty { NEUTRAL } }).distinct().sortedBy { if (it == NEUTRAL) "~" else it }
        val sb = StringBuilder()
        sb.append("# $id — every side played by the AI\n\n")
        sb.append("$turns turns · ${runs.size} seed(s) · difficulty OFFICER · $totalProvinces provinces\n\n")

        sb.append("## Share of provinces at the end\n\n")
        sb.append("| Bloc | Start | ").append(runs.joinToString(" | ") { "Seed ${it.seed}" }).append(" |\n")
        sb.append("|---|---|").append(runs.joinToString("") { "---|" }).append("\n")
        for (bloc in blocs) {
            val start = runs.first().snapshots.first().provinces[bloc] ?: 0
            sb.append("| $bloc | ${percent(start, totalProvinces)} | ")
            sb.append(runs.joinToString(" | ") { percent(it.snapshots.last().provinces[bloc] ?: 0, totalProvinces) })
            sb.append(" |\n")
        }
        sb.append("\n")

        for (run in runs) {
            sb.append("## Seed ${run.seed}\n\n")
            sb.append("About ${run.millisPerTurn} ms per turn.\n\n")
            sb.append("Turn 0 is the opening position; each row after it is the map after that turn.\n\n")
            sb.append("| Turn | ").append(blocs.joinToString(" | ") { "$it provinces / cities / units / nations" }).append(" |\n")
            sb.append("|---|").append(blocs.joinToString("") { "---|" }).append("\n")
            for (snap in run.snapshots) {
                sb.append("| ${snap.turn} | ")
                sb.append(blocs.joinToString(" | ") {
                    "${snap.provinces[it] ?: 0} / ${snap.cities[it] ?: 0} / ${snap.units[it] ?: 0} / ${snap.alive[it] ?: 0}"
                })
                sb.append(" |\n")
            }
            sb.append("\n")

            if (run.eliminated.isEmpty()) {
                sb.append("No nation surrendered.\n\n")
            } else {
                sb.append("Surrendered: ")
                sb.append(run.eliminated.entries.joinToString(", ") { "${it.key} (turn ${it.value})" })
                sb.append("\n\n")
            }

            val change = run.endProvinces.mapValues { (code, end) -> end - (run.startProvinces[code] ?: 0) }
            val gainers = change.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(MOVERS)
            val losers = change.entries.filter { it.value < 0 }.sortedBy { it.value }.take(MOVERS)
            sb.append("Biggest gains: ")
            sb.append(gainers.joinToString(", ") { "${it.key} +${it.value}" }.ifEmpty { "none" })
            sb.append("  \nBiggest losses: ")
            sb.append(losers.joinToString(", ") { "${it.key} ${it.value}" }.ifEmpty { "none" })
            sb.append("\n\n")
        }
        return sb.toString()
    }

    private fun percent(part: Int, whole: Int): String =
        if (whole <= 0) "–" else "${part * 100 / whole}% ($part)"

    private companion object {
        const val NEUTRAL = "neutral"
        const val STEP_LIMIT = 20_000
        const val MOVERS = 6
    }
}
