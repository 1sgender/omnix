package com.omnix.assistant.presentation.activation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark

/**
 * Активация по моку 2026-09-25: шесть ячеек с подчёркиванием вместо одного
 * поля в рамке — длина кода видна сразу, курсор сам идёт к следующей цифре.
 * Кнопка честно выключена (fill-уровень + приглушённый текст), пока код не
 * введён целиком; при ошибке ячейки и линии краснеют, снизу — короткое
 * сообщение. Декоративное кольцо убрано: верх — маленький знак, состояние
 * несут сами элементы ввода. Сканирования камерой в приложении нет — ссылка
 * из мока не перенесена (разрешение дизайнера).
 *
 * Экран живёт и как гейт лицензии, и внутри флоу подключения Clip: кнопка
 * «Ввести код активации» открывает его поверх поиска.
 */
@Composable
fun ActivationScreen(
    onActivationSuccess: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ActivationViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing

    LaunchedEffect(uiState.isActivated) {
        if (uiState.isActivated) onActivationSuccess()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Контент — в скроллируемой области с весом: на высоком экране
        // занимает всё свободное место (кнопка прижата к низу, как в моке),
        // на низком с клавиатурой — скроллится, кнопка остаётся над IME.
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(TOP_GAP))

            OmnixRingMark(width = MARK_WIDTH, height = MARK_HEIGHT)

            Spacer(Modifier.height(AFTER_MARK_GAP))

            Text(
                text = stringResource(R.string.omnix_activation_title),
                style = ActivationTitleStyle,
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(TITLE_BODY_GAP))

            Text(
                text = stringResource(R.string.omnix_activation_body),
                style = ActivationBodyStyle,
                color = colors.textSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(BODY_FIELD_GAP))

            Text(
                text = stringResource(R.string.omnix_activation_field),
                style = ActivationLabelStyle,
                color = colors.textSecondary,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(LABEL_CELLS_GAP))

            ActivationCodeCells(
                code = uiState.inputCode,
                onCodeChange = viewModel::onCodeChanged,
                isError = uiState.errorMessage != null,
                enabled = !uiState.isLoading
            )

            // Резерв под сообщение (min-height 20px мока) — появление и уход
            // ошибки не двигают кнопку.
            Spacer(Modifier.height(HINT_TOP_GAP))
            ActivationErrorHint(message = uiState.errorMessage)
        }

        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_activation_cta),
            onClick = viewModel::activate,
            enabled = uiState.inputCode.length == CODE_CELL_COUNT &&
                !uiState.isLoading &&
                uiState.errorMessage == null,
            // Мок: выключенная кнопка — заливка fill-уровня, приглушённый текст.
            disabledContainerColor = colors.surfaceFilled,
            disabledContentColor = colors.textSecondary,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(BOTTOM_GAP))
    }
}

/**
 * Сообщение об ошибке: резервирует высоту (min-height 20px мока), появляется
 * фейдом 0.2s. Отдельный composable вне ColumnScope — иначе компилятор
 * разрешает AnimatedVisibility в расширение колонки.
 */
@Composable
private fun ActivationErrorHint(message: String?) {
    Box(modifier = Modifier.heightIn(min = HINT_MIN_HEIGHT)) {
        AnimatedVisibility(
            visible = message != null,
            enter = fadeIn(tween(durationMillis = 200)),
            exit = fadeOut(tween(durationMillis = 200))
        ) {
            Text(
                text = message.orEmpty(),
                style = ActivationHintStyle,
                color = OmnixTheme.colors.stateError,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    // Ошибка должна дойти до скринридера без взгляда на
                    // экран (§28) — «живая» область, как в клип-флоу.
                    .semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
    }
}

// ---- Типографика мока (локально: OmnixTypography — зона PR #93) ----

/** Заголовок мока: 22px/600, трекинг −0.02em. */
private val ActivationTitleStyle = TextStyle(
    fontSize = 22.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = (-0.44).sp
)

/** Подзаголовок мока: 15px серым, интерлиньяж 1.4, трекинг −0.008em. */
private val ActivationBodyStyle = TextStyle(
    fontSize = 15.sp,
    letterSpacing = (-0.12).sp,
    lineHeight = 21.sp
)

/** Подпись поля и сообщение ошибки мока: 13px. */
private val ActivationLabelStyle = TextStyle(fontSize = 13.sp)
private val ActivationHintStyle = TextStyle(fontSize = 13.sp)

// ---- Ритм мока ----
private val TOP_GAP = 36.dp
private val MARK_WIDTH = 26.dp
private val MARK_HEIGHT = 20.dp
private val AFTER_MARK_GAP = 26.dp
private val TITLE_BODY_GAP = 6.dp
private val BODY_FIELD_GAP = 26.dp
private val LABEL_CELLS_GAP = 8.dp
private val HINT_TOP_GAP = 10.dp
private val HINT_MIN_HEIGHT = 20.dp
private val BOTTOM_GAP = 28.dp
