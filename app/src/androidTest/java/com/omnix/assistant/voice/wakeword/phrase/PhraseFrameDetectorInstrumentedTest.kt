package com.omnix.assistant.voice.wakeword.phrase

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 * Фраза вызова на настоящих ассетах APK: модель скачана сборкой и упакована,
 * нативная библиотека sherpa-onnx грузится, ключевые слова каждой фразы
 * принимаются, а тишина и шум не будят ассистента.
 *
 * Узнавание самой фразы проверено на той же версии sherpa-onnx и тех же
 * параметрах вне телефона (docs/WAKEWORD_NEURAL.md, «Фраза вызова»).
 */
@RunWith(AndroidJUnit4::class)
class PhraseFrameDetectorInstrumentedTest {

    private val assets get() = InstrumentationRegistry.getInstrumentation().targetContext.assets

    @Test
    fun modelAssetsArePackaged() {
        PhraseFrameDetector.ASSETS.forEach { path ->
            assets.open(path).use { assertTrue("empty asset: $path", it.read() != -1) }
        }
    }

    /** Токен вне словаря модели нативный код не прощает — процесс завершается. */
    @Test
    fun everyKeywordTokenIsInTheModelVocabulary() {
        val vocabulary = assets.open(PhraseFrameDetector.TOKENS).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.map { it.substringBeforeLast(' ') }.toSet()
        }
        WakePhrase.entries.forEach { phrase ->
            phrase.keywords.split("/").forEach { keyword ->
                // «:буст» и «#порог» — параметры слова, а не токены.
                keyword.trim().split(" ").dropLast(1).filterNot { it.startsWith(":") || it.startsWith("#") }.forEach { token ->
                    assertTrue("$phrase: token '$token' is not in tokens.txt", token in vocabulary)
                }
            }
        }
    }

    @Test
    fun silenceAndNoiseDoNotWakeEitherPhrase() {
        val random = Random(SEED)
        val silence = ShortArray(CHUNK)
        WakePhrase.entries.forEach { phrase ->
            PhraseFrameDetector(assets, phrase).use { detector ->
                var detections = 0
                repeat(CHUNKS_PER_SIGNAL) { if (detector.accept(silence).score > 0f) detections++ }
                repeat(CHUNKS_PER_SIGNAL) {
                    val noise = ShortArray(CHUNK) { random.nextInt(-NOISE_LSB, NOISE_LSB).toShort() }
                    if (detector.accept(noise).score > 0f) detections++
                }
                detector.reset()
                repeat(CHUNKS_PER_SIGNAL) { if (detector.accept(silence).score > 0f) detections++ }
                assertEquals("$phrase woke on silence or noise", 0, detections)
            }
        }
    }

    private companion object {
        const val CHUNK = 1280
        /** 80 мс × 75 = 6 секунд каждого сигнала. */
        const val CHUNKS_PER_SIGNAL = 75
        const val NOISE_LSB = 3000
        const val SEED = 7
    }
}
