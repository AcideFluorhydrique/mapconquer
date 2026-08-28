// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 設定與進度的唯一入口。
 *
 * 存檔本身（大塊 JSON）走檔案，不放這裡：SharedPreferences 是整檔重寫，
 * 每回合塞一份幾十 KB 的戰局進去會在低階機上卡住主執行緒。
 */
object Prefs {

    private const val NAME = "mapconquer"

    const val KEY_LANGUAGE = "language_tag"
    const val KEY_SOUND = "sound_enabled"
    const val KEY_ANIMATIONS = "animations_enabled"
    const val KEY_GRID = "grid_visible"
    const val KEY_MEDALS = "medals"
    const val KEY_CAMPAIGN_STARS = "campaign_stars"
    const val KEY_UNLOCKED_COMMANDERS = "unlocked_commanders"
    const val KEY_LAST_SESSION = "last_session_label"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getString(context: Context, key: String, fallback: String = ""): String =
        prefs(context).getString(key, fallback) ?: fallback

    fun putString(context: Context, key: String, value: String) {
        prefs(context).edit().putString(key, value).apply()
    }

    fun getBool(context: Context, key: String, fallback: Boolean): Boolean =
        prefs(context).getBoolean(key, fallback)

    fun putBool(context: Context, key: String, value: Boolean) {
        prefs(context).edit().putBoolean(key, value).apply()
    }

    fun getInt(context: Context, key: String, fallback: Int): Int =
        prefs(context).getInt(key, fallback)

    fun putInt(context: Context, key: String, value: Int) {
        prefs(context).edit().putInt(key, value).apply()
    }
}
