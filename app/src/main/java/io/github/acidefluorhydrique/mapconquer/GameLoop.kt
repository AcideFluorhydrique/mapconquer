// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer

import android.graphics.Canvas
import android.view.SurfaceHolder

/**
 * 繪製執行緒。
 *
 * 這是一款回合制遊戲，畫面大部分時間是靜止的 —— 但仍然跑固定影格迴圈，
 * 因為 AI 的分段執行、部隊移動的補間、以及捲動的慣性都需要穩定的時間基準。
 * 沒事做的時候每幀的工作量很小，實測待機時的 CPU 佔用可以忽略。
 */
class GameLoop(
    private val surfaceHolder: SurfaceHolder,
    private val view: GameView
) : Thread("mapconquer-loop") {

    @Volatile
    var running = false

    override fun run() {
        var previous = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val deltaMs = ((now - previous) / 1_000_000L).coerceAtMost(MAX_DELTA_MS)
            previous = now

            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) {
                    // 觸控事件也在這把鎖裡處理，因此 update 與 draw 看到的一定是同一份狀態。
                    synchronized(surfaceHolder) {
                        view.update(deltaMs.toInt())
                        view.render(canvas)
                    }
                }
            } catch (e: IllegalStateException) {
                // Surface 正在被回收，下一輪重試。
            } finally {
                if (canvas != null) {
                    try {
                        surfaceHolder.unlockCanvasAndPost(canvas)
                    } catch (e: IllegalStateException) {
                        // 忽略：surface 已失效。
                    }
                }
            }

            val elapsedMs = (System.nanoTime() - now) / 1_000_000L
            val sleepMs = FRAME_MS - elapsedMs
            if (sleepMs > 0) {
                try {
                    sleep(sleepMs)
                } catch (e: InterruptedException) {
                    currentThread().interrupt()
                    return
                }
            }
        }
    }

    companion object {
        private const val TARGET_FPS = 60
        private const val FRAME_MS = 1000L / TARGET_FPS

        /** 從背景回到前景時的時間跳躍要夾住，否則動畫會瞬間播完。 */
        private const val MAX_DELTA_MS = 64L
    }
}
