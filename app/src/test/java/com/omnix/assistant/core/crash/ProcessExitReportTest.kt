package com.omnix.assistant.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Записи Android о смерти процесса — единственный след нативного вылета
 * и OOM-убийства. Проверяем, что они попадают в отчёт читаемо и что
 * спокойное закрытие не выдаётся за вылет.
 */
class ProcessExitReportTest {

    private fun exit(
        reason: Int,
        at: Long = 1_000_000L,
        description: String = "",
        importance: Int = 100,
        rssKb: Long = 512L * 1024
    ) = ProcessExit(at, reason, status = 11, importance = importance, rssKb = rssKb, description = description)

    @Test
    fun `native crash is named and flagged abnormal`() {
        val block = ProcessExitReport.format(
            listOf(exit(reason = 5, description = "crash: signal 11 (SIGSEGV)"))
        ).single()

        assertTrue(block.contains("CRASH_NATIVE (ABNORMAL)"))
        assertTrue(block.contains("signal 11 (SIGSEGV)"))
        assertTrue(block.contains("state: foreground"))
        assertTrue(block.contains("rss=512 MB"))
    }

    @Test
    fun `low memory kill is abnormal`() {
        val block = ProcessExitReport.format(listOf(exit(reason = 3))).single()
        assertTrue(block.contains("LOW_MEMORY (ABNORMAL)"))
    }

    @Test
    fun `swiping the app away is reported as normal`() {
        val block = ProcessExitReport.format(listOf(exit(reason = 10, importance = 400))).single()
        assertTrue(block.contains("USER_REQUESTED (normal)"))
        assertFalse(block.contains("ABNORMAL"))
    }

    @Test
    fun `newest exits come first and the limit is respected`() {
        val blocks = ProcessExitReport.format(
            listOf(exit(10, at = 1_000L), exit(5, at = 3_000L), exit(3, at = 2_000L)),
            limit = 2
        )
        assertEquals(2, blocks.size)
        assertTrue(blocks[0].contains("CRASH_NATIVE"))
        assertTrue(blocks[1].contains("LOW_MEMORY"))
    }

    @Test
    fun `missing description and unknown reason stay readable`() {
        val block = ProcessExitReport.format(listOf(exit(reason = 99))).single()
        assertTrue(block.contains("REASON_99"))
        assertTrue(block.contains("system note: (none)"))
    }

    @Test
    fun `very long description is cut`() {
        val block = ProcessExitReport.format(
            listOf(exit(reason = 5, description = "x".repeat(5_000))),
            maxDescriptionChars = 100
        ).single()
        assertTrue(block.length < 400)
    }

    @Test
    fun `tombstone highlights keep the crashing libraries and the abort message in order`() {
        // A protobuf-like blob: binary framing around readable strings.
        val blob = byteArrayOf(0x0A, 0x12) +
            "Abort message: 'FORTIFY: pthread_mutex_lock called on a destroyed mutex'".toByteArray() +
            byteArrayOf(0x00, 0x1A, 0x05) + "noise".toByteArray() + byteArrayOf(0x02) +
            "/data/app/~~x/com.omnix.assistant.staging/lib/arm64/libonnxruntime.so".toByteArray() +
            byteArrayOf(0x10, 0x01) + "OrtSessionRun".toByteArray() + byteArrayOf(0x7F, 0x03)

        val highlights = ProcessExitReport.tombstoneHighlights(blob)

        assertEquals(3, highlights.size)
        assertTrue(highlights[0].contains("FORTIFY"))
        assertTrue(highlights[1].endsWith("libonnxruntime.so"))
        assertEquals("OrtSessionRun", highlights[2])
    }

    @Test
    fun `native trace lines are printed under the exit`() {
        val block = ProcessExitReport.format(
            listOf(exit(reason = 5).copy(traceHighlights = listOf("libonnxruntime.so")))
        ).single()
        assertTrue(block.contains("native trace:\n  libonnxruntime.so"))
    }
}
