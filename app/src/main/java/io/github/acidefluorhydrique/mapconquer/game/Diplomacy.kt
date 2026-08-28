// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.game

/** 兩國之間的狀態。 */
enum class Relation { WAR, PEACE, ALLIED }

/**
 * 外交關係表。
 *
 * 用一維陣列存對稱矩陣：關係永遠是雙向的，沒有「我覺得跟你和平但你覺得在打」這種狀態。
 * 單向宣戰在這裡表現為「宣戰方把雙方一起改成 WAR」，這是刻意的簡化 ——
 * 讓玩家永遠看得懂地圖上誰跟誰在打。
 */
class Diplomacy(private val nationCount: Int) {

    private val matrix = ByteArray(nationCount * nationCount) { Relation.PEACE.ordinal.toByte() }

    /** 停戰倒數：> 0 時不得再次宣戰，避免 AI 每回合開關戰爭。 */
    private val truce = IntArray(nationCount * nationCount)

    private fun idx(a: Int, b: Int) = a * nationCount + b

    fun relation(a: Int, b: Int): Relation {
        if (a == b) return Relation.ALLIED
        if (a !in 0 until nationCount || b !in 0 until nationCount) return Relation.PEACE
        return Relation.values()[matrix[idx(a, b)].toInt()]
    }

    fun set(a: Int, b: Int, relation: Relation) {
        if (a == b) return
        matrix[idx(a, b)] = relation.ordinal.toByte()
        matrix[idx(b, a)] = relation.ordinal.toByte()
    }

    fun isAtWar(a: Int, b: Int): Boolean = relation(a, b) == Relation.WAR

    fun isAllied(a: Int, b: Int): Boolean = a == b || relation(a, b) == Relation.ALLIED

    /** 敵對＝可以互相攻擊。同盟與和平都不行。 */
    fun isHostile(a: Int, b: Int): Boolean = a != b && relation(a, b) == Relation.WAR

    fun declareWar(a: Int, b: Int): Boolean {
        if (a == b || truceLeft(a, b) > 0) return false
        set(a, b, Relation.WAR)
        return true
    }

    /** 停火並附帶 [turns] 回合的冷卻。 */
    fun ceasefire(a: Int, b: Int, turns: Int) {
        set(a, b, Relation.PEACE)
        truce[idx(a, b)] = turns
        truce[idx(b, a)] = turns
    }

    fun truceLeft(a: Int, b: Int): Int =
        if (a == b || a !in 0 until nationCount || b !in 0 until nationCount) 0 else truce[idx(a, b)]

    fun tickTruces() {
        for (i in truce.indices) if (truce[i] > 0) truce[i]--
    }

    /** 存檔用的緊湊字串。 */
    fun encode(): String {
        val sb = StringBuilder(matrix.size)
        for (b in matrix) sb.append(('0' + b.toInt()))
        return sb.toString()
    }

    fun decode(text: String) {
        val n = minOf(text.length, matrix.size)
        for (i in 0 until n) {
            val v = text[i] - '0'
            if (v in 0 until Relation.values().size) matrix[i] = v.toByte()
        }
    }
}
