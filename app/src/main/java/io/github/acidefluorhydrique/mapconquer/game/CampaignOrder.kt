// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

/**
 * 戰役的章節與路線。
 *
 * 每一章分成兩條陣營路線 —— 二戰是軸心與同盟，冷戰是東方與西方 —— 而同一場
 * 戰役常常兩邊都能打。解鎖沿著路線走：同一條路線的前一關有星，下一關才開；
 * 一章的第一關，要前一章至少有一條路線整條打完。
 *
 * 只要求一條、不要求兩條：逼玩家把兩邊都打一遍才看得到冷戰，是把重玩當成門票。
 *
 * 這裡刻意沒有任何 Android 相依，解鎖規則才能在 JVM 上直接測。
 */
object CampaignOrder {

    /** 章節的先後。沒列到的章節排在最後，而且永遠開著。 */
    val CHAPTERS = listOf("ww2_europe", "ww2_pacific", "cold_war")

    private fun chapterIndex(s: Scenario): Int =
        CHAPTERS.indexOf(s.chapter).let { if (it < 0) CHAPTERS.size else it }

    private fun routeKey(s: Scenario): String = s.chapter + "/" + s.route

    /**
     * 章節 → 路線 → 關卡順序。
     *
     * 路線之間的先後取該路線裡最小的 order，讓劇本定義決定誰排前面，
     * 而不是在程式裡再寫一份清單。
     */
    fun sorted(scenarios: List<Scenario>): List<Scenario> {
        val routeRank = HashMap<String, Int>()
        for (s in scenarios.sortedBy { it.order }) {
            if (routeKey(s) !in routeRank) routeRank[routeKey(s)] = routeRank.size
        }
        return scenarios.sortedWith(
            compareBy<Scenario>({ chapterIndex(it) }, { routeRank[routeKey(it)] ?: 0 }, { it.order })
        )
    }

    fun isUnlocked(scenarios: List<Scenario>, index: Int, starsOf: (String) -> Int): Boolean {
        val scenario = scenarios.getOrNull(index) ?: return false
        val route = scenarios
            .filter { it.chapter == scenario.chapter && it.route == scenario.route }
            .sortedBy { it.order }
        val position = route.indexOf(scenario)
        if (position > 0) return starsOf(route[position - 1].id) > 0

        val chapter = CHAPTERS.indexOf(scenario.chapter)
        if (chapter <= 0) return true
        val routes = scenarios.filter { it.chapter == CHAPTERS[chapter - 1] }.groupBy { it.route }
        if (routes.isEmpty()) return true
        return routes.values.any { missions -> missions.all { starsOf(it.id) > 0 } }
    }
}
