package com.omnix.assistant.voice.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Чистая предобработка готовой фразы (PCM 16 бит, моно) перед распознаванием:
 * 1) фильтр высоких частот ~80 Гц: убирает постоянную составляющую и гул/стук корпуса;
 * 2) мягкое выравнивание громкости: тихую речь поднимает, слишком громкую слегка опускает;
 * 3) мягкий ограничитель пиков: после усиления звук не клиппирует.
 *
 * Только для собственных захватов (серверное и офлайн-распознавание). Детектор слова
 * вызова сюда не подключён и работает по прежней цепочке.
 */
object SpeechEnhancer {

    const val SAMPLE_RATE = 16_000
    const val HIGH_PASS_HZ = 80.0

    /** Целевой RMS речи: около -20 dBFS. */
    const val TARGET_RMS = 3_000.0
    const val MIN_GAIN = 0.5
    const val MAX_GAIN = 6.0

    /** Ниже этого RMS фраза считается тишиной/шумом и не усиливается. */
    const val SILENCE_RMS = 60.0

    private const val FRAME_MS = 20
    private const val PEAK = 32_767.0

    /** С этого уровня пики сжимаются плавно, а не обрезаются. */
    private const val KNEE = 0.85 * PEAK

    /** Полная обработка фразы; на пустом входе возвращает его же. */
    fun process(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ShortArray {
        if (samples.isEmpty()) return samples
        val filtered = highPass(samples, sampleRate)
        val gain = levelingGain(speechRms(filtered, sampleRate))
        return applyGainWithLimiter(filtered, gain)
    }

    /**
     * Однополюсный фильтр высоких частот: y[n] = a * (y[n-1] + x[n] - x[n-1]).
     * Начальное состояние берётся из первого сэмпла, чтобы постоянный сдвиг
     * не давал щелчка в начале фразы.
     */
    fun highPass(samples: ShortArray, sampleRate: Int = SAMPLE_RATE, cutoffHz: Double = HIGH_PASS_HZ): ShortArray {
        if (samples.isEmpty()) return samples
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sampleRate
        val a = rc / (rc + dt)
        val out = ShortArray(samples.size)
        var prevX = samples[0].toDouble()
        var prevY = 0.0
        for (i in samples.indices) {
            val x = samples[i].toDouble()
            val y = a * (prevY + x - prevX)
            prevX = x
            prevY = y
            out[i] = y.coerceIn(-PEAK, PEAK).toInt().toShort()
        }
        return out
    }

    /**
     * RMS только по «говорящим» кадрам по 20 мс: паузы внутри фразы не занижают оценку.
     * Кадр говорящий, если его RMS не ниже четверти среднего по всем кадрам выше порога
     * тишины. Если таких нет — RMS всей фразы.
     */
    fun speechRms(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): Double {
        if (samples.isEmpty()) return 0.0
        val frame = maxOf(1, sampleRate / 1000 * FRAME_MS)
        val levels = ArrayList<Double>()
        var start = 0
        while (start < samples.size) {
            val end = minOf(samples.size, start + frame)
            levels.add(rms(samples, start, end))
            start = end
        }
        val voiced = levels.filter { it >= SILENCE_RMS }
        if (voiced.isEmpty()) return rms(samples, 0, samples.size)
        val gate = voiced.average() * 0.25
        val speech = voiced.filter { it >= gate }
        return sqrt(speech.sumOf { it * it } / speech.size)
    }

    /** Множитель громкости к целевому уровню; тишина не усиливается. */
    fun levelingGain(speechRms: Double): Double {
        if (speechRms < SILENCE_RMS) return 1.0
        return (TARGET_RMS / speechRms).coerceIn(MIN_GAIN, MAX_GAIN)
    }

    /** Усиление и плавное ограничение пиков: результат всегда в пределах 16 бит. */
    fun applyGainWithLimiter(samples: ShortArray, gain: Double): ShortArray {
        val out = ShortArray(samples.size)
        val headroom = PEAK - KNEE
        for (i in samples.indices) {
            val v = samples[i] * gain
            val a = abs(v)
            val limited = if (a <= KNEE) a else KNEE + headroom * tanh((a - KNEE) / headroom)
            val signed = if (v < 0) -limited else limited
            out[i] = signed.coerceIn(-PEAK, PEAK).toInt().toShort()
        }
        return out
    }

    private fun rms(samples: ShortArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from))
    }
}
