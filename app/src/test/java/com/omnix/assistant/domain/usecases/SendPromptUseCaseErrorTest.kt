package com.omnix.assistant.domain.usecases

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.ExecutionRequest
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.decision.RequestSource
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.memory.manager.OmniMemoryManager
import com.omnix.assistant.agent.pipeline.AgentPipeline
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.domain.chat.ChatAnswerOriginStore
import com.omnix.assistant.domain.repository.MessageRepository
import com.omnix.assistant.domain.repository.SettingsRepository
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Mock 2026-09-26: провал запроса сохраняется ролью [MessageRole.ERROR],
 * а сетевой сбой — спокойной формулировкой, а не «сырым» текстом
 * исключения. Так чат отрисовывает ошибку отдельным пузырём, а не
 * обычным ответом OMNIX. Диагностическая часть (хинт про нескачанную
 * офлайн-модель, текст несетевого сбоя) сохраняется.
 */
class SendPromptUseCaseErrorTest {

    private lateinit var context: Context
    private lateinit var messageRepo: MessageRepository
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var memoryManager: OmniMemoryManager
    private lateinit var localModelManager: LocalModelManager
    private lateinit var pipeline: AgentPipeline
    private lateinit var useCase: SendPromptUseCase

    private val inserted = mutableListOf<Message>()

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        // Catch-all first: mockk applies the LAST matching stub, so the
        // specific ids below win over it.
        every { context.getString(any()) } answers { "str-${firstArg<Int>()}" }
        every { context.getString(R.string.omnix_chat_network_error) } returns "NETWORK_ERR"
        every { context.getString(R.string.oflayn_model_ne_skachana) } returns "OFFLINE_HINT"
        every { context.getString(R.string.oshibka_vypolneniya_zaprosa) } returns "DEFAULT_ERR"

        messageRepo = mockk(relaxed = true)
        coEvery { messageRepo.getRecentMessages(any()) } returns emptyList()
        coEvery { messageRepo.insertMessage(any()) } answers {
            inserted.add(firstArg())
            0L
        }

        settingsRepo = mockk(relaxed = true)
        every { settingsRepo.systemPromptFlow } returns flowOf("")

        memoryManager = mockk(relaxed = true)
        every { memoryManager.workingMemory.resolveContextualQuery(any()) } answers { firstArg<String>() }

        // Нейтральный фон: модель не скачана — срабатывает офлайн-хинт.
        localModelManager = mockk(relaxed = true)
        every { localModelManager.state } returns LocalModelState.NotInstalled("/data/local/model.task")

        pipeline = mockk(relaxed = true)

        useCase = SendPromptUseCase(context, messageRepo, settingsRepo, memoryManager, pipeline, localModelManager, ChatAnswerOriginStore())
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    private fun storedErrors() = inserted.filter { it.role == MessageRole.ERROR }

    @Test
    fun `network failure is stored as ERROR role with calm wording`() = runTest {
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Error(IOException("boom"), "Unable to resolve host api.omnix")

        useCase("привет", RequestSource.CHAT, PrivacyLevel.NORMAL, false)

        val errors = storedErrors()
        assertEquals(1, errors.size)
        // Спокойная формулировка + диагностический офлайн-хинт.
        assertEquals("NETWORK_ERR OFFLINE_HINT", errors.first().text)
        // «Сырой» текст исключения в чат не уходит.
        assertFalse("сырой текст исключения в тексте ошибки", errors.first().text.contains("resolve host"))
    }

    @Test
    fun `network failure keeps calm wording when offline model is present`() = runTest {
        every { localModelManager.state } returns LocalModelState.Ready("gemma", 1000L)
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Error(IOException("io"), "boom")

        useCase("привет", RequestSource.CHAT, PrivacyLevel.NORMAL, false)

        assertEquals("NETWORK_ERR", storedErrors().first().text)
    }

    @Test
    fun `non-network failure is stored as ERROR role with its diagnostic message`() = runTest {
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Error(IllegalStateException("pipeline exploded"), "pipeline exploded")

        useCase("привет", RequestSource.CHAT, PrivacyLevel.NORMAL, false)

        val errors = storedErrors()
        assertEquals(1, errors.size)
        assertEquals("pipeline exploded", errors.first().text)
    }

    @Test
    fun `error without message falls back to default wording`() = runTest {
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Error(IllegalStateException("x"))

        useCase("привет", RequestSource.CHAT, PrivacyLevel.NORMAL, false)

        assertEquals("DEFAULT_ERR", storedErrors().first().text)
    }
}
