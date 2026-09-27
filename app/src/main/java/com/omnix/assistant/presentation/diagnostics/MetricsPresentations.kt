package com.omnix.assistant.presentation.diagnostics

import com.omnix.assistant.agent.metrics.VoiceLatencyMetrics
import kotlin.math.roundToInt

/**
 * Чистое форматирование dev-метрик (блок 8 плана пересборки фронта,
 * 2026-09-26). Числа собираются в Kotlin-коде, а не параметрами
 * string-ресурсов: «%d + слово» в XML ловит lint PluralsCandidate, а
 * dev-секции точность локализации формул не нужна — метки локализованы,
 * значения универсальны.
 */

/** «63% · 120» — процент и счётчик запросов одной строкой. */
fun formatPercentCount(percent: Double, count: Long): String =
    "${percent.roundToInt()}% · $count"

/** «450/900/1500» — перцентили без единиц; подпись P50/P95/P99 отдельной строкой UI. */
fun formatPercentiles(p: VoiceLatencyMetrics.Percentiles?): String =
    if (p == null) "" else "${p.p50Ms}/${p.p95Ms}/${p.p99Ms}"
