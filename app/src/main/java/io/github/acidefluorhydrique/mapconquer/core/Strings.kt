// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.core

import android.content.Context
import android.content.res.Resources
import java.util.concurrent.ConcurrentHashMap

/**
 * 整個畫面都畫在 Canvas 上，renderer 手上沒有 Context，
 * 所以用單例持有 Resources，讓繪製端可以直接查字串。
 *
 * 另外提供「以名稱查字串」：地圖、劇本、兵種表都是資料驅動的，
 * 它們手上只有 `prov_berlin` 這種鍵，拿不到 R 常數。
 *
 * 語系切換時 Activity 會重建 → GameView 重建 → 這裡重新 init 並清快取。
 */
object Strings {

    @Volatile
    private var resources: Resources? = null

    @Volatile
    private var packageName: String = ""

    /** 無參數字串每影格都會被查，快取起來省掉重複配置。 */
    private val cache = ConcurrentHashMap<Int, String>()

    /** 名稱 → 資源 id 的解析結果；找不到時記 0，避免每幀重跑反射式查表。 */
    private val idCache = ConcurrentHashMap<String, Int>()

    fun init(context: Context) {
        resources = context.resources
        packageName = context.packageName
        cache.clear()
        idCache.clear()
    }

    fun get(id: Int): String {
        if (id == 0) return ""
        cache[id]?.let { return it }
        val res = resources ?: return ""
        val value = runCatching { res.getString(id) }.getOrDefault("")
        if (value.isNotEmpty()) cache[id] = value
        return value
    }

    fun format(id: Int, vararg args: Any): String {
        if (id == 0) return ""
        val res = resources ?: return ""
        return runCatching { res.getString(id, *args) }.getOrDefault("")
    }

    /**
     * 資料檔裡的字串鍵。查不到時回傳鍵本身 —— 這樣新增地圖忘了補翻譯，
     * 畫面上會出現 `prov_xxx` 而不是一片空白，比較好抓。
     */
    fun byName(name: String): String {
        if (name.isEmpty()) return ""
        val id = idCache.getOrPut(name) {
            val res = resources ?: return name
            @Suppress("DiscouragedApi")
            runCatching { res.getIdentifier(name, "string", packageName) }.getOrDefault(0)
        }
        if (id == 0) return name
        val value = get(id)
        return value.ifEmpty { name }
    }
}
