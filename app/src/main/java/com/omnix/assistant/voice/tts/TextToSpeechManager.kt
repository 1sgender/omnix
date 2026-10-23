package com.omnix.assistant.voice.tts

import com.omnix.assistant.core.locale.AppLanguage
import com.omnix.assistant.core.locale.SpeechLanguage
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TtsState {
    data object Idle : TtsState
    data object Initializing : TtsState
    data object Ready : TtsState
    data object Speaking : TtsState
    data object Done : TtsState
    data object Error : TtsState
}

/**
 * Text-to-Speech Manager v2.0
 * 
 * Управляет синтезом речи с поддержкой:
 * - Настройки скорости и высоты голоса
 * - Очереди utterances
 * - Состояния воспроизведения через StateFlow
 * - Корректного shutdown при уничтожении
 */
@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TtsManager"
        private const val UTTERANCE_ID_PREFIX = "omnix_tts_"
        private const val INIT_TIMEOUT_MS = 4000L

        /**
         * EAR-MODE (audio focus): реакция на чужой audio focus. LOSS (фокус
         * забрали насовсем — навигация, другой плеер) — останавливаем речь;
         * LOSS_TRANSIENT / LOSS_TRANSIENT_CAN_DUCK (звонок ожидания, короткая
         * чужая подсказка) — продолжаем: прерывать шёпот перевода из-за
         * секундного чужого звука хуже, чем он его перекроет. JVM-тестируется
         * напрямую (TextToSpeechFocusPolicyTest).
         */
        enum class FocusReaction { STOP_SPEECH, CONTINUE }

        fun mapFocusChange(change: Int): FocusReaction =
            if (change == AudioManager.AUDIOFOCUS_LOSS) FocusReaction.STOP_SPEECH
            else FocusReaction.CONTINUE
    }

    private val audioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var holdingFocus = false

    /**
     * EAR-MODE: число не доигравших utterance. Фокус держим, пока > 0 —
     * QUEUE_ADD переводчика не должен терять duck между фразами диалога.
     */
    private val activeUtterances = java.util.concurrent.atomic.AtomicInteger(0)

    private val disposed = java.util.concurrent.atomic.AtomicBoolean(false)

    // CR-13/CR-24: именованный SupervisorJob + CoroutineExceptionHandler,
    // чтобы можно было безопасно отменить scope на shutdown().
    private val ttsJob = SupervisorJob()
    private val exceptionHandler = CoroutineExceptionHandler { _, t ->
        Log.e(TAG, "uncaught exception in tts scope", t)
    }
    private val scope = CoroutineScope(
        Dispatchers.Main.immediate + ttsJob + exceptionHandler
    )

    private var tts: TextToSpeech? = null
    private var utteranceCounter = 0L
    private var initializationGeneration = 0L

    /**
     * CR-09: буфер для фразы, которая поступила до готовности движка. Играется
     * ровно один раз сразу после успешной инициализации.
     *
     * Структура: (text, speechRate, pitch, queueMode).
     */
    @Volatile
    private var pendingUtterance: PendingUtterance? = null

    /** CR-09: защита от гонок между concurrent speak()/init(). */
    private val speakMutex = Mutex()

    private val _ttsState = MutableStateFlow<TtsState>(TtsState.Idle)
    val ttsState: StateFlow<TtsState> = _ttsState.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private data class PendingUtterance(
        val text: String,
        val speechRate: Float,
        val pitch: Float,
        val queueMode: Int,
        val stream: Int? = null
    )

    init {
        initializeTts()
    }

    private fun initializeTts() {
        if (_ttsState.value == TtsState.Initializing) return
        _ttsState.value = TtsState.Initializing
        val generation = ++initializationGeneration
        var candidate: TextToSpeech? = null

        // CR-09: таймаут инициализации — не молчим бесконечно.
        scope.launch {
            delay(INIT_TIMEOUT_MS)
            if (generation == initializationGeneration && _ttsState.value == TtsState.Initializing) {
                Log.e(TAG, "TTS init timed out after ${INIT_TIMEOUT_MS}ms")
                _isInitialized.value = false
                _ttsState.value = TtsState.Error
                // Сбрасываем pending, чтобы он не «завис» навечно.
                pendingUtterance = null
                runCatching { candidate?.shutdown() }
                tts = null
            }
        }

        // Важно: присваиваем tts = candidate ДО того, как init-callback может
        // сработать (на некоторых версиях Android callback вызывается прямо из
        // конструктора TextToSpeech синхронно). Иначе configureTts()/drainPendingUtterance()
        // увидят tts == null и фраза будет потеряна / NPE.
        candidate = TextToSpeech(context) { status ->
            if (generation != initializationGeneration) {
                runCatching { candidate?.shutdown() }
                return@TextToSpeech
            }
            if (status == TextToSpeech.SUCCESS) {
                configureTts()
                _isInitialized.value = true
                _ttsState.value = TtsState.Ready
                Log.d(TAG, "TTS initialized successfully")
                // CR-09: если во время init пришла фраза — озвучиваем ровно один раз.
                scope.launch {
                    speakMutex.withLock { drainPendingUtterance() }
                }
            } else {
                _isInitialized.value = false
                _ttsState.value = TtsState.Error
                Log.e(TAG, "TTS initialization failed with status: $status")
                pendingUtterance = null
            }
        }
        tts = candidate
    }

    /** CR-09: разыгрывает ровно один pending-utterance после успешного init. */
    private fun drainPendingUtterance() {
        val pending = pendingUtterance ?: return
        pendingUtterance = null
        Log.d(TAG, "Draining pending utterance after init (chars=${pending.text.length})")
        speakInternal(
            text = pending.text,
            speechRate = pending.speechRate,
            pitch = pending.pitch,
            queueMode = pending.queueMode,
            stream = pending.stream
        )
    }

    private fun configureTts() {
        tts?.apply {
            applyAppLanguage(this)
            
            // Listener для отслеживания состояния
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _ttsState.value = TtsState.Speaking
                    Log.d(TAG, "TTS started: $utteranceId")
                }

                override fun onDone(utteranceId: String?) {
                    _ttsState.value = TtsState.Done
                    onUtteranceFinished()
                    Log.d(TAG, "TTS done: $utteranceId")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    _ttsState.value = TtsState.Error
                    onUtteranceFinished()
                    Log.e(TAG, "TTS error: $utteranceId")
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    _ttsState.value = TtsState.Error
                    onUtteranceFinished()
                    Log.e(TAG, "TTS error: $utteranceId, code: $errorCode")
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    _ttsState.value = TtsState.Ready
                    onUtteranceFinished()
                    Log.d(TAG, "TTS stopped: $utteranceId, interrupted: $interrupted")
                }
            })
        }
    }

    /**
     * Озвучивает текст с заданными параметрами.
     *
     * CR-09: если движок ещё не готов, фраза сохраняется в pendingUtterance,
     * запускается (ре)инициализация, и после успешного init воспроизводится
     * РОВНО ОДИН РАЗ. Вместо silent-drop возвращаем TtsState.Error по таймауту.
     * Гонки между concurrent speak() исключены speakMutex.
     *
     * @param text Текст для озвучивания
     * @param speechRate Скорость речи (0.5 - 2.0, по умолчанию 1.0)
     * @param pitch Высота голоса (0.5 - 2.0, по умолчанию 1.0)
     * @param queueMode Режим очереди (FLUSH - прервать текущее, ADD - добавить в очередь)
     */
    fun speak(
        text: String,
        speechRate: Float = 1.0f,
        pitch: Float = 1.0f,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        // Аудиопоток вывода; null — поток движка по умолчанию. STREAM_VOICE_CALL
        // ведёт речь за коммуникационным устройством (переводчик «вслух на телефоне»).
        stream: Int? = null
    ) {
        if (disposed.get()) return
        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping TTS")
            return
        }

        scope.launch {
            speakMutex.withLock {
                val engine = tts
                if (engine != null && _isInitialized.value && _ttsState.value !is TtsState.Initializing) {
                    speakInternal(text, speechRate, pitch, queueMode, stream)
                    return@withLock
                }
                // Движок не готов — сохраняем текст (последний вызов «выигрывает»),
                // (ре)стартуем init, после чего drainPendingUtterance() произнесёт его.
                Log.w(TAG, "TTS not ready — buffering utterance and (re)initializing")
                pendingUtterance = PendingUtterance(
                    text = text,
                    speechRate = speechRate.coerceIn(0.5f, 2.0f),
                    pitch = pitch.coerceIn(0.5f, 2.0f),
                    queueMode = queueMode,
                    stream = stream
                )
                if (_ttsState.value == TtsState.Error || _ttsState.value == TtsState.Idle || tts == null) {
                    initializeTts()
                }
            }
        }
    }

    /** Внутренний вызов speak, вызывается ТОЛЬКО когда движок гарантированно готов. */
    /**
     * Голос на языке приложения (6 языков); если движок его не знает —
     * русский, затем английский. Язык можно сменить на ходу, поэтому он
     * сверяется перед каждой фразой.
     */
    private var appliedLanguage: AppLanguage? = null

    private fun applyAppLanguage(engine: TextToSpeech) {
        val language = AppLanguage.current()
        if (language == appliedLanguage) return
        val locale = SpeechLanguage.ttsLocale(language) {
            engine.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE
        }
        engine.setLanguage(locale)
        appliedLanguage = language
        if (locale.language != language.code) {
            Log.w(TAG, "no ${language.code} voice, speaking ${locale.language}")
        }
    }

    private fun speakInternal(
        text: String,
        speechRate: Float,
        pitch: Float,
        queueMode: Int,
        stream: Int?
    ) {
        val engine = tts
        if (engine == null) {
            Log.e(TAG, "speakInternal: engine is null unexpectedly")
            _ttsState.value = TtsState.Error
            return
        }
        applyAppLanguage(engine)
        engine.setSpeechRate(speechRate.coerceIn(0.5f, 2.0f))
        engine.setPitch(pitch.coerceIn(0.5f, 2.0f))

        // EAR-MODE (audio focus): фокус запрашивается ДО старта звука — музыка
        // приглушается на время речи, а не играет поверх перевода/ответа.
        acquireFocusIfNeeded()
        val utteranceId = "${UTTERANCE_ID_PREFIX}${++utteranceCounter}"
        val params = stream?.let { Bundle().apply { putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, it) } }
        val result = engine.speak(text, queueMode, params, utteranceId)
        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "TTS speak() returned error")
            _ttsState.value = TtsState.Error
        } else {
            activeUtterances.incrementAndGet()
        }
    }

    /**
     * EAR-MODE: GAIN_TRANSIENT_MAY_DUCK + USAGE_ASSISTANT — музыка duck-ится
     * на время речи и продолжается после. Не owns-фокус, если уже держим.
     */
    private fun acquireFocusIfNeeded() {
        if (holdingFocus) return
        val am = audioManager ?: return
        val request = focusRequest ?: AudioFocusRequest.Builder(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        ).setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        ).setOnAudioFocusChangeListener(::onFocusChange).build()
            .also { focusRequest = it }
        holdingFocus = try {
            am.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed — играем без фокуса", e)
            false
        }
    }

    private fun releaseFocusIfIdle() {
        if (!holdingFocus) return
        if (activeUtterances.get() > 0) return
        abandonFocus()
    }

    private fun abandonFocus() {
        holdingFocus = false
        val am = audioManager
        val request = focusRequest
        if (am != null && request != null) {
            runCatching { am.abandonAudioFocusRequest(request) }
                .onFailure { Log.w(TAG, "abandonAudioFocusRequest failed", it) }
        }
    }

    private fun onFocusChange(change: Int) {
        when (mapFocusChange(change)) {
            // Чужой фокус забрал эфир насовсем — молчим, не переговариваемся.
            FocusReaction.STOP_SPEECH -> stop()
            FocusReaction.CONTINUE -> Unit
        }
    }

    /** Терминальный callback одного utterance: done / error / stop. */
    private fun onUtteranceFinished() {
        if (activeUtterances.decrementAndGet() <= 0) {
            activeUtterances.set(0)
            releaseFocusIfIdle()
        }
    }

    /**
     * Добавляет текст в очередь озвучивания (не прерывает текущее)
     */
    fun speakQueued(text: String, speechRate: Float = 1.0f, pitch: Float = 1.0f, stream: Int? = null) {
        speak(text, speechRate, pitch, TextToSpeech.QUEUE_ADD, stream)
    }

    /**
     * Останавливает текущее воспроизведение.
     * CR-09: также сбрасывает pendingUtterance, чтобы не всплыл отменённый текст.
     */
    fun stop() {
        if (disposed.get()) return
        scope.launch {
            speakMutex.withLock {
                pendingUtterance = null
                tts?.stop()
                // EAR-MODE: tts.stop() шлёт onStop только для ГОВОРЯЩЕЙ фразы —
                // стоявшие в очереди (QUEUE_ADD) не принесут callback, поэтому
                // счётчик активных фраз сбрасываем принудительно.
                activeUtterances.set(0)
                releaseFocusIfIdle()
                if (_isInitialized.value) {
                    _ttsState.value = TtsState.Ready
                }
            }
        }
    }

    /**
     * Проверяет, идёт ли сейчас воспроизведение
     */
    fun isSpeaking(): Boolean {
        return if (disposed.get()) false else tts?.isSpeaking == true
    }

    /**
     * Полное освобождение ресурсов TTS.
     * ОБЯЗАТЕЛЬНО вызывать при уничтожении сервиса/активности!
     * Идемпотентен.
     */
    fun shutdown() {
        if (!disposed.compareAndSet(false, true)) return
        Log.d(TAG, "Shutting down TTS")
        initializationGeneration++
        pendingUtterance = null
        abandonFocus() // EAR-MODE: не держим audio focus после уничтожения движка
        activeUtterances.set(0)
        ttsJob.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down TTS", e)
        } finally {
            tts = null
            _isInitialized.value = false
            _ttsState.value = TtsState.Idle
        }
    }

    /**
     * Перезапуск TTS (например, после ошибки)
     */
    fun restart() {
        shutdown()
        initializeTts()
    }

    /**
     * Устанавливает язык TTS
     */
    fun setLanguage(locale: Locale): Boolean {
        val result = tts?.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA && 
               result != TextToSpeech.LANG_NOT_SUPPORTED
    }
}
