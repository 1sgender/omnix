package com.jarvis.assistant.voice.wakeword.oww

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.res.AssetManager
import com.jarvis.assistant.voice.wakeword.WakeWordConfig
import java.io.Closeable
import java.io.IOException
import java.nio.FloatBuffer

/**
 * ORT-реализация трёх стадий openWakeWord (§3 ТЗ).
 *
 * Ключевое отличие от сторонних портов: сессии создаются ОДИН раз в [open]
 * и переиспользуются на каждый 80-мс чанк. Создание OrtSession на каждый чанк
 * (как в Re-MENTIA) — production-киллер для CPU и батареи.
 *
 * Не потокобезопасен: вызывается только из однопоточного цикла
 * NeuralWakeWordEngine.
 */
class OrtOwwSessions(
    private val assets: AssetManager,
    private val config: WakeWordConfig,
) : MelComputer, EmbeddingComputer, WakeClassifier, Closeable {

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private var melSession: OrtSession? = null
    private var embSession: OrtSession? = null
    private var clfSession: OrtSession? = null

    /**
     * Открыть все три сессии. Fail-fast: при отсутствующем/битом ассете
     * кидает IOException с путём — движок превращает это в громкую ошибку.
     */
    @Throws(IOException::class)
    fun open() {
        if (melSession != null) return
        try {
            melSession = openSession(config.melAssetPath)
            embSession = openSession(config.embeddingAssetPath)
            clfSession = openSession(config.modelAssetPath)
        } catch (e: Exception) {
            close()
            throw IOException("wakeword model load failed: ${e.message}", e)
        }
    }

    private fun openSession(assetPath: String): OrtSession {
        val bytes = try {
            assets.open(assetPath).use { it.readBytes() }
        } catch (e: IOException) {
            throw IOException("wakeword model asset missing: $assetPath", e)
        }
        return env.createSession(bytes)
    }

    override fun compute(samples: FloatArray): Array<FloatArray> {
        val session = checkNotNull(melSession) { "mel session not open" }
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, FloatBuffer.wrap(samples), longArrayOf(1, samples.size.toLong())).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                // Выход [1, 1, T, 32]; дальше squeeze + x/10+2 как в эталоне.
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>>
                val frames = out[0][0]
                return Array(frames.size) { t ->
                    FloatArray(OpenWakeWordPipeline.MEL_BINS) { c -> frames[t][c] / 10f + 2f }
                }
            }
        }
    }

    override fun embed(window: Array<Array<Array<FloatArray>>>): Array<FloatArray> {
        val session = checkNotNull(embSession) { "embedding session not open" }
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, window).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                // Выход [B, 1, 1, 96] -> reshape в [B, 96] как в эталоне.
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<Array<Array<FloatArray>>>
                return Array(out.size) { b -> out[b][0][0] }
            }
        }
    }

    override fun score(features: Array<Array<FloatArray>>): Float {
        val session = checkNotNull(clfSession) { "classifier session not open" }
        val inputName = session.inputNames.first()
        OnnxTensor.createTensor(env, features).use { tensor ->
            session.run(mapOf(inputName to tensor)).use { result ->
                // Выход [1, 1].
                @Suppress("UNCHECKED_CAST")
                val out = result[0].value as Array<FloatArray>
                return out[0][0]
            }
        }
    }

    override fun close() {
        try {
            melSession?.close()
        } catch (_: Exception) {
        }
        try {
            embSession?.close()
        } catch (_: Exception) {
        }
        try {
            clfSession?.close()
        } catch (_: Exception) {
        }
        melSession = null
        embSession = null
        clfSession = null
        // OrtEnvironment общий — не закрываем.
    }
}
