package com.omnix.assistant.voice.stt

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * На каком языке сказана фраза: встроенная модель Whisper tiny (assets/langid, та же, что у
 * фильтра языка синхронного перевода), офлайн, ~0,1–0,3 с на фразу.
 *
 * Модель занимает около 100 МБ памяти, поэтому грузится при первой фразе и выгружается,
 * если [IDLE_RELEASE_MS] её никто не спрашивал.
 */
@Singleton
class SpokenLanguageDetector @Inject constructor(@ApplicationContext private val context: Context) {

    private var lid: SpokenLanguageIdentification? = null
    private val timer = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "omnix-lang-detect").apply { isDaemon = true }
    }
    private var releaseTask: ScheduledFuture<*>? = null

    /** Код языка ISO 639-1 («ru», «tk»…) или null, если определить не удалось. */
    @Synchronized
    fun detect(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): String? {
        if (samples.size < sampleRate * MIN_SECONDS) return null
        val model = lid ?: open() ?: return null
        val n = minOf(samples.size, sampleRate * MAX_SECONDS)
        val floats = FloatArray(n) { samples[it] / FULL_SCALE }
        return try {
            val stream = model.createStream()
            try {
                stream.acceptWaveform(floats, sampleRate)
                model.compute(stream).trim().lowercase().takeIf { it.isNotEmpty() }
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "language detection failed: ${e.javaClass.simpleName}")
            null
        } finally {
            scheduleRelease()
        }
    }

    private fun open(): SpokenLanguageIdentification? = try {
        SpokenLanguageIdentification(
            assetManager = context.assets,
            config = SpokenLanguageIdentificationConfig(
                whisper = SpokenLanguageIdentificationWhisperConfig(encoder = ENCODER, decoder = DECODER),
                numThreads = 2
            )
        ).also { lid = it }
    } catch (e: Exception) {
        Log.w(TAG, "language model unavailable: ${e.javaClass.simpleName}")
        null
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "sherpa native library missing")
        null
    }

    private fun scheduleRelease() {
        releaseTask?.cancel(false)
        releaseTask = timer.schedule({ release() }, IDLE_RELEASE_MS, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun release() {
        lid?.let { runCatching { it.release() } }
        lid = null
    }

    private companion object {
        const val TAG = "SpokenLanguage"
        const val ENCODER = "langid/tiny-encoder.int8.onnx"
        const val DECODER = "langid/tiny-decoder.int8.onnx"
        const val SAMPLE_RATE = 16_000
        const val MIN_SECONDS = 1
        const val MAX_SECONDS = 8
        const val FULL_SCALE = 32768f
        const val IDLE_RELEASE_MS = 120_000L
    }
}
