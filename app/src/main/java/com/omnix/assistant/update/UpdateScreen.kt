package com.omnix.assistant.update

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.omnix.assistant.presentation.components.OmnixAlertIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.LocalReducedMotion
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark

/** Что показывает кольцо экрана обновления. */
internal enum class UpdateStage { AVAILABLE, DOWNLOADING, READY, FAILED }

/** Кнопка экрана обновления: подпись и действие. */
internal class UpdateAction(val label: String, val onClick: () -> Unit)

/**
 * Экран обновления на весь экран (просьба владельца 2026-10-21: «максимально
 * красивым»): лаймовое кольцо со знаком OMNIX, кольцо прогресса загрузки,
 * галочка готовности или знак ошибки; под ним заголовок, плашка версии,
 * «что нового» и кнопки. Анимации выключаются при «уменьшить движение».
 */
@Composable
internal fun UpdateScreen(
    stage: UpdateStage,
    title: String,
    onDismiss: () -> Unit,
    progress: Float? = null,
    centerLabel: String? = null,
    chip: String? = null,
    body: String? = null,
    notes: List<String> = emptyList(),
    hint: String? = null,
    primary: UpdateAction? = null,
    secondary: UpdateAction? = null,
    secondaryOutlined: Boolean = false
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        val colors = OmnixTheme.colors
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .statusBarsPadding()
                .navigationBarsPadding(),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Spacer(Modifier.height(24.dp))
                    UpdateRing(stage = stage, progress = progress, centerLabel = centerLabel)
                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = title,
                        style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 24.sp),
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    )
                    if (chip != null) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = chip,
                            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                            color = colors.accentBrand,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(colors.accentBrand.copy(alpha = 0.14f))
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    if (body != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = body,
                            style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp),
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(20.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(colors.surface)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            notes.forEach { note -> NoteRow(note) }
                        }
                    }
                    if (hint != null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = hint,
                            style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp, lineHeight = 19.sp),
                            color = colors.textTertiary,
                            textAlign = TextAlign.Center
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                }

                if (primary != null) {
                    OmnixPrimaryButton(
                        text = primary.label,
                        onClick = primary.onClick,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (secondary != null) {
                    Spacer(Modifier.height(8.dp))
                    if (secondaryOutlined) {
                        OmnixSecondaryButton(
                            text = secondary.label,
                            onClick = secondary.onClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    } else {
                        OmnixTextButton(
                            text = secondary.label,
                            onClick = secondary.onClick,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteRow(note: String) {
    val colors = OmnixTheme.colors
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 8.dp)
                .size(6.dp)
                .clip(RoundedCornerShape(50))
                .background(colors.accentBrand)
        )
        Text(
            text = note,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp, lineHeight = 21.sp),
            color = colors.textPrimary
        )
    }
}

/**
 * Кольцо: «есть обновление» — лаймовое кольцо со знаком OMNIX, мягко дышит;
 * загрузка — дуга прогресса с процентом (без размера — бегущая дуга); готово —
 * лаймовый круг с галочкой; ошибка — красное кольцо со знаком внимания.
 */
@Composable
private fun UpdateRing(stage: UpdateStage, progress: Float?, centerLabel: String?) {
    val colors = OmnixTheme.colors
    val reduced = LocalReducedMotion.current
    val transition = rememberInfiniteTransition(label = "updateRing")
    val breath by transition.animateFloat(
        initialValue = 1f,
        targetValue = if (!reduced && stage == UpdateStage.AVAILABLE) BREATH_SCALE else 1f,
        animationSpec = infiniteRepeatable(tween(BREATH_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "updateRingBreath"
    )
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = if (reduced) 0f else FULL_TURN,
        animationSpec = infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)),
        label = "updateRingSpin"
    )
    val shownProgress by animateFloatAsState(
        targetValue = (progress ?: 0f).coerceIn(0f, 1f),
        animationSpec = tween(PROGRESS_MS),
        label = "updateRingProgress"
    )
    val track = colors.surfaceElevated
    val accent = colors.accentBrand
    val error = colors.stateError
    val onAccent = colors.background

    Box(
        modifier = Modifier
            .size(RING_SIZE.dp)
            .scale(breath)
            .semantics { centerLabel?.let { contentDescription = it } },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = RING_STROKE.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            val topLeft = Offset(inset, inset)
            when (stage) {
                UpdateStage.AVAILABLE -> drawArc(
                    color = accent, startAngle = 0f, sweepAngle = FULL_TURN, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(strokePx)
                )
                UpdateStage.DOWNLOADING -> {
                    drawArc(
                        color = track, startAngle = 0f, sweepAngle = FULL_TURN, useCenter = false,
                        topLeft = topLeft, size = arcSize, style = Stroke(strokePx)
                    )
                    if (progress == null) {
                        drawArc(
                            color = accent, startAngle = spin - QUARTER_TURN, sweepAngle = QUARTER_TURN,
                            useCenter = false, topLeft = topLeft, size = arcSize,
                            style = Stroke(strokePx, cap = StrokeCap.Round)
                        )
                    } else {
                        drawArc(
                            color = accent, startAngle = -QUARTER_TURN, sweepAngle = FULL_TURN * shownProgress,
                            useCenter = false, topLeft = topLeft, size = arcSize,
                            style = Stroke(strokePx, cap = StrokeCap.Round)
                        )
                    }
                }
                UpdateStage.READY -> {
                    drawCircle(color = accent)
                    val check = Path().apply {
                        moveTo(size.width * 0.32f, size.height * 0.51f)
                        lineTo(size.width * 0.45f, size.height * 0.64f)
                        lineTo(size.width * 0.69f, size.height * 0.39f)
                    }
                    drawPath(
                        path = check,
                        color = onAccent,
                        style = Stroke(width = strokePx * 1.2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    )
                }
                UpdateStage.FAILED -> drawArc(
                    color = error, startAngle = 0f, sweepAngle = FULL_TURN, useCenter = false,
                    topLeft = topLeft, size = arcSize, style = Stroke(strokePx)
                )
            }
        }
        when (stage) {
            UpdateStage.AVAILABLE -> OmnixRingMark(width = 76.dp, height = 58.dp, strokeUnits = 3.0f)
            UpdateStage.DOWNLOADING -> if (centerLabel != null) {
                Text(
                    text = centerLabel,
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 30.sp),
                    color = colors.textPrimary
                )
            } else {
                OmnixRingMark(width = 64.dp, height = 49.dp, strokeUnits = 3.0f)
            }
            UpdateStage.READY -> Unit
            UpdateStage.FAILED -> OmnixAlertIcon(color = error, size = 48.dp)
        }
    }
}

private const val RING_SIZE = 168
private const val RING_STROKE = 8
private const val FULL_TURN = 360f
private const val QUARTER_TURN = 90f
private const val BREATH_SCALE = 1.04f
private const val BREATH_MS = 1_400
private const val SPIN_MS = 1_200
private const val PROGRESS_MS = 400
