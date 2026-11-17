package com.omnix.assistant.presentation.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.decision.AiModelChoiceCatalog
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.license.AiQuotaRemaining
import com.omnix.assistant.core.license.QuotaLine
import com.omnix.assistant.presentation.components.OmnixLockLineIcon
import com.omnix.assistant.presentation.components.OmnixSheet
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.failureText
import com.omnix.assistant.presentation.settings.localStatus
import java.util.Locale

/** Всё, что нужно чипу и листу выбора модели в чате. */
data class ChatModelPickerState(
    val choice: AiModelChoice,
    val applying: AiModelChoice?,
    val failure: AiModelActivation.Failed?,
    val proAvailable: Boolean,
    val remaining: AiQuotaRemaining,
    val localModelState: LocalModelState
)

/** Название варианта: «Авто», «Локально», «Flash», «Pro». */
@Composable
internal fun modelName(choice: AiModelChoice): String = stringResource(
    when (choice) {
        AiModelChoice.AUTO -> R.string.omnix_ai_model_auto
        AiModelChoice.ON_DEVICE -> R.string.omnix_ai_model_on_device
        AiModelChoice.CLOUD -> R.string.omnix_ai_model_cloud
        AiModelChoice.PRO -> R.string.omnix_tier_pro
    }
)

/**
 * Чип у поля ввода: «FLASH ▾» — тонкая рамка, моно-подпись. Видимая часть
 * компактная, зона нажатия не меньше 48dp.
 */
@Composable
internal fun ChatModelChip(
    choice: AiModelChoice,
    checking: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val name = modelName(choice)
    val description = stringResource(R.string.omnix_model_chip_cd, name)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .border(BorderStroke(1.dp, colors.border), CircleShape)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = name.uppercase(Locale.ROOT),
                style = OmnixMonoLabelStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                color = if (checking) colors.accentBrand else colors.textSecondary
            )
            Text(
                text = CHEVRON,
                style = OmnixMonoLabelStyle.copy(fontSize = 10.sp),
                color = colors.textTertiary
            )
        }
    }
}

/**
 * Нижний лист «Кто отвечает»: каждый вариант — название, одна строка
 * «быстро / умнее» и остаток на сегодня по тарифу. Недоступное по тарифу
 * видно с замком и ведёт на экран тарифов. Выбор проверяется (модель на
 * телефоне должна загрузиться, сервер — ответить) и запоминается для всех
 * следующих сообщений; получилось — лист закрывается сам.
 */
@Composable
internal fun ChatModelSheet(
    state: ChatModelPickerState,
    onSelect: (AiModelChoice) -> Unit,
    onOpenTariffs: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = OmnixTheme.colors
    var requested by remember { mutableStateOf<AiModelChoice?>(null) }
    LaunchedEffect(state.choice, state.applying, state.failure, requested) {
        val target = requested ?: return@LaunchedEffect
        if (state.applying == null && state.failure == null && state.choice == target) onDismiss()
    }

    OmnixSheet(onDismiss = onDismiss, title = stringResource(R.string.omnix_model_sheet_title)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(SheetCardShape)
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), SheetCardShape)
                .selectableGroup()
        ) {
            AiModelChoiceCatalog.options.forEachIndexed { index, (option, _) ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(1.dp)
                            .background(colors.textPrimary.copy(alpha = 0.06f))
                    )
                }
                val locked = option == AiModelChoice.PRO && !state.proAvailable
                ModelOptionRow(
                    option = option,
                    selected = option == state.choice,
                    checking = option == state.applying,
                    enabled = state.applying == null,
                    locked = locked,
                    meta = optionMeta(option, state),
                    onClick = {
                        when {
                            locked -> {
                                onDismiss()
                                onOpenTariffs()
                            }
                            option == state.choice -> onDismiss()
                            else -> {
                                requested = option
                                onSelect(option)
                            }
                        }
                    }
                )
            }
        }
        state.failure?.let { failure ->
            Text(
                text = failureText(failure),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.stateWarning,
                modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

/** Мелкая строка под вариантом: остаток на сегодня или состояние модели на телефоне. */
@Composable
private fun optionMeta(option: AiModelChoice, state: ChatModelPickerState): String = when (option) {
    AiModelChoice.ON_DEVICE -> {
        val status = localStatus(state.localModelState)
        if (state.localModelState is LocalModelState.Ready || status.isEmpty()) {
            stringResource(R.string.omnix_model_no_limit)
        } else {
            status
        }
    }
    AiModelChoice.PRO -> quotaText(state.remaining.proToday)
    AiModelChoice.AUTO, AiModelChoice.CLOUD -> quotaText(state.remaining.flashText)
}

@Composable
private fun quotaText(line: QuotaLine): String {
    val left = line.remaining
    return if (left != null) {
        stringResource(R.string.omnix_model_left_today, left, line.limit)
    } else {
        stringResource(R.string.omnix_model_limit_today, line.limit)
    }
}

@Composable
private fun ModelOptionRow(
    option: AiModelChoice,
    selected: Boolean,
    checking: Boolean,
    enabled: Boolean,
    locked: Boolean,
    meta: String,
    onClick: () -> Unit
) {
    val colors = OmnixTheme.colors
    val line = stringResource(
        when (option) {
            AiModelChoice.AUTO -> R.string.omnix_model_line_auto
            AiModelChoice.ON_DEVICE -> R.string.omnix_model_line_on_device
            AiModelChoice.CLOUD -> R.string.omnix_model_line_flash
            AiModelChoice.PRO -> R.string.omnix_model_line_pro
        }
    )
    val rowModifier = if (locked) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .then(rowModifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(if (locked) LOCKED_ALPHA else 1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = modelName(option),
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
                    color = colors.textPrimary
                )
                if (locked) {
                    Icon(
                        OmnixLockLineIcon,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }
            Text(
                text = line,
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textSecondary
            )
            Text(
                text = when {
                    checking -> stringResource(R.string.omnix_ai_model_checking)
                    locked -> stringResource(R.string.omnix_tier_pro_locked)
                    else -> meta
                },
                style = OmnixMonoLabelStyle.copy(fontSize = 11.sp),
                color = if (checking) colors.accentBrand else colors.textTertiary
            )
        }
        if (locked) {
            // Pro нет в тарифе: тап ведёт на экран тарифов.
            Text(
                text = stringResource(R.string.omnix_model_see_plans),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = colors.textPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .border(BorderStroke(1.dp, colors.border), CircleShape)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        } else {
            RadioMark(selected = selected)
        }
    }
}

/** Отметка выбора: лаймовая точка в кольце. */
@Composable
private fun RadioMark(selected: Boolean) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .size(22.dp)
            .border(
                BorderStroke(2.dp, if (selected) colors.accentBrand else colors.textPrimary.copy(alpha = 0.24f)),
                CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(colors.accentBrand, CircleShape)
            )
        }
    }
}

private const val CHEVRON = "▾"

/** Недоступный по тарифу вариант виден, но приглушён. */
private const val LOCKED_ALPHA = 0.55f
private val SheetCardShape = RoundedCornerShape(20.dp)
