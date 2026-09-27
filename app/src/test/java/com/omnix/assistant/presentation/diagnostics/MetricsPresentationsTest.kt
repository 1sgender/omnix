package com.omnix.assistant.presentation.diagnostics

import com.omnix.assistant.agent.metrics.VoiceLatencyMetrics
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dev-метрики (блок 8 плана пересборки фронта, 2026-09-26): форматирование
 * без string-параметров (PluralsCandidate не касается) и односторонний
 * доступ, скрытый до long-press версии.
 */
class MetricsPresentationsTest {

    @Test
    fun `percent and count render as percent dot count`() {
        assertEquals("63% · 120", formatPercentCount(63.4, 120L))
        assertEquals("0% · 0", formatPercentCount(0.0, 0L))
        assertEquals("100% · 7", formatPercentCount(99.9, 7L))
    }

    @Test
    fun `percentiles render compactly, null renders empty`() {
        val p = VoiceLatencyMetrics.Percentiles(count = 9, p50Ms = 450, p95Ms = 900, p99Ms = 1500)
        assertEquals("450/900/1500", formatPercentiles(p))
        assertEquals("", formatPercentiles(null))
    }

    @Test
    fun `dev metrics access starts hidden and reveal is one-way`() = runTest {
        val access = DeveloperMetricsAccess()
        assertFalse(access.revealed.first())

        access.reveal()
        access.reveal()
        assertTrue(access.revealed.first())
    }
}
