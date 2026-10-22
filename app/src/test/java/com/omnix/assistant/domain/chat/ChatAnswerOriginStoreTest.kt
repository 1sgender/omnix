package com.omnix.assistant.domain.chat

import com.omnix.assistant.agent.decision.ExecutionType
import com.omnix.assistant.core.admin.AdminCloudModel
import com.omnix.assistant.core.admin.AdminModelPin
import com.omnix.assistant.core.admin.AdminModelTesting
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

    @Test
    fun `admin labels are recorded only when testing names the answer`() = runTest {
        val admin = object : AdminModelTesting {
            override fun pin(): AdminModelPin = AdminModelPin.None
            override fun onLicenseChecked(adminCloudModels: List<AdminCloudModel>?) = Unit
            override fun onCloudAnswer(provider: String?, model: String?) = Unit
            override fun answerLabel(executionType: ExecutionType?): String? =
                if (executionType == ExecutionType.CLOUD_AI) "Groq · m" else null
        }
        val store = ChatAnswerOriginStore(admin)

        store.markAdminLabel(7L, ExecutionType.CLOUD_AI)
        store.markAdminLabel(8L, ExecutionType.DEVICE_TOOL)
        store.markAdminLabel(0L, ExecutionType.CLOUD_AI)

        assertEquals(mapOf(7L to "Groq · m"), store.adminLabels.first())
    }

    @Test
    fun `no admin labels for ordinary users`() = runTest {
        val store = ChatAnswerOriginStore()

        store.markAdminLabel(7L, ExecutionType.CLOUD_AI)

        assertTrue(store.adminLabels.first().isEmpty())
    }
}
