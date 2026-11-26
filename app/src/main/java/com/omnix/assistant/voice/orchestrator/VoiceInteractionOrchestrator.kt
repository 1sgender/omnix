package com.omnix.assistant.voice.orchestrator

import com.omnix.assistant.R
import android.content.Context
import android.media.ToneGenerator
import com.omnix.assistant.voice.audio.Haptics
import com.omnix.assistant.voice.audio.SoftChime
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.util.Log
import com.omnix.assistant.core.request.RequestIds
import com.omnix.assistant.agent.decision.PrivacyClassification
import com.omnix.assistant.agent.decision.PrivacyClassifier
import com.omnix.assistant.agent.decision.PrivacyContent
import com.omnix.assistant.agent.decision.PrivacyReason
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.decision.RequestSource
import com.omnix.assistant.agent.metrics.VoiceLatencyMetrics
import com.omnix.assistant.agent.executor.ConfirmationOwner
import com.omnix.assistant.agent.executor.ToolExecutor
import com.omnix.assistant.agent.model.ToolCall
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.translator.LiveEarSession
import com.omnix.assistant.agent.translator.LiveInterpretCommand
import com.omnix.assistant.agent.translator.LiveTranslatorEngine
import com.omnix.assistant.agent.translator.TranslationLanguages
import com.omnix.assistant.core.locale.AppLanguage
import com.omnix.assistant.voice.live.LiveAudioCapture
import com.omnix.assistant.voice.live.LiveMicMode
import com.omnix.assistant.voice.live.LiveMicSettings
import com.omnix.assistant.voice.live.PcmPipeFeeder
import com.omnix.assistant.core.confirmation.ConfirmationIntent
import com.omnix.assistant.core.crash.CrashCapture
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.models.PromptExecutionResult
import com.omnix.assistant.domain.models.VoiceAssistantState
import com.omnix.assistant.domain.usecases.GetSettingsUseCase
import com.omnix.assistant.domain.usecases.SendPromptUseCase
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import com.omnix.assistant.voice.stt.AsrPostProcessor
import com.omnix.assistant.core.permission.AppVisibility
import com.omnix.assistant.core.permission.PermissionPrompts
import com.omnix.assistant.core.permission.PermissionRequestNotifier
import com.omnix.assistant.core.permission.PermissionTexts
import com.omnix.assistant.voice.stt.SpeechRecognitionEvent
import com.omnix.assistant.voice.stt.SpeechRecognizerManager
import com.omnix.assistant.voice.tts.TextToSpeechManager
import com.omnix.assistant.voice.tts.TtsState
import com.omnix.assistant.voice.wakeword.WakeWordDetection
import com.omnix.assistant.voice.wakeword.WakeWordEngine
import com.omnix.assistant.voice.wakeword.WakeWordEngineError
import com.omnix.assistant.voice.wakeword.WakeWordExtractor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

enum class OrchestratorMode {
    STANDBY_WAKE_WORD,        // Ожидание «Джарвис»
    LISTENING_USER_QUERY,     // Запись голоса
    CONTINUOUS_CONVERSATION,  // Диалоговое окно (без повтора «Джарвис»)
    AI_THINKING,              // Запрос AI / Fast Router
    TTS_SPEAKING,             // Озвучивание ответа
    AWAITING_CONFIRMATION,    // Ожидание голосового подтверждения действия (Да/Нет)
    AWAITING_PRIVACY_CONSENT, // C-02: ожидание согласия на отправку приватного запроса в облако
    LIVE_EAR_INTERPRETER,     // Непрерывный синхронный перевод речи собеседника прямо в ухо
    PAUSED_CALL_OR_SLEEP      // Пауза
}

