package com.jarvis.assistant.voice.wakeword

/**
 * Метрики wake-word детекции (§12 ТЗ). Отдельно от VoiceLatencyMetrics:
 * там — сквозные стадии голосового запроса, здесь — внутренности детектора.
 *
 * Сырое аудио никогда не хранится и не логируется — только скоры и задержки.
 * Чистый Kotlin — покрыт JVM-тестами.
 */
class WakeWordMetrics {
    data class Snapshot(
        val framesObserved: Long,
        val detections: Long,
        val maxScore: Float,
        /** Средние задержки стадий по последним кадрам, мс. */
        val avgMelMs: Double,
        val avgEmbMs: Double,
        val avgClfMs: Double,
    )

    private val lock = Any()
    private var frames = 0L
    private var fires = 0L
    private var maxScore = 0f
    private val latencies = ArrayDeque<Triple<Long, Long, Long>>()

    companion object {
        /** Окно усреднения задержек (64 кадра ~= 5 c детекции). */
        const val LATENCY_WINDOW = 64
    }

    fun recordFrame(score: Float, fired: Boolean, melMs: Long, embMs: Long, clfMs: Long) {
        synchronized(lock) {
            frames++
            if (fired) fires++
            if (score > maxScore) maxScore = score
            latencies.addLast(Triple(melMs, embMs, clfMs))
            while (latencies.size > LATENCY_WINDOW) latencies.removeFirst()
        }
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            val n = latencies.size.coerceAtLeast(1)
            return Snapshot(
                framesObserved = frames,
                detections = fires,
                maxScore = maxScore,
                avgMelMs = latencies.sumOf { it.first.toDouble() } / n,
                avgEmbMs = latencies.sumOf { it.second.toDouble() } / n,
                avgClfMs = latencies.sumOf { it.third.toDouble() } / n,
            )
        }
    }

    fun reset() {
        synchronized(lock) {
            frames = 0
            fires = 0
            maxScore = 0f
            latencies.clear()
        }
    }
}
