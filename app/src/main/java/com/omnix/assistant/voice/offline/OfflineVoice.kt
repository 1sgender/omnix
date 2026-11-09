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
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Собственный голос OMNIX без интернета: Piper (VITS) через sherpa-onnx.
 * Сейчас озвучивает туркменский — у системных движков Android его нет. Остальные
 * языки пока говорят системным голосом.
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

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var track: AudioTrack? = null

    /** Голос установлен и умеет говорить на языке [language]. */
    fun canSpeak(language: AppLanguage): Boolean =
        language == AppLanguage.TK && store.isInstalled(OfflineSpeechModels.TK_VOICE)

    /**
     * Ставит фразу в очередь. [flush] — сначала оборвать всё, что говорится.
     * [speed] 1.0 — обычная скорость (на неё делится length_scale модели).
     * [voiceCall] — вести речь по аудиопотоку звонка (переводчик «вслух»).
     */
    fun speak(text: String, speed: Float, flush: Boolean, voiceCall: Boolean, listener: Listener) {
        val gen = if (flush) generation.incrementAndGet() else generation.get()
        if (flush) stopTrack()
        worker.execute {
            if (gen != generation.get()) {
                listener.onFinished(false)
                return@execute
            }
            val completed = try {
                synthesizeAndPlay(text, speed, voiceCall, gen, listener)
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
            tts?.release()
            tts = null
        }
    }

    private fun stopTrack() {
        val current = track ?: return
        runCatching { current.pause() }
        runCatching { current.flush() }
    }

    private fun engine(): OfflineTts? {
        tts?.let { return it }
        val dir = store.dirOf(OfflineSpeechModels.TK_VOICE)
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                vits = OfflineTtsVitsModelConfig(
                    model = "${dir.path}/model.onnx",
                    tokens = "${dir.path}/tokens.txt",
                    dataDir = "${dir.path}/espeak-ng-data",
                    noiseScale = NOISE_SCALE,
                    noiseScaleW = NOISE_SCALE_W,
                    lengthScale = LENGTH_SCALE
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
            ?.also { tts = it }
    }

    /** @return true, если фраза доиграна до конца. */
    private fun synthesizeAndPlay(
        text: String,
        speed: Float,
        voiceCall: Boolean,
        gen: Int,
        listener: Listener
    ): Boolean {
        val engine = engine() ?: return false
        val audioTrack = buildTrack(engine.sampleRate(), voiceCall)
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
                audioTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
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

    private companion object {
        const val TAG = "OfflineVoice"

        // Настройки чёткости «D», выбранные владельцем 2026-11-06.
        const val NOISE_SCALE = 0.3f
        const val NOISE_SCALE_W = 0.5f
        const val LENGTH_SCALE = 1.15f
        const val THREADS = 2
        const val MAX_DRAIN_MS = 15_000
        const val DRAIN_POLL_MS = 120L
    }
}
