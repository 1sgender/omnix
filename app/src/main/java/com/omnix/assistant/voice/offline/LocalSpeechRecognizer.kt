package com.omnix.assistant.voice.offline

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import com.omnix.assistant.voice.wakeword.phrase.PhraseFrameDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Распознавание речи на телефоне, без интернета (sherpa-onnx):
 *  - туркменский — Omnilingual ASR CTC 300M (Meta), скачивается отдельным пакетом
 *    ([OfflineSpeechModels.TK_ASR]);
 *  - русский — маленькая потоковая модель Vosk, она уже лежит в APK ради слова вызова.
 *
 * Фраза записывается целиком (границы по паузе ставит UtteranceSegmenter), поэтому
 * потоковость не нужна: модель получает готовые отсчёты 16 кГц.
 */
@Singleton
class LocalSpeechRecognizer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: OfflineSpeechModelStore,
    private val dispatchers: CoroutineDispatchers
) {
    private val mutex = Mutex()
    private var turkmen: OfflineRecognizer? = null
    private var russian: OnlineRecognizer? = null

    /** Модель для языка [languageHint] ("tk", "ru") готова к работе. */
    fun isReady(languageHint: String): Boolean = when (languageHint) {
        LANG_TK -> store.isInstalled(OfflineSpeechModels.TK_ASR)
        LANG_RU -> russianAssetsPresent()
        else -> false
    }

    /**
     * Текст фразы или null, если модели нет, она не загрузилась или ничего не услышала.
     * Тяжёлая работа идёт вне главного потока.
     */
    suspend fun transcribe(samples: ShortArray, languageHint: String): String? = mutex.withLock {
        withContext(dispatchers.default) {
            try {
                val floats = FloatArray(samples.size) { samples[it] / PCM_SCALE }
                when (languageHint) {
                    LANG_TK -> decodeTurkmen(floats)
                    LANG_RU -> decodeRussian(floats)
                    else -> null
                }?.trim()?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(TAG, "local recognition failed ($languageHint)", e)
                null
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "sherpa native library missing", e)
                null
            }
        }
    }

    /** Освободить модели (нехватка памяти, удаление пакета). */
    suspend fun release() = mutex.withLock {
        turkmen?.release()
        turkmen = null
        russian?.release()
        russian = null
    }

    private fun decodeTurkmen(samples: FloatArray): String? {
        val recognizer = turkmen ?: createTurkmen()?.also { turkmen = it } ?: return null
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private fun createTurkmen(): OfflineRecognizer? {
        if (!store.isInstalled(OfflineSpeechModels.TK_ASR)) return null
        val dir = store.dirOf(OfflineSpeechModels.TK_ASR)
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OfflineModelConfig(
                omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                    model = "${dir.path}/model.int8.onnx"
                ),
                tokens = "${dir.path}/tokens.txt",
                numThreads = THREADS,
                provider = "cpu"
            )
        )
        return runCatching { OfflineRecognizer(config = config) }
            .onFailure { Log.e(TAG, "cannot load turkmen recognizer", it) }
            .getOrNull()
    }

    private fun decodeRussian(samples: FloatArray): String? {
        val recognizer = russian ?: createRussian()?.also { russian = it } ?: return null
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            // Хвост тишины, чтобы потоковая модель выдала последние слова.
            stream.acceptWaveform(FloatArray(SAMPLE_RATE / 2), SAMPLE_RATE)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    private fun createRussian(): OnlineRecognizer? {
        if (!russianAssetsPresent()) return null
        val config = OnlineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = FEATURE_DIM),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder = PhraseFrameDetector.ENCODER,
                    decoder = PhraseFrameDetector.DECODER,
                    joiner = PhraseFrameDetector.JOINER
                ),
                tokens = PhraseFrameDetector.TOKENS,
                numThreads = THREADS,
                provider = "cpu"
            ),
            enableEndpoint = false,
            decodingMethod = "greedy_search"
        )
        return runCatching { OnlineRecognizer(assetManager = context.assets, config = config) }
            .onFailure { Log.e(TAG, "cannot load russian recognizer", it) }
            .getOrNull()
    }

    private fun russianAssetsPresent(): Boolean = listOf(
        PhraseFrameDetector.ENCODER,
        PhraseFrameDetector.DECODER,
        PhraseFrameDetector.JOINER,
        PhraseFrameDetector.TOKENS
    ).all { path ->
        try {
            context.assets.open(path).close()
            true
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        private const val TAG = "LocalSpeechRecognizer"
        const val LANG_TK = "tk"
        const val LANG_RU = "ru"
        private const val SAMPLE_RATE = 16_000
        private const val FEATURE_DIM = 80
        private const val PCM_SCALE = 32768f
        private const val THREADS = 2
    }
}
