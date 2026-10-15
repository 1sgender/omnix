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
