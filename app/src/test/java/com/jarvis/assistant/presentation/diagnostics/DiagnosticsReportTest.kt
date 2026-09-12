package com.jarvis.assistant.presentation.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EXPORT REPORT (§21 ТЗ) — формат текстового отчёта.
 *
 * Чистый JVM-тест: DiagnosticsReport не зависит от Android.
 */
class DiagnosticsReportTest {

    private val build = DiagnosticsReport.BuildInfo(
        appVersion = "1.2.3",
        versionCode = 45L,
        deviceModel = "Pixel 8",
        androidRelease = "14",
        sdkInt = 34,
        timestamp = "2026-09-12 10:00:00"
    )

    @Test
    fun `report contains header with versions and device`() {
        val text = DiagnosticsReport.build(emptyList(), build)

        assertTrue(text.contains("OMNIX DIAGNOSTICS REPORT"))
        assertTrue(text.contains("2026-09-12 10:00:00"))
        assertTrue(text.contains("1.2.3 (45)"))
        assertTrue(text.contains("Pixel 8"))
        assertTrue(text.contains("14 (sdk 34)"))
    }

    @Test
    fun `report lists every check with status mark and detail`() {
        val results = listOf(
            DiagnosticResult(DiagnosticCheckId.MIC, DiagnosticStatus.OK, "captured 320 samples", 120),
            DiagnosticResult(DiagnosticCheckId.LICENSE, DiagnosticStatus.WARNING, "not activated", 5),
            DiagnosticResult(DiagnosticCheckId.NETWORK, DiagnosticStatus.FAIL, "offline", 30),
            DiagnosticResult(DiagnosticCheckId.BLUETOOTH, DiagnosticStatus.NA, "no radio", 1)
        )

        val text = DiagnosticsReport.build(results, build)

        assertTrue(text.contains("[OK  ] MIC"))
        assertTrue(text.contains("[WARN] LICENSE"))
        assertTrue(text.contains("[FAIL] NETWORK"))
        assertTrue(text.contains("[N/A ] BLUETOOTH"))
        assertTrue(text.contains("captured 320 samples"))
        assertTrue(text.contains("summary: 1/4 OK"))
    }

    @Test
    fun `report honestly states log unavailability`() {
        val text = DiagnosticsReport.build(emptyList(), build)

        assertTrue(text.contains("recent app log: unavailable"))
    }
}
