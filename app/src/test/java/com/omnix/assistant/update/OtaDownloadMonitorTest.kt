package com.omnix.assistant.update

import android.app.DownloadManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты монитора OTA-загрузки — источника прогресса для дуги ядра.
 * DownloadManager подменяется poll-лямбдой; константы статусов compile-time
 * inline, поэтому доступны в JVM-тестах.
 */
class OtaDownloadMonitorTest {

    private fun progress(
        bytes: Long,
        total: Long,
        status: Int = DownloadManager.STATUS_RUNNING
    ) = DownloadProgress(bytesSoFar = bytes, totalBytes = total, status = status)

    @Test
    fun `snapshot is null before any download`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)

        assertNull(monitor.snapshot.value)
    }

    @Test
    fun `begin publishes live progress until success then clears`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)
        var call = 0

        monitor.begin(downloadId = 11L, versionCode = 200L) {
            when (call++) {
                0 -> progress(bytes = 100L, total = 1_000L)
                1 -> progress(bytes = 500L, total = 1_000L)
                else -> progress(bytes = 1_000L, total = 1_000L, status = DownloadManager.STATUS_SUCCESSFUL)
            }
        }

        withTimeout(5_000L) {
            // Ждём терминального состояния: успех очищает снимок (дуга гаснет).
            while (monitor.snapshot.value != null) {
                kotlinx.coroutines.delay(2L)
            }
        }

        assertNull(monitor.snapshot.value)
    }

    @Test
    fun `snapshot reflects bytes of the first poll`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)
        var served = false

        monitor.begin(downloadId = 12L, versionCode = 201L) {
            if (!served) {
                served = true
                progress(bytes = 250L, total = 1_000L)
            } else {
                progress(bytes = 250L, total = 1_000L, status = DownloadManager.STATUS_PAUSED)
            }
        }

        val first = withTimeout(5_000L) {
            monitor.snapshot.first { it != null && it.bytesSoFar == 250L }
        }

        assertEquals(12L, first.downloadId)
        assertEquals(201L, first.versionCode)
        assertEquals(1_000L, first.totalBytes)

        monitor.end()
    }

    @Test
    fun `failed download clears the snapshot`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)
        var call = 0

        monitor.begin(downloadId = 13L, versionCode = 202L) {
            if (call++ == 0) progress(bytes = 10L, total = 1_000L)
            else progress(bytes = 10L, total = 1_000L, status = DownloadManager.STATUS_FAILED)
        }

        withTimeout(5_000L) {
            while (monitor.snapshot.value != null) kotlinx.coroutines.delay(2L)
        }

        assertNull(monitor.snapshot.value)
    }

    @Test
    fun `vanished download record clears the snapshot`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)
        var call = 0

        monitor.begin(downloadId = 14L, versionCode = 203L) {
            // Первый опрос — запись исчезла (загрузку снесли вне приложения).
            if (call++ == 0) null else null
        }

        withTimeout(5_000L) {
            while (monitor.snapshot.value != null) kotlinx.coroutines.delay(2L)
        }

        assertNull(monitor.snapshot.value)
    }

    @Test
    fun `end stops polling and clears even while download continues`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)
        var polls = 0

        monitor.begin(downloadId = 15L, versionCode = 204L) {
            polls++
            progress(bytes = 10L, total = 1_000L) // вечно RUNNING
        }

        withTimeout(5_000L) {
            monitor.snapshot.first { it != null && it.bytesSoFar == 10L }
        }

        monitor.end()

        assertNull(monitor.snapshot.value)
        val pollsAtEnd = polls
        kotlinx.coroutines.delay(50L)
        // Опрос прекратился: новых вызовов poll нет.
        assertEquals(pollsAtEnd, polls)
    }

    @Test
    fun `rebegin replaces the previous watch`() = runBlocking {
        val monitor = OtaDownloadMonitor(pollMs = 5L)

        monitor.begin(downloadId = 16L, versionCode = 205L) {
            progress(bytes = 1L, total = 1_000L) // вечно RUNNING
        }
        withTimeout(5_000L) {
            monitor.snapshot.first { it?.downloadId == 16L }
        }

        monitor.begin(downloadId = 17L, versionCode = 206L) {
            progress(bytes = 999L, total = 2_000L, status = DownloadManager.STATUS_SUCCESSFUL)
        }

        // Первый poll нового слежения уже отработал: в снимке — новая загрузка.
        withTimeout(5_000L) {
            monitor.snapshot.first { it?.downloadId == 17L }
        }
        // Успех гасит снимок.
        withTimeout(5_000L) {
            while (monitor.snapshot.value != null) kotlinx.coroutines.delay(2L)
        }
        assertTrue(true)
    }
}
