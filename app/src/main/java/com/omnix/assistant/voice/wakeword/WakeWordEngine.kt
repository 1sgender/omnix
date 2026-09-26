package com.omnix.assistant.voice.wakeword

import kotlinx.coroutines.flow.SharedFlow

/**
 * Конфигурация neural wake-word движка. Хранится в DataStore (см. SettingsDataStore),
 * движок подхватывает изменения без перезапуска сервиса.
 *
 * Порог по умолчанию 0.5 — дефолт openWakeWord; точное значение подбирается
 * на устройстве по протоколу docs/WAKEWORD_NEURAL.md (§ threshold tuning).
 */
data class WakeWordConfig(
    val enabled: Boolean = true,
    val wakeWord: String = "Omni",
    // omni_v0.1: собственная synthetic-data модель на фразу «Omni»
    // (training/OMNI_V0.1_REPORT.md). hey_jarvis_v0.1 сохранён в assets как
    // legacy-фолбэк до приёмки real-data v0.2 (training/README.md).
    val modelAssetPath: String = "wakeword/omni_v0.1.onnx",
    val melAssetPath: String = "wakeword/melspectrogram.onnx",
    val embeddingAssetPath: String = "wakeword/embedding_model.onnx",
    // 0.35: recall 0.93 / FP 4% на held-out голосах (см. отчёт); регулируется
    // настройкой WAKEWORD_THRESHOLD.
    val threshold: Float = 0.35f,
    /** Сколько подряд фреймов (по 80 мс) выше порога нужно для срабатывания (oWW 'patience'). */
    val patienceFrames: Int = 2,
    val cooldownMs: Long = 2000L,
    val debugLogging: Boolean = false,
    /**
     * Динамический буст порога по шуму (owner review 2026-09-26, п.2b):
     * при низком SNR порог временно растёт до +0.10, в тишине — базовый.
     */
    val noiseAdaptiveThreshold: Boolean = true,
    /**
     * Near-miss захват для данных v0.2 (owner review 2026-09-26, п.1).
     * По умолчанию ВЫКЛ: сырое аудио записывается только в явной
     * бете/деве, только в app-private хранилище, наружу не уходит.
     */
    val nearMissCapture: Boolean = false,
    /** Минимальный скор кадра для near-miss захвата (ниже — чистый шум). */
    val nearMissMinScore: Float = 0.25f,
)

/**
 * Событие детекции. Единственное, что оркестратор знает о wake word:
 * ONNX, модель, препроцессинг и AudioRecord остаются внутри движка.
 */
data class WakeWordDetection(
    val wakeWord: String,
    /** Сырой скор классификатора 0..1 (не калиброванная вероятность). */
    val score: Float,
    /** Метка времени детекции, SystemClock.elapsedRealtime(). */
    val timestampMs: Long,
    /** Сквозная задержка инференса трёх стадий на триггерном чанке, мс. */
    val inferenceLatencyMs: Long = 0L,
)

/** Ошибки движка. Громкие и типизированные: тихих мёртвых состояний быть не должно. */
sealed interface WakeWordEngineError {
    /** ONNX-файл отсутствует в assets (сборка без модели). */
    data class ModelMissing(val assetPath: String) : WakeWordEngineError
    /**
     * ONNX-файл подменён/повреждён: sha256 не совпал с закреплённым в коде
     * (ModelDigests, owner review 2026-09-26, п.7). Грузить модель с
     * чужим дайджестом нельзя — детекция стала бы непредсказуемой.
     */
    data class ModelCorrupted(
        val assetPath: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : WakeWordEngineError
    /** Падение инференса (битый тензор, OOM ORT и т.п.). */
    data class InferenceFailed(val reason: String) : WakeWordEngineError
    /** AudioRecord не инициализировался / микрофон занят. */
    data object MicrophoneUnavailable : WakeWordEngineError
    /** RECORD_AUDIO отозвано. */
    data object PermissionDenied : WakeWordEngineError
    /**
     * «Мёртвый» микрофон (owner review 2026-09-26, п.5): AudioRecord жив,
     * но поток — константа (баг прошивок носимых устройств). 20 секунд
     * сигнала с размахом ≤ 4 LSB — движок останавливается с этой ошибкой
     * вместо молчаливой работы вслепую.
     */
    data object MicrophoneDeadSignal : WakeWordEngineError
}

/**
 * Абстракция neural wake-word детектора (§4 ТЗ).
 *
 * Контракт владения микрофоном: движок держит AudioRecord ТОЛЬКО пока запущен.
 * Оркестратор останавливает движок перед STT и запускает после — двух
 * одновременных захватов микрофона не существует.
 */
interface WakeWordEngine {
    val detections: SharedFlow<WakeWordDetection>
    val errors: SharedFlow<WakeWordEngineError>
    fun start()
    fun stop()
    fun isRunning(): Boolean
    fun destroy()
    /**
     * Сбросить текущую patience-серию детекции БЕЗ перезапуска движка
     * (owner review 2026-09-26, п.6). Назначение: потеря аудиопути
     * (клип/наушники отключились) — удар «Omni» в старой акустике не
     * должен доживаться до подтверждения в новой.
     */
    fun resetDetectionSeries()
}
