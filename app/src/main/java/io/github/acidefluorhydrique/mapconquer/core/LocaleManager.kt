// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * App 內語言切換。
 *
 * 不走 AppCompatDelegate.setApplicationLocales（那條路需要 AppCompatActivity，
 * 本專案用的是純 android.app.Activity），改用 createConfigurationContext 覆寫，
 * minSdk 26 全支援。
 */
object LocaleManager {

    /** 空字串代表跟隨系統。 */
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val CHINESE_TRADITIONAL = "zh-Hant"
    const val CHINESE_SIMPLIFIED = "zh-Hans"

    val options = listOf(SYSTEM, CHINESE_TRADITIONAL, CHINESE_SIMPLIFIED, ENGLISH)

    fun stored(context: Context): String = Prefs.getString(context, Prefs.KEY_LANGUAGE, SYSTEM)

    fun store(context: Context, tag: String) = Prefs.putString(context, Prefs.KEY_LANGUAGE, tag)

    /**
     * 各語言一律用自己的寫法顯示，
     * 這樣就算目前的介面語言看不懂，也找得到自己的語言。
     */
    fun displayName(tag: String): String = when (tag) {
        ENGLISH -> "English"
        CHINESE_TRADITIONAL -> "正體中文"
        CHINESE_SIMPLIFIED -> "简体中文"
        else -> Strings.byName("settings_language_system")
    }

    /** 回傳套用使用者選擇語言的 Context；選「跟隨系統」時原樣回傳。 */
    fun localized(context: Context): Context {
        val tag = stored(context)
        if (tag.isEmpty()) return context
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }
}
