package com.jarvis.assistant.voice.wakeword

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W06: счётчики кадров/детекций, maxScore, средние задержки стадий.
 */
class WakeWordMetricsTest {

    @Test
    fun `пустой снапшот без деления на ноль`() {
        val snap = WakeWordMetrics().snapshot()
        assertEquals(0L, snap.framesObserved)
        assertEquals(0L, snap.detections)
        assertEquals(0f, snap.maxScore, 0f)
        assertEquals(0.0, snap.avgMelMs, 0.0)
        assertEquals(0.0, snap.avgEmbMs, 0.0)
        assertEquals(0.0, snap.avgClfMs, 0.0)
    }

    @Test
    fun `кадры и детекции считаются, maxScore держит максимум`() {
        val metrics = WakeWordMetrics()
        metrics.recordFrame(0.2f, false, 10, 20, 2)
        metrics.recordFrame(0.9f, true, 12, 22, 3)
        metrics.recordFrame(0.5f, false, 14, 24, 4)
        val snap = metrics.snapshot()
        assertEquals(3L, snap.framesObserved)
        assertEquals(1L, snap.detections)
        assertEquals(0.9f, snap.maxScore, 0f)
        assertEquals(12.0, snap.avgMelMs, 1e-9)
        assertEquals(22.0, snap.avgEmbMs, 1e-9)
        assertEquals(3.0, snap.avgClfMs, 1e-9)
    }

    @Test
    fun `окно усреднения ограничено последними кадрами`() {
        val metrics = WakeWordMetrics()
        for (i in 0 until 70) {
            metrics.recordFrame(0.1f, false, i.toLong(), 0, 0)
        }
        val snap = metrics.snapshot()
        assertEquals(70L, snap.framesObserved)
        // Среднее по кадрам 6..69: (6 + 69) / 2 = 37.5.
        assertEquals(37.5, snap.avgMelMs, 1e-9)
    }

    @Test
    fun `reset обнуляет всё`() {
        val metrics = WakeWordMetrics()
        metrics.recordFrame(0.9f, true, 10, 20, 2)
        metrics.reset()
        val snap = metrics.snapshot()
        assertEquals(0L, snap.framesObserved)
        assertEquals(0L, snap.detections)
        assertEquals(0f, snap.maxScore, 0f)
    }
}
