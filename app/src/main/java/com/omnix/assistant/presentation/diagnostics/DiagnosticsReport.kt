package com.omnix.assistant.presentation.diagnostics

/**
 * Текстовый отчёт OMNIX DIAGNOSTICS (§21 ТЗ) для EXPORT REPORT.
 *
 * Чистый Kotlin без Android-зависимостей — покрывается JVM-тестом.
 * Формат: шапка (версии/сборка/устройство) + построчные результаты +
 * честная пометка про недоступность лога (in-app буфера логов в
 * приложении нет, а чужой logcat приложению недоступен).
 */
object DiagnosticsReport {

    data class BuildInfo(
        val appVersion: String,
        val versionCode: Long,
        val deviceModel: String,
        val androidRelease: String,
        val sdkInt: Int,
        val timestamp: String
    )

    fun build(results: List<DiagnosticResult>, build: BuildInfo): String =
        buildString {
            appendLine("OMNIX DIAGNOSTICS REPORT")
            appendLine("generated: ${build.timestamp}")
            appendLine("app: ${build.appVersion} (${build.versionCode})")
            appendLine("device: ${build.deviceModel}")
            appendLine("android: ${build.androidRelease} (sdk ${build.sdkInt})")
            appendLine()
            val ok = results.count { it.status == DiagnosticStatus.OK }
            appendLine("summary: $ok/${results.size} OK")
            appendLine()
            for (r in results) {
                val mark = when (r.status) {
                    DiagnosticStatus.OK -> "OK  "
                    DiagnosticStatus.WARNING -> "WARN"
                    DiagnosticStatus.FAIL -> "FAIL"
                    DiagnosticStatus.NA -> "N/A "
                    DiagnosticStatus.RUNNING -> "...."
                    DiagnosticStatus.PENDING -> "----"
                }
                appendLine(
                    "[$mark] ${r.id.name} (${r.durationMs}ms)" +
                        (if (r.detail.isNotEmpty()) " — ${r.detail}" else "")
                )
            }
            appendLine()
            appendLine("recent app log: unavailable (no in-app log buffer; use adb logcat)")
        }
}
