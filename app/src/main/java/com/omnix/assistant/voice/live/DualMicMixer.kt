package com.omnix.assistant.voice.live

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Сводит два микрофона синхронного перевода — телефона и наушника — в один поток для
 * распознавания и отсекает то, что переводить нельзя.
 *
 * Голос владельца и перевод, звучащий у него в ухе, микрофон наушника слышит намного
 * громче, чем микрофон телефона (рот и динамик в сантиметрах от него). Собеседник в метре
 * звучит в обоих примерно одинаково. Поэтому кадр, где наушник громче телефона больше чем
 * на [ownerGateDb] (во время перевода — на [echoGateDb]), заменяется тишиной: распознаватель
 * не услышит ни владельца, ни перевод. После такого кадра тишина держится [hangoverFrames]
 * кадров, чтобы не пропускать хвосты слов.
 *
 * Остальные кадры смешиваются с весом громкости: если телефон в кармане и слышит хуже,
 * больше берётся от наушника, и наоборот. Вес сглаживается, чтобы не было щелчков.
 */
class DualMicMixer(
    private val ownerGateDb: Float = DEFAULT_OWNER_GATE_DB,
    private val echoGateDb: Float = DEFAULT_ECHO_GATE_DB,
    private val minLevelDb: Float = DEFAULT_MIN_LEVEL_DB,
    private val hangoverFrames: Int = DEFAULT_HANGOVER_FRAMES
) {
    /** Сейчас звучит перевод в ухо: порог отсечения ниже. */
    @Volatile
    var ttsActive: Boolean = false

    private var earbudWeight = 0.5f
    private var hangover = 0

    /** Сколько кадров отсечено (для логов и проверки). */
    var gatedFrames: Long = 0
        private set

    /** Кадры одинаковой длины; возвращает новый кадр. */
    fun mix(phone: ShortArray, earbud: ShortArray): ShortArray {
        val n = minOf(phone.size, earbud.size)
        val phoneDb = levelDb(phone, n)
        val earbudDb = levelDb(earbud, n)
        val gate = if (ttsActive) echoGateDb else ownerGateDb
        val nearEarbud = earbudDb > minLevelDb && earbudDb - phoneDb > gate
        if (nearEarbud) hangover = hangoverFrames
        if (nearEarbud || hangover > 0) {
            if (!nearEarbud) hangover--
            gatedFrames++
            return ShortArray(n)
        }

        val phoneRms = rms(phone, n)
        val earbudRms = rms(earbud, n)
        val total = phoneRms + earbudRms
        val target = if (total > 0.0) (earbudRms / total).toFloat() else 0.5f
        earbudWeight += (target - earbudWeight) * WEIGHT_SMOOTHING
        val w = earbudWeight
        return ShortArray(n) { i ->
            val v = phone[i] * (1f - w) + earbud[i] * w
            v.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
    }

    companion object {
        const val DEFAULT_OWNER_GATE_DB = 12f
        const val DEFAULT_ECHO_GATE_DB = 6f
        const val DEFAULT_MIN_LEVEL_DB = -50f
        const val DEFAULT_HANGOVER_FRAMES = 5
        private const val WEIGHT_SMOOTHING = 0.2f
        private const val FULL_SCALE = 32768.0
        private const val SILENCE_DB = -120f

        private fun rms(frame: ShortArray, n: Int): Double {
            if (n == 0) return 0.0
            var sum = 0.0
            for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
            return sqrt(sum / n)
        }

        fun levelDb(frame: ShortArray, n: Int = frame.size): Float {
            val r = rms(frame, n)
            return if (r <= 0.0) SILENCE_DB else (20.0 * log10(r / FULL_SCALE)).toFloat()
        }
    }
}
