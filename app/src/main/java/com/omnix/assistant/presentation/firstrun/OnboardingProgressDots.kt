package com.omnix.assistant.presentation.firstrun

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Точки прогресса онбординга (мок 2026-09-24): три вехи, активная — брендовый
 * синий и растянута в капсулу, пройденные и будущие — нейтральные. Несёт
 * смысл «шаг 1 из 3» вместо прежнего подчёркивания под логотипом.
 *
 * Для скринридера весь ряд — одна метка «Шаг N из M»: отдельные точки —
 * декорация, озвучивать их по одной — шум.
 */
@Composable
internal fun OnboardingProgressDots(
    total: Int,
    active: Int,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val a11y = stringResource(R.string.omnix_onboarding_step_a11y, active + 1, total)

    Row(
        modifier = modifier.clearAndSetSemantics { contentDescription = a11y },
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(total) { index ->
            val dotColor = when {
                index == active -> colors.accentBrand
                index < active -> colors.textTertiary
                else -> colors.actionSecondaryBorder
            }
            if (index == active) {
                Box(
                    modifier = Modifier
                        .width(ACTIVE_WIDTH)
                        .height(DOT_HEIGHT)
                        .background(dotColor, RoundedCornerShape(percent = 50))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(DOT_HEIGHT)
                        .background(dotColor, CircleShape)
                )
            }
        }
    }
}

private val DOT_HEIGHT = 6.dp
private val ACTIVE_WIDTH = 18.dp
