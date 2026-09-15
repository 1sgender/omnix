package com.jarvis.assistant.voice.orchestrator

import android.content.Context
import com.jarvis.assistant.agent.executor.ToolExecutor
import com.jarvis.assistant.agent.metrics.VoiceLatencyMetrics
import com.jarvis.assistant.agent.translator.LiveTranslatorEngine
import com.jarvis.assistant.domain.models.VoiceAssistantState
import com.jarvis.assistant.domain.models.VoiceSettings
import com.jarvis.assistant.domain.usecases.GetSettingsUseCase
import com.jarvis.assistant.domain.usecases.SendPromptUseCase
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.stt.SpeechRecognitionEvent
import com.jarvis.assistant.voice.stt.SpeechRecognizerManager
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import com.jarvis.assistant.voice.tts.TtsState
import com.jarvis.assistant.voice.wakeword.WakeWordDetection
import com.jarvis.assistant.voice.wakeword.WakeWordEngine
import com.jarvis.assistant.voice.wakeword.WakeWordEngineError
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.Runs
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * W07: микрофонный хэндофф neural wake word <-> STT.
 *
 * detection → engine.stop() → chime → STT на КОМАНДУ (STT-верификация удалена);
 * дубликаты в не-STANDBY игнорируются; фатальные ошибки движка видны пользователю;
 * возврат в STANDBY перезапускает движок. Android-методы безопасны:
 * testOptions.isReturnDefaultValues=true, ToneGenerator обёрнут в try/catch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeWordHandoffTest {

    private lateinit var context: Context
    private lateinit var engine: WakeWordEngine
    private lateinit var stt: SpeechRecognizerManager
    private lateinit var tts: TextToSpeechManager
    private lateinit var bluetooth: BluetoothAudioRouter
    private lateinit var sendPrompt: SendPromptUseCase
    private lateinit var getSettings: GetSettingsUseCase
    private lateinit var toolExecutor: ToolExecutor
    private lateinit var translator: LiveTranslatorEngine
    private lateinit var latency: VoiceLatencyMetrics

    private lateinit var detections: MutableSharedFlow<WakeWordDetection>
    private lateinit var engineErrors: MutableSharedFlow<WakeWordEngineError>
    private lateinit var orchestrator: VoiceInteractionOrchestrator

    @Before
    fun setUp() {
        context = mockk()
        engine = mockk()
        stt = mockk()
        tts = mockk()
        bluetooth = mockk()
        sendPrompt = mockk()
        getSettings = mockk()
        toolExecutor = mockk()
        translator = mockk()
        latency = mockk()

        detections = MutableSharedFlow()
        engineErrors = MutableSharedFlow()

        every { context.getString(any()) } returns ""
        every { engine.detections } returns detections
        every { engine.errors } returns engineErrors
        every { stt.speechState } returns MutableStateFlow(SpeechRecognitionEvent.Idle)
        every { stt.audioLevel } returns MutableStateFlow(0f)
        every { tts.ttsState } returns MutableStateFlow(TtsState.Idle)
        every { engine.start() } just Runs
        every { engine.stop() } just Runs
        every { stt.startListening() } just Runs
        every { stt.stopListening() } just Runs
        every { bluetooth.routeAudioToSpeaker() } just Runs
        every { bluetooth.restoreDefaultRouting() } just Runs
        every { toolExecutor.clearPendingConfirmation() } just Runs
        every { bluetooth.isHeadsetPlugged } returns MutableStateFlow(false)
        every { getSettings() } returns flowOf(VoiceSettings())

    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Оркестратор конструируется ВНУТРИ runTest после setMain(testScheduler):
     * его коллекторы встают на тот же шедулер, что прокачивает runCurrent().
     * Конструирование в @Before вешало их на чужой шедулер — tryEmit терялся.
     */
    private fun TestScope.buildOrchestrator() {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        orchestrator = VoiceInteractionOrchestrator(
            context,
            engine,
            stt,
            tts,
            bluetooth,
            sendPrompt,
            getSettings,
            toolExecutor,
            translator,
            latency,
        )
    }

    @Test
    fun `detection в STANDBY останавливает движок и стартует STT на команду`() = runTest {
        buildOrchestrator()
        runCurrent()
        assertTrue(detections.tryEmit(WakeWordDetection("Hey Jarvis", 0.9f, 12345L)))
        runCurrent()

        assertEquals(OrchestratorMode.LISTENING_USER_QUERY, orchestrator.currentMode.value)
        verify { engine.stop() }
        verify { stt.startListening() }
    }

    @Test
    fun `дубликат детекции вне STANDBY игнорируется`() = runTest {
        buildOrchestrator()
        runCurrent()
        assertTrue(detections.tryEmit(WakeWordDetection("Hey Jarvis", 0.9f, 12345L)))
        runCurrent()
        assertTrue(detections.tryEmit(WakeWordDetection("Hey Jarvis", 0.95f, 12400L)))
        runCurrent()

        assertEquals(OrchestratorMode.LISTENING_USER_QUERY, orchestrator.currentMode.value)
        verify(exactly = 1) { stt.startListening() }
        verify(exactly = 1) { engine.stop() }
    }

    @Test
    fun `ModelMissing показывает ошибку ассистента`() = runTest {
        buildOrchestrator()
        runCurrent()
        assertTrue(engineErrors.tryEmit(WakeWordEngineError.ModelMissing("wakeword/x.onnx")))
        runCurrent()

        assertTrue(orchestrator.assistantState.value is VoiceAssistantState.Error)
    }

    @Test
    fun `возврат в STANDBY после активации перезапускает движок`() = runTest {
        buildOrchestrator()
        every { bluetooth.checkHeadsetConnection() } returns true
        every { bluetooth.isHeadsetConnected() } returns false
        runCurrent()

        orchestrator.startServicePipeline()
        runCurrent()

        assertEquals(OrchestratorMode.STANDBY_WAKE_WORD, orchestrator.currentMode.value)
        verify { engine.start() }
    }
}
