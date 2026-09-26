package com.omnix.assistant.voice.wakeword

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
import com.omnix.assistant.data.preferences.SettingsDataStore
import com.omnix.assistant.voice.wakeword.oww.ModelDigestMismatch
import com.omnix.assistant.voice.wakeword.oww.OpenWakeWordPipeline
import com.omnix.assistant.voice.wakeword.oww.OrtOwwSessions
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
 * Neural wake-word движок (§4 ТЗ): AudioRecord 16 кГц -> OpenWakeWordPipeline
 * -> DetectionPolicy (+ NoiseAdaptiveThreshold, owner review 2026-09-26).
 *
 * Владение микрофоном: AudioRecord создаётся в [start] и освобождается в
 * [stop]. Оркестратор держит движок запущенным ТОЛЬКО в STANDBY — перед
 * STT движок останавливается, конфликта за микрофон нет по построению.
 *
 * Сессии ONNX и пайплайн живут на уровне движка (вытеснено из runLoop,
 * owner review 2026-09-26, п.3/п.4): раньше каждый вход в STANDBY перечитывал
 * ~3.7 МБ ассетов и создавал три OrtSession заново — теперь один раз до
 * [destroy] (или до смены путей моделей в конфиге). Это же инфраструктура
 * для «тёплого» буфера: когда движок будет получать аудио и в фазе
 * AI_THINKING, стартовый прогон 1.3 с закроется без пересоздания.
 *
 * Ошибки громкие: отсутствующая модель, ПОВРЕЖДЁННАЯ модель (digest
 * mismatch), падение инференса, занятый микрофон, МЁРТВЫЙ микрофон
 * (константный поток, баг прошивок) и отозванный пермишен уходят в [errors].
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

    // Живут на уровне движка: переиспользуются между start/stop (owner
    // review п.3). Создаются в ensureSessions (worker-поток), закрываются в
    // [destroy] (произвольный поток) — volatile-видимость обязательна.
    @Volatile
    private var sessions: OrtOwwSessions? = null
    @Volatile
    private var pipeline: OpenWakeWordPipeline? = null
    @Volatile
    private var sessionAssetPaths: AssetPaths? = null

    /** Near-miss (owner review п.1): кольцо 2.5 с + рекордер, живут между запусками. */
    private val nearMissRing = AudioRingBuffer(
        SAMPLE_RATE * NearMissRecorder.CAPTURE_WINDOW_MS / 1000
    )
    @Volatile
    private var recorder: NearMissRecorder? = null

    @Volatile
    private var debugLogging = false

    @Volatile
    private var activeWakeWord = "Omni"

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
        sessions?.close()
        sessions = null
        pipeline = null
        sessionAssetPaths = null
        scope.cancel()
    }

    /**
     * Сброс patience-серии без перезапуска (owner review п.6): потеря
     * аудиопути (клип/наушники отключились) не должна допускать
     * «додумывания» детекции в новой акустике. Дёшево: только счётчики
     * [DetectionPolicy], запуск не трогаем.
     */
    override fun resetDetectionSeries() {
        policy.reset()
    }

    /** Снапшот метрик для device-validation (§12 ТЗ). */
    fun metricsSnapshot(): WakeWordMetrics.Snapshot = metrics.snapshot()

    /** Пути моделей — то, от чего зависят сессии (остальной конфиг сессий не меняет). */
    private data class AssetPaths(val model: String, val mel: String, val embedding: String)

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

        val s = try {
            ensureSessions(config)
        } catch (e: ModelDigestMismatch) {
            Log.e(TAG, "wakeword model digest mismatch", e)
            _errors.emit(
                WakeWordEngineError.ModelCorrupted(
                    e.assetPath, e.expectedSha256, e.actualSha256
                )
            )
            running.set(false)
            return
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

        val p = pipeline ?: OpenWakeWordPipeline(s, s, s).also { pipeline = it }
        val record = createAudioRecord()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            _errors.emit(WakeWordEngineError.MicrophoneUnavailable)
            running.set(false)
            return
        }

        // Компоненты кадра — на цикл: шумовой пол и окно мёртвого сигнала
        // перенабатываются с каждого входа в STANDBY (акустика могла
        // сменить комнату/устройство).
        val noise = NoiseAdaptiveThreshold()
        val dead = DeadSignalDetector()
        val nearMiss = if (config.nearMissCapture) {
            (recorder ?: NearMissRecorder(config.nearMissMinScore).also { recorder = it })
        } else {
            null
        }

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try {
                record.startRecording()
            } catch (e: SecurityException) {
                // Гонка: пермишен отозвали между проверкой и стартом.
                // Громко в errors, без тихой смерти (§21); release в finally ниже.
                Log.e(TAG, "startRecording denied", e)
                _errors.emit(WakeWordEngineError.PermissionDenied)
                running.set(false)
                return
            }
            val chunk = ShortArray(OpenWakeWordPipeline.CHUNK_SAMPLES)
            var chunkIndex = 0L
            while (running.get()) {
                val read = record.read(chunk, 0, chunk.size)
                if (read < 0) {
                    Log.w(TAG, "AudioRecord.read error=$read")
                    continue
                }
                if (read == 0) continue
                val frame = if (read == chunk.size) chunk else chunk.copyOf(read)

                // «Мёртвый» микрофон: 20 с константного потока (owner review п.5).
                if (dead.observe(frame)) {
                    Log.e(TAG, "microphone dead signal: constant stream for 20s")
                    _errors.emit(WakeWordEngineError.MicrophoneDeadSignal)
                    running.set(false)
                    break
                }

                // RMS до мел-спектрограммы: один проход, для шумового пола.
                val rms = noise.rmsLsb(frame)
                noise.update(rms)

                ring.write(chunk, 0, read)
                if (nearMiss != null) nearMissRing.write(chunk, 0, read)
                // Пайплайн сам добирает 1280-сэмпловые чанки из потока.
                val result = try {
                    p.accept(frame)
                } catch (e: Exception) {
                    Log.e(TAG, "wakeword inference failed", e)
                    _errors.emit(WakeWordEngineError.InferenceFailed(e.message ?: "inference failed"))
                    running.set(false)
                    break
                }
                chunkIndex++
                if (result == null) continue

                // Динамический порог: базовый + буст по SNR (owner review п.2b).
                val threshold = if (config.noiseAdaptiveThreshold) {
                    noise.effectiveThreshold(config.threshold, rms)
                } else {
                    config.threshold
                }
                val fired = policy.observe(result.score, threshold)
                metrics.recordFrame(result.score, fired, result.melMs, result.embMs, result.clfMs)
                if (debugLogging && chunkIndex % DEBUG_LOG_EVERY_CHUNK == 0L) {
                    Log.d(
                        TAG,
                        "score=${result.score} infer=${result.melMs + result.embMs + result.clfMs}ms" +
                            " (mel=${result.melMs} emb=${result.embMs} clf=${result.clfMs}) " +
                            "thr=$threshold floor=${String.format("%.0f", noise.noiseFloorLsb)}"
                    )
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
                } else {
                    captureNearMiss(nearMiss, result.score, rms, noise, config)
                }
            }
        } finally {
            try {
                record.stop()
            } catch (_: Exception) {
            }
            record.release()
            // Сессии НЕ закрываем (переиспользуются в следующем start);
            // контекст пайплайна сбрасываем — без stale-фич после STT.
            p.reset()
            ring.clear()
            nearMissRing.clear()
            policy.reset()
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

    /**
     * Сессии ONNX по путям текущего конфига: переиспользуются, пока пути не
     * поменялись; при смене — закрытые старые уходят, открываются новые.
     */
    private fun ensureSessions(config: WakeWordConfig): OrtOwwSessions {
        val paths = AssetPaths(config.modelAssetPath, config.melAssetPath, config.embeddingAssetPath)
        val current = sessions
        if (current != null && sessionAssetPaths == paths) return current
        current?.close()
        val fresh = OrtOwwSessions(context.assets, config).also { it.open() }
        sessions = fresh
        pipeline = null
        sessionAssetPaths = paths
        return fresh
    }

    /**
     * Near-miss захват (owner review п.1): аудио-хвост кадра с скором ≥
     * nearMissMinScore, который НЕ стал детекцией — будущие негативы v0.2.
     * Только app-private, с капом и манифестом без метаданных пользователя.
     */
    private fun captureNearMiss(
        nearMiss: NearMissRecorder?,
        score: Float,
        rms: Float,
        noise: NoiseAdaptiveThreshold,
        config: WakeWordConfig,
    ) {
        if (nearMiss == null) return
        val decision = nearMiss.shouldCapture(score, fired = false)
        if (!decision.capture) return
        val samples = nearMissRing.snapshotLast(nearMissRing.sizeSamples)
        val epochMs = System.currentTimeMillis()
        val file = File(
            context.filesDir.nearMissDir(),
            "nm-${epochMs}-${String.format("score%.3f", score)}.wav"
        )
        try {
            val bytes = nearMiss.writeWav(file, samples, NearMissRecorder.SAMPLE_RATE)
            val line = nearMiss.manifestLine(
                epochMs = epochMs,
                score = score,
                model = config.modelAssetPath.substringAfterLast('/'),
                threshold = config.threshold,
                snrDb = noise.snrDb(rms),
            )
            File(context.filesDir.nearMissDir(), "manifest.jsonl")
                .appendText(line + "\n", Charsets.UTF_8)
            // Кап хранилища: самые старые (имена = таймстампы) уходят первыми.
            val toDelete = nearMiss.filesToDelete(listNearMissWavs(File(context.filesDir)))
            toDelete.forEach { it.delete() }
            if (debugLogging) {
                Log.d(TAG, "NEAR-MISS captured ${bytes}B -> ${file.name}")
            }
        } catch (e: Exception) {
            // Захват данных не должен ронять детекцию: лог и дальше.
            Log.w(TAG, "near-miss capture failed", e)
        }
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
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord create denied (RECORD_AUDIO revoked?)", e)
            null
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
