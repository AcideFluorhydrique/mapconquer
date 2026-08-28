// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import io.github.acidefluorhydrique.mapconquer.core.LocaleManager

/**
 * 唯一的 Activity。
 *
 * 用純 android.app.Activity 而不是 AppCompatActivity：這個畫面沒有任何
 * AppCompat 元件（沒有 ActionBar、沒有 Toolbar、沒有 View 樹），
 * 繼承它只會多背一層生命週期。語系切換因此走
 * [LocaleManager.localized] 的 configuration context，minSdk 26 全支援。
 */
class MainActivity : Activity() {

    private lateinit var gameView: GameView

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.localized(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        gameView = GameView(this)
        setContentView(gameView)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersiveMode()
    }

    override fun onResume() {
        super.onResume()
        gameView.onActivityResume()
    }

    override fun onPause() {
        gameView.onActivityPause()
        super.onPause()
    }

    override fun onDestroy() {
        gameView.onActivityDestroy()
        Audio.release()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (!gameView.onBackPressed()) {
            super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
