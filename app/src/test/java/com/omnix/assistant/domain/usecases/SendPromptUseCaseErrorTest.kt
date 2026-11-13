package com.omnix.assistant.domain.usecases

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.AiTier
import com.omnix.assistant.agent.decision.AiTierPreference
import com.omnix.assistant.agent.decision.ExecutionRequest
import com.omnix.assistant.agent.decision.ExecutionType
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.decision.RequestSource
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.memory.manager.OmniMemoryManager
import com.omnix.assistant.agent.pipeline.AgentPipeline
import com.omnix.assistant.core.license.AiQuotaBucket
import com.omnix.assistant.core.license.AiTierLedger
import com.omnix.assistant.core.license.QuotaExceeded
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.domain.models.PromptExecutionResult
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
import org.junit.Assert.assertTrue
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

    // ------------------------------------------------ лимиты и уровень Pro

    private fun tierUseCase(ledger: AiTierLedger, origin: ChatAnswerOriginStore, proInPlan: Boolean): SendPromptUseCase {
        // Настоящие id строк: метки «лимит Pro» и «ответил Flash» ставятся по id.
        coEvery { messageRepo.insertMessage(any()) } answers {
            inserted.add(firstArg())
            inserted.size.toLong()
        }
        every { context.getString(R.string.omnix_tier_quota_hours, *anyVararg()) } returns "QUOTA_HOURS"
        every { context.getString(R.string.omnix_tier_quota_days, *anyVararg()) } returns "QUOTA_DAYS"
        every { context.getString(R.string.omnix_tier_quota_upgrade) } returns "UPGRADE"
        val preference = object : AiTierPreference {
            override fun requestedTier() = AiTier.PRO
            override fun proInPlan() = proInPlan
        }
        return SendPromptUseCase(
            context, messageRepo, settingsRepo, memoryManager, pipeline, localModelManager, origin, ledger, preference
        )
    }

    @Test
    fun `pro quota refusal says when it resets and offers flash`() = runTest {
        val ledger = AiTierLedger()
        val origin = ChatAnswerOriginStore()
        ledger.onQuotaExceeded("omx_q1", QuotaExceeded(AiQuotaBucket.PRO, 5 * 3_600L, System.currentTimeMillis()))
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Error(IllegalStateException("QUOTA_EXCEEDED"), "Дневной лимит тарифа исчерпан.")

        val result = tierUseCase(ledger, origin, proInPlan = true)(
            "вопрос", RequestSource.CHAT, PrivacyLevel.NORMAL, false, requestId = "omx_q1"
        )

        val error = storedErrors().single()
        assertEquals("QUOTA_HOURS", error.text)
        assertEquals("QUOTA_HOURS", (result as Resource.Error).message)
        assertEquals("под сообщением — «Переключиться на Flash»", 1, origin.proQuotaIds.value.size)
    }

    @Test
    fun `free flash quota refusal suggests pro and max`() = runTest {
        val ledger = AiTierLedger()
        val origin = ChatAnswerOriginStore()
        ledger.onQuotaExceeded("omx_q2", QuotaExceeded(AiQuotaBucket.FLASH_TEXT, 3_600L, System.currentTimeMillis()))
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns Resource.Error(IllegalStateException("q"), "q")

        tierUseCase(ledger, origin, proInPlan = false)("вопрос", RequestSource.CHAT, PrivacyLevel.NORMAL, false, requestId = "omx_q2")

        assertEquals("QUOTA_HOURS UPGRADE", storedErrors().single().text)
        assertTrue("Flash кончился — переключать некуда", origin.proQuotaIds.value.isEmpty())
    }

    @Test
    fun `flash answer to a pro request is labelled`() = runTest {
        val ledger = AiTierLedger()
        val origin = ChatAnswerOriginStore()
        ledger.onAnswered("omx_q3", requestedPro = true, servedPro = false, voice = false)
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Success(PromptExecutionResult.DirectAnswer("ответ", executionType = ExecutionType.CLOUD_AI))

        tierUseCase(ledger, origin, proInPlan = true)("вопрос", RequestSource.CHAT, PrivacyLevel.NORMAL, false, requestId = "omx_q3")

        assertEquals(1, origin.flashFallbackIds.value.size)
        assertEquals(origin.cloudMessageIds.value, origin.flashFallbackIds.value)
    }

    @Test
    fun `error without message falls back to default wording`() = runTest {
        coEvery { pipeline.process(any<ExecutionRequest>()) } returns
            Resource.Error(IllegalStateException("x"))

        useCase("привет", RequestSource.CHAT, PrivacyLevel.NORMAL, false)

        assertEquals("DEFAULT_ERR", storedErrors().first().text)
    }
}
