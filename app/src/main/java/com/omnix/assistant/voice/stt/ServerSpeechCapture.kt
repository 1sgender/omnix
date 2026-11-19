package com.omnix.assistant.voice.stt

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.data.remote.OmnixApiClient
import com.omnix.assistant.voice.audio.MicEffects
import com.omnix.assistant.voice.audio.SpeechEnhancer
import com.omnix.assistant.voice.conversation.PcmWav
import com.omnix.assistant.voice.conversation.UtteranceSegmenter
import com.omnix.assistant.voice.offline.LocalSpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Одна фраза с микрофона, распознанная на сервере (Whisper). Нужна для языков,
 * которых нет у распознавателя Android: туркменский (решение владельца: 6 языков).
 * Конец фразы определяет [UtteranceSegmenter] по паузе, как в переводчике.
 */
@Singleton
class ServerSpeechCapture @Inject constructor(
    private val api: OmnixApiClient,
    private val local: LocalSpeechRecognizer,
    /** V012: серверное распознавание — облачная функция, только с подключённой парой OMNIX. */
    private val pairAccess: com.omnix.assistant.agent.decision.CloudPairAccess =
        com.omnix.assistant.agent.decision.CloudPairAccess.OPEN
) {

    sealed interface Outcome {
        data class Heard(val text: String) : Outcome
        data object Silence : Outcome
        data object MicrophoneBusy : Outcome
        data class Failed(val message: String) : Outcome
    }

    /**
     * Ждёт начала речи до [waitForSpeechMs], пишет фразу и отправляет на сервер.
     *
     * @param languageHint код языка для Whisper, например «tk».
     * @param prompt подсказка Whisper (слово вызова, названия функций); без личных данных.
     * @param onLevel громкость 0..1 для индикатора на экране.
     */
    @SuppressLint("MissingPermission") // Голосовой ввод запускается только с разрешением на микрофон.
    suspend fun listen(
        languageHint: String,
        waitForSpeechMs: Long = WAIT_FOR_SPEECH_MS,
        prompt: String? = null,
        onLevel: (Float) -> Unit = {}
    ): Outcome = withContext(Dispatchers.IO) {
        when (val captured = record(waitForSpeechMs, onLevel)) {
            Captured.Busy -> Outcome.MicrophoneBusy
            Captured.Quiet -> Outcome.Silence
            is Captured.Speech -> recognizeOnPhoneThenServer(enhance(captured.samples), languageHint, prompt)
        }
    }

    /**
     * Офлайн прежде всего (решение владельца 2026-11-09): если на телефоне есть модель
     * для языка, фраза распознаётся без интернета. Не услышала или модели нет — сервер.
     */
    private suspend fun recognizeOnPhoneThenServer(samples: ShortArray, languageHint: String, prompt: String?): Outcome {
        if (local.isReady(languageHint)) {
            val text = local.transcribe(samples, languageHint)
            if (text != null && text.length >= MIN_TEXT_CHARS) return Outcome.Heard(text)
        }
        pairAccess.blockedMessage(com.omnix.assistant.agent.decision.CloudUse.FULL)?.let { return Outcome.Failed(it) }
        return when (val stt = api.transcribe(PcmWav.encode(samples, SAMPLE_RATE), languageHint, prompt)) {
            is Resource.Success -> {
                val text = stt.data.text.trim()
                if (text.length < MIN_TEXT_CHARS) Outcome.Silence else Outcome.Heard(text)
            }
            is Resource.Error -> Outcome.Failed(stt.message.orEmpty())
            else -> Outcome.Silence
        }
    }

    private sealed interface Captured {
        data class Speech(val samples: ShortArray) : Captured
        data object Quiet : Captured
        data object Busy : Captured
    }

    @SuppressLint("MissingPermission")
    private suspend fun record(waitForSpeechMs: Long, onLevel: (Float) -> Unit): Captured {
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
            return Captured.Busy
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return Captured.Busy
        }
        val effects = MicEffects.attach(record.audioSessionId)
        val segmenter = UtteranceSegmenter(SAMPLE_RATE, endSilenceMs = END_OF_PHRASE_SILENCE_MS)
        val frame = ShortArray(FRAME)
        var waitedSamples = 0L
        val waitLimit = SAMPLE_RATE * waitForSpeechMs / 1000
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = record.read(frame, 0, FRAME)
                if (read <= 0) continue
                val chunk = if (read == FRAME) frame.copyOf() else frame.copyOf(read)
                onLevel(level(chunk))
                val utterance = segmenter.accept(chunk)
                if (utterance != null) return Captured.Speech(utterance)
                if (!segmenter.isCapturing) {
                    waitedSamples += read
                    if (waitedSamples >= waitLimit) return Captured.Quiet
                }
            }
            return Captured.Quiet
        } finally {
            runCatching { record.stop() }
            effects.release()
            record.release()
        }
    }

    /** Фильтр 80 Гц и выравнивание громкости; при любом сбое фраза уходит как записана. */
    private fun enhance(samples: ShortArray): ShortArray =
        runCatching { SpeechEnhancer.process(samples, SAMPLE_RATE) }.getOrDefault(samples)

    private fun level(frame: ShortArray): Float {
        if (frame.isEmpty()) return 0f
        var sum = 0.0
        for (s in frame) sum += s.toDouble() * s
        return (sqrt(sum / frame.size) / LEVEL_LOUD).toFloat().coerceIn(0f, 1f)
    }

    private companion object {
        const val TAG = "ServerSpeechCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME = 320
        const val WAIT_FOR_SPEECH_MS = 8_000L

        /**
         * Пауза, после которой фраза считается законченной. Стандартные 0,7 с режут
         * речь, когда человек задумывается; 1,6 с терпимо по задержке и не обрезает.
         */
        const val END_OF_PHRASE_SILENCE_MS = 1_600
        const val MIN_TEXT_CHARS = 2
        const val LEVEL_LOUD = 6_000.0
    }
}
