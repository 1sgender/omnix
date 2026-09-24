package com.omnix.assistant.core.network

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM-тесты монитора облачной активности — источника бейджа CLOUD.
 * Проверяется контракт счётчика: параллельные запросы не гасят сигнал раньше
 * времени, а лишний requestFinished не уводит счётчик в минус.
 */
class CloudProcessingMonitorTest {

    @Test
    fun `active is false initially`() = runTest {
        val monitor = CloudProcessingMonitor()

        assertFalse(monitor.active.first())
    }

    @Test
    fun `request started makes active true`() = runTest {
        val monitor = CloudProcessingMonitor()

        monitor.requestStarted()

        assertTrue(monitor.active.first())
    }

    @Test
    fun `start then finish returns to false`() = runTest {
        val monitor = CloudProcessingMonitor()

        monitor.requestStarted()
        monitor.requestFinished()

        assertFalse(monitor.active.first())
    }

    @Test
    fun `overlapping requests keep active true until the last one finishes`() = runTest {
        val monitor = CloudProcessingMonitor()

        monitor.requestStarted()
        monitor.requestStarted()
        monitor.requestFinished()

        // Второй запрос ещё в полёте — бейдж не гаснем.
        assertTrue(monitor.active.first())

        monitor.requestFinished()

        assertFalse(monitor.active.first())
    }

    @Test
    fun `extra finish never drops the counter below zero`() = runTest {
        val monitor = CloudProcessingMonitor()

        monitor.requestFinished()
        monitor.requestFinished()

        assertFalse(monitor.active.first())

        // После «лишних» finish счётчик всё ещё корректно поднимается.
        monitor.requestStarted()

        assertTrue(monitor.active.first())
    }

    @Test
    fun `active flow emits distinct values only`() = runTest {
        val monitor = CloudProcessingMonitor()
        val emissions = mutableListOf<Boolean>()

        // UnconfinedTestDispatcher: коллектор обрабатывает эмиссии синхронно,
        // в момент изменения счётчика — тест детерминирован без виртуальных
        // задержек (паттерн из документации kotlinx.coroutines).
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            monitor.active.collect { emissions.add(it) }
        }

        monitor.requestStarted()
        monitor.requestStarted()
        monitor.requestFinished()
        monitor.requestFinished()
        monitor.requestStarted()

        job.cancel()

        // false→true→false→true: по одному значению на смену, дублей нет.
        assertEquals(listOf(false, true, false, true), emissions)
    }
}
