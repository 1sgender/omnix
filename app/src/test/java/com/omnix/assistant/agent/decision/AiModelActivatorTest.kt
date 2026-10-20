package com.omnix.assistant.agent.decision

import com.omnix.assistant.agent.localai.LocalModelState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смена ИИ-модели сохраняется только тогда, когда новая модель реально
 * готова: иначе пользователь остаётся на прежней рабочей модели.
 */
class AiModelActivatorTest {

    private val saved = mutableListOf<AiModelChoice>()

    private fun activator(
        local: LocalModelState = LocalModelState.Ready("qwen", 10),
        server: Boolean = true
    ) = AiModelActivator(
        loadLocalModel = { local },
        serverReachable = { server },
        save = { saved += it }
    )

    @Test
    fun `auto needs nothing and is saved`() = runBlocking {
        val result = activator(local = LocalModelState.NotInstalled("/x"), server = false)
            .activate(AiModelChoice.AUTO)
        assertEquals(AiModelActivation.Applied, result)
        assertEquals(listOf(AiModelChoice.AUTO), saved)
    }

    @Test
    fun `on device is saved only when the model is ready`() = runBlocking {
        assertEquals(AiModelActivation.Applied, activator().activate(AiModelChoice.ON_DEVICE))
        assertEquals(listOf(AiModelChoice.ON_DEVICE), saved)
    }

    @Test
    fun `on device keeps the previous choice when the model is missing or broken`() = runBlocking {
        val cases = mapOf(
            LocalModelState.NotInstalled("/x") to AiModelActivationError.LOCAL_NOT_INSTALLED,
            LocalModelState.Downloading(0, 0L, 100L) to AiModelActivationError.LOCAL_DOWNLOADING,
            LocalModelState.InsufficientMemory(1536) to AiModelActivationError.LOCAL_NOT_ENOUGH_MEMORY,
            LocalModelState.Failed("engine crashed") to AiModelActivationError.LOCAL_LOAD_FAILED,
            LocalModelState.DownloadFailed("sha mismatch") to AiModelActivationError.LOCAL_NOT_INSTALLED
        )
        for ((state, expected) in cases) {
            val result = activator(local = state).activate(AiModelChoice.ON_DEVICE)
            assertTrue("state=$state result=$result", result is AiModelActivation.Failed)
            assertEquals(expected, (result as AiModelActivation.Failed).error)
        }
        assertTrue("nothing must be saved", saved.isEmpty())
    }

    @Test
    fun `cloud is saved only when the server answers`() = runBlocking {
        val failed = activator(server = false).activate(AiModelChoice.CLOUD)
        assertEquals(AiModelActivation.Failed(AiModelActivationError.SERVER_UNREACHABLE), failed)
        assertTrue(saved.isEmpty())

        assertEquals(AiModelActivation.Applied, activator(server = true).activate(AiModelChoice.CLOUD))
        assertEquals(listOf(AiModelChoice.CLOUD), saved)
    }
}
