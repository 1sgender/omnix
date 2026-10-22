package com.omnix.assistant.voice.wakeword.phrase

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.omnix.assistant.voice.wakeword.FrameDetector
import com.omnix.assistant.voice.wakeword.oww.OpenWakeWordPipeline
import java.io.IOException

/**
 * Фраза вызова на sherpa-onnx: маленькая русская модель распознавания речи
 * (Vosk small-streaming-ru, zipformer-transducer) ищет в потоке только
 * токены [WakePhrase.keywords]. Скор кадра 1 — фраза услышана, 0 — нет;
 * порог и паузы между срабатываниями остаются у движка.
 *
 * Модель кладётся в assets сборкой (downloadWakePhraseModel в app/build.gradle.kts).
 */
class PhraseFrameDetector(assets: AssetManager, private val phrase: WakePhrase) : FrameDetector {

    companion object {
        const val MODEL_DIR = "wakeword/phrase"
        const val ENCODER = "$MODEL_DIR/encoder.int8.onnx"
        const val DECODER = "$MODEL_DIR/decoder.onnx"
        const val JOINER = "$MODEL_DIR/joiner.int8.onnx"
        const val TOKENS = "$MODEL_DIR/tokens.txt"

        /** Ключевые слова по умолчанию; поток получает свои — [WakePhrase.keywords]. */
        const val KEYWORDS = "wakeword/phrase-keywords.txt"

        val ASSETS = listOf(ENCODER, DECODER, JOINER, TOKENS, KEYWORDS)

        private const val SAMPLE_RATE = 16000
        private const val FEATURE_DIM = 80
        private const val PCM_SCALE = 32768f

        /**
         * Подобрано на синтетической речи (docs/WAKEWORD_NEURAL.md): буст 2.0 и
         * порог 0.15 ловят фразу и в шуме, ложных на 5 минутах речи — ноль.
         */
        const val KEYWORDS_SCORE = 2.0f
        const val KEYWORDS_THRESHOLD = 0.15f
        private const val MAX_ACTIVE_PATHS = 4
        private const val TRAILING_BLANKS = 1
    }

    private val spotter: KeywordSpotter
    private var stream: OnlineStream

    init {
        // Нативный код завершает процесс, если файла модели нет: проверяем сами.
        for (path in ASSETS) {
            try {
                assets.open(path).close()
            } catch (e: IOException) {
                throw IOException("wakeword model asset missing: $path", e)
            }
        }
        spotter = KeywordSpotter(
            assets,
            KeywordSpotterConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(encoder = ENCODER, decoder = DECODER, joiner = JOINER),
                    tokens = TOKENS,
                    numThreads = 1,
                    provider = "cpu",
                ),
                maxActivePaths = MAX_ACTIVE_PATHS,
                keywordsFile = KEYWORDS,
                keywordsScore = KEYWORDS_SCORE,
                keywordsThreshold = KEYWORDS_THRESHOLD,
                numTrailingBlanks = TRAILING_BLANKS,
            ),
        )
        stream = spotter.createStream(phrase.keywords)
    }

    override fun accept(frame: ShortArray): OpenWakeWordPipeline.FrameResult {
        val started = System.nanoTime()
        stream.acceptWaveform(FloatArray(frame.size) { frame[it] / PCM_SCALE }, SAMPLE_RATE)
        var heard = false
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            if (spotter.getResult(stream).keyword.isNotEmpty()) {
                heard = true
                spotter.reset(stream)
            }
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L
        return OpenWakeWordPipeline.FrameResult(
            score = if (heard) 1f else 0f,
            melMs = 0L,
            embMs = 0L,
            clfMs = elapsedMs,
        )
    }

    override fun reset() {
        stream.release()
        stream = spotter.createStream(phrase.keywords)
    }

    override fun close() {
        stream.release()
        spotter.release()
    }
}
