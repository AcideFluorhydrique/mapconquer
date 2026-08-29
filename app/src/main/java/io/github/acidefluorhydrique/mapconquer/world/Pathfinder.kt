// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer.world

/**
 * 移動規則。尋路器本身只管圖論，「誰能走哪裡」全部由呼叫端注入。
 *
 * 這樣同一份 Dijkstra 可以同時服務：玩家的移動範圍、AI 的威脅評估、
 * 補給線的連通判定、以及 AI 找目標時的「假設沒有敵人」規劃。
 */
interface MoveRules {
    /** 從 [from] 走進 [to] 的成本；回傳 <= 0 代表不可通行。 */
    fun enterCost(from: Int, to: Int): Int

    /**
     * 走到 [to] 之後是否必須停下。
     *
     * 需要 [from] 是因為有些規則看的是**轉換**而不是目的地本身：
     * 陸軍入海與登陸各要耗掉一整個回合，而「這格是海」或「這格是陸」
     * 單獨都判斷不出來。
     */
    fun stopsAt(from: Int, to: Int): Boolean = false

    /** 這格能不能作為最終落點（有友軍佔著的格子可以路過但不能停）。 */
    fun canEndOn(tile: Int): Boolean = true
}

/**
 * 六角格上的移動範圍與路徑計算。
 *
 * 一個 Pathfinder 綁定一張地圖並持有所有工作陣列 —— 世界地圖有 5000 多格，
 * 而 AI 一回合可能要跑上百次搜尋，每次重新配置四個 IntArray 是不可接受的。
 * 用「訪問戳記」代替清空陣列，讓每次搜尋的固定成本降到 O(1)。
 *
 * 走 Dijkstra 而不是 A*：玩家要看到的是**整個**可達範圍（高亮那一圈），
 * 這種需求下 A* 的啟發式沒有意義；而算完範圍之後，路徑直接從 [cameFrom] 回溯，
 * 等於一次搜尋同時得到兩個答案。
 */
class Pathfinder(private val map: WorldMap) {

    private val n = map.tileCount
    private val cost = IntArray(n)
    private val cameFrom = IntArray(n)
    private val stamp = IntArray(n)
    private val settled = BooleanArray(n)
    private var currentStamp = 0

    private val neighbourBuf = IntArray(6)

    /** 二元堆積，元素打包成 (cost << 32) or tile，直接比大小就是先比成本。 */
    private var heap = LongArray(1024)
    private var heapSize = 0

    /** 上一次 [explore] 實際觸及的格子。 */
    val reached = ArrayList<Int>(256)

    var origin: Int = -1
        private set

    /**
     * 從 [start] 出發、預算 [budget] 移動點的可達範圍。
     * 結果留在內部，之後可用 [costTo] / [buildPath] 查詢。
     */
    fun explore(start: Int, budget: Int, rules: MoveRules) {
        currentStamp++
        heapSize = 0
        reached.clear()
        origin = start

        stamp[start] = currentStamp
        cost[start] = 0
        cameFrom[start] = -1
        settled[start] = false
        push(0, start)

        while (heapSize > 0) {
            val top = pop()
            val tile = (top and 0xFFFFFFFFL).toInt()
            val spent = (top ushr 32).toInt()
            // 堆積裡允許留下過期的條目（找到更短路徑時不做 decrease-key），
            // 這三行就是把它們濾掉：戳記不符 = 上一次搜尋的殘留，
            // 已 settled 或成本對不上 = 這一輪更好的路徑已經處理過了。
            if (stamp[tile] != currentStamp) continue
            if (settled[tile]) continue
            if (cost[tile] != spent) continue
            settled[tile] = true
            reached.add(tile)

            // 進了敵方控制區就停：不能再往前推，但這格本身是合法落點。
            if (tile != start && rules.stopsAt(cameFrom[tile], tile)) continue

            val count = map.neighbours(tile, neighbourBuf)
            for (i in 0 until count) {
                val next = neighbourBuf[i]
                val step = rules.enterCost(tile, next)
                if (step <= 0) continue
                val total = spent + step
                if (total > budget) continue
                if (stamp[next] == currentStamp && (settled[next] || cost[next] <= total)) continue
                stamp[next] = currentStamp
                cost[next] = total
                cameFrom[next] = tile
                settled[next] = false
                push(total, next)
            }
        }
    }

    /** 這次搜尋中到 [tile] 的花費；不可達回傳 -1。 */
    fun costTo(tile: Int): Int =
        if (stamp[tile] == currentStamp && settled[tile]) cost[tile] else -1

    fun isReachable(tile: Int): Boolean = costTo(tile) >= 0

    /**
     * 回溯出從起點到 [target] 的路徑（含起點與終點）。
     * 不可達時 [out] 會是空的。
     */
    fun buildPath(target: Int, out: MutableList<Int>) {
        out.clear()
        if (!isReachable(target)) return
        var cur = target
        while (cur != -1) {
            out.add(cur)
            if (cur == origin) break
            cur = cameFrom[cur]
        }
        out.reverse()
    }

    /**
     * 忽略移動點上限，找出到 [target] 的最短成本路徑，供 AI 規劃多回合行軍。
     * 回傳總成本，不可達時回傳 -1。
     */
    fun routeTo(start: Int, target: Int, rules: MoveRules, out: MutableList<Int>): Int {
        explore(start, Int.MAX_VALUE / 4, rules)
        val c = costTo(target)
        buildPath(target, out)
        return c
    }

    private fun push(priority: Int, tile: Int) {
        if (heapSize == heap.size) heap = heap.copyOf(heap.size * 2)
        var i = heapSize++
        val value = (priority.toLong() shl 32) or (tile.toLong() and 0xFFFFFFFFL)
        heap[i] = value
        while (i > 0) {
            val parent = (i - 1) shr 1
            if (heap[parent] <= heap[i]) break
            val tmp = heap[parent]; heap[parent] = heap[i]; heap[i] = tmp
            i = parent
        }
    }

    private fun pop(): Long {
        val top = heap[0]
        heapSize--
        if (heapSize > 0) {
            heap[0] = heap[heapSize]
            var i = 0
            while (true) {
                val l = i * 2 + 1
                val r = l + 1
                var best = i
                if (l < heapSize && heap[l] < heap[best]) best = l
                if (r < heapSize && heap[r] < heap[best]) best = r
                if (best == i) break
                val tmp = heap[best]; heap[best] = heap[i]; heap[i] = tmp
                i = best
            }
        }
        return top
    }
}
