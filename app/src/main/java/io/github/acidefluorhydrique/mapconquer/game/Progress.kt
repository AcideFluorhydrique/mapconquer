// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

import android.content.Context
import io.github.acidefluorhydrique.mapconquer.core.Prefs
import io.github.acidefluorhydrique.mapconquer.units.Commander

/**
 * 跨局的長期進度：戰役星等、勳章、已招募的指揮官。
 *
 * 這些量很小（幾十個鍵值），用 SharedPreferences 剛好；
 * 大塊的戰局狀態則走 [SaveGame] 的檔案，兩者刻意分開。
 */
object Progress {

    /** 戰役關卡 → 最佳星等。 */
    private fun starsMap(context: Context): MutableMap<String, Int> {
        val raw = Prefs.getString(context, Prefs.KEY_CAMPAIGN_STARS, "")
        val map = LinkedHashMap<String, Int>()
        for (chunk in raw.split(';')) {
            if (chunk.isBlank()) continue
            val sep = chunk.indexOf(':')
            if (sep <= 0) continue
            val stars = chunk.substring(sep + 1).toIntOrNull() ?: continue
            map[chunk.substring(0, sep)] = stars
        }
        return map
    }

    private fun writeStars(context: Context, map: Map<String, Int>) {
        Prefs.putString(
            context,
            Prefs.KEY_CAMPAIGN_STARS,
            map.entries.joinToString(";") { "${it.key}:${it.value}" }
        )
    }

    fun starsFor(context: Context, scenarioId: String): Int = starsMap(context)[scenarioId] ?: 0

    fun totalStars(context: Context): Int = starsMap(context).values.sum()

    fun clearedCount(context: Context): Int = starsMap(context).count { it.value > 0 }

    /**
     * 記錄一次通關。只在打得比以前好的時候發勳章 ——
     * 否則重刷同一關就變成無限刷勳章。
     */
    fun recordCampaignResult(context: Context, scenarioId: String, stars: Int): Int {
        if (stars <= 0) return 0
        val map = starsMap(context)
        val previous = map[scenarioId] ?: 0
        if (stars <= previous) return 0
        map[scenarioId] = stars
        writeStars(context, map)
        val reward = (stars - previous) * MEDALS_PER_STAR
        addMedals(context, reward)
        return reward
    }

    fun medals(context: Context): Int = Prefs.getInt(context, Prefs.KEY_MEDALS, 0)

    fun addMedals(context: Context, amount: Int) {
        if (amount == 0) return
        Prefs.putInt(context, Prefs.KEY_MEDALS, (medals(context) + amount).coerceAtLeast(0))
    }

    fun unlockedCommanders(context: Context): MutableSet<String> {
        val raw = Prefs.getString(context, Prefs.KEY_UNLOCKED_COMMANDERS, "")
        val set = LinkedHashSet<String>()
        set.add(Commander.starter.id)
        for (id in raw.split(',')) if (id.isNotBlank()) set.add(id.trim())
        return set
    }

    fun isUnlocked(context: Context, commanderId: String): Boolean =
        unlockedCommanders(context).contains(commanderId)

    fun recruit(context: Context, commander: Commander): Boolean {
        if (isUnlocked(context, commander.id)) return false
        if (medals(context) < commander.medalCost) return false
        addMedals(context, -commander.medalCost)
        val set = unlockedCommanders(context)
        set.add(commander.id)
        Prefs.putString(context, Prefs.KEY_UNLOCKED_COMMANDERS, set.joinToString(","))
        return true
    }

    /** 戰役關卡的解鎖規則在 [CampaignOrder]；這裡只負責把星等讀出來。 */
    fun isScenarioUnlocked(context: Context, scenarios: List<Scenario>, index: Int): Boolean =
        CampaignOrder.isUnlocked(scenarios, index) { starsFor(context, it) }

    const val MEDALS_PER_STAR = 3
}
