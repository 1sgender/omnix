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
import com.omnix.assistant.voice.wakeword.phrase.PhraseFrameDetector
import com.omnix.assistant.voice.wakeword.phrase.WakeEngineKind
import com.omnix.assistant.voice.wakeword.phrase.WakePhrase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import com.omnix.assistant.core.crash.CrashCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Neural wake-word движок (§4 ТЗ): AudioRecord 16 кГц -> [FrameDetector]
 * -> DetectionPolicy (+ NoiseAdaptiveThreshold, owner review 2026-09-26).
 * Детектор по умолчанию — фраза «Окей, Омни» на sherpa-onnx
 * ([PhraseFrameDetector]); прежняя модель «Omni» ([OwwFrameDetector])
 * осталась для сравнения админом.
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
        /** Ошибок чтения микрофона подряд до остановки (~1 с с паузами). */
        private const val MAX_READ_ERRORS_IN_ROW = 50
        private const val READ_ERROR_PAUSE_MS = 20L
        /** Порог для фразы: детектор отдаёт скор 0 или 1. */
        private const val PHRASE_THRESHOLD = 0.5f
    }

    // Любая ошибка движка (ONNX Runtime, нативная библиотека, AudioRecord)
    // останавливает распознавание «Omni», но не роняет приложение: без
    // обработчика исключение фоновой корутины убивало процесс при включении.
    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "wakeword engine failed", throwable)
        CrashCapture.recordHandled("wakeword", throwable)
        running.set(false)
        lastProblem = "engine failed: ${throwable.javaClass.simpleName}"
        _errors.tryEmit(WakeWordEngineError.InferenceFailed(throwable.javaClass.simpleName))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + exceptionHandler)
    private val running = AtomicBoolean(false)

    /**
     * Номер текущего запуска. stop() его меняет, и прошлый цикл выходит на
     * следующем кадре, даже если start() уже снова поднял [running]: раньше
     * старый цикл продолжал работать рядом с новым на тех же сессиях ONNX.
     */
    private val generation = AtomicInteger(0)

    /** Циклы и закрытие сессий — строго по очереди, никогда во время вычисления. */
    private val loopMutex = Mutex()
    private var worker: Job? = null

    private val _detections = MutableSharedFlow<WakeWordDetection>(extraBufferCapacity = 4)
    override val detections: SharedFlow<WakeWordDetection> = _detections

    private val _errors = MutableSharedFlow<WakeWordEngineError>(extraBufferCapacity = 4)
    override val errors: SharedFlow<WakeWordEngineError> = _errors

    private val policy = DetectionPolicy()
    private val ring = AudioRingBuffer(RING_SAMPLES)
    private val metrics = WakeWordMetrics()

    // Живёт на уровне движка: переиспользуется между start/stop (owner
    // review п.3). Создаётся в ensureDetector (worker-поток), закрывается в
    // [destroy] (произвольный поток) — volatile-видимость обязательна.
    @Volatile
    private var detector: FrameDetector? = null
    @Volatile
    private var detectorKey: DetectorKey? = null

    /** Near-miss (owner review п.1): кольцо 2.5 с + рекордер, живут между запусками. */
    private val nearMissRing = AudioRingBuffer(
        SAMPLE_RATE * NearMissRecorder.CAPTURE_WINDOW_MS / 1000
    )
    @Volatile
    private var recorder: NearMissRecorder? = null

    @Volatile
    private var debugLogging = false

    /** Что слушает текущий запуск: «Окей, Омни» или «Omni» прежней модели (для «Диагностики»). */
    @Volatile
    var activeWakeWord = WakePhrase.ALL.displayName
        private set

    /**
     * Почему движок последний раз остановился сам (ошибка или выключен в
     * настройках); null, пока текущий запуск слушает. Для «Диагностики»:
     * [errors] не хранит прошлых событий.
     */
    @Volatile
    var lastProblem: String? = null
        private set

    /** Базовый порог из настроек и действующий порог последнего кадра (с поправкой на шум). */
    @Volatile
    var baseThreshold: Float = 0f
        private set

    @Volatile
    var effectiveThreshold: Float = 0f
        private set

    /** Сколько раз AudioRecord.read вернул ошибку и её последний код (0 — ошибок не было). */
    @Volatile
    var micReadErrors: Long = 0
        private set

    @Volatile
    var lastMicReadError: Int = 0
        private set

    private suspend fun report(error: WakeWordEngineError) {
        lastProblem = error.toString()
        _errors.emit(error)
    }

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        val run = generation.incrementAndGet()
        // Новый цикл ждёт, пока прошлый выйдет (он видит смену номера на
        // следующем кадре), и только потом открывает микрофон.
        worker = scope.launch {
            loopMutex.withLock {
                if (generation.get() == run) runLoop(run)
            }
        }
    }

    override fun stop() {
        if (!running.compareAndSet(true, false)) return
        generation.incrementAndGet()
        worker?.cancel()
        worker = null
        policy.reset()
    }

    override fun isRunning(): Boolean = running.get()

    override fun destroy() {
        stop()
        // Сессии закрываются под замком цикла — никогда посреди вычисления
        // (закрытие сессии ONNX во время run() роняет процесс нативно).
        // Область не отменяем: движок один на приложение и снова нужен при
        // следующем включении ассистента.
        scope.launch {
            loopMutex.withLock {
                if (running.get()) return@withLock
                detector?.close()
                detector = null
                detectorKey = null
            }
        }
    }

    /** Закончить ТОЛЬКО свой запуск: новый, уже поднятый start(), не трогаем. */
    private fun endRun(run: Int) {
        if (generation.get() == run) running.set(false)
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

    /** То, от чего зависит детектор (остальной конфиг его не меняет). */
    private data class DetectorKey(
        val engine: WakeEngineKind,
        val phrase: WakePhrase,
        val model: String,
        val mel: String,
        val embedding: String,
    )

    private suspend fun runLoop(run: Int) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            report(WakeWordEngineError.PermissionDenied)
            endRun(run)
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
            lastProblem = "disabled in settings"
            endRun(run)
            return
        }
        applyConfig(config)

        val p = try {
            ensureDetector(config)
        } catch (e: ModelDigestMismatch) {
            Log.e(TAG, "wakeword model digest mismatch", e)
            report(
                WakeWordEngineError.ModelCorrupted(
                    e.assetPath, e.expectedSha256, e.actualSha256
                )
            )
            endRun(run)
            return
        } catch (e: IOException) {
            Log.e(TAG, "wakeword model load failed", e)
            val missing = missingAssetPath(config, e)
            if (missing != null) {
                report(WakeWordEngineError.ModelMissing(missing))
            } else {
                report(WakeWordEngineError.InferenceFailed(e.message ?: "model load failed"))
            }
            endRun(run)
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // OrtException, UnsatisfiedLinkError и прочее из нативного движка:
            // распознавание «Omni» недоступно, приложение продолжает работать.
            Log.e(TAG, "wakeword sessions failed", e)
            CrashCapture.recordHandled("wakeword sessions", e)
            report(WakeWordEngineError.InferenceFailed(e.javaClass.simpleName))
            endRun(run)
            return
        }

        val record = createAudioRecord()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            report(WakeWordEngineError.MicrophoneUnavailable)
            endRun(run)
            return
        }

        // Компоненты кадра — на цикл: шумовой пол и окно мёртвого сигнала
        // перенабатываются с каждого входа в STANDBY (акустика могла
        // сменить комнату/устройство).
        val noise = NoiseAdaptiveThreshold()
        val dead = DeadSignalDetector()
        val nearMiss = if (config.nearMissCapture && config.engine == WakeEngineKind.LEGACY_OMNI) {
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
                report(WakeWordEngineError.PermissionDenied)
                endRun(run)
                return
            }
            lastProblem = null
            val chunk = ShortArray(OpenWakeWordPipeline.CHUNK_SAMPLES)
            var chunkIndex = 0L
            var readErrorsInRow = 0
            while (running.get() && generation.get() == run) {
                val read = record.read(chunk, 0, chunk.size)
                if (read < 0) {
                    // Раньше ошибка чтения молча повторялась без паузы: движок
                    // «работал», но ничего не слышал. Микрофон потерян
                    // (DEAD_OBJECT) или ошибки идут подряд — говорим об этом.
                    Log.w(TAG, "AudioRecord.read error=$read")
                    micReadErrors++
                    lastMicReadError = read
                    readErrorsInRow++
                    if (read == AudioRecord.ERROR_DEAD_OBJECT || readErrorsInRow >= MAX_READ_ERRORS_IN_ROW) {
                        report(WakeWordEngineError.MicrophoneUnavailable)
                        endRun(run)
                        break
                    }
                    delay(READ_ERROR_PAUSE_MS)
                    continue
                }
                readErrorsInRow = 0
                if (read == 0) continue
                val frame = if (read == chunk.size) chunk else chunk.copyOf(read)

                // «Мёртвый» микрофон: 20 с константного потока (owner review п.5).
                if (dead.observe(frame)) {
                    Log.e(TAG, "microphone dead signal: constant stream for 20s")
                    report(WakeWordEngineError.MicrophoneDeadSignal)
                    endRun(run)
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
                    report(WakeWordEngineError.InferenceFailed(e.message ?: "inference failed"))
                    endRun(run)
                    break
                }
                chunkIndex++
                if (result == null) continue

                // Динамический порог: базовый + буст по SNR (owner review п.2b).
                // Фраза даёт скор 0 или 1 — поправка на шум ей не нужна.
                val threshold = if (config.noiseAdaptiveThreshold && config.engine == WakeEngineKind.LEGACY_OMNI) {
                    noise.effectiveThreshold(config.threshold, rms)
                } else {
                    baseThreshold
                }
                effectiveThreshold = threshold
                val fired = policy.observe(result.score, threshold)
                metrics.recordFrame(result.score, fired, result.melMs, result.embMs, result.clfMs)
                if (debugLogging && chunkIndex % DEBUG_LOG_EVERY_CHUNK == 0L) {
                    Log.d(
                        TAG,
                        "score=${result.score} infer=${result.melMs + result.embMs + result.clfMs}ms" +
                            " (mel=${result.melMs} emb=${result.embMs} clf=${result.clfMs}) " +
                            "thr=$threshold floor=${noise.noiseFloorLsb}"
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
            // Детектор НЕ закрываем (переиспользуется в следующем start);
            // накопленный звук сбрасываем — без stale-фич после STT.
            p.reset()
            ring.clear()
            nearMissRing.clear()
            policy.reset()
        }
    }

    private fun applyConfig(config: WakeWordConfig) {
        val phrase = config.engine == WakeEngineKind.PHRASE
        // Фраза: скор кадра 0/1, одного кадра достаточно.
        val threshold = if (phrase) PHRASE_THRESHOLD else config.threshold
        baseThreshold = threshold
        effectiveThreshold = threshold
        policy.threshold = threshold
        policy.patienceFrames = if (phrase) 1 else config.patienceFrames
        policy.cooldownMs = config.cooldownMs
        policy.reset()
        debugLogging = config.debugLogging
        activeWakeWord = if (phrase) config.phrase.displayName else config.wakeWord
    }

    /**
     * Детектор текущего конфига: переиспользуется, пока движок, фраза и пути
     * моделей не поменялись; при смене старый закрывается, создаётся новый.
     */
    private fun ensureDetector(config: WakeWordConfig): FrameDetector {
        val key = DetectorKey(
            config.engine, config.phrase,
            config.modelAssetPath, config.melAssetPath, config.embeddingAssetPath,
        )
        val current = detector
        if (current != null && detectorKey == key) return current
        current?.close()
        detector = null
        val fresh = when (config.engine) {
            WakeEngineKind.PHRASE -> PhraseFrameDetector(context.assets, config.phrase)
            WakeEngineKind.LEGACY_OMNI -> OwwFrameDetector(OrtOwwSessions(context.assets, config).also { it.open() })
        }
        detector = fresh
        detectorKey = key
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
            "nm-${epochMs}-${String.format(Locale.US, "score%.3f", score)}.wav"
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
            val toDelete = nearMiss.filesToDelete(context.filesDir.listNearMissWavs())
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
        val paths = when (config.engine) {
            WakeEngineKind.PHRASE -> PhraseFrameDetector.ASSETS
            WakeEngineKind.LEGACY_OMNI -> listOf(config.modelAssetPath, config.melAssetPath, config.embeddingAssetPath)
        }
        return paths.firstOrNull { msg.contains(it) }
    }
}
