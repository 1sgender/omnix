package com.omnix.assistant.voice.offline

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.omnix.assistant.core.locale.AppLanguage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Собственный голос OMNIX без интернета: Piper (VITS) через sherpa-onnx.
 * Озвучивает туркменский (у системных движков Android его нет) и русский (необязательная
 * загрузка). Остальные языки говорят системным голосом.
 *
 * Все вызовы безопасны из любого потока: синтез и воспроизведение идут на одном
 * рабочем потоке, `flush` обрывает текущую фразу и снимает с очереди остальные.
 */
@Singleton
class OfflineVoice @Inject constructor(
    private val store: OfflineSpeechModelStore
) {
    /** События одной фразы для менеджера озвучки. */
    interface Listener {
        fun onStart()

        /** [completed] false — фраза оборвана (flush/stop) или произошла ошибка. */
        fun onFinished(completed: Boolean)
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "omnix-offline-voice") }

    /** Поколение очереди: фразы старых поколений пропускаются, текущая обрывается. */
    private val generation = AtomicInteger(0)

    /** Загруженные движки по языкам; создаются при первой фразе на языке. */
    private val engines = ConcurrentHashMap<AppLanguage, OfflineTts>()

    @Volatile
    private var track: AudioTrack? = null

    /** Голос установлен и умеет говорить на языке [language]. */
    fun canSpeak(language: AppLanguage): Boolean {
        val model = modelFor(language) ?: return false
        return store.isInstalled(model)
    }

    private fun modelFor(language: AppLanguage): OfflineSpeechModel? = when (language) {
        AppLanguage.TK -> OfflineSpeechModels.TK_VOICE
        AppLanguage.RU -> OfflineSpeechModels.RU_VOICE
        else -> null
    }

    /**
     * Ставит фразу в очередь. [flush] — сначала оборвать всё, что говорится.
     * [speed] 1.0 — обычная скорость (на неё делится length_scale модели).
     * [voiceCall] — вести речь по аудиопотоку звонка (переводчик «вслух»).
     */
    fun speak(text: String, speed: Float, flush: Boolean, voiceCall: Boolean, listener: Listener) {
        val gen = if (flush) generation.incrementAndGet() else generation.get()
        val language = AppLanguage.current()
        if (flush) stopTrack()
        worker.execute {
            if (gen != generation.get()) {
                listener.onFinished(false)
                return@execute
            }
            val completed = try {
                synthesizeAndPlay(language, text, speed, voiceCall, gen, listener)
            } catch (e: Exception) {
                Log.e(TAG, "offline voice failed", e)
                false
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "sherpa native library missing", e)
                false
            }
            listener.onFinished(completed)
        }
    }

    /** Оборвать текущую фразу и очередь. */
    fun stop() {
        generation.incrementAndGet()
        stopTrack()
    }

    /** Освободить модель (нехватка памяти, удаление голоса). */
    fun release() {
        stop()
        worker.execute {
            engines.values.forEach { it.release() }
            engines.clear()
        }
    }

    private fun stopTrack() {
        val current = track ?: return
        runCatching { current.pause() }
        runCatching { current.flush() }
    }

    private fun engine(language: AppLanguage): OfflineTts? {
        engines[language]?.let { return it }
        val model = modelFor(language) ?: return null
        val dir = store.dirOf(model)
        val tuning = if (language == AppLanguage.RU) RU_TUNING else TK_TUNING
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "${dir.path}/model.onnx",
                    tokens = "${dir.path}/tokens.txt",
                    dataDir = "${dir.path}/espeak-ng-data",
                    noiseScale = tuning.noiseScale,
                    noiseScaleW = tuning.noiseScaleW,
                    lengthScale = tuning.lengthScale
                ),
                numThreads = THREADS,
                debug = false,
                provider = "cpu"
            ),
            maxNumSentences = 1
        )
        return runCatching { OfflineTts(config = config) }
            .onFailure { Log.e(TAG, "cannot load offline voice", it) }
            .getOrNull()
            ?.also { engines[language] = it }
    }

    /** @return true, если фраза доиграна до конца. */
    private fun synthesizeAndPlay(
        language: AppLanguage,
        text: String,
        speed: Float,
        voiceCall: Boolean,
        gen: Int,
        listener: Listener
    ): Boolean {
        val engine = engine(language) ?: return false
        val audioTrack = buildTrack(engine.sampleRate(), voiceCall)
        // Русский голос обучен на любительских записях: срезаем гул и шипение.
        val filter = if (language == AppLanguage.RU) VoicePostFilter(engine.sampleRate()) else null
        track = audioTrack
        var started = false
        try {
            audioTrack.play()
            engine.generateWithCallback(text, 0, speed.coerceIn(0.5f, 2f)) { samples ->
                if (gen != generation.get()) return@generateWithCallback 0
                if (!started) {
                    started = true
                    listener.onStart()
                }
                val clean = filter?.process(samples) ?: samples
                audioTrack.write(clean, 0, clean.size, AudioTrack.WRITE_BLOCKING)
                1
            }
            if (gen != generation.get()) return false
            // Дать буферу доиграть, но не дольше разумного.
            waitForPlayback(audioTrack, gen)
            return started && gen == generation.get()
        } finally {
            runCatching { audioTrack.stop() }
            audioTrack.release()
            if (track === audioTrack) track = null
        }
    }

    private fun waitForPlayback(audioTrack: AudioTrack, gen: Int) {
        var waited = 0
        var lastHead = -1
        while (gen == generation.get() && waited < MAX_DRAIN_MS) {
            val head = audioTrack.playbackHeadPosition
            if (head == lastHead) return
            lastHead = head
            Thread.sleep(DRAIN_POLL_MS)
            waited += DRAIN_POLL_MS.toInt()
        }
    }

    private fun buildTrack(sampleRate: Int, voiceCall: Boolean): AudioTrack {
        val usage = if (voiceCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    /** Параметры синтеза одного голоса. */
    private data class Tuning(val noiseScale: Float, val noiseScaleW: Float, val lengthScale: Float)

    private companion object {
        const val TAG = "OfflineVoice"

        // Настройки чёткости «D» туркменского голоса, выбранные владельцем 2026-11-06.
        val TK_TUNING = Tuning(noiseScale = 0.3f, noiseScaleW = 0.5f, lengthScale = 1.15f)

        // Русский голос: те же шумы, чуть быстрее (проверено на примерах 2026-11-25).
        val RU_TUNING = Tuning(noiseScale = 0.3f, noiseScaleW = 0.5f, lengthScale = 1.1f)
        const val THREADS = 2
        const val MAX_DRAIN_MS = 15_000
        const val DRAIN_POLL_MS = 120L
    }
}
