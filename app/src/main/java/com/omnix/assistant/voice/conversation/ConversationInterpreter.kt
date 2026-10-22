package com.omnix.assistant.voice.conversation

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.omnix.assistant.agent.translator.LiveTranslatorEngine
import com.omnix.assistant.agent.translator.TranslationResult
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.data.remote.OmnixApiClient
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Одна реплика разговора: кто сказал, что и как это перевели. */
data class ConversationLine(
    val id: Long,
    val speaker: Speaker,
    val original: String,
    val translated: String,
    val sourceLang: String,
    val targetLang: String,
    /** false — перевод не получился, в [translated] причина. */
    val translatedOk: Boolean = true,
    /** false — для языка нет голоса, перевод только на экране. */
    val spoken: Boolean = true
)

data class ConversationState(
    val running: Boolean = false,
    /** Идёт фраза — экран показывает «слушаю». */
    val hearing: Boolean = false,
    /** Фраза распознаётся и переводится. */
    val working: Boolean = false,
    val myLanguage: String = "ru",
    /** null — язык собеседника определится по его первой фразе. */
    val theirLanguage: String? = null,
    /** Ухо собеседника; моё — другое. */
    val theirEar: Ear = Ear.RIGHT,
    val lines: List<ConversationLine> = emptyList(),
    /** Почему тихо: микрофон занят или фраза не распозналась (сеть, лимит). */
    val problem: ConversationProblem? = null,
    /** Текст ошибки сервера для [ConversationProblem.RECOGNITION]. */
    val problemDetail: String? = null
)

enum class ConversationProblem { MICROPHONE, RECOGNITION }

/**
 * Переводчик «по наушнику на каждого» (отзыв владельца на v344): один наушник
 * у владельца, второй у собеседника. Микрофон телефона слушает обоих,
 * сервер распознаёт фразу и называет язык, OMNIX понимает, кто говорит, и
 * переводит в ухо другого.
 *
 * Пока перевод звучит, микрофон не слушает: иначе перевод, услышанный
 * телефоном, перевёлся бы обратно.
 */
