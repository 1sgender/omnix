package com.omnix.assistant.voice.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Мягкие сигналы отклика на голос (просьба владельца: «приятный глухой звук»).
 * Вместо резкого писка ToneGenerator — короткая тёплая нота с плавным началом,
 * затухающим хвостом и срезом высоких частот. Звук синтезируется кодом, файлов нет.
 */
object SoftChime {
    const val SAMPLE_RATE = 22_050

    /** «Слушаю»: две ноты вверх, как лёгкое прикосновение. */
    fun wake(sampleRate: Int = SAMPLE_RATE): ShortArray =
        mix(sampleRate, 0.30, Note(392.0, 0.0, 0.16), Note(523.25, 0.09, 0.20))

    /** Отмена: одна низкая нота вниз. */
    fun cancel(sampleRate: Int = SAMPLE_RATE): ShortArray =
        mix(sampleRate, 0.24, Note(330.0, 0.0, 0.14), Note(247.0, 0.07, 0.17))

    private data class Note(val hz: Double, val startSec: Double, val lengthSec: Double)

    private fun mix(sampleRate: Int, tailSec: Double, vararg notes: Note): ShortArray {
        val total = (sampleRate * (notes.maxOf { it.startSec + it.lengthSec } + tailSec - 0.12)).toInt()
        val out = DoubleArray(total)
        for (note in notes) {
            val start = (note.startSec * sampleRate).toInt()
            val len = (note.lengthSec * sampleRate).toInt() + (sampleRate * 0.12).toInt()
            for (i in 0 until len) {
                val idx = start + i
                if (idx >= total) break
                val t = i.toDouble() / sampleRate
                val attack = (1.0 - exp(-t * 90.0))
                val decay = exp(-t * 14.0)
                // Основной тон и тихая вторая гармоника: тепло без звона.
                val tone = sin(2 * PI * note.hz * t) + 0.18 * sin(4 * PI * note.hz * t)
                out[idx] += tone * attack * decay
            }
        }
        // Однополюсный фильтр низких частот: «глухой» тембр.
        val alpha = 0.22
        var prev = 0.0
        val peak = 0.32
        return ShortArray(total) { i ->
            prev += alpha * (out[i] - prev)
            (prev.coerceIn(-1.0, 1.0) * peak * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Проигрывает готовый звук на канале системных сигналов, затем освобождает плеер. */
    fun play(samples: ShortArray, sampleRate: Int = SAMPLE_RATE) {
        try {
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size - 1)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) {
                    runCatching { t.release() }
                }

                override fun onPeriodicNotification(t: AudioTrack) {}
            })
            track.play()
        } catch (e: Exception) {
            Log.w("SoftChime", "play failed: ${e.javaClass.simpleName}")
        }
    }
}
