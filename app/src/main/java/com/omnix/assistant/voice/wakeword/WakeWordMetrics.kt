package com.omnix.assistant.voice.wakeword

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
        /** P95 задержек стадий, мс: среднее маскирует всплески (GC, теплота CPU). */
        val p95MelMs: Double,
        val p95EmbMs: Double,
        val p95ClfMs: Double,
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
            val mel = latencies.map { it.first }.sorted()
            val emb = latencies.map { it.second }.sorted()
            val clf = latencies.map { it.third }.sorted()
            return Snapshot(
                framesObserved = frames,
                detections = fires,
                maxScore = maxScore,
                avgMelMs = latencies.sumOf { it.first.toDouble() } / n,
                avgEmbMs = latencies.sumOf { it.second.toDouble() } / n,
                avgClfMs = latencies.sumOf { it.third.toDouble() } / n,
                p95MelMs = p95(mel),
                p95EmbMs = p95(emb),
                p95ClfMs = p95(clf),
            )
        }
    }

    private fun p95(sorted: List<Long>): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = ((sorted.size * 0.95).toInt()).coerceIn(0, sorted.size - 1)
        return sorted[idx].toDouble()
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
