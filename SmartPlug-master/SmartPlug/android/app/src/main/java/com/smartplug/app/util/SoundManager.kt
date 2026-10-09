package com.smartplug.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.media.ToneGenerator
import android.media.AudioManager
import com.smartplug.app.data.local.TapSoundStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional tap sound (spec: "Gunakan SoundPool untuk suara tap opsional; pengguna dapat
 * mematikannya."). The `tap` raw resource is looked up by name rather than `R.raw.tap` so the
 * module still compiles/runs with the tap sound disabled if no `res/raw/tap.*` asset is bundled;
 * drop a short click/tap sample at `app/src/main/res/raw/tap.ogg` to enable it.
 */
@Singleton
class SoundManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                // Media usage follows the media volume. The sonification/system stream is often
                // set to 0 on phones, which made the tap silent even with the setting on.
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    private val soundIds = mutableMapOf<TapSoundStyle, Int>()
    private val loadedIds = java.util.Collections.synchronizedSet(mutableSetOf<Int>())
    private val fallbackTone = ToneGenerator(AudioManager.STREAM_MUSIC, 60)

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loadedIds += sampleId }
        TapSoundStyle.entries.forEach { style ->
            val raw = style.rawName ?: return@forEach
            runCatching {
                val resId = context.resources.getIdentifier(raw, "raw", context.packageName)
                if (resId != 0) soundIds[style] = soundPool.load(context, resId, 1)
            }
        }
    }

    private var lastPlayedMs = 0L

    // Pitch ratios of a major pentatonic run (about 0.89x to 1.5x): any two notes sound consonant.
    private val variedPitches = floatArrayOf(0.891f, 1.0f, 1.122f, 1.26f, 1.498f)
    private var lastPitchIndex = -1

    fun playTap(enabled: Boolean, style: TapSoundStyle = TapSoundStyle.VARIED) {
        if (!enabled) return
        // Several layers (global tap host + explicit confirm calls) can report the same tap.
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastPlayedMs < 90) return
        lastPlayedMs = now
        play(style)
    }

    /** Plays [style] immediately (used for the preview in Settings); no debounce. */
    fun preview(style: TapSoundStyle) = play(style)

    private fun play(style: TapSoundStyle) {
        val varied = style == TapSoundStyle.VARIED
        val id = soundIds[if (varied) TapSoundStyle.POP else style]
        if (id != null && id in loadedIds) {
            var rate = 1f
            if (varied) {
                // Never the same note twice in a row, and never a jump of more than a third, so
                // consecutive taps feel related rather than random.
                val candidates = variedPitches.indices.filter {
                    it != lastPitchIndex && (lastPitchIndex < 0 || kotlin.math.abs(it - lastPitchIndex) <= 2)
                }
                lastPitchIndex = candidates.random()
                rate = variedPitches[lastPitchIndex]
            }
            soundPool.play(id, 1f, 1f, 0, 0, rate)
        } else {
            fallbackTone.startTone(ToneGenerator.TONE_PROP_BEEP2, 20)
        }
    }

    fun release() {
        soundPool.release()
        fallbackTone.release()
    }
}
