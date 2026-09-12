package com.jarvis.assistant.presentation.diagnostics

/**
 * OMNIX DIAGNOSTICS (§21 ТЗ): модели экрана самопроверки.
 *
 * Каждая проверка — РЕАЛЬНОЕ измерение через существующие менеджеры
 * (см. [DiagnosticsEngine]). Статусы честные: то, что нельзя проверить,
 * помечается [DiagnosticStatus.NA], а не «успехом».
 */
enum class DiagnosticCheckId {
    MIC,
    STT,
    LOCAL_AI,
    CLOUD_AI,
    BLUETOOTH,
    CLIP,
    ACCESSIBILITY,
    PERMISSIONS,
    TTS,
    LICENSE,
    NETWORK,
    BATTERY
}

/** Статус одной проверки. */
enum class DiagnosticStatus {
    /** Ещё не запускалась. */
    PENDING,

    /** Проверка выполняется прямо сейчас. */
    RUNNING,

    /** Реальная проверка прошла. */
    OK,

    /** Работает с оговорками / частично недоступно. */
    WARNING,

    /** Реальная проверка провалена. */
    FAIL,

    /** Проверить невозможно (нет API/железа) — честное «не знаем». */
    NA
}

/**
 * Результат одной проверки.
 *
 * @param detail короткая техническая деталь на английском (инженерный
 *        инструмент): измеренное значение, а не общие слова.
 */
data class DiagnosticResult(
    val id: DiagnosticCheckId,
    val status: DiagnosticStatus,
    val detail: String = "",
    val durationMs: Long = 0L
)
