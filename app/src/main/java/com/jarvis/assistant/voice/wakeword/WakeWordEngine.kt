package com.jarvis.assistant.voice.wakeword

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
    val wakeWord: String = "Hey Jarvis",
    val modelAssetPath: String = "wakeword/hey_jarvis_v0.1.onnx",
    val melAssetPath: String = "wakeword/melspectrogram.onnx",
    val embeddingAssetPath: String = "wakeword/embedding_model.onnx",
    val threshold: Float = 0.5f,
    /** Сколько подряд фреймов (по 80 мс) выше порога нужно для срабатывания (oWW 'patience'). */
    val patienceFrames: Int = 2,
    val cooldownMs: Long = 2000L,
    val debugLogging: Boolean = false,
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
    /** Падение инференса (битый тензор, OOM ORT и т.п.). */
    data class InferenceFailed(val reason: String) : WakeWordEngineError
    /** AudioRecord не инициализировался / микрофон занят. */
    data object MicrophoneUnavailable : WakeWordEngineError
    /** RECORD_AUDIO отозвано. */
    data object PermissionDenied : WakeWordEngineError
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
}
