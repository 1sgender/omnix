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

        /** [completed] false — фраза оборвана (flush/stop). */
        fun onFinished(completed: Boolean)

        /**
         * Голос не смог произнести фразу (модель не загрузилась, ошибка синтеза, ни одного
         * отсчёта звука). Фразу нужно сказать системным голосом; [onFinished] не вызывается.
         */
        fun onUnavailable()
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "omnix-offline-voice") }

    /** Поколение очереди: фразы старых поколений пропускаются, текущая обрывается. */
    private val generation = AtomicInteger(0)

    /** Загруженные движки по языкам; создаются при первой фразе на языке. */
    private val engines = ConcurrentHashMap<AppLanguage, OfflineTts>()

    @Volatile
    private var track: AudioTrack? = null

    /** Языки, голос которых сломался в этом запуске: до перезапуска говорит системный голос. */
    private val broken: MutableSet<AppLanguage> = ConcurrentHashMap.newKeySet()

    /** Голос установлен, умеет говорить на языке [language] и не ломался в этом запуске. */
    fun canSpeak(language: AppLanguage): Boolean {
        if (language in broken) return false
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
            val result = try {
                synthesizeAndPlay(language, text, speed, voiceCall, gen, listener)
            } catch (e: Exception) {
                Log.e(TAG, "offline voice failed", e)
                markBroken(language)
                Played.FAILED
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "sherpa native library missing", e)
                markBroken(language)
                Played.FAILED
            }
            // Голос молчал, а фразу никто не отменял — её скажет системный голос.
            if (result == Played.FAILED && gen == generation.get()) {
                listener.onUnavailable()
            } else {
                listener.onFinished(result == Played.COMPLETED)
            }
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

    private fun markBroken(language: AppLanguage) {
        broken.add(language)
        engines.remove(language)?.let { runCatching { it.release() } }
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

    private enum class Played { COMPLETED, INTERRUPTED, FAILED }

    private fun synthesizeAndPlay(
        language: AppLanguage,
        text: String,
        speed: Float,
        voiceCall: Boolean,
        gen: Int,
        listener: Listener
    ): Played {
        val engine = engine(language) ?: run {
            markBroken(language)
            return Played.FAILED
        }
        val audioTrack = buildTrack(engine.sampleRate(), voiceCall)
        if (audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "offline voice: audio output not initialized")
            audioTrack.release()
            return Played.FAILED
        }
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
            if (gen != generation.get()) return Played.INTERRUPTED
            if (!started) {
                Log.e(TAG, "offline voice produced no audio for ${text.length} chars")
                return Played.FAILED
            }
            // Дать буферу доиграть, но не дольше разумного.
            waitForPlayback(audioTrack, gen)
            return if (gen == generation.get()) Played.COMPLETED else Played.INTERRUPTED
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
