// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.render

import android.graphics.RectF

/**
 * 可點區域的登記簿。
 *
 * 整個介面都是 Canvas 自繪，沒有 View 樹可以幫忙處理點擊。
 * 這裡的作法是：renderer 一邊畫一邊把自己畫出來的按鈕位置登記進來，
 * 輸入端再拿座標回查。
 *
 * 這樣「按鈕畫在哪」與「按鈕點得到哪」永遠是同一份計算 ——
 * 版面一改，兩邊自動同步，不會出現看得到卻按不到的按鈕。
 */
class ButtonLayer {

    private val ids = ArrayList<String>(48)
    private val rects = ArrayList<RectF>(48)
    private val payloads = ArrayList<Int>(48)
    private val enabled = ArrayList<Boolean>(48)
    private var size = 0

    fun clear() {
        size = 0
    }

    /**
     * 登記一個按鈕。[payload] 讓同一種按鈕帶不同的參數
     * （例如「生產第 3 種兵」或「選第 5 個國家」）。
     *
     * RectF 是複用的：登記時複製一份數值，避免呼叫端之後改到同一個物件。
     */
    fun add(id: String, rect: RectF, payload: Int = 0, isEnabled: Boolean = true) {
        if (size < ids.size) {
            ids[size] = id
            rects[size].set(rect)
            payloads[size] = payload
            enabled[size] = isEnabled
        } else {
            ids.add(id)
            rects.add(RectF(rect))
            payloads.add(payload)
            enabled.add(isEnabled)
        }
        size++
    }

    /** 由後往前找：後畫的（疊在上層的面板）先被點到。 */
    fun hit(x: Float, y: Float): Hit? {
        for (i in size - 1 downTo 0) {
            if (rects[i].contains(x, y)) {
                return Hit(ids[i], payloads[i], enabled[i])
            }
        }
        return null
    }

    /** 這個點有沒有落在任何已登記的區域上 —— 用來擋住穿透到地圖的點擊。 */
    fun consumes(x: Float, y: Float): Boolean = hit(x, y) != null

    fun rectOf(id: String): RectF? {
        for (i in 0 until size) if (ids[i] == id) return rects[i]
        return null
    }

    class Hit(val id: String, val payload: Int, val enabled: Boolean)
}
