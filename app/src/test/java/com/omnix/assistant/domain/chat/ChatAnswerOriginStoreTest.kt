package com.omnix.assistant.domain.chat

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * On-device бейдж (audit 2026-09-26): read-model пометок «обработано на
 * устройстве» для чата. Контракт: аккумулирует id, игнорирует невалидные,
 * стрим отражает изменения.
 */
class ChatAnswerOriginStoreTest {

    @Test
    fun `marks accumulate without duplicates`() = runTest {
        val store = ChatAnswerOriginStore()

        store.markOnDevice(1L)
        store.markOnDevice(2L)
        store.markOnDevice(1L)

        assertEquals(setOf(1L, 2L), store.onDeviceMessageIds.first())
    }

    @Test
    fun `invalid ids are ignored silently`() = runTest {
        val store = ChatAnswerOriginStore()

        store.markOnDevice(0L)
        store.markOnDevice(-5L)

        assertTrue(store.onDeviceMessageIds.first().isEmpty())
    }

    @Test
    fun `flow emits every marked update`() = runTest {
        val store = ChatAnswerOriginStore()
        val emissions = mutableListOf<Set<Long>>()
        val job = launch {
            store.onDeviceMessageIds.take(3).toList(emissions)
        }

        store.markOnDevice(42L)
        advanceUntilIdle()
        store.markOnDevice(43L)
        advanceUntilIdle()
        job.join()

        assertEquals(
            listOf(emptySet<Long>(), setOf(42L), setOf(42L, 43L)),
            emissions
        )
    }
}
