package com.omnix.assistant.presentation.chat

import android.content.Context
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.executor.ToolExecutor
import com.omnix.assistant.agent.memory.manager.OmniMemoryManager
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.domain.models.PromptExecutionResult
import com.omnix.assistant.domain.models.VoiceSettings
import com.omnix.assistant.domain.chat.ChatAnswerOriginStore
import com.omnix.assistant.domain.chat.ChatConversationStore
import com.omnix.assistant.domain.repository.MessageRepository
import com.omnix.assistant.domain.usecases.ClearChatHistoryUseCase
import com.omnix.assistant.domain.usecases.GetChatHistoryUseCase
import com.omnix.assistant.domain.usecases.GetSettingsUseCase
import com.omnix.assistant.domain.usecases.SendPromptUseCase
import com.omnix.assistant.voice.stt.SpeechRecognitionEvent
import com.omnix.assistant.voice.stt.SpeechRecognizerManager
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Сбой внутри конвейера отправки не должен ронять приложение. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSendFailureTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var getChatHistory: GetChatHistoryUseCase
    private lateinit var clearHistory: ClearChatHistoryUseCase
    private lateinit var sendPrompt: SendPromptUseCase
    private lateinit var getSettings: GetSettingsUseCase
    private lateinit var stt: SpeechRecognizerManager
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var messageRepository: MessageRepository
    private lateinit var memoryManager: OmniMemoryManager
    private val conversationStore = ChatConversationStore()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        // Контекст — замокан, чтобы не тянуть Robolectric. Все строки
        // из R.string возвращаем как сами идентификаторы (для верификации
        // нам нужны только вызовы use case, не человекочитаемый текст).
        context = mockk(relaxed = true)
        every { context.getString(any()) } answers { "str-${firstArg<Int>()}" }

        getChatHistory = mockk(relaxed = true)
        coEvery { getChatHistory.invoke() } returns flowOf(emptyList())
        clearHistory = mockk(relaxed = true)
        getSettings = mockk(relaxed = true)
        every { getSettings.invoke() } returns flowOf(
            VoiceSettings(
                userName = "user",
                systemPrompt = "",
                speechRate = 1.0f,
                speechPitch = 1.0f,
                selectedModel = "local",
                isHeadsetOnlyMode = false,
                wakeWordSensitivity = 0.5f
            )
        )
        stt = mockk(relaxed = true)
        toolExecutor = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        coEvery { messageRepository.getRecentMessages(any()) } returns emptyList()

        memoryManager = mockk(relaxed = true)
        sendPrompt = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = ChatViewModel(
        context = context,
        getChatHistoryUseCase = getChatHistory,
        clearChatHistoryUseCase = clearHistory,
        sendPromptUseCase = sendPrompt,
        getSettingsUseCase = getSettings,
        speechRecognizerManager = stt,
        toolExecutor = toolExecutor,
        messageRepository = messageRepository,
        answerOriginStore = ChatAnswerOriginStore(),
        conversationStore = conversationStore
    )

    @Test
    fun `exception from the send pipeline does not crash and puts an error bubble in the chat`() = runTest {
        val vm = createViewModel()
        coEvery {
            sendPrompt.invoke(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("pipeline blew up")
        val saved = mutableListOf<com.omnix.assistant.domain.models.Message>()
        coEvery { messageRepository.insertMessage(capture(saved)) } returns 1L

        vm.sendTextMessage("привет")
        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("isSending снят после сбоя", vm.uiState.value.isSending)
        assertEquals(1, saved.size)
        assertEquals(MessageRole.ERROR, saved.single().role)
    }
}
