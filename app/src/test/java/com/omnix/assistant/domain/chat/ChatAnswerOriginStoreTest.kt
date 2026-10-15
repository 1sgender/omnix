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
 * Подпись «Ответ из облака» (design v2): read-model пометок облачных
 * ответов для чата. Контракт: аккумулирует id, игнорирует невалидные,
 * стрим отражает изменения.
 */
class ChatAnswerOriginStoreTest {

    @Test
    fun `marks accumulate without duplicates`() = runTest {
        val store = ChatAnswerOriginStore()

        store.markCloud(1L)
        store.markCloud(2L)
        store.markCloud(1L)

        assertEquals(setOf(1L, 2L), store.cloudMessageIds.first())
    }

    @Test
    fun `invalid ids are ignored silently`() = runTest {
        val store = ChatAnswerOriginStore()

        store.markCloud(0L)
        store.markCloud(-5L)

        assertTrue(store.cloudMessageIds.first().isEmpty())
    }

    @Test
    fun `flow emits every marked update`() = runTest {
        val store = ChatAnswerOriginStore()
        val emissions = mutableListOf<Set<Long>>()
        val job = launch {
            store.cloudMessageIds.take(3).toList(emissions)
        }
        // Прогрев: коллектор обязан подписаться ДО первой пометки, иначе
        // стартовая пустая эмиссия пропущена и take(3) не наберёт три.
        advanceUntilIdle()

        store.markCloud(42L)
        advanceUntilIdle()
        store.markCloud(43L)
        advanceUntilIdle()
        job.join()

        assertEquals(
            listOf(emptySet<Long>(), setOf(42L), setOf(42L, 43L)),
            emissions
        )
    }
}