@Singleton
class VoiceInteractionOrchestrator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wakeWordEngine: WakeWordEngine,
    private val speechRecognizerManager: SpeechRecognizerManager,
    private val textToSpeechManager: TextToSpeechManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val sendPromptUseCase: SendPromptUseCase,
    private val getSettingsUseCase: GetSettingsUseCase,
    private val toolExecutor: ToolExecutor,
    private val translatorEngine: LiveTranslatorEngine,
    private val latencyMetrics: VoiceLatencyMetrics
) {
    companion object {
        private const val TAG = "VoiceOrchestrator"
        
        /**
         * Сколько ждать новых слов после последнего промежуточного результата, прежде чем
         * считать фразу законченной. Было 1,2 с и обрезало речь на паузе-раздумье.
         */
        private const val SILENCE_AFTER_PARTIAL_MS = 2_400L

        /** Перевод в ухо чуть быстрее обычной речи, чтобы не отставать от собеседника. */
        private const val LIVE_SPEECH_RATE = 1.15f
        private const val LIVE_WATCHDOG_TICK_MS = 1_000L
        private const val LIVE_WATCHDOG_VOICED_MS = 5_000L
        private const val FOLLOW_UP_WINDOW_MS = 8000L
        private const val CONFIRMATION_TIMEOUT_MS = 10000L

        /** Паузы перед повторным запуском движка фразы после его ошибки. */
        private val WAKE_RETRY_DELAYS_MS = longArrayOf(1_000L, 2_000L, 5_000L, 15_000L, 60_000L)

        /**
         * CR-01: режимы, в которых FinalResult speech-распознавателя ДОЛЖЕН
         * обрабатываться. В STANDBY/AI_THINKING/TTS_SPEAKING/PAUSED финальный
         * результат игнорируется — он обычно является эхом/остаточным callback
         * от ПРЕДЫДУЩЕГО распознавания и приводит к двойной обработке команд,
         * «оживающим» ответам и несанкционированным AI/TTS вызовам.
         */
        private val ALLOWED_FINAL_RESULT_MODES = setOf(
            OrchestratorMode.LISTENING_USER_QUERY,
            OrchestratorMode.CONTINUOUS_CONVERSATION,
            OrchestratorMode.AWAITING_CONFIRMATION,
            OrchestratorMode.AWAITING_PRIVACY_CONSENT, // C-02: ловим «да/нет» в ответ на cloud-consent
            OrchestratorMode.LIVE_EAR_INTERPRETER
        )
    }

    private var orchestratorJob = SupervisorJob()

    // Ошибка в наблюдателе или запросе не должна ронять приложение вместе со
    // службой: логируем, пишем в «Диагностику», голосовой режим продолжает жить.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "orchestrator coroutine failed", throwable)
        CrashCapture.recordHandled("orchestrator", throwable)
    }

    private var scope = CoroutineScope(Dispatchers.Main.immediate + orchestratorJob + exceptionHandler)

    private val _currentMode = MutableStateFlow(OrchestratorMode.STANDBY_WAKE_WORD)
    val currentMode: StateFlow<OrchestratorMode> = _currentMode.asStateFlow()

    private val _assistantState = MutableStateFlow<VoiceAssistantState>(VoiceAssistantState.Idle)
    val assistantState: StateFlow<VoiceAssistantState> = _assistantState.asStateFlow()

    private val _lastQuery = MutableStateFlow("")
    val lastQuery: StateFlow<String> = _lastQuery.asStateFlow()

    private val _lastAnswer = MutableStateFlow("")
    val lastAnswer: StateFlow<String> = _lastAnswer.asStateFlow()

    private val _privacyClassification = MutableStateFlow(
        PrivacyClassification.unknown(PrivacyReason.NOT_CLASSIFIED)
    )
    val privacyClassification: StateFlow<PrivacyClassification> =
        _privacyClassification.asStateFlow()

    /**
     * Live microphone amplitude, 0..1, taken straight from the recogniser.
     *
     * Exposed so the UI Core can be audio reactive with a real signal instead
     * of a fabricated animation (frontend specification §16, §33).
     */
    val audioLevel: StateFlow<Float> = speechRecognizerManager.audioLevel

    /**
     * The tool call OMNIX is currently executing or awaiting confirmation for,
     * or null when nothing is in flight.
     *
     * The UI turns this into a sentence such as "Calling Alex…"; the
     * orchestrator remains the only owner of the truth (specification §16).
     */
    private val _currentToolCall = MutableStateFlow<ToolCall?>(null)
    val currentToolCall: StateFlow<ToolCall?> = _currentToolCall.asStateFlow()

    /** Result of the last executed tool call, paired with [currentToolCall]. */
    private val _lastToolResult = MutableStateFlow<ToolExecutionResult?>(null)
    val lastToolResult: StateFlow<ToolExecutionResult?> = _lastToolResult.asStateFlow()

    /** Human prompt of the confirmation currently awaiting a decision. */
    private val _confirmationPrompt = MutableStateFlow<String?>(null)
    val confirmationPrompt: StateFlow<String?> = _confirmationPrompt.asStateFlow()

    private var toneGenerator: ToneGenerator? = null
    private var aiJob: Job? = null
    private var silenceJob: Job? = null
    private var followUpWindowJob: Job? = null
    private var confirmationTimeoutJob: Job? = null

    /** CR-22: актуальный job перевода в режиме LIVE_EAR_INTERPRETER. Новый перевод
     * отменяет предыдущий — «последний побеждает». Без этого параллельные
     * переводы могут завершиться в неправильном порядке и перекрыться в TTS. */
    private var translationJob: Job? = null

    /**
     * Синхронный перевод в ухо: сессия (куски перевода по ходу речи), язык собеседника и
     * флаг «распознавание ещё не запущено» (стартует после фразы о включении режима).
     */
    private var liveSession: LiveEarSession? = null
    private var liveSessionViewJob: Job? = null
    private var liveSourceLang: String = LiveInterpretCommand.DEFAULT_SOURCE
    private var liveTargetLang: String = "ru"
    private var liveListenPending = false

    /** Микрофон, названный в команде («переводчик через наушник»); null — из настройки. */
    private var liveMicOverride: LiveMicMode? = null
    private var liveCapture: LiveAudioCapture? = null
    private var liveFeeder: PcmPipeFeeder? = null
    private var liveCaptureJob: Job? = null
    private var liveWatchdogJob: Job? = null
    private var liveTtsJob: Job? = null

    /** Перевод озвучивается потоком звонка (наушник в режиме гарнитуры). */
    @Volatile
    private var liveSpeakViaCall = false

    /** Распознаватель уже прислал хоть что-то в этой сессии перевода. */
    @Volatile
    private var liveHeardSomething = false

    private val _serviceActive = MutableStateFlow(false)

    /**
     * Включён ли ассистент на самом деле: служба поднята и голосовой пайплайн
     * запущен. Это источник правды для переключателя на главном экране
     * (нажатие на логотип), а не состояние кнопки.
     */
    val serviceActive: StateFlow<Boolean> = _serviceActive.asStateFlow()

    private var isServiceActive: Boolean
        get() = _serviceActive.value
        set(value) {
            _serviceActive.value = value
        }

    private val isProcessingQuery = AtomicBoolean(false)

    /**
     * CR-07: поколение (эпоха) голосового сеанса. Стартует в 0, инкрементируется
     * при каждом входе в STANDBY. processUserQuery запоминает эпоху на старте и
     * отбрасывает любые поздние результаты, если эпоха успела смениться
     * (т.е. пользователь нажал «стоп» / перешёл в standby).
     */
    private val sessionEpoch = AtomicInteger(0)

    private var pendingToolCall: ToolCall? = null
    private var pendingConfirmationPrompt: String = ""

    /** Одноразовый токен подтверждения (пункт аудита #5). */
    private var pendingConfirmationToken: String? = null

    /**
     * C-02: ожидание голосового ответа на privacy-consent вопрос
     * («отправить в облако?»). Отдельное поле, а не часть pendingToolCall —
     * в этом состоянии голосовой оркестратор НЕ вызывает ToolExecutor,
     * а должен повторно вызвать sendPromptUseCase с cloudExplicitlyAllowed=true.
     */
    private var pendingCloudConsentQuery: String? = null
    private var pendingCloudConsentLevel: PrivacyLevel = PrivacyLevel.UNKNOWN
    private var pendingCloudConsentCaptureEpoch: Int = 0

    private var speechRate = 1.05f
    private var speechPitch = 0.90f
    private var systemPrompt = ""
    private var isHeadsetOnlyMode = false

    private val wakeKeywords = WakeWordExtractor.DEFAULT_WAKE_WORDS

    init {
        initToneGenerator()
        observePipelines()
    }

    private fun initToneGenerator() {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85)
        } catch (e: Exception) {
            Log.e(TAG, "initToneGenerator: не удалось инициализировать ToneGenerator", e)
        }
    }

    private fun observePipelines() {
        observeSettings()
        observeHeadsetPlugging()
        observeWakeEngine()
        observeSpeechRecognizer()
        observeTtsEngine()
        observeSpeakingWatchdog()
        observePermissionOutcomes()
    }

    /** Голосовой запрос ждёт ответа на системное окно разрешений. */
    @Volatile
    private var awaitingPermissionOutcome = false
    private var awaitingExpiryJob: Job? = null

    /**
     * Начало обработки текущей реплики: запрос разрешения, сделанный ДО неё
     * (другой командой), не должен подменять ответ на эту.
     */
    @Volatile
    private var commandStartedAtMs = 0L

    /** Новая реплика: ожидание итога прошлого запроса доступа больше не актуально. */
    private fun beginCommand() {
        commandStartedAtMs = System.currentTimeMillis()
        awaitingPermissionOutcome = false
        awaitingExpiryJob?.cancel()
    }

    /**
     * Инструмент упёрся в разрешение: вместо сухого ответа инструмента говорим,
     * что разрешить и где. Экран закрыт: окно показать нельзя, зовём
     * уведомлением (его тап запускает приложение законно). Подсказка привязана
     * к запросу, сделанному при обработке этой реплики ([sinceMs]); ожидание
     * итога само гаснет через срок годности повтора.
     */
    private fun permissionHintOr(text: String, sinceMs: Long = commandStartedAtMs): String {
        val requested = PermissionPrompts.consumeRecent(System.currentTimeMillis(), sinceMs) ?: return text
        awaitingPermissionOutcome = true
        awaitingExpiryJob?.cancel()
        awaitingExpiryJob = scope.launch {
            delay(PermissionPrompts.RETRY_TTL_MS)
            awaitingPermissionOutcome = false
        }
        val label = PermissionTexts.describe(context, requested)
        if (AppVisibility.isForeground) {
            return context.getString(R.string.omnix_perm_voice_request, label)
        }
        PermissionRequestNotifier.show(context, label)
        return context.getString(R.string.omnix_perm_voice_notification, label)
    }

    /** Итог запроса (повтор команды или отказ) озвучиваем, если команда была голосовой. */
    private fun observePermissionOutcomes() {
        scope.launch {
            PermissionPrompts.outcomes.collect { message ->
                if (!awaitingPermissionOutcome || !isServiceActive) return@collect
                awaitingPermissionOutcome = false
                speechRecognizerManager.stopListening()
                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                _assistantState.value = VoiceAssistantState.Speaking(message)
                _lastAnswer.value = message
                textToSpeechManager.speak(message, speechRate, speechPitch)
            }
        }
    }

    /**
     * Страж субтитров ответа: режим TTS_SPEAKING выходит только по событию
     * синтезатора «Done». Если оно не пришло (стрим дозвучал раньше, чем режим
     * сменился, или озвучку прервала система), текст ответа висел на экране
     * бесконечно. Страж возвращает ассистента в слушающий режим.
     */
    private fun observeSpeakingWatchdog() {
        scope.launch {
            _currentMode.collectLatest { mode ->
                if (mode != OrchestratorMode.TTS_SPEAKING) return@collectLatest
                val text = (_assistantState.value as? VoiceAssistantState.Speaking)?.answerText.orEmpty()
                var elapsed = 0L
                while (_currentMode.value == OrchestratorMode.TTS_SPEAKING) {
                    delay(SubtitleLifecycle.WATCHDOG_POLL_MS)
                    elapsed += SubtitleLifecycle.WATCHDOG_POLL_MS
                    val ttsState = textToSpeechManager.ttsState.value
                    val engaged = SubtitleLifecycle.isTtsEngaged(
                        stateSpeaking = ttsState is TtsState.Speaking || ttsState is TtsState.Initializing,
                        utterancesPending = textToSpeechManager.isBusy()
                    )
                    if (SubtitleLifecycle.isSpeakingStuck(elapsed, engaged, text)) {
                        Log.w(TAG, "speaking watchdog: no TTS completion event, releasing subtitles")
                        // Жёсткий лимит при всё ещё занятом синтезаторе: глушим его,
                        // иначе голос зазвучит поверх прослушивания.
                        if (SubtitleLifecycle.shouldStopTtsBeforeListening(engaged)) textToSpeechManager.stop()
                        openContinuousConversationWindow()
                        return@collectLatest
                    }
                }
            }
        }
    }

    fun startServicePipeline() {
        // H-04: защита от двойного/повторного старта при прямом вызове
        // (например из ManualWakeWordTrigger до того, как сервис поднялся,
        // или при race между resumeAfterPhoneCall и onServiceConnected).
        if (isServiceActive) {
            Log.d(TAG, "startServicePipeline: already active — skip")
            return
        }

        if (!orchestratorJob.isActive) {
            orchestratorJob = SupervisorJob()
            scope = CoroutineScope(Dispatchers.Main.immediate + orchestratorJob + exceptionHandler)
            observePipelines()
        }

        if (toneGenerator == null) initToneGenerator()
        isServiceActive = true
        enterActivePipeline()
    }

    /**
     * Маршрут звука и вход в STANDBY для уже включённого ассистента. Отдельно от
     * [startServicePipeline], чтобы возврат после звонка не упирался в защиту
     * от повторного старта (раньше resumeAfterPhoneCall оставлял ассистента на паузе).
     */
    private fun enterActivePipeline() {
        bluetoothAudioRouter.checkHeadsetConnection()

        if (isHeadsetOnlyMode && !bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error(context.getString(R.string.podklyuchite_naushniki_dlya_raboty))
            return
        }

        if (bluetoothAudioRouter.isHeadsetConnected()) {
            bluetoothAudioRouter.routeAudioToEarbud()
        } else {
            bluetoothAudioRouter.routeAudioToSpeaker()
        }
        startStandbyMode()
    }

    fun stopServicePipeline() {
        isServiceActive = false
        stopAll()
        // EAR-MODE (speaker): на паузе/сне не держим коммуникационный
        // аудиорежим — медиа возвращаются к обычному маршруту (на наушники
        // A2DP придет сам, без SCO).
        bluetoothAudioRouter.routeAudioToSpeaker()
        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
        _assistantState.value = VoiceAssistantState.Idle
    }

    private fun observeHeadsetPlugging() {
        scope.launch {
            bluetoothAudioRouter.isHeadsetPlugged.collectLatest { isPlugged ->
                if (isServiceActive) {
                    if (isPlugged) {
                        bluetoothAudioRouter.routeAudioToEarbud()
                        playWakeChime()
                        startStandbyMode()
                    } else {
                        bluetoothAudioRouter.routeAudioToSpeaker()
                        if (isHeadsetOnlyMode) {
                            stopAll()
                            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
                            _assistantState.value = VoiceAssistantState.Error(context.getString(R.string.naushniki_otklyucheny_ozhidanie))
                        } else {
                            // Потеря аудиопути: недетектированную до этого
                            // patience-серию сбрасываем явно (owner review
                            // 2026-09-26, п.6) — «Omni», ударенное в старой
                            // акустике, не должно подтвердиться в новой.
                            wakeWordEngine.resetDetectionSeries()
                            startStandbyMode()
                        }
                    }
                }
            }
        }
    }

    private fun observeSettings() {
        scope.launch {
            getSettingsUseCase().collectLatest { settings ->
                speechRate = settings.speechRate
                // Голос OMNIX зафиксирован: тембр из настроек больше не применяется.
                systemPrompt = settings.systemPrompt
                isHeadsetOnlyMode = settings.isHeadsetOnlyMode
            }
        }
    }

    private fun observeWakeEngine() {
        scope.launch {
            wakeWordEngine.detections.collectLatest { detection ->
                wakeRetryAttempt = 0
                // Единственная точка маршрутизации wake-детекта (чистая логика,
                // JVM-тест): STANDBY — обычное слушание; AI_THINKING — барридж-ин.
                when (resolveWakeDetectionAction(_currentMode.value)) {
                    WakeDetectionAction.START_LISTENING -> {
                        if (!isHeadsetOnlyMode || bluetoothAudioRouter.isHeadsetConnected()) {
                            onWakeWordDetected(detection)
                        }
                    }
                    WakeDetectionAction.CANCEL_THINKING_AND_LISTEN ->
                        onWakeWordDetectedDuringThinking(detection)
                    WakeDetectionAction.IGNORE -> Unit
                }
            }
        }
        scope.launch {
            wakeWordEngine.errors.collectLatest { error ->
                Log.e(TAG, "wakeword engine error: $error")
                // Любая ошибка движка останавливает распознавание «Omni»:
                // повреждённая модель, мёртвый или занятый микрофон, сбой
                // инференса. Раньше сбой инференса и занятый микрофон не
                // показывались — OMNIX выглядел включённым, но не слышал.
                _assistantState.value = VoiceAssistantState.Error(
                    context.getString(R.string.wakeword_engine_unavailable),
                )
                // Занятый/«мёртвый» микрофон и сбой инференса часто проходят сами
                // (закончился чужой звонок, вернули доступ к микрофону): пробуем
                // снова, пока ассистент включён. Битая или отсутствующая модель
                // сама не починится — её не перезапускаем.
                if (error !is WakeWordEngineError.ModelMissing && error !is WakeWordEngineError.ModelCorrupted) {
                    scheduleWakeRetry()
                }
            }
        }
    }

    private var wakeRetryJob: Job? = null
    private var wakeRetryAttempt = 0

    private fun scheduleWakeRetry() {
        val delayMs = WAKE_RETRY_DELAYS_MS[minOf(wakeRetryAttempt, WAKE_RETRY_DELAYS_MS.lastIndex)]
        wakeRetryAttempt++
        wakeRetryJob?.cancel()
        wakeRetryJob = scope.launch {
            delay(delayMs)
            if (isServiceActive && _currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD && !wakeWordEngine.isRunning()) {
                Log.i(TAG, "wakeword engine retry #$wakeRetryAttempt after ${delayMs}ms")
                _assistantState.value = VoiceAssistantState.Idle
                wakeWordEngine.start()
            }
        }
    }

    fun startStandbyMode() {
        if (!isServiceActive) return

        if (isHeadsetOnlyMode && !bluetoothAudioRouter.isHeadsetConnected()) {
            _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
            _assistantState.value = VoiceAssistantState.Error(context.getString(R.string.podklyuchite_naushniki))
            return
        }

        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        stopLiveSession()
        confirmationTimeoutJob = null
        silenceJob?.cancel()
        silenceJob = null

        // CR-07: отмена активного AI-запроса при уходе в STANDBY. Инкрементируем
        // эпоху — старый aiJob, даже если он вернётся после IO, не сможет
        // мутировать UI/TTS состояние (см. проверку captureEpoch ниже).
        sessionEpoch.incrementAndGet()
        aiJob?.cancel()
        aiJob = null
        pendingToolCall = null
        pendingConfirmationToken = null
        pendingConfirmationPrompt = ""
        _currentToolCall.value = null
        _confirmationPrompt.value = null
        _lastToolResult.value = null
        // C-02: при уходе в standby сбрасываем и privacy-consent.
        pendingCloudConsentQuery = null
        pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
        pendingCloudConsentCaptureEpoch = 0
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)

        // EAR-MODE (speaker): возврат в STANDBY — это и есть «return idle» для
        // аудио: коммуникационный режим/SCO не живут дольше сессии переводчика.
        bluetoothAudioRouter.restoreDefaultRouting()

        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
        _assistantState.value = VoiceAssistantState.Idle
        speechRecognizerManager.stopListening()
        wakeWordEngine.start()
    }

    /**
     * Синхронный перевод в ухо: собеседник говорит на [sourceLang], перевод на язык приложения
     * звучит кусками по ходу его речи (не дожидаясь конца фразы).
     *
     * Звук идёт обычным путём (A2DP): слушает микрофон телефона, а не наушника — наушник в ухе
     * владельца слышит его самого, а не собеседника. Без наушника перевод только на экране:
     * голос из динамика микрофон услышал бы и стал «переводить» обратно.
     */
    fun startLiveEarInterpreter(sourceLang: String = liveSourceLang, mic: LiveMicMode? = liveMicOverride) {
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        translationJob?.cancel()
        translationJob = null
        stopLiveSession()
        wakeWordEngine.stop()
        speechRecognizerManager.stopListening()

        liveSourceLang = sourceLang
        liveMicOverride = mic
        val appLang = AppLanguage.current().code
        liveTargetLang = if (appLang != sourceLang) appLang else if (sourceLang == "ru") "en" else "ru"
        val source = sourceLang
        val target = liveTargetLang
        val session = LiveEarSession(
            scope = scope,
            translate = { fragment, previous -> translatorEngine.translateFragment(fragment, previous, source, target) },
            speak = { text ->
                if (bluetoothAudioRouter.isHeadsetConnected()) {
                    textToSpeechManager.speak(
                        text,
                        LIVE_SPEECH_RATE,
                        speechPitch,
                        TextToSpeech.QUEUE_ADD,
                        stream = if (liveSpeakViaCall) AudioManager.STREAM_VOICE_CALL else null
                    )
                }
            },
            describeFailure = translatorEngine::describeFailure
        )
        liveSession = session
        liveSessionViewJob = scope.launch {
            session.view.collect { view ->
                _lastQuery.value = view.source
                _lastAnswer.value = view.error ?: view.translation
            }
        }

        _currentMode.value = OrchestratorMode.LIVE_EAR_INTERPRETER
        bluetoothAudioRouter.routeAudioToSpeaker()
        val msg = context.getString(
            if (bluetoothAudioRouter.isHeadsetConnected()) R.string.live_ear_started else R.string.live_ear_started_no_headset,
            TranslationLanguages.displayName(sourceLang)
        )
        _assistantState.value = VoiceAssistantState.Speaking(msg)
        liveListenPending = true
        textToSpeechManager.speak(msg, speechRate, speechPitch)
    }

    /** Язык распознавания речи собеседника (локаль Android, например en-US). */
    private fun liveRecognitionTag(): String =
        TranslationLanguages.SUPPORTED.firstOrNull { it.code == liveSourceLang }?.localeTag ?: "en-US"

    /**
     * Запуск распознавания речи собеседника. На Android 13+ звук берётся своим захватом
     * ([LiveAudioCapture]: телефон, наушник или оба) и подаётся распознавателю каналом; на
     * старых версиях распознаватель слушает сам (микрофон наушника — через режим гарнитуры).
     */
    private fun startLiveListening() {
        liveListenPending = false
        wakeWordEngine.stop()
        stopLiveCapture()
        liveHeardSomething = false
        val requested = liveMicOverride ?: LiveMicSettings.get(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            val earbud = requested == LiveMicMode.EARBUD && bluetoothAudioRouter.isHeadsetConnected()
            if (earbud) bluetoothAudioRouter.routeAudioToEarbud() else bluetoothAudioRouter.routeAudioToSpeaker()
            liveSpeakViaCall = earbud
            if (requested == LiveMicMode.BOTH && bluetoothAudioRouter.isHeadsetConnected()) {
                announceLive(R.string.live_ear_dual_unavailable)
            }
            speechRecognizerManager.startListening(liveRecognitionTag(), continuous = true)
            return
        }
        val capture = LiveAudioCapture(context, bluetoothAudioRouter)
        val feeder = PcmPipeFeeder()
        liveCapture = capture
        liveFeeder = feeder
        liveTtsJob = scope.launch {
            // Во время перевода в ухо смеситель отсекает наушник строже (эхо перевода).
            textToSpeechManager.ttsState.collect { capture.mixer.ttsActive = it is TtsState.Speaking }
        }
        liveCaptureJob = scope.launch {
            feeder.start()
            // Открытие микрофонов не прерывается на полпути: иначе запись осталась бы открытой.
            val started = withContext(NonCancellable + Dispatchers.IO) { capture.start(requested) { feeder.push(it) } }
            if (_currentMode.value != OrchestratorMode.LIVE_EAR_INTERPRETER || liveCapture !== capture) {
                withContext(NonCancellable + Dispatchers.IO) { capture.stop() }
                feeder.stop()
                return@launch
            }
            if (started == null) {
                // Свой захват не открылся: распознаватель слушает сам.
                stopLiveCapture()
                speechRecognizerManager.startListening(liveRecognitionTag(), continuous = true)
                return@launch
            }
            liveSpeakViaCall = capture.speaksThroughHeadset
            if (started.fellBack && requested != LiveMicMode.PHONE && bluetoothAudioRouter.isHeadsetConnected()) {
                announceLive(
                    if (requested == LiveMicMode.BOTH) R.string.live_ear_dual_unavailable else R.string.live_ear_earbud_unavailable
                )
            }
            speechRecognizerManager.startListening(
                liveRecognitionTag(),
                continuous = true,
                audioSource = { feeder.newSession() }
            )
            startLiveWatchdog(capture)
        }
    }

    /**
     * Если распознаватель не принимает наш звук (не все движки поддерживают
     * EXTRA_AUDIO_SOURCE), он молчит, хотя человек говорит. Через несколько секунд речи без
     * единого слова переключаемся на прямой микрофон телефона и честно говорим об этом.
     */
    private fun startLiveWatchdog(capture: LiveAudioCapture) {
        liveWatchdogJob?.cancel()
        liveWatchdogJob = scope.launch {
            while (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER && liveCapture === capture) {
                delay(LIVE_WATCHDOG_TICK_MS)
                if (liveHeardSomething) return@launch
                if (capture.voicedMs >= LIVE_WATCHDOG_VOICED_MS) {
                    Log.w(TAG, "recognizer ignores own audio source — falling back to direct microphone")
                    stopLiveCapture()
                    liveSpeakViaCall = false
                    announceLive(R.string.live_ear_own_audio_unsupported)
                    speechRecognizerManager.startListening(liveRecognitionTag(), continuous = true)
                    return@launch
                }
            }
        }
    }

    /**
     * Ошибка распознавателя: перезапускаем только его, а захват звука (и режим гарнитуры)
     * не трогаем — звук за время перезапуска ждёт в очереди канала.
     */
    private fun restartLiveRecognizer() {
        val feeder = liveFeeder
        if (liveCapture != null && feeder != null) {
            speechRecognizerManager.startListening(
                liveRecognitionTag(),
                continuous = true,
                audioSource = { feeder.newSession() }
            )
        } else {
            speechRecognizerManager.startListening(liveRecognitionTag(), continuous = true)
        }
    }

    /** Короткое сообщение в режиме перевода: на экран и голосом (тем же путём, что и перевод). */
    private fun announceLive(resId: Int) {
        val msg = context.getString(resId)
        _lastAnswer.value = msg
        textToSpeechManager.speak(
            msg,
            speechRate,
            speechPitch,
            TextToSpeech.QUEUE_ADD,
            stream = if (liveSpeakViaCall) AudioManager.STREAM_VOICE_CALL else null
        )
    }

    private fun stopLiveCapture() {
        liveWatchdogJob?.cancel()
        liveWatchdogJob = null
        liveCaptureJob?.cancel()
        liveCaptureJob = null
        liveTtsJob?.cancel()
        liveTtsJob = null
        liveCapture?.stop()
        liveCapture = null
        liveFeeder?.stop()
        liveFeeder = null
        liveSpeakViaCall = false
    }

    private fun stopLiveSession() {
        liveSession?.stop()
        liveSession = null
        liveSessionViewJob?.cancel()
        liveSessionViewJob = null
        liveListenPending = false
        stopLiveCapture()
    }

    /** OBSERVABILITY: request id текущей голосовой реплики. */
    @Volatile
    private var voiceRequestId: String = RequestIds.newId()

    /** Voice Latency: момент детекта wake-word (monotonic clock). */
    private var wakeDetectedAtMs: Long = 0L

    /** Voice Latency: момент финального STT-результата текущего запроса. */
    private var sttFinalAtMs: Long = 0L

    /**
     * Neural wake word подтверждён движком — STT-верификация больше не нужна:
     * сразу chime и слушаем КОМАНДУ. Микрофон: движок останавливается ПЕРЕД
     * chime и STT — двух захватов нет по построению.
     */
    private fun onWakeWordDetected(detection: WakeWordDetection) {
        wakeWordEngine.stop()
        // Voice Latency: сегмент «Wake → STT» начинается меткой детекции
        // (elapsedRealtime, те же часы, что latencyMetrics.nowMs()).
        wakeDetectedAtMs = detection.timestampMs
        _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
        _assistantState.value = VoiceAssistantState.Listening
        playWakeChime()
        Log.i(
            TAG,
            "wakeword '${detection.wakeWord}' score=${detection.score} " +
                "infer=${detection.inferenceLatencyMs}ms",
        )
        speechRecognizerManager.startListening()
    }

    /**
     * Tap on the Core (design v2). From standby it takes the same path as a
     * confirmed wake word; while OMNIX is listening for a query it goes back
     * to standby. Any other mode is left alone, so a tap never interrupts an
     * answer, a pending confirmation or the live interpreter.
     */
    fun toggleListeningFromUi() {
        if (!isServiceActive) return
        when (_currentMode.value) {
            OrchestratorMode.STANDBY_WAKE_WORD -> {
                playWakeChime()
                switchToSpeechRecognition()
            }
            OrchestratorMode.LISTENING_USER_QUERY -> startStandbyMode()
            else -> Unit
        }
    }

    private fun switchToSpeechRecognition() {
        wakeWordEngine.stop()
        wakeDetectedAtMs = latencyMetrics.nowMs()
        _currentMode.value = OrchestratorMode.LISTENING_USER_QUERY
        _assistantState.value = VoiceAssistantState.Listening
        speechRecognizerManager.startListening()
    }

    private fun observeSpeechRecognizer() {
        scope.launch {
            speechRecognizerManager.speechState.collectLatest { event ->
                when (event) {
                    is SpeechRecognitionEvent.PartialResult -> {
                        if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            liveHeardSomething = true
                            _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                            liveSession?.onPartial(event.partialText)
                            return@collectLatest
                        }
                        if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                            val cleaned = WakeWordExtractor.extractQuery(AsrPostProcessor.process(event.partialText), wakeKeywords)
                                ?: event.partialText
                            _assistantState.value = VoiceAssistantState.Recognizing(event.partialText)
                            _lastQuery.value = cleaned

                            silenceJob?.cancel()
                            silenceJob = scope.launch {
                                delay(SILENCE_AFTER_PARTIAL_MS)
                                val current = _currentMode.value
                                val listening = current == OrchestratorMode.LISTENING_USER_QUERY ||
                                    current == OrchestratorMode.CONTINUOUS_CONVERSATION
                                if (listening && SubtitleLifecycle.shouldDropEmptyRecognition(cleaned)) {
                                    // Одно имя ассистента без команды: субтитр не оставляем.
                                    _assistantState.value = VoiceAssistantState.Listening
                                } else if (listening) {
                                    speechRecognizerManager.stopListening()
                                    processUserQuery(cleaned)
                                }
                            }
                        }
                    }
                    is SpeechRecognitionEvent.FinalResult -> {
                        // CR-01: игнорируем FinalResult, если мы не в слушающем режиме
                        // (STANDBY/AI_THINKING/TTS_SPEAKING/PAUSED). Приходит от старого
                        // распознавания после перехода на другой режим и провоцирует
                        // двойную обработку.
                        if (_currentMode.value !in ALLOWED_FINAL_RESULT_MODES) {
                            Log.d(TAG, "FinalResult ignored in mode=${_currentMode.value} (CR-01 guard)")
                            return@collectLatest
                        }

                        silenceJob?.cancel()
                        followUpWindowJob?.cancel()
                        val text = event.recognizedText.trim()

                        if (text.lowercase() in listOf("стоп", "хватит", "отмена", "джарвис стоп", "выйти")) {
                            handleCancel()
                            return@collectLatest
                        }

                        // Синхронный перевод в ухо: остаток фразы уходит в перевод, сессия
                        // распознавания перезапускается сама (continuous).
                        if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            if (LiveInterpretCommand.isStop(text)) {
                                handleCancel()
                                return@collectLatest
                            }
                            liveHeardSomething = true
                            _privacyClassification.value =
                                PrivacyClassifier.classifySafely(PrivacyContent(text))
                            liveSession?.onFinal(text)
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.AWAITING_PRIVACY_CONSENT) {
                            handlePrivacyConsentResponse(text)
                            return@collectLatest
                        }

                        if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                            handleConfirmationResponse(text)
                            return@collectLatest
                        }

                        // CR-02: вместо чистки с ifEmpty { raw } используем extractQuery,
                        // который возвращает null при пустом результате — не дёргаем
                        // processUserQuery с сырым wake-word.
                        val query = WakeWordExtractor.extractQuery(text, wakeKeywords)
                        if (query != null) {
                            processUserQuery(query)
                        } else {
                            // Прозвучало только имя ассистента — переходим в LISTENING
                            // за командой (без AI-вызова/TTS).
                            switchToSpeechRecognition()
                        }
                    }
                    is SpeechRecognitionEvent.RecognitionError -> {
                        silenceJob?.cancel()
                        if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            // В режиме переводчика продолжаем слушать дальше
                            delay(500)
                            if (_currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER) {
                                restartLiveRecognizer()
                            }
                        } else if (_currentMode.value == OrchestratorMode.LISTENING_USER_QUERY ||
                            _currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                            // Субтитр распознавания убираем сразу. Отложенный возврат в
                            // standby идёт отдельной корутиной: collectLatest отменял
                            // блок с delay при любом новом событии распознавателя, и
                            // ассистент навсегда оставался в Recognizing.
                            _assistantState.value = VoiceAssistantState.Listening
                            scope.launch {
                                delay(300)
                                val now = _currentMode.value
                                if (now == OrchestratorMode.LISTENING_USER_QUERY ||
                                    now == OrchestratorMode.CONTINUOUS_CONVERSATION
                                ) {
                                    startStandbyMode()
                                }
                            }
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun processUserQuery(query: String) {
        // Voice Latency: финальный STT — точка отсчёта «STT → Router»
        // (originTimestampMs протаскивается в ExecutionRequest) и конец
        // сегмента «Wake → STT».
        sttFinalAtMs = latencyMetrics.nowMs()

        // OBSERVABILITY: id запроса рождается здесь (начало «Voice» этапа) и
        // идёт через SendPromptUseCase → Router/Tool/AI → сервер — по нему
        // собирается весь путь одной реплики.
        voiceRequestId = RequestIds.newId()
        Log.d(TAG, "voice query accepted | requestId=$voiceRequestId | chars=${query.length}")
        if (wakeDetectedAtMs > 0L) {
            latencyMetrics.record(
                VoiceLatencyMetrics.VoiceStage.WAKE_TO_STT,
                sttFinalAtMs - wakeDetectedAtMs
            )
        }
        val clean = query.trim()
        if (clean.isBlank()) {
            _privacyClassification.value =
                PrivacyClassification.unknown(PrivacyReason.EMPTY_INPUT)
            startStandbyMode()
            return
        }
        // H-02: text-only быстрая метка для UI/логов (без systemPrompt/истории).
        // Полная контекстная классификация (prompt+systemPrompt+history) будет
        // сделана ровно один раз в SendPromptUseCase — мы не дублируем её здесь.
        val quickClassification = PrivacyClassifier.classifySafely(PrivacyContent(clean))
        _privacyClassification.value = quickClassification

        // Команда активации режима синхронного переводчика
        LiveInterpretCommand.parse(clean)?.let { source ->
            startLiveEarInterpreter(source, LiveInterpretCommand.parseMic(clean))
            return
        }

        if (!isProcessingQuery.compareAndSet(false, true)) {
            Log.d(
                TAG,
                "Query already processing; duplicate skipped | chars=${clean.length} | " +
                    "privacy=${quickClassification.level}"
            )
            return
        }

        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()

        _currentMode.value = OrchestratorMode.AI_THINKING
        _assistantState.value = VoiceAssistantState.Thinking
        _lastQuery.value = clean
        // Fresh query ⇒ the previous action display is stale: without this a
        // follow-up inside the conversation window would re-render the old
        // Executing/Success (the UI mapper keys off these two flows).
        _currentToolCall.value = null
        _lastToolResult.value = null

        aiJob?.cancel()
        // §13: новый запрос останавливает очередь TTS — недосказанное
        // от прошлого ответа не должно доигрываться поверх нового.
        textToSpeechManager.stop()
        // think-phase: движок живёт и в AI_THINKING (тёплая ONNX-сессия —
        // без перечитывания ассетов, см. «Холодный старт» в
        // docs/WAKEWORD_NEURAL.md): «Omni» во время раздумий = барридж-ин отмена.
        // Self-voice guard — в observeTtsEngine: на TtsState.Speaking движок
        // гасится (стриминг §13 запускает TTS, не меняя режим).
        wakeWordEngine.start()
        val captureEpoch = sessionEpoch.get()
        // §13: счётчик предложений, уже озвученных стримом (см. onSentence
        // ниже). Если > 0 — финальный speak целиком пропускаем, чтобы не
        // дублировать ответ.
        val streamedSentences = AtomicInteger(0)
        beginCommand()
        aiJob = scope.launch {
            try {
                // CR-07: если пользователь успел перейти в standby до отправки запроса,
                // выходим, не тратя ресурсы на AI-вызов.
                if (sessionEpoch.get() != captureEpoch) {
                    Log.d(TAG, "Discarding late AI result (epoch mismatch: $captureEpoch vs ${sessionEpoch.get()})")
                    return@launch
                }

                // H-02: передаём quick-уровень как hint — authoritative classification
                // сделает SendPromptUseCase (с контекстом systemPrompt+history) и вернёт
                // NeedsConsent при необходимости; мы не дублируем classifySafely здесь.
                val result = sendPromptUseCase(
                    clean,
                    source = RequestSource.VOICE,
                    privacyLevel = quickClassification.level,
                    originTimestampMs = sttFinalAtMs,
                    requestId = voiceRequestId,
                    // §13 ТЗ: TTS-стриминг — каждое готовое предложение
                    // локального ответа озвучивается сразу (QUEUE_ADD, без
                    // прерывания). Вызывается НЕ на Main — speakQueued
                    // потокобезопасен. Облако стрим не поддерживает:
                    // там onSentence не вызывается и ответ звучит целиком.
                    onSentence = { sentence ->
                        streamedSentences.incrementAndGet()
                        textToSpeechManager.speakQueued(sentence, speechRate, speechPitch)
                    }
                )
                val resultReceivedAt = latencyMetrics.nowMs()

                // CR-07: вторая точка проверки эпохи — после сетевого/AI-вызова.
                if (sessionEpoch.get() != captureEpoch) {
                    Log.d(TAG, "Discarding late AI result post-sendPrompt (epoch mismatch)")
                    return@launch
                }

                when (result) {
                    is Resource.Success -> {
                        when (val execution = result.data) {
                            is PromptExecutionResult.ConfirmationRequired -> {
                                pendingToolCall = execution.toolCall
                                pendingConfirmationPrompt = execution.promptMessage
                                // UI mirror: the pending call and its prompt.
                                _currentToolCall.value = execution.toolCall
                                _confirmationPrompt.value = execution.promptMessage
                                _lastToolResult.value = null
                                // CR-04: забираем вызов у CHAT_UI под управление голоса
                                // (чтобы «да» из TTS-потока не подтвердило чат-запрос),
                                // а токен берём ПО callId, а не с головы очереди.
                                toolExecutor.claimPendingConfirmation(
                                    execution.toolCall.callId,
                                    ConfirmationOwner.VOICE
                                )
                                pendingConfirmationToken =
                                    toolExecutor.confirmationTokenFor(execution.toolCall.callId)

                                _lastAnswer.value = pendingConfirmationPrompt
                                _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                                _assistantState.value = VoiceAssistantState.Speaking(pendingConfirmationPrompt)

                                textToSpeechManager.speak(pendingConfirmationPrompt, speechRate, speechPitch)

                                confirmationTimeoutJob?.cancel()
                                confirmationTimeoutJob = scope.launch {
                                    delay(CONFIRMATION_TIMEOUT_MS)
                                    // CR-07: таймер подтверждения тоже уважает эпоху.
                                    if (sessionEpoch.get() != captureEpoch) return@launch
                                    if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                                        val timeoutMsg = context.getString(R.string.vremya_ozhidaniya_isteklo)
                                        _lastAnswer.value = timeoutMsg
                                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                                        val timedOut = pendingToolCall
                                        pendingToolCall = null
                                        pendingConfirmationToken = null
                                        if (timedOut != null) toolExecutor.removePendingConfirmation(timedOut)
                                        delay(2000)
                                        startStandbyMode()
                                    }
                                }
                            }
                            is PromptExecutionResult.DirectAnswer -> {
                                val answer = permissionHintOr(execution.text)
                                _lastAnswer.value = answer
                                // Voice Latency «Tool → TTS»: от получения
                                // результата до вызова озвучки.
                                latencyMetrics.record(
                                    VoiceLatencyMetrics.VoiceStage.TOOL_TO_TTS,
                                    latencyMetrics.nowMs() - resultReceivedAt
                                )
                                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                                _assistantState.value = VoiceAssistantState.Speaking(answer)
                                // §13: стрим уже озвучил ответ — целиком не повторяем.
                                if (streamedSentences.get() == 0) {
                                    textToSpeechManager.speak(answer, speechRate, speechPitch)
                                }
                            }
                        }
                    }
                    // C-02: use case просит согласия на облачную отправку —
                    // переключаемся в режим ожидания голосового «да/нет».
                    is Resource.NeedsConsent -> {
                        pendingCloudConsentQuery = clean
                        pendingCloudConsentLevel = result.privacyLevel
                        pendingCloudConsentCaptureEpoch = captureEpoch

                        val voicePrompt = when (result.privacyLevel) {
                            PrivacyLevel.SENSITIVE -> context.getString(R.string.cloud_consent_voice_sensitive)
                            else -> context.getString(R.string.cloud_consent_voice_private)
                        }
                        _lastAnswer.value = voicePrompt
                        _currentMode.value = OrchestratorMode.AWAITING_PRIVACY_CONSENT
                        _assistantState.value = VoiceAssistantState.Speaking(voicePrompt)

                        textToSpeechManager.speak(voicePrompt, speechRate, speechPitch)

                        // Таймаут — как у tool-confirmation: N секунд и авто-отказ.
                        confirmationTimeoutJob?.cancel()
                        confirmationTimeoutJob = scope.launch {
                            delay(CONFIRMATION_TIMEOUT_MS)
                            if (sessionEpoch.get() != captureEpoch) return@launch
                            if (_currentMode.value == OrchestratorMode.AWAITING_PRIVACY_CONSENT) {
                                val timeoutMsg = context.getString(R.string.cloud_consent_timeout)
                                _lastAnswer.value = timeoutMsg
                                _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                                textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                                pendingCloudConsentQuery = null
                                pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                                pendingCloudConsentCaptureEpoch = 0
                                delay(2000)
                                startStandbyMode()
                            }
                        }
                    }
                    is Resource.Error -> {
                        val errorMsg = result.message ?: context.getString(R.string.oshibka_svyazi_s_ai)
                        _lastAnswer.value = "Ошибка: $errorMsg"
                        _currentMode.value = OrchestratorMode.STANDBY_WAKE_WORD
                        _assistantState.value = VoiceAssistantState.Error(errorMsg)
                        textToSpeechManager.speak("Ошибка: $errorMsg", speechRate, speechPitch)
                        delay(2500)
                        startStandbyMode()
                    }
                    is Resource.Loading -> {
                        _assistantState.value = VoiceAssistantState.Thinking
                    }
                }
            } finally {
                // CR-07: флаг сбрасываем только для «нашей» эпохи, чтобы не
                // сломать флаг нового сеанса, запущенного поверх отмены.
                if (sessionEpoch.get() == captureEpoch) {
                    isProcessingQuery.set(false)
                }
            }
        }
    }

    private fun handleConfirmationResponse(response: String) {
        // Единый источник распознавания «да/нет» — ConfirmationIntent
        // (общий для голосового флоу и текстового чата).
        handleConfirmationDecision(
            isYes = ConfirmationIntent.isYes(response),
            isNo = ConfirmationIntent.isNo(response)
        )
    }

    /**
     * Подтверждение действия, пришедшее из UI (кнопка в bottom sheet), а не
     * голосом. Спецификация фронтенда (§17) требует, чтобы подтвердить можно
     * было и голосом, и касанием; обе двери ведут в один и тот же код.
     */
    fun confirmPendingToolCallFromUi() {
        if (_currentMode.value != OrchestratorMode.AWAITING_CONFIRMATION) return
        handleConfirmationDecision(isYes = true, isNo = false)
    }

    /** Отмена ожидающего действия из UI. */
    fun cancelPendingToolCallFromUi() {
        if (_currentMode.value != OrchestratorMode.AWAITING_CONFIRMATION) return
        handleConfirmationDecision(isYes = false, isNo = true)
    }

    private fun handleConfirmationDecision(isYes: Boolean, isNo: Boolean) {
        confirmationTimeoutJob?.cancel()

        when {
            // N-02: захватываем pendingToolCall в локальную val ДО ветвления,
            // чтобы не использовать !! и не словить NPE между проверкой
            // `!= null` и smart-cast в многопоточном контексте.
            isYes -> {
                val callToExecute = pendingToolCall
                if (callToExecute == null) {
                    // Гонка: pending сбросили другим cancellation-path —
                    // возвращаемся в standby без краша.
                    startStandbyMode()
                    return
                }
                // Пункт аудита #5: сохраняем токен ДО сброса состояния.
                val tokenToUse = pendingConfirmationToken
                pendingToolCall = null
                pendingConfirmationToken = null
                // Пункт аудита #4: НЕ чистим всю очередь — executeWithBypass
                // извлечёт из неё именно этот вызов, остальные останутся ждать.

                _currentMode.value = OrchestratorMode.AI_THINKING
                _assistantState.value = VoiceAssistantState.Thinking
                // UI mirror: confirmation resolved, execution starts.
                _confirmationPrompt.value = null
                _currentToolCall.value = callToExecute

                scope.launch {
                    val confirmedStartedAtMs = System.currentTimeMillis()
                    val result = toolExecutor.executeWithBypass(
                        call = callToExecute,
                        confirmationToken = tokenToUse,
                        source = "voice_orchestrator"
                    )
                    _lastToolResult.value = result
                    val voiceResponse = when {
                        result.isSuccess -> "${result.summary}."
                        // Разрешение/системный UI/неподдерживаемая возможность —
                        // это не «ошибка выполнения», а понятное объяснение.
                        result.isBlockedByAndroid -> permissionHintOr(result.summary, confirmedStartedAtMs)
                        else -> "Не удалось выполнить: ${result.error ?: result.summary}"
                    }
                    _lastAnswer.value = voiceResponse
                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                    _assistantState.value = VoiceAssistantState.Speaking(voiceResponse)
                    textToSpeechManager.speak(voiceResponse, speechRate, speechPitch)

                    // Пункт аудита #4: следующий запрос подтверждения из очереди.
                    // Голос забирает его себе (owner=VOICE) и токен берёт по callId.
                    toolExecutor.peekPendingConfirmation()?.let { head ->
                        toolExecutor.claimPendingConfirmation(head.toolCall.callId, ConfirmationOwner.VOICE)
                        val next = toolExecutor.findPendingConfirmation(head.toolCall.callId) ?: head
                        pendingToolCall = next.toolCall
                        pendingConfirmationPrompt = next.promptMessage
                        _currentToolCall.value = next.toolCall
                        _confirmationPrompt.value = next.promptMessage
                        _lastToolResult.value = null
                        pendingConfirmationToken =
                            toolExecutor.confirmationTokenFor(next.toolCall.callId)
                        _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                        _assistantState.value = VoiceAssistantState.Speaking(next.promptMessage)
                        textToSpeechManager.speak(next.promptMessage, speechRate, speechPitch)
                    }
                }
            }
            isNo -> {
                val cancelled = pendingToolCall
                pendingToolCall = null
                pendingConfirmationToken = null
                if (cancelled != null) toolExecutor.removePendingConfirmation(cancelled)
                _confirmationPrompt.value = null
                _currentToolCall.value = null
                val cancelMsg = context.getString(R.string.operaciya_otmenena_sir)
                _lastAnswer.value = cancelMsg
                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                _assistantState.value = VoiceAssistantState.Speaking(cancelMsg)
                textToSpeechManager.speak(cancelMsg, speechRate, speechPitch)
            }
            else -> {
                val retryMsg = context.getString(R.string.ne_ponyal_skazhite_da_ili_net)
                _assistantState.value = VoiceAssistantState.Speaking(retryMsg)
                textToSpeechManager.speak(retryMsg, speechRate, speechPitch)

                confirmationTimeoutJob?.cancel()
                confirmationTimeoutJob = scope.launch {
                    delay(CONFIRMATION_TIMEOUT_MS)
                    if (_currentMode.value == OrchestratorMode.AWAITING_CONFIRMATION) {
                        val timeoutMsg = context.getString(R.string.vremya_ozhidaniya_isteklo)
                        _lastAnswer.value = timeoutMsg
                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                        val timedOut = pendingToolCall
                        pendingToolCall = null
                        pendingConfirmationToken = null
                        if (timedOut != null) toolExecutor.removePendingConfirmation(timedOut)
                        _confirmationPrompt.value = null
                        _currentToolCall.value = null
                        delay(2000)
                        startStandbyMode()
                    }
                }
            }
        }
    }

    /**
     * C-02: обработка голосового ответа «да/нет» на вопрос об отправке приватного
     * запроса в облако. Аналогично [handleConfirmationResponse], но вместо вызова
     * инструмента делает повторный вызов [SendPromptUseCase] с флагом
     * cloudExplicitlyAllowed=true.
     */
    private fun handlePrivacyConsentResponse(response: String) {
        confirmationTimeoutJob?.cancel()

        val isYes = ConfirmationIntent.isYes(response)
        val isNo = ConfirmationIntent.isNo(response)
        val consentQuery = pendingCloudConsentQuery
        val consentCaptureEpoch = pendingCloudConsentCaptureEpoch

        when {
            isYes -> {
                if (consentQuery == null) {
                    // Гонка — consent уже сброшен (таймаут / отмена / новая сессия).
                    startStandbyMode()
                    return
                }
                // Запоминаем уровень с пришедшего consent, чтобы не
                // пересчитывать классификатор на повторе (H-02), и сбрасываем
                // pending СРАЗУ перед запуском aiJob — чтобы гонка с новым
                // запросом не подхватила чужое состояние.
                val consentLevel = pendingCloudConsentLevel
                pendingCloudConsentQuery = null
                pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                pendingCloudConsentCaptureEpoch = 0

                _currentMode.value = OrchestratorMode.AI_THINKING
                _assistantState.value = VoiceAssistantState.Thinking

                aiJob?.cancel()
                // §13: повтор после consent — тоже новый запрос: очередь TTS
                // останавливаем, счётчик стрим-предложений заводим заново.
                textToSpeechManager.stop()
                // think-phase: то же, что в processUserQuery — движок жив в
                // AI_THINKING, self-voice guard тот же (TtsState.Speaking).
                wakeWordEngine.start()
                val consentStreamedSentences = AtomicInteger(0)

                aiJob = scope.launch {
                    // Важно: после «да» контекст/эпоха должны совпадать с той,
                    // в которой мы задавали вопрос. Если пользователь уже ушёл
                    // в standby — не выполняем ничего.
                    if (sessionEpoch.get() != consentCaptureEpoch) return@launch

                    // H-02: передаём сохранённый уровень как hint; authoritative
                    // classification с учётом systemPrompt+history будет сделана
                    // один раз в SendPromptUseCase.
                    val result = sendPromptUseCase(
                        userPrompt = consentQuery,
                        source = RequestSource.VOICE,
                        privacyLevel = consentLevel,
                        cloudExplicitlyAllowed = true,
                        // §13: тот же TTS-стриминг, что и в основном пути.
                        onSentence = { sentence ->
                            consentStreamedSentences.incrementAndGet()
                            textToSpeechManager.speakQueued(sentence, speechRate, speechPitch)
                        }
                    )
                    if (sessionEpoch.get() != consentCaptureEpoch) return@launch

                    when (result) {
                        is Resource.Success -> {
                            when (val execution = result.data) {
                                is PromptExecutionResult.ConfirmationRequired -> {
                                    // Может потребоваться подтверждение действия (звонок/SMS)
                                    // — делегируем в существующий confirmation-flow.
                                    pendingToolCall = execution.toolCall
                                    pendingConfirmationPrompt = execution.promptMessage
                                    toolExecutor.claimPendingConfirmation(
                                        execution.toolCall.callId,
                                        ConfirmationOwner.VOICE
                                    )
                                    pendingConfirmationToken =
                                        toolExecutor.confirmationTokenFor(execution.toolCall.callId)
                                    _lastAnswer.value = pendingConfirmationPrompt
                                    _currentMode.value = OrchestratorMode.AWAITING_CONFIRMATION
                                    _assistantState.value = VoiceAssistantState.Speaking(pendingConfirmationPrompt)
                                    textToSpeechManager.speak(pendingConfirmationPrompt, speechRate, speechPitch)
                                }
                                is PromptExecutionResult.DirectAnswer -> {
                                    _lastAnswer.value = execution.text
                                    _currentMode.value = OrchestratorMode.TTS_SPEAKING
                                    _assistantState.value = VoiceAssistantState.Speaking(execution.text)
                                    // §13: стрим уже озвучил ответ — целиком не повторяем.
                                    if (consentStreamedSentences.get() == 0) {
                                        textToSpeechManager.speak(execution.text, speechRate, speechPitch)
                                    }
                                }
                            }
                        }
                        is Resource.NeedsConsent -> {
                            // Не должно случиться (cloudExplicitlyAllowed=true),
                            // но fail-safe — возвращаемся в standby.
                            startStandbyMode()
                        }
                        is Resource.Error -> {
                            val err = result.message ?: context.getString(R.string.oshibka_svyazi_s_ai)
                            _lastAnswer.value = "Ошибка: $err"
                            _assistantState.value = VoiceAssistantState.Error(err)
                            textToSpeechManager.speak("Ошибка: $err", speechRate, speechPitch)
                            delay(2500)
                            startStandbyMode()
                        }
                        is Resource.Loading -> Unit
                    }
                }
            }
            isNo -> {
                pendingCloudConsentQuery = null
                pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                pendingCloudConsentCaptureEpoch = 0
                val declineMsg = context.getString(R.string.cloud_consent_declined)
                _lastAnswer.value = declineMsg
                _currentMode.value = OrchestratorMode.TTS_SPEAKING
                _assistantState.value = VoiceAssistantState.Speaking(declineMsg)
                textToSpeechManager.speak(declineMsg, speechRate, speechPitch)
            }
            else -> {
                val retryMsg = context.getString(R.string.ne_ponyal_skazhite_da_ili_net)
                _assistantState.value = VoiceAssistantState.Speaking(retryMsg)
                textToSpeechManager.speak(retryMsg, speechRate, speechPitch)

                confirmationTimeoutJob?.cancel()
                confirmationTimeoutJob = scope.launch {
                    delay(CONFIRMATION_TIMEOUT_MS)
                    if (sessionEpoch.get() != consentCaptureEpoch) return@launch
                    if (_currentMode.value == OrchestratorMode.AWAITING_PRIVACY_CONSENT) {
                        val timeoutMsg = context.getString(R.string.cloud_consent_timeout)
                        _lastAnswer.value = timeoutMsg
                        _assistantState.value = VoiceAssistantState.Speaking(timeoutMsg)
                        textToSpeechManager.speak(timeoutMsg, speechRate, speechPitch)
                        pendingCloudConsentQuery = null
                        pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
                        pendingCloudConsentCaptureEpoch = 0
                        delay(2000)
                        startStandbyMode()
                    }
                }
            }
        }
    }

    private fun observeTtsEngine() {
        scope.launch {
            textToSpeechManager.ttsState.collectLatest { ttsState ->
                when (ttsState) {
                    is TtsState.Speaking -> {
                        // Self-voice guard (think-phase): собственное аудио OMNIX
                        // не должно попадать в wake-движок — «Omni» из уст
                        // ассистента = self-trigger. Одна точка покрывает все
                        // места старта TTS, включая стриминг §13 (speakQueued
                        // при режиме, который всё ещё AI_THINKING).
                        wakeWordEngine.stop()
                    }
                    is TtsState.Done -> {
                        when (_currentMode.value) {
                            OrchestratorMode.TTS_SPEAKING -> {
                                openContinuousConversationWindow()
                            }
                            OrchestratorMode.AWAITING_CONFIRMATION -> {
                                wakeWordEngine.stop()
                                speechRecognizerManager.startListening()
                            }
                            // C-02: после фразы «отправить в облако?» начинаем слушать ответ.
                            OrchestratorMode.AWAITING_PRIVACY_CONSENT -> {
                                wakeWordEngine.stop()
                                speechRecognizerManager.startListening()
                            }
                            OrchestratorMode.LIVE_EAR_INTERPRETER -> {
                                // Распознавание идёт параллельно с переводом в ухо и не
                                // перезапускается после каждого куска (иначе терялись бы слова).
                                // Запускаем его один раз — после фразы о включении режима.
                                if (liveListenPending) startLiveListening()
                            }
                            OrchestratorMode.STANDBY_WAKE_WORD -> resumeWakeWordAfterSpeech()
                            else -> Unit
                        }
                    }
                    // Речь прервана (stop) — тоже конец фразы.
                    is TtsState.Ready -> resumeWakeWordAfterSpeech()
                    is TtsState.Error -> {
                        if (_currentMode.value != OrchestratorMode.LIVE_EAR_INTERPRETER) {
                            startStandbyMode()
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    /**
     * Фраза закончилась, а ассистент ждёт «Omni»: движок, остановленный на
     * время речи, включаем обратно. Синтезатор общий — ответы чата и
     * автоматизаций тоже озвучиваются, — и раньше после такой фразы движок
     * оставался выключенным: режим «жду Omni», но никто не слушал
     * (отчёт владельца 2026-10-21, v305).
     */
    private fun resumeWakeWordAfterSpeech() {
        if (isServiceActive && _currentMode.value == OrchestratorMode.STANDBY_WAKE_WORD) {
            wakeWordEngine.start()
        }
    }

    private fun openContinuousConversationWindow() {
        if (!isServiceActive) return
        _currentMode.value = OrchestratorMode.CONTINUOUS_CONVERSATION
        _assistantState.value = VoiceAssistantState.Idle
        wakeWordEngine.stop()
        speechRecognizerManager.startListening()

        followUpWindowJob?.cancel()
        followUpWindowJob = scope.launch {
            delay(FOLLOW_UP_WINDOW_MS)
            if (_currentMode.value == OrchestratorMode.CONTINUOUS_CONVERSATION) {
                startStandbyMode()
            }
        }
    }

    /**
     * Отмена in-flight голосового взаимодействия (без chime и без возврата в
     * STANDBY — продолжение определяет вызывающий): эпоха CR-07 инвалидирует
     * поздние результаты отменённого aiJob, TTS-очередь сбрасывается (§13),
     * pending-состояния чистятся.
     */
    private fun cancelInFlightInteraction() {
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        // CR-07: инкремент эпохи — поздний результат aiJob (даже «вернувшийся»
        // после отмены) отбрасывается обеими проверками эпохи и не мутирует
        // контур.
        sessionEpoch.incrementAndGet()
        pendingToolCall = null
        pendingConfirmationToken = null
        pendingConfirmationPrompt = ""
        pendingCloudConsentQuery = null
        pendingCloudConsentLevel = PrivacyLevel.UNKNOWN
        pendingCloudConsentCaptureEpoch = 0
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)
        aiJob?.cancel()
        aiJob = null
        // §13: очередь TTS сбрасываем (стриминг / недосказанный ответ).
        textToSpeechManager.stop()
        _currentToolCall.value = null
        _confirmationPrompt.value = null
        _lastToolResult.value = null
    }

    private fun handleCancel() {
        cancelInFlightInteraction()
        playCancelChime()
        startStandbyMode()
    }

    /**
     * «Omni» в фазе AI_THINKING — голосовой barge-in: отменяем in-flight
     * запрос и СРАЗУ слушаем новую команду (пользователь сказал wake word,
     * чтобы сказать что-то новое — повторять его не заставляем). Chime —
     * wake, а не cancel: для пользователя это сработавший wake word.
     * Движок гасится в switchToSpeechRecognition (STT забирает микрофон).
     */
    private fun onWakeWordDetectedDuringThinking(detection: WakeWordDetection) {
        Log.i(
            TAG,
            "wakeword '${detection.wakeWord}' in AI_THINKING: cancelling in-flight " +
                "query, listening for new command (score=${detection.score} " +
                "infer=${detection.inferenceLatencyMs}ms)",
        )
        cancelInFlightInteraction()
        playWakeChime()
        switchToSpeechRecognition()
    }

    private val wakeChime by lazy { SoftChime.wake() }
    private val cancelChime by lazy { SoftChime.cancel() }

    private fun playWakeChime() {
        try {
            SoftChime.play(wakeChime)
            Haptics.tick(context)
        } catch (e: Exception) {
            Log.e(TAG, "playWakeChime: не удалось проиграть звук", e)
        }
    }

    private fun playCancelChime() {
        try {
            SoftChime.play(cancelChime)
        } catch (e: Exception) {
            Log.e(TAG, "playCancelChime: не удалось проиграть звук", e)
        }
    }

    /**
     * EAR-MODE (phone call): был ли активен синхронный переводчик на момент
     * звонка — чтобы resumeAfterPhoneCall вернул именно его, а не молча
     * сбросил пользователя в wake-word STANDBY.
     */
    @Volatile
    private var interpreterActiveBeforeCall = false

    fun pauseForPhoneCall() {
        interpreterActiveBeforeCall = _currentMode.value == OrchestratorMode.LIVE_EAR_INTERPRETER
        stopAll()
        _currentMode.value = OrchestratorMode.PAUSED_CALL_OR_SLEEP
    }

    fun resumeAfterPhoneCall() {
        if (isServiceActive) {
            if (interpreterActiveBeforeCall) {
                interpreterActiveBeforeCall = false
                Log.d(TAG, "resumeAfterPhoneCall: restoring LIVE_EAR_INTERPRETER")
                startLiveEarInterpreter()
            } else {
                enterActivePipeline()
            }
        }
    }

    fun stopAll() {
        silenceJob?.cancel()
        followUpWindowJob?.cancel()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        // CR-22: отменяем in-flight перевод при любом останове.
        translationJob?.cancel()
        translationJob = null
        stopLiveSession()

        sessionEpoch.incrementAndGet()
        aiJob?.cancel()
        aiJob = null
        pendingToolCall = null
        pendingConfirmationPrompt = ""
        pendingConfirmationToken = null
        toolExecutor.clearPendingConfirmation()
        isProcessingQuery.set(false)
        wakeWordEngine.stop()
        speechRecognizerManager.stopListening()
        textToSpeechManager.stop()
    }

    /**
     * Служба остановлена (ассистент выключен). Останавливаем всё, но общие
     * компоненты НЕ уничтожаем: распознавание, озвучка и роутер наушников —
     * синглтоны на весь процесс, и после destroy() восстановиться они не умели.
     * Раньше ВЫКЛ→ВКЛ без перезапуска приложения оставлял OMNIX глухим: фраза
     * срабатывала, а STT молча не стартовал (отзыв владельца на v344).
     */
    fun destroy() {
        stopAll()
        isServiceActive = false
        orchestratorJob.cancel()
        runCatching { wakeWordEngine.destroy() }
            .onFailure { Log.e(TAG, "destroy: wakeWordEngine.destroy() failed", it) }
        // Звонковый режим и SCO не должны пережить выключенного ассистента.
        runCatching { bluetoothAudioRouter.routeAudioToSpeaker() }
            .onFailure { Log.e(TAG, "destroy: routeAudioToSpeaker() failed", it) }
        try {
            toneGenerator?.release()
        } catch (e: Exception) {
            Log.e(TAG, "releaseToneGenerator: не удалось освободить ToneGenerator", e)
        }
        toneGenerator = null
    }
}
