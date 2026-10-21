package com.omnix.assistant.core.crash

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Одна запись Android о том, почему завершился прошлый процесс приложения
 * (ApplicationExitInfo, API 30+). Это единственный след НАТИВНОГО вылета
 * (SIGSEGV в C++-библиотеке, например в MediaPipe), убийства из-за нехватки
 * памяти и ANR: Java-обработчик в [CrashCapture] их не видит.
 */
data class ProcessExit(
    val timestampMs: Long,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val rssKb: Long,
    val description: String,
    /** Читаемые строки из дампа нативного вылета (библиотеки, функции, abort message). */
    val traceHighlights: List<String> = emptyList()
)

/**
 * Чистое форматирование записей [ProcessExit] в блоки для отчёта диагностики —
 * без Android-зависимостей, покрывается JVM-тестами.
 */
object ProcessExitReport {

    /** Значения ApplicationExitInfo.REASON_* (API 30+); стабильные константы платформы. */
    private val REASON_NAMES = mapOf(
        0 to "UNKNOWN",
        1 to "EXIT_SELF",
        2 to "SIGNALED",
        3 to "LOW_MEMORY",
        4 to "CRASH (Java)",
        5 to "CRASH_NATIVE",
        6 to "ANR",
        7 to "INITIALIZATION_FAILURE",
        8 to "PERMISSION_CHANGE",
        9 to "EXCESSIVE_RESOURCE_USAGE",
        10 to "USER_REQUESTED",
        11 to "USER_STOPPED",
        12 to "DEPENDENCY_DIED",
        13 to "OTHER",
        14 to "FREEZER",
        15 to "PACKAGE_STATE_CHANGE",
        16 to "PACKAGE_UPDATED"
    )

    /** Значения RunningAppProcessInfo.IMPORTANCE_* для подписи состояния процесса. */
    private val IMPORTANCE_NAMES = mapOf(
        100 to "foreground",
        125 to "foreground service",
        200 to "visible",
        300 to "service",
        400 to "cached"
    )

    fun reasonName(reason: Int): String = REASON_NAMES[reason] ?: "REASON_$reason"

    fun importanceName(importance: Int): String =
        IMPORTANCE_NAMES[importance] ?: "importance $importance"

    /** Нативный вылет, нехватка памяти, ANR: причины, ради которых блок вообще нужен. */
    fun isAbnormal(reason: Int): Boolean = reason in ABNORMAL

    /**
     * Блоки «--- process exit … ---» для самых свежих записей, новые первее.
     * Спокойные завершения (смахнул из недавних, обновление) помечаются как
     * обычные, чтобы не путать их с вылетом.
     */
    fun format(
        exits: List<ProcessExit>,
        limit: Int = 5,
        maxDescriptionChars: Int = 600
    ): List<String> {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return exits.sortedByDescending { it.timestampMs }.take(limit).map { e ->
            val kind = if (isAbnormal(e.reason)) "ABNORMAL" else "normal"
            buildString {
                append("--- process exit ${stamp.format(Date(e.timestampMs))} ---\n")
                append("reason: ${reasonName(e.reason)} ($kind), status=${e.status}\n")
                append("state: ${importanceName(e.importance)}, rss=${e.rssKb / 1024} MB\n")
                append("system note: ")
                append(e.description.trim().ifEmpty { "(none)" }.take(maxDescriptionChars))
                if (e.traceHighlights.isNotEmpty()) {
                    append("\nnative trace:")
                    e.traceHighlights.forEach { append("\n  ").append(it) }
                }
            }
        }
    }

    /**
     * Дамп нативного вылета (tombstone) — бинарный protobuf; его строки
     * (пути .so, имена функций, abort message) лежат в нём читаемым текстом.
     * Достаём их без парсера proto: печатные ASCII-цепочки, и только те, что
     * говорят о месте сбоя. Порядок сохраняется — это порядок кадров стека.
     */
    fun tombstoneHighlights(bytes: ByteArray, max: Int = 24): List<String> {
        val runs = mutableListOf<String>()
        val current = StringBuilder()
        fun flush() {
            if (current.length >= MIN_RUN) runs += current.toString()
            current.setLength(0)
        }
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) current.append(c.toChar()) else flush()
        }
        flush()
        return runs
            .filter { run -> HIGHLIGHT_MARKERS.any { run.contains(it) } }
            .distinct()
            .take(max)
            .map { it.take(MAX_RUN_CHARS) }
    }

    private const val MIN_RUN = 6
    private const val MAX_RUN_CHARS = 200
    private val HIGHLIGHT_MARKERS = listOf(".so", "abort", "Abort", "FORTIFY", "JNI", "Fatal", "terminat", "Check failed", "Ort", "onnx", "mediapipe")

    private val ABNORMAL = setOf(2, 3, 4, 5, 6, 7, 9, 14)
}
