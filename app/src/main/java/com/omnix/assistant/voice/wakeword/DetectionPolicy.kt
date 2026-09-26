package com.omnix.assistant.voice.wakeword

/**
 * Временная политика детекции (§9 ТЗ). Чистый Kotlin, без Android — покрыт JVM-тестами.
 *
 * Правила (в порядке проверки):
 * 1. threshold: скор кадра >= порога считается хитом, иначе серия сбрасывается;
 * 2. patience: срабатывание только после [patienceFrames] хитов подряд
 *    (семантика openWakeWord 'patience'; давит одиночные всплески);
 * 3. cooldown: после срабатывания тишина [cooldownMs] (дубликаты одной фразы).
 *
 * Все параметры конфигурируемы через [WakeWordConfig]; magic numbers запрещены.
 */
class DetectionPolicy(
    threshold: Float = 0.5f,
    patienceFrames: Int = 2,
    cooldownMs: Long = 2000L,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    var threshold: Float = threshold.coerceIn(0f, 1f)
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    var patienceFrames: Int = patienceFrames.coerceAtLeast(1)
        set(value) {
            field = value.coerceAtLeast(1)
        }

    var cooldownMs: Long = cooldownMs.coerceAtLeast(0L)
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    private var consecutiveHits = 0
    private var lastFireMs: Long? = null

    /**
     * Скор очередного 80-мс фрейма.
     * @param thresholdOverride действующий порог кадра — например, базовый
     *        порог + динамический буст по шуму (NoiseAdaptiveThreshold).
     *        По умолчанию — настройка [threshold].
     * @return true ровно в момент подтверждённой детекции.
     */
    fun observe(score: Float, thresholdOverride: Float = threshold): Boolean {
        if (score >= thresholdOverride) {
            consecutiveHits++
        } else {
            consecutiveHits = 0
            return false
        }
        if (consecutiveHits < patienceFrames) return false
        val now = clockMs()
        val last = lastFireMs
        if (last != null && now - last < cooldownMs) return false
        lastFireMs = now
        consecutiveHits = 0
        return true
    }

    /** Сброс серий и cooldown (перезапуск движка, смена конфига). */
    fun reset() {
        consecutiveHits = 0
        lastFireMs = null
    }
}
