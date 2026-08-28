// SPDX-FileCopyrightText: 2026 AcideFluorhydrique
// SPDX-License-Identifier: GPL-3.0-or-later

package io.github.acidefluorhydrique.mapconquer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import io.github.acidefluorhydrique.mapconquer.core.Rng
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** 音效種類。 */
enum class Sfx { CLICK, DENIED, MOVE, ATTACK, BUILD, TURN, VICTORY, DEFEAT }

/**
 * 音效。
 *
 * 波形是即時合成的，APK 裡沒有任何音訊檔。這不只是為了體積 ——
 * 對一個要上 F-Droid 的專案來說，「所有素材都是原始碼」是最省事的狀態：
 * 沒有二進位資產就沒有來源說明、沒有授權相容性、也沒有可重現建置的疑慮。
 *
 * 每種音效各自持有一條 AudioTrack（STATIC 模式，資料只寫一次），
 * 播放時重播同一份緩衝區。所有呼叫都包在 runCatching 裡：
 * 音訊裝置在某些機器上會拒絕配置，而遊戲不該因為沒有聲音就掛掉。
 */
object Audio {

    private const val SAMPLE_RATE = 22050

    @Volatile
    private var enabled = true

    private val tracks = HashMap<Sfx, AudioTrack?>(Sfx.values().size)

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    fun play(sfx: Sfx) {
        if (!enabled) return
        runCatching {
            val track = tracks.getOrPut(sfx) { build(sfx) } ?: return
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
            track.reloadStaticData()
            track.play()
        }
    }

    fun release() {
        runCatching {
            for (track in tracks.values) track?.release()
            tracks.clear()
        }
    }

    private fun build(sfx: Sfx): AudioTrack? = runCatching {
        val samples = synth(sfx)
        val bytes = samples.size * 2
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(samples, 0, samples.size)
        track.setVolume(AudioTrack.getMaxVolume() * 0.45f)
        track
    }.getOrNull()

    // ------------------------------------------------------------------
    // 合成
    // ------------------------------------------------------------------

    private fun synth(sfx: Sfx): ShortArray = when (sfx) {
        Sfx.CLICK -> tone(0.045f, 880f, 880f, decay = 40f, square = true, amplitude = 0.35f)
        Sfx.DENIED -> tone(0.11f, 190f, 150f, decay = 12f, square = true, amplitude = 0.45f)
        Sfx.MOVE -> tone(0.07f, 460f, 620f, decay = 22f, square = false, amplitude = 0.35f)
        Sfx.BUILD -> sequence(floatArrayOf(660f, 880f), 0.075f, square = false)
        Sfx.TURN -> sequence(floatArrayOf(440f, 587f), 0.09f, square = false)
        Sfx.VICTORY -> sequence(floatArrayOf(523f, 659f, 784f, 1046f), 0.11f, square = false)
        Sfx.DEFEAT -> sequence(floatArrayOf(392f, 330f, 262f), 0.14f, square = false)
        Sfx.ATTACK -> attack()
    }

    /** 單音：頻率可以滑動，包絡是指數衰減。 */
    private fun tone(
        seconds: Float,
        startHz: Float,
        endHz: Float,
        decay: Float,
        square: Boolean,
        amplitude: Float
    ): ShortArray {
        val count = (SAMPLE_RATE * seconds).toInt().coerceAtLeast(1)
        val out = ShortArray(count)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i / count.toFloat()
            val hz = startHz + (endHz - startHz) * t
            phase += 2.0 * PI * hz / SAMPLE_RATE
            val raw = if (square) (if (sin(phase) >= 0) 1.0 else -1.0) else sin(phase)
            val envelope = exp(-decay * t.toDouble())
            out[i] = (raw * envelope * amplitude * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    /** 依序播放幾個音，用來做「完成」與「勝負」這類短旋律。 */
    private fun sequence(notes: FloatArray, secondsEach: Float, square: Boolean): ShortArray {
        val chunks = notes.map { tone(secondsEach, it, it, decay = 6f, square = square, amplitude = 0.32f) }
        val total = chunks.sumOf { it.size }
        val out = ShortArray(total)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(out, offset)
            offset += chunk.size
        }
        return out
    }

    /** 交戰音：低頻衝擊加上一段確定性的雜訊，聽起來比純音有重量。 */
    private fun attack(): ShortArray {
        val count = (SAMPLE_RATE * 0.16f).toInt()
        val out = ShortArray(count)
        val rng = Rng(0x5EED_1234L)
        var phase = 0.0
        for (i in 0 until count) {
            val t = i / count.toFloat()
            phase += 2.0 * PI * (150f - 60f * t) / SAMPLE_RATE
            val body = sin(phase)
            val noise = (rng.nextFloat() * 2f - 1f).toDouble()
            val envelope = exp(-9.0 * t)
            val mixed = body * 0.6 + noise * 0.4 * exp(-24.0 * t)
            out[i] = (mixed * envelope * 0.5 * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    @Suppress("unused")
    private val legacyStreamHint = AudioManager.STREAM_MUSIC
}
