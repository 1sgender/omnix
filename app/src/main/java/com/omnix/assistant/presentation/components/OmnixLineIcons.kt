package com.omnix.assistant.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Line icons of the design v2 canvas: a 24-unit grid, 2-unit round stroke.
 * The path data is taken from the canvas as is, so the app draws exactly what
 * the canvas shows; the colour comes from the `tint` of the `Icon` that
 * renders them. Circles and rounded rects are written as arcs.
 */
private fun omnixLineIcon(name: String, vararg paths: String): ImageVector =
    omnixLineIcon(name, 2f, *paths)

private fun omnixLineIcon(name: String, strokeWidth: Float, vararg paths: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        paths.forEach { data ->
            addPath(
                pathData = addPathNodes(data),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        }
    }.build()

// ---- Tab bar ----

val OmnixChatBubbleLineIcon: ImageVector = omnixLineIcon(
    "ChatBubble",
    "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"
)

val OmnixHeadphonesLineIcon: ImageVector = omnixLineIcon(
    "Headphones",
    "M4 15v-3a8 8 0 0 1 16 0v3",
    // rect x=3 y=14 w=5 h=7 rx=2 and rect x=16 y=14 w=5 h=7 rx=2
    "M5 14h1a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z",
    "M18 14h1a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2h-1a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z"
)

val OmnixPersonLineIcon: ImageVector = omnixLineIcon(
    "Person",
    // circle cx=12 cy=8 r=4
    "M8 8a4 4 0 1 0 8 0a4 4 0 1 0-8 0",
    "M4 21a8 8 0 0 1 16 0"
)

// ---- Home quick actions ----

val OmnixTranslatorLineIcon: ImageVector = omnixLineIcon(
    "Translator",
    "M4 5h8", "M8 3v2", "M5 9c1.5 3 4 5 7 6", "M11 5c-1 4-3 7-6 9",
    "M13 21l4-9 4 9", "M14.5 18h5"
)

val OmnixMemoryLineIcon: ImageVector = omnixLineIcon(
    "Memory",
    "M12 3l9 5-9 5-9-5z", "M3 13l9 5 9-5"
)

val OmnixAlarmLineIcon: ImageVector = omnixLineIcon(
    "Alarm",
    // circle cx=12 cy=13 r=8
    "M4 13a8 8 0 1 0 16 0a8 8 0 1 0-16 0",
    "M12 9v4l2 2", "M5 3L2 6", "M22 6l-3-3"
)

val OmnixBoltLineIcon: ImageVector = omnixLineIcon(
    "Bolt",
    "M13 2L4 14h7l-1 8 9-12h-7z"
)

/** The on-device chip in the Home status pill. */
val OmnixOnDeviceLineIcon: ImageVector = omnixLineIcon(
    "OnDevice",
    "M12 3v3",
    // rect x=6 y=6 w=12 h=15 rx=2
    "M8 6h8a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-11a2 2 0 0 1 2-2z",
    "M10 11h4"
)

// ---- Translator ----

val OmnixBackLineIcon: ImageVector = omnixLineIcon("Back", "M15 18l-6-6 6-6")

// ---- Chat header ----

/** История чатов. */
val OmnixMenuLineIcon: ImageVector = omnixLineIcon("Menu", "M4 7h16", "M4 12h16", "M4 17h10")

/** Новый чат: карандаш над строкой. */
val OmnixNewChatLineIcon: ImageVector = omnixLineIcon(
    "NewChat",
    "M12 20h8",
    "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"
)

/** Удалить: корзина. */
val OmnixTrashLineIcon: ImageVector = omnixLineIcon(
    "Trash",
    "M4 7h16",
    "M10 11v6",
    "M14 11v6",
    "M6 7l1 13h10l1-13",
    "M9 7V4h6v3"
)

val OmnixSwapLineIcon: ImageVector = omnixLineIcon("Swap", "M7 7h13l-4-4", "M17 17H4l4 4")

val OmnixPauseLineIcon: ImageVector = omnixLineIcon("Pause", 2.2f, "M9 5v14", "M15 5v14")

val OmnixMicLineIcon: ImageVector = omnixLineIcon(
    "Mic",
    2.2f,
    // rect x=9 y=3 w=6 h=11 rx=3
    "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z",
    "M5 11a7 7 0 0 0 14 0",
    "M12 18v3"
)

val OmnixCloseLineIcon: ImageVector = omnixLineIcon("Close", 2.2f, "M6 6l12 12", "M18 6L6 18")

// ---- Chat ----

/** "More": three filled dots (r = 1.8), the one filled glyph of the canvas. */
val OmnixMoreIcon: ImageVector = ImageVector.Builder(
    name = "More",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f
).apply {
    listOf(5f, 12f, 19f).forEach { cx ->
        addPath(
            pathData = addPathNodes("M${cx - 1.8f} 12a1.8 1.8 0 1 0 3.6 0a1.8 1.8 0 1 0-3.6 0z"),
            fill = SolidColor(Color.Black)
        )
    }
}.build()

val OmnixCloudLineIcon: ImageVector = omnixLineIcon(
    "Cloud",
    "M17.5 19a4.5 4.5 0 1 0-1.4-8.8A6 6 0 0 0 4 12a4 4 0 0 0 1 7.9z"
)

val OmnixSendLineIcon: ImageVector = omnixLineIcon("Send", 2.2f, "M12 19V5", "M5 12l7-7 7 7")

/** Поиск по чатам: лупа. */
val OmnixSearchLineIcon: ImageVector = omnixLineIcon(
    "Search",
    "M11 4a7 7 0 1 1 0 14a7 7 0 1 1 0-14z",
    "M20 20l-4-4"
)

/** Скопировать ответ: два листа. */
val OmnixCopyLineIcon: ImageVector = omnixLineIcon(
    "Copy",
    "M10 8h9a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1h-9a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z",
    "M5 16V5a1 1 0 0 1 1-1h10"
)

/** Скопировано: галочка. */
val OmnixCheckLineIcon: ImageVector = omnixLineIcon("Check", 2.2f, "M5 12l5 5 9-10")

// ---- Me ----

val OmnixChevronLineIcon: ImageVector = omnixLineIcon("Chevron", "M9 18l6-6-6-6")

// ---- Me rows (2026-10-22: настоящие иконки вместо двухбуквенных плиток) ----

/** Голос и фраза вызова. */
val OmnixVoiceLineIcon: ImageVector = omnixLineIcon(
    "Voice",
    // rect x=9 y=3 w=6 h=11 rx=3
    "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0v-5a3 3 0 0 1 3-3z",
    "M5 11a7 7 0 0 0 14 0", "M12 18v3"
)

/** ИИ-модель: искра. */
val OmnixSparkLineIcon: ImageVector = omnixLineIcon(
    "Spark",
    "M12 3l1.9 5.1L19 10l-5.1 1.9L12 17l-1.9-5.1L5 10l5.1-1.9z",
    "M19 16v4", "M17 18h4"
)

/** Оформление: круг, наполовину залитый линиями. */
val OmnixAppearanceLineIcon: ImageVector = omnixLineIcon(
    "Appearance",
    // circle cx=12 cy=12 r=9
    "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0",
    "M12 3v18", "M12 8h7.5", "M12 12h9", "M12 16h7.5"
)

/** Язык: глобус. */
val OmnixGlobeLineIcon: ImageVector = omnixLineIcon(
    "Globe",
    "M3 12a9 9 0 1 0 18 0a9 9 0 1 0-18 0",
    "M3 12h18",
    "M12 3c2.5 2.5 3.8 5.5 3.8 9s-1.3 6.5-3.8 9c-2.5-2.5-3.8-5.5-3.8-9s1.3-6.5 3.8-9z"
)

/** Уведомления: колокольчик. */
val OmnixBellLineIcon: ImageVector = omnixLineIcon(
    "Bell",
    "M6 16V11a6 6 0 0 1 12 0v5l2 2H4z",
    "M10 21h4"
)