class ConversationInterpreter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OmnixApiClient,
    private val translator: LiveTranslatorEngine,
    private val audioRouter: BluetoothAudioRouter
) {
    private val _state = MutableStateFlow(ConversationState())
    val state: StateFlow<ConversationState> = _state.asStateFlow()

    private var captureJob: Job? = null
    private var workJob: Job? = null
    private var player: EarSplitPlayer? = null
    private var resolver = SpeakerResolver("ru")

    @Volatile
    private var muted = false

    /** «Перевод в наушники» включён; без наушников перевод всё равно звучит вслух. */
    @Volatile
    private var splitEars = true
    private var nextId = 0L

    fun configure(myLanguage: String, theirLanguage: String?, theirEar: Ear) {
        _state.update { it.copy(myLanguage = myLanguage, theirLanguage = theirLanguage, theirEar = theirEar) }
        resolver = SpeakerResolver(myLanguage, theirLanguage)
    }

    fun setSplitEars(enabled: Boolean) {
        splitEars = enabled
    }

    fun swapEars() {
        _state.update { it.copy(theirEar = it.theirEar.other) }
    }

    fun start(scope: CoroutineScope) {
        if (_state.value.running) return
        val current = _state.value
        resolver = SpeakerResolver(current.myLanguage, current.theirLanguage)
        // Звук перевода — стерео по A2DP; режим гарнитуры (SCO) это ломает.
        audioRouter.routeAudioToSpeaker()
        player = EarSplitPlayer(context)
        val utterances = Channel<ShortArray>(capacity = 4)
        _state.update { it.copy(running = true, problem = null, problemDetail = null) }
        captureJob = scope.launch(Dispatchers.IO) { capture(utterances) }
        workJob = scope.launch(Dispatchers.IO) {
            for (utterance in utterances) handle(utterance)
        }
    }

    fun stop() {
        captureJob?.cancel()
        workJob?.cancel()
        captureJob = null
        workJob = null
        player?.release()
        player = null
        muted = false
        _state.update { it.copy(running = false, hearing = false, working = false) }
    }

    fun clear() {
        _state.update { it.copy(lines = emptyList(), problem = null, problemDetail = null) }
    }

    @SuppressLint("MissingPermission") // Экран переводчика запускается только с разрешением на микрофон.
    private suspend fun capture(out: Channel<ShortArray>) {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME * 2 * 8)
            )
        } catch (e: Exception) {
            Log.w(TAG, "microphone unavailable: ${e.javaClass.simpleName}")
            _state.update { it.copy(problem = ConversationProblem.MICROPHONE) }
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            _state.update { it.copy(problem = ConversationProblem.MICROPHONE) }
            return
        }
        val segmenter = UtteranceSegmenter(SAMPLE_RATE)
        val frame = ShortArray(FRAME)
        try {
            record.startRecording()
            while (kotlinx.coroutines.currentCoroutineContext().isActive) {
                val read = record.read(frame, 0, FRAME)
                if (read <= 0) continue
                if (muted) {
                    segmenter.reset()
                    continue
                }
                val utterance = segmenter.accept(if (read == FRAME) frame.copyOf() else frame.copyOf(read))
                _state.update { if (it.hearing != segmenter.isCapturing) it.copy(hearing = segmenter.isCapturing) else it }
                if (utterance != null) out.trySend(utterance)
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            out.close()
        }
    }

    private suspend fun handle(utterance: ShortArray) {
        _state.update { it.copy(working = true) }
        try {
            val stt = api.transcribe(PcmWav.encode(utterance, SAMPLE_RATE))
            if (stt !is Resource.Success) {
                _state.update {
                    it.copy(problem = ConversationProblem.RECOGNITION, problemDetail = (stt as? Resource.Error)?.message)
                }
                return
            }
            val text = stt.data.text.trim()
            if (text.length < 2) return
            val resolution = resolver.resolve(stt.data.language)
            if (resolution == null) {
                // Моя фраза, пока язык собеседника неизвестен: показываем без перевода.
                if (stt.data.language == resolver.mine) {
                    addLine(Speaker.ME, text, "", resolver.mine, resolver.mine, translatedOk = false, spoken = false)
                }
                return
            }
            if (_state.value.theirLanguage == null && resolver.theirs != null) {
                _state.update { it.copy(theirLanguage = resolver.theirs) }
            }
            val result = translator.translateStructured(text, resolution.sourceLang, resolution.targetLang)
            val ok = result is TranslationResult.Success
            val translated = translator.describeFailure(result)
            // Говорю я — перевод слышит собеседник, и наоборот.
            val listenerEar = when {
                !splitEars || !audioRouter.isHeadsetConnected() -> null
                resolution.speaker == Speaker.ME -> _state.value.theirEar
                else -> _state.value.theirEar.other
            }
            var spoken = false
            if (ok) {
                muted = true
                try {
                    spoken = player?.speak(translated, resolution.targetLang, listenerEar) == true
                } finally {
                    muted = false
                }
            }
            addLine(resolution.speaker, text, translated, resolution.sourceLang, resolution.targetLang, ok, spoken)
            _state.update { it.copy(problem = null, problemDetail = null) }
        } finally {
            _state.update { it.copy(working = false) }
        }
    }

    private fun addLine(
        speaker: Speaker,
        original: String,
        translated: String,
        source: String,
        target: String,
        translatedOk: Boolean,
        spoken: Boolean
    ) {
        val line = ConversationLine(nextId++, speaker, original, translated, source, target, translatedOk, spoken)
        _state.update { it.copy(lines = it.lines + line) }
    }

    private companion object {
        const val TAG = "ConversationInterpreter"
        const val SAMPLE_RATE = 16_000
        const val FRAME = 320
    }
}
