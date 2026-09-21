package com.omnix.assistant.agent.localai

import com.omnix.assistant.agent.decision.ExecutionRequest
import java.util.Locale

/**
 * Результат работы локальной модели (Этап 2).
 *
 * Три состояния намеренно разделены (пункт 19 ТЗ):
 *  - [Success]     — модель сгенерировала ответ;
 *  - [Unsupported] — модель НЕ БЕРЁТСЯ за запрос (нужен web, модель не
 *                    установлена, запрос — device-команда). Это НЕ ошибка:
 *                    ExecutionDecisionEngine спокойно уходит в Cloud/Agent;
 *  - [FailedToFallback] — локальный слой не смог СТАРТОВАТЬ (инициализация
 *                    модели провалилась). Решение владельца 2026-09-21: запрос
 *                    НЕ погибает — уходит в облако, а пользователю показывается
 *                    сообщение про офлайн-версию;
 *  - [Error]       — реальный сбой во время генерации (runtime бросил,
 *                    пустой ответ) — остаётся честной ошибкой.
 */
sealed class LocalAiResult {

    data class Success(
        val text: String,
        val metrics: InferenceMetrics = InferenceMetrics()
    ) : LocalAiResult()

    data class Unsupported(val reason: String) : LocalAiResult()

    /**
     * Инициализация модели провалилась: не приговор запросу, а повод уйти в
     * облако и предупредить пользователя (решение владельца 2026-09-21).
     */
    data class FailedToFallback(val reason: String) : LocalAiResult()

    data class Error(val message: String) : LocalAiResult()
}

/**
 * Параметры генерации (пункт 9 ТЗ) — не хардкодятся внутри runtime.
 *
 * Значения по умолчанию рассчитаны на ГОЛОСОВОЙ ассистент: ответ уходит в TTS,
 * поэтому он должен быть коротким. 256 токенов ≈ 2-4 предложения на русском
 * (кириллица токенизируется плотнее латиницы) — это осознанно немного.
 */
data class GenerationConfig(
    val maxTokens: Int = 256,
    val temperature: Float = 0.7f,
    val topP: Float = 0.95f,
    val topK: Int = 40,
    val randomSeed: Int = 0
) {
    init {
        require(maxTokens in 1..2048) { "maxTokens вне разумного диапазона: $maxTokens" }
        require(temperature.isFinite() && temperature in 0f..2f) {
            "temperature должна быть конечной и в диапазоне 0..2"
        }
        require(topP.isFinite() && topP in 0f..1f) { "topP должен быть в диапазоне 0..1" }
        require(topK in 1..1_000) { "topK должен быть в диапазоне 1..1000" }
    }

    companion object {
        /** Профиль для голоса: максимально короткий ответ. */
        val VOICE = GenerationConfig(maxTokens = 192, temperature = 0.6f)

        /** Профиль для чата: можно чуть длиннее. */
        val CHAT = GenerationConfig(maxTokens = 320, temperature = 0.7f)

        fun forRequest(request: ExecutionRequest): GenerationConfig =
            when (request.source) {
                com.omnix.assistant.agent.decision.RequestSource.VOICE -> VOICE
                com.omnix.assistant.agent.decision.RequestSource.CHAT -> CHAT
            }
    }
}

/**
 * Диагностика инференса (пункт 20 ТЗ).
 *
 * Намеренно простая data-структура, а не telemetry-система: значения только
 * логируются и доступны для отладки.
 */
data class InferenceMetrics(
    val promptChars: Int = 0,
    val responseChars: Int = 0,
    val latencyMs: Long = 0L,
    /** Время до первого токена; -1, если streaming не использовался. */
    val timeToFirstTokenMs: Long = -1L,
    val approxTokensPerSecond: Float = 0f
) {
    /** Строка для лога — без содержимого запроса и ответа. */
    fun toLogString(): String = buildString {
        append("latencyMs=").append(latencyMs)
        if (timeToFirstTokenMs >= 0) append(" | ttftMs=").append(timeToFirstTokenMs)
        append(" | promptChars=").append(promptChars)
        append(" | responseChars=").append(responseChars)
        append(" | ~tok/s=").append(String.format(Locale.ROOT, "%.1f", approxTokensPerSecond))
    }
}

/** Результат одной генерации на уровне runtime. */
data class LocalGeneration(
    val text: String,
    val metrics: InferenceMetrics
)

