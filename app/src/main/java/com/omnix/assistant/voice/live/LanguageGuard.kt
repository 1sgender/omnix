package com.omnix.assistant.voice.live

import android.util.Log
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Решения «какой язык звучал» по времени. Кусок перевода отбрасывается, если за последние
 * [lookbackMs] все определения языка (их хотя бы одно) — не язык собеседника. Если определений
 * нет или они смешанные, кусок пропускается: лучше лишний перевод, чем потерянная фраза.
 */
class LanguageVerdicts(private val lookbackMs: Long = DEFAULT_LOOKBACK_MS) {

    private val verdicts = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun record(language: String, atMs: Long) {
        if (language.isBlank()) return
        verdicts.addLast(atMs to language.lowercase())
        while (verdicts.size > MAX_VERDICTS) verdicts.removeFirst()
    }

    @Synchronized
    fun isForeign(source: String, nowMs: Long): Boolean {
        val recent = verdicts.filter { nowMs - it.first in 0..lookbackMs }
        if (recent.isEmpty()) return false
        val src = source.lowercase()
        return recent.none { it.second == src }
    }

    @Synchronized
    fun clear() = verdicts.clear()

    companion object {
        const val DEFAULT_LOOKBACK_MS = 2_500L
        private const val MAX_VERDICTS = 32
    }
}

/**
 * Фильтр языка синхронного перевода: офлайн-модель (Whisper tiny через sherpa-onnx) раз в
 * секунду определяет язык последних двух секунд речи. Пока звучит не язык собеседника —
 * например, русский владельца или перевод, попавший в микрофон, — куски не переводятся.
 *
 * Звук приходит кадрами из захвата ([push], поток захвата); модель считает на своём потоке,
 * и если не успевает, окно пропускается (захват никогда не ждёт).
 */
class LanguageGuard private constructor(
    private val lid: SpokenLanguageIdentification,
    private val now: () -> Long
) {
    val verdicts = LanguageVerdicts()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "omnix-lang-guard").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)
    private val ring = ShortArray(WINDOW_SAMPLES)
    private var ringPos = 0
    private var filled = 0
    private var sinceLast = 0
    private val voicedFlags = BooleanArray(WINDOW_FRAMES)
    private var frameIndex = 0

    @Volatile
    private var released = false

    /** Кадр 16 кГц моно (поток захвата). */
    fun push(frame: ShortArray) {
        if (released) return
        for (s in frame) {
            ring[ringPos] = s
            ringPos = (ringPos + 1) % ring.size
        }
        filled = minOf(ring.size, filled + frame.size)
        voicedFlags[frameIndex % WINDOW_FRAMES] = DualMicMixer.levelDb(frame) > VOICED_DB
        frameIndex++
        sinceLast += frame.size
        if (sinceLast < HOP_SAMPLES || filled < ring.size) return
        sinceLast = 0
        if (voicedFlags.count { it } < WINDOW_FRAMES * MIN_VOICED_SHARE) return
        if (!busy.compareAndSet(false, true)) return
        val window = FloatArray(ring.size) { i -> ring[(ringPos + i) % ring.size] / FULL_SCALE }
        worker.execute {
            try {
                if (!released) {
                    val stream = lid.createStream()
                    try {
                        stream.acceptWaveform(window, SAMPLE_RATE)
                        val lang = lid.compute(stream)
                        verdicts.record(lang, now())
                    } finally {
                        stream.release()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "language id failed: ${e.javaClass.simpleName}")
            } finally {
                busy.set(false)
            }
        }
    }

    /** Сейчас звучит не [source]: кусок переводить не надо. */
    fun isForeign(source: String): Boolean = !released && verdicts.isForeign(source, now())

    fun release() {
        released = true
        worker.execute { runCatching { lid.release() } }
        worker.shutdown()
    }

    companion object {
        private const val TAG = "LanguageGuard"
        private const val SAMPLE_RATE = 16_000
        private const val FRAME = 320
        private const val WINDOW_SAMPLES = SAMPLE_RATE * 2
        private const val WINDOW_FRAMES = WINDOW_SAMPLES / FRAME
        private const val HOP_SAMPLES = SAMPLE_RATE
        private const val MIN_VOICED_SHARE = 0.4f
        private const val VOICED_DB = -45f
        private const val FULL_SCALE = 32768f
        const val ENCODER = "tiny-encoder.int8.onnx"
        const val DECODER = "tiny-decoder.int8.onnx"

        /** null — модели нет на телефоне или она не загрузилась. */
        fun open(dir: File, now: () -> Long = System::currentTimeMillis): LanguageGuard? {
            val encoder = File(dir, ENCODER)
            val decoder = File(dir, DECODER)
            if (!encoder.isFile || !decoder.isFile) return null
            return try {
                val lid = SpokenLanguageIdentification(
                    config = SpokenLanguageIdentificationConfig(
                        whisper = SpokenLanguageIdentificationWhisperConfig(
                            encoder = encoder.path,
                            decoder = decoder.path
                        ),
                        numThreads = 2
                    )
                )
                LanguageGuard(lid, now)
            } catch (e: Exception) {
                Log.w(TAG, "cannot load language id: ${e.javaClass.simpleName}")
                null
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "sherpa native library missing")
                null
            }
        }
    }
}
