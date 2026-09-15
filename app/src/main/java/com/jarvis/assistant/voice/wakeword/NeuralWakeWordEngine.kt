package com.jarvis.assistant.voice.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.data.settings.SettingsDataStore
import com.jarvis.assistant.voice.wakeword.oww.OpenWakeWordPipeline
import com.jarvis.assistant.voice.wakeword.oww.OrtOwwSessions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Neural wake-word движок (§4 ТЗ): AudioRecord 16 кГц -> OpenWakeWordPipeline -> DetectionPolicy.
 *
 * Владение микрофоном: AudioRecord создаётся в [start] и освобождается в [stop].
 * Оркестратор держит движок запущенным ТОЛЬКО в STANDBY — перед STT движок
 * останавливается, конфликта за микрофон нет по построению.
 *
 * Ошибки громкие: отсутствующая модель, падение инференса, занятый микрофон
 * и отозванный пермишен уходят в [errors], тихих мёртвых состояний нет.
 */
@Singleton
class NeuralWakeWordEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsDataStore,
) : WakeWordEngine {

    companion object {
        private const val TAG = "NeuralWakeWord"
        private const val SAMPLE_RATE = 16000
        /** Хвост аудио для метрик/будущего локального STT: 640 мс. */
        private const val RING_SAMPLES = (SAMPLE_RATE * 640) / 1000
        /** Период debug-лога скоров (каждый N-й чанк), чтобы не спамить. */
        private const val DEBUG_LOG_EVERY_CHUNK = 25
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = AtomicBoolean(false)
    private var worker: Job? = null

    private val _detections = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 4)
    override val detections: SharedFlow<WakeWordDetection> = _detections

    private val _errors = MutableSharedFlow<WakeWordEngineError>(extraBufferCapacity = 4)
    override val errors: SharedFlow<WakeWordEngineError> = _errors

    private val policy = DetectionPolicy()
    private val ring = AudioRingBuffer(RING_SAMPLES)
    private val metrics = WakeWordMetrics()

    @Volatile
    private var debugLogging = false

    @Volatile
    private var activeWakeWord = "Hey Jarvis"

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = scope.launch { runLoop() }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        worker?.cancel()
        worker = null
        policy.reset()
    }

    override fun isRunning(): Boolean = running.get()

    override fun destroy() {
        stop()
        scope.cancel()
    }

    /** Снапшот метрик для device-validation (§12 ТЗ). */
    fun metricsSnapshot(): WakeWordMetrics.Snapshot = metrics.snapshot()

    private suspend fun runLoop() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            _errors.emit(WakeWordEngineError.PermissionDenied)
            running.set(false)
            return
        }

        val config = try {
            settings.wakeWordConfig.first()
        } catch (e: Exception) {
            Log.e(TAG, "wakeword config read failed", e)
            WakeWordConfig()
        }
        if (!config.enabled) {
            Log.i(TAG, "wakeword disabled in settings")
            running.set(false)
            return
        }
        applyConfig(config)

        val sessions = OrtOwwSessions(context.assets, config)
        try {
            sessions.open()
        } catch (e: IOException) {
            Log.e(TAG, "wakeword model load failed", e)
            val missing = missingAssetPath(config, e)
            if (missing != null) {
                _errors.emit(WakeWordEngineError.ModelMissing(missing))
            } else {
                _errors.emit(WakeWordEngineError.InferenceFailed(e.message ?: "model load failed"))
            }
            running.set(false)
            return
        }

        val pipeline = OpenWakeWordPipeline(sessions, sessions, sessions)
        val record = createAudioRecord()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            sessions.close()
            _errors.emit(WakeWordEngineError.MicrophoneUnavailable)
            running.set(false)
            return
        }

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            record.startRecording()
            val chunk = ShortArray(OpenWakeWordPipeline.CHUNK_SAMPLES)
            var chunkIndex = 0L
            while (running.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error=$read")
                    continue
                }
                if (read == 0) continue
                ring.write(chunk, 0, read)
                // Пайплайн сам добирает 1280-сэмпловые чанки из потока.
                val result = try {
                    if (read == chunk.size) pipeline.accept(chunk) else pipeline.accept(chunk.copyOf(read))
                } catch (e: Exception) {
                    Log.e(TAG, "wakeword inference failed", e)
                    _errors.emit(WakeWordEngineError.InferenceFailed(e.message ?: "inference failed"))
                    running.set(false)
                    break
                }
                chunkIndex++
                if (result == null) continue
                val fired = policy.observe(result.score)
                metrics.recordFrame(result.score, fired, result.melMs, result.embMs, result.clfMs)
                if (debugLogging && chunkIndex % DEBUG_LOG_EVERY_CHUNK == 0L) {
                    Log.d(TAG, "score=${result.score} infer=${result.melMs + result.embMs + result.clfMs}ms")
                }
                if (fired) {
                    val onset = ring.snapshotLast(RING_SAMPLES)
                    if (debugLogging) {
                        Log.d(TAG, "WAKE score=${result.score} onsetSamples=${onset.size}")
                    }
                    _detections.emit(
                        WakeWordDetection(
                            wakeWord = activeWakeWord,
                            score = result.score,
                            timestampMs = SystemClock.elapsedRealtime(),
                            inferenceLatencyMs = result.melMs + result.embMs + result.clfMs,
                        ),
                    )
                }
            }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            sessions.close()
            pipeline.reset()
            ring.clear()
        }
    }

    private fun applyConfig(config: WakeWordConfig) {
        policy.threshold = config.threshold
        policy.patienceFrames = config.patienceFrames
        policy.cooldownMs = config.cooldownMs
        policy.reset()
        debugLogging = config.debugLogging
        activeWakeWord = config.wakeWord
    }

    private fun createAudioRecord(): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuf <= 0) return null
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, OpenWakeWordPipeline.CHUNK_SAMPLES * 8),
            )
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord create failed", e)
            null
        }
    }

    private fun missingAssetPath(config: WakeWordConfig, e: IOException): String? {
        val msg = e.message ?: return null
        return listOf(config.modelAssetPath, config.melAssetPath, config.embeddingAssetPath)
            .firstOrNull { msg.contains(it) }
    }
}