/**
 * Состояние локальной модели — для логов, настроек и честного UI.
 */
sealed class LocalModelState {

    /** Модель ещё не загружалась (lazy init). */
    data object NotInitialized : LocalModelState()

    data object Loading : LocalModelState()

    data class Ready(val modelId: String, val loadTimeMs: Long) : LocalModelState()

    /**
     * Файла модели нет на устройстве. Это ОЖИДАЕМОЕ состояние: модель весит
     * ~521 МБ и не входит в APK — приложение скачивает её само после
     * одноразового согласия пользователя (см. docs/LOCAL_AI.md).
     */
    data class NotInstalled(val expectedPath: String) : LocalModelState()

    /**
     * Файл модели скачивается через системный DownloadManager.
     * Запросы в это время честно уходят в Cloud AI (Unsupported), а не ждут.
     */
    data class Downloading(
        val progressPercent: Int,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : LocalModelState()

    /**
     * Скачивание не удалось (сеть, место, несовпадение размера).
     * Состояние повторяемое: из настроек можно запустить загрузку заново.
     */
    data class DownloadFailed(val reason: String) : LocalModelState()

    /** Модель есть, но инициализация упала — это уже ошибка. */
    data class Failed(val reason: String) : LocalModelState()

    /**
     * Модель скачана, но устройству не хватает свободной RAM для запуска
     * (нужно ~[requiredMb] МБ). Это ОЖИДАЕМОЕ состояние слабого устройства,
     * а не сбой — как [NotInstalled], оно уходит в Cloud AI (Unsupported),
     * а не в Error. Проверка повторяется при каждой попытке: как только
     * память освободится (перезагрузка, закрытые приложения), модель
     * загрузится сама.
     */
    data class InsufficientMemory(val requiredMb: Int) : LocalModelState()
}

/**
 * Описание модели, с которой работает локальный слой.
 *
 * Вынесено в данные, чтобы заменить модель можно было без правки кода
 * (пункт: «простота дальнейшего обновления модели»).
 */
data class LocalModelSpec(
    val modelId: String,
    val fileName: String,
    val approxSizeMb: Int,
    val contextTokens: Int,
    /** Минимум свободной RAM, при котором вообще есть смысл грузить модель. */
    val minRuntimeMemoryMb: Int,
    /** Прямая ссылка для автозагрузки. Должна быть доступна БЕЗ авторизации. */
    val downloadUrl: String,
    /** Точный размер файла в байтах — проверка целостности после скачивания. */
    val expectedSizeBytes: Long
) {
    companion object {
        /**
         * Qwen2.5-0.5B-Instruct, dynamic-int8, multi-prefill, формат MediaPipe `.task`.
         *
         * Обоснование выбора — docs/LOCAL_AI.md. Кратко: 521 МБ, ~1.36 ГБ RSS
         * (замер Google на S24 Ultra), ~30 tok/s decode на CPU, русский —
         * штатно поддерживаемый, лицензия Apache 2.0 (допускает автозагрузку
         * без click-through, в отличие от Gemma Terms of Use).
         *
         * Размер сверен с Hugging Face (Content-Length, 2026-09-11):
         * 546660344 байта. Если апстрим обновит файл — загрузка честно
         * упадёт в DownloadFailed, а не подсунет битый файл в рантайм.
         */
        val QWEN2_5_0_5B_INSTRUCT_Q8 = LocalModelSpec(
            modelId = "qwen2.5-0.5b-instruct-q8",
            fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            approxSizeMb = 521,
            contextTokens = 1280,
            minRuntimeMemoryMb = 1536,
            downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/" +
                "resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            expectedSizeBytes = 546660344L
        )
    }
}

/**
 * Причина сбоя для UI: класс исключения + первая содержательная строка
 * сообщения. Нативные движки (MediaPipe) кладут диагностику в многострочные
 * сообщения — пользователю нужна первая строка, а не весь дамп.
 */
fun throwableSummary(t: Throwable, maxChars: Int = 160): String {
    val className = t.javaClass.simpleName.ifEmpty { t.javaClass.name }
    val firstLine = t.message
        ?.lineSequence()
        ?.firstOrNull { it.isNotBlank() }
        ?.trim()
        ?: return className
    val summary = "$className: $firstLine"
    return if (summary.length <= maxChars) summary else summary.take(maxChars) + "..."
}
