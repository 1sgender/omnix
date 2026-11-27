package com.omnix.assistant.voice.wakeword.personal

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.omnix.assistant.voice.wakeword.AudioRingBuffer
import com.omnix.assistant.voice.wakeword.WakeWordConfig
import com.omnix.assistant.voice.wakeword.oww.OpenWakeWordPipeline
import com.omnix.assistant.voice.wakeword.oww.OrtOwwSessions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Тренировка «Омни» и фоновая проверка — свой захват микрофона и тот же детектор, что в фоне.
 *
 *  - [recordAttempt]: человек говорит «Омни», лучший момент попытки становится примером «я»;
 *  - [backgroundTest]: детектор слушает обычную жизнь, ничего не включая; каждое срабатывание —
 *    пример «не я» (человек в это время «Омни» не говорит);
 *  - [exportZip]: все сохранённые записи одним архивом — для глубокого переобучения модели.
 *
 * Пока идёт тренировка, фоновый движок «Омни» должен быть остановлен (микрофон один).
 */
@Singleton
class WakeTrainer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: PersonalWakeStore
) {
    data class Attempt(val accepted: Boolean, val score: Float)

    /** Одна попытка: [seconds] секунд слушаем, берём лучший момент. */
    suspend fun recordAttempt(seconds: Float = ATTEMPT_SECONDS): Attempt = withContext(Dispatchers.Default) {
        var bestScore = 0f
        var bestFeatures: FloatArray? = null
        var bestAudio: ShortArray? = null
        val ring = AudioRingBuffer(SAMPLE_RATE * 2)
        capture((seconds * SAMPLE_RATE).toInt()) { frame, result ->
            ring.write(frame, 0, frame.size)
            val f = result?.features
            if (result != null && f != null && result.score > bestScore) {
                bestScore = result.score
                bestFeatures = WakeFeatures.summarize(f)
                bestAudio = ring.snapshotLast(SAMPLE_RATE * 2)
            }
            true
        }
        val features = bestFeatures
        val accepted = features != null && bestScore >= ACCEPT_SCORE
        if (accepted && features != null) store.addPositive(features, bestAudio)
        Attempt(accepted, bestScore)
    }

    /**
     * Фоновая проверка на [durationMs]: срабатывания (скор ≥ [FIRE_SCORE], не чаще раза в 2 с)
     * сохраняются как «не я». [onProgress] — прошло мс и сколько срабатываний.
     */
    suspend fun backgroundTest(durationMs: Long, onProgress: (Long, Int) -> Unit): Int = withContext(Dispatchers.Default) {
        var hits = 0
        var lastHitSample = -COOLDOWN_SAMPLES
        var samples = 0L
        val ring = AudioRingBuffer(SAMPLE_RATE * 2)
        capture((durationMs * SAMPLE_RATE / 1000).toInt()) { frame, result ->
            samples += frame.size
            ring.write(frame, 0, frame.size)
            val f = result?.features
            if (result != null && f != null && result.score >= FIRE_SCORE && samples - lastHitSample >= COOLDOWN_SAMPLES) {
                lastHitSample = samples
                hits++
                store.addNegative(WakeFeatures.summarize(f), ring.snapshotLast(SAMPLE_RATE * 2))
            }
            if (samples % (SAMPLE_RATE / 2) < frame.size) onProgress(samples * 1000 / SAMPLE_RATE, hits)
            true
        }
        hits
    }

    /** Архив всех записей примеров для отправки; null — записей нет. */
    fun exportZip(): File? {
        val clips = store.clips()
        if (clips.isEmpty()) return null
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        val zip = File(dir, "omni-samples-${System.currentTimeMillis()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { out ->
            clips.forEach { f ->
                out.putNextEntry(ZipEntry(f.name))
                f.inputStream().use { it.copyTo(out) }
                out.closeEntry()
            }
        }
        return zip
    }

    /**
     * Захват [totalSamples] сэмплов: каждый кадр уходит в детектор, [onFrame] получает кадр и
     * результат детектора (если готов). false из [onFrame] — остановиться раньше.
     */
    @SuppressLint("MissingPermission") // Экран тренировки открывается с разрешением на микрофон.
    private suspend fun capture(totalSamples: Int, onFrame: (ShortArray, OpenWakeWordPipeline.FrameResult?) -> Boolean) {
        val sessions = OrtOwwSessions(context.assets, WakeWordConfig())
        try {
            sessions.open()
            val pipeline = OpenWakeWordPipeline(sessions, sessions, sessions)
            val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, CHUNK * 8)
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("microphone unavailable")
            }
            try {
                record.startRecording()
                val chunk = ShortArray(CHUNK)
                var done = 0
                while (done < totalSamples && currentCoroutineContext().isActive) {
                    val n = record.read(chunk, 0, chunk.size)
                    if (n <= 0) continue
                    val frame = if (n == chunk.size) chunk.copyOf() else chunk.copyOf(n)
                    done += n
                    if (!onFrame(frame, pipeline.accept(frame))) break
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "training capture failed: ${e.javaClass.simpleName}")
            throw e
        } finally {
            runCatching { sessions.close() }
        }
    }

    companion object {
        private const val TAG = "WakeTrainer"
        private const val SAMPLE_RATE = 16_000
        private const val CHUNK = OpenWakeWordPipeline.CHUNK_SAMPLES
        const val ATTEMPT_SECONDS = 3f

        /** Попытка засчитывается, если детектор хоть немного узнал «Омни». */
        const val ACCEPT_SCORE = 0.15f

        /** Порог срабатывания при фоновой проверке — как у детектора с личным фильтром. */
        const val FIRE_SCORE = 0.5f
        private const val COOLDOWN_SAMPLES = SAMPLE_RATE * 2L
    }
}
