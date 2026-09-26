package com.omnix.assistant.voice.wakeword

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Динамический порог по уровню шума (owner review 2026-09-26, п.2b): когда
 * фоновый SNR низкий (голос в толпе, транспорт), порог временно поднимается,
 * чтобы не ловить ложные срабатывания; в тишине и на чистом сигнале порог —
 * базовый, recall не страдает.
 *
 * Оценка шума почти бесплатна: RMS одного 80-мс чанка (один проход, без
 * аллокаций) доходит до мел-спектрограммы. Шумовой пол — 20-й перцентиль
 * окна из 25 чанков (2 с): не погоняется за громкой речью (громкие кадры
 * занимают верхние перцентили), но быстро опускается в тишине.
 *
 * Кривая буста: при SNR ≥ [refSnrDb] дБ буст 0; ниже — линейно до [maxBoost]
 * при SNR = 0 дБ (кадр на уровне шумового пола). Кадры ТИШЕ пола буста не
 * дают — детекции в них и так нет. Эффективный порог жмётся в
 * [0f..maxEffective].
 *
 * Чистый Kotlin, без Android — покрыт JVM-тестами.
 */
class NoiseAdaptiveThreshold(
    private val refSnrDb: Float = 10f,
    private val maxBoost: Float = 0.10f,
    private val maxEffective: Float = 0.60f,
    private val minNoiseFloorLsb: Float = 1f,
    private val frameWindow: Int = 25,
) {
    private val rmsWindow = ArrayDeque<Float>(frameWindow.coerceAtLeast(1))
    private var floorSeeded = false

    /**
     * Скорить RMS 80-мс чанка в LSB (0..32767). Один проход, Long-накопитель
     * (32767^2 ≈ 1e9 не влезает в Int-сумму).
     */
    fun rmsLsb(chunk: ShortArray): Float {
        if (chunk.isEmpty()) return 0f
        var sumSq = 0L
        for (s in chunk) sumSq += s.toLong() * s
        return sqrt(sumSq / chunk.size.toDouble()).toFloat()
    }

    /** Обновить шумовой пол новым кадром. Вызывать на КАЖДЫЙ чанк. */
    fun update(frameRmsLsb: Float) {
        rmsWindow.addLast(frameRmsLsb)
        while (rmsWindow.size > frameWindow) rmsWindow.removeFirst()
        floorSeeded = true
    }

    /** Текущий шумовой пол, LSB (20-й перцентиль окна; до заполнения — минимум). */
    val noiseFloorLsb: Float
        get() {
            if (!floorSeeded) return minNoiseFloorLsb
            val sorted = rmsWindow.sorted()
            val idx = (frameWindow * 0.2f).toInt().coerceIn(0, sorted.size - 1)
            return max(sorted[idx], minNoiseFloorLsb)
        }

    /** SNR кадра над шумовым полом, дБ; null — кадр не громче пола. */
    fun snrDb(frameRmsLsb: Float): Float? {
        val floor = noiseFloorLsb
        if (frameRmsLsb <= floor) return null
        // ln из kotlin.math принимает Double — переходим и возвращаемся в Float.
        return (20.0 * ln((frameRmsLsb / floor).toDouble()) / LN10).toFloat()
    }

    /**
     * Эффективный порог для кадра: базовый + буст по SNR кадра над полом.
     * [update] для этого кадра должен быть вызван ДО (пол учитывает кадр).
     */
    fun effectiveThreshold(base: Float, frameRmsLsb: Float): Float {
        val snr = snrDb(frameRmsLsb) ?: return base.coerceIn(0f, maxEffective)
        val boost = when {
            snr >= refSnrDb -> 0f
            snr <= 0f -> maxBoost
            else -> (refSnrDb - snr) * (maxBoost / refSnrDb)
        }
        return (base + boost).coerceIn(0f, maxEffective)
    }

    /** Сброс окна (перезапуск движка). */
    fun reset() {
        rmsWindow.clear()
        floorSeeded = false
    }

    private companion object {
        const val LN10 = 2.302585092994046
    }
}
