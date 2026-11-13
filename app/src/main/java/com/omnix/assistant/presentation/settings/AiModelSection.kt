package com.omnix.assistant.presentation.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.localmodel.rememberLocalModelSpec
import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelActivationError
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.decision.AiModelChoiceCatalog
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.license.AiQuotaRemaining
import com.omnix.assistant.core.license.QuotaLine
import com.omnix.assistant.presentation.components.OmnixLockLineIcon
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * The AI model choice: one card per option from [AiModelChoiceCatalog], the
 * current choice marked. A tap checks the option first (the phone model must
 * load, the server must answer); while that runs the option says so and the
 * others wait. A failed check leaves the previous choice selected and says
 * why.
 */
@Composable
fun AiModelSection(
    choice: AiModelChoice,
    applying: AiModelChoice?,
    failure: AiModelActivation.Failed?,
    localModelState: LocalModelState,
    onSelect: (AiModelChoice) -> Unit,
    modifier: Modifier = Modifier,
    proAvailable: Boolean = false,
    remaining: AiQuotaRemaining? = null
) {
    val colors = OmnixTheme.colors
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
                .selectableGroup()
        ) {
            AiModelChoiceCatalog.options.forEachIndexed { index, (option, _) ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.textPrimary.copy(alpha = 0.06f))
                    )
                }
                val locked = option == AiModelChoice.PRO && !proAvailable
                OptionRow(
                    option = option,
                    selected = option == choice,
                    checking = option == applying,
                    enabled = applying == null && !locked,
                    locked = locked,
                    status = if (option == AiModelChoice.ON_DEVICE) localStatus(localModelState) else null,
                    onClick = { onSelect(option) }
                )
            }
        }
        AnimatedVisibility(visible = failure != null) {
            failure?.let {
                Text(
                    text = failureText(it),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp, lineHeight = 19.sp),
                    color = colors.stateWarning,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
        if (remaining != null) {
            QuotaCard(remaining = remaining, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

/**
 * «Сегодня осталось»: Flash голос и текст, а в тарифах с Pro — ещё Pro на
 * сегодня и на месяц. Остаток неизвестен (старый сервер) — показываем лимит.
 */
@Composable
private fun QuotaCard(remaining: AiQuotaRemaining, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.omnix_tier_left_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = colors.textPrimary
        )
        QuotaRow(stringResource(R.string.omnix_tier_left_flash_voice), remaining.flashVoice)
        QuotaRow(stringResource(R.string.omnix_tier_left_flash_text), remaining.flashText)
        if (remaining.proInPlan) {
            QuotaRow(stringResource(R.string.omnix_tier_left_pro_day), remaining.proToday)
            QuotaRow(stringResource(R.string.omnix_tier_left_pro_month), remaining.proMonth)
        } else {
            Text(
                text = stringResource(R.string.omnix_tier_quota_upgrade),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.textTertiary
            )
        }
    }
}

@Composable
private fun QuotaRow(label: String, line: QuotaLine) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        val left = line.remaining
        Text(
            text = if (left != null) {
                stringResource(R.string.omnix_tier_left_value, left, line.limit)
            } else {
                stringResource(R.string.omnix_tier_left_limit, line.limit)
            },
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color = if (left == 0) colors.stateWarning else colors.textPrimary
        )
    }
}

@Composable
private fun OptionRow(
    option: AiModelChoice,
    selected: Boolean,
    checking: Boolean,
    enabled: Boolean,
    locked: Boolean,
    status: String?,
    onClick: () -> Unit
) {
    val colors = OmnixTheme.colors
    val (title, body) = when (option) {
        AiModelChoice.AUTO -> R.string.omnix_ai_model_auto to R.string.omnix_ai_model_auto_body
        AiModelChoice.ON_DEVICE -> R.string.omnix_ai_model_on_device to R.string.omnix_ai_model_on_device_body
        AiModelChoice.CLOUD -> R.string.omnix_ai_model_cloud to R.string.omnix_ai_model_cloud_body
        AiModelChoice.PRO -> R.string.omnix_tier_pro to R.string.omnix_tier_pro_body
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (locked) LOCKED_ALPHA else 1f)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = stringResource(title),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
                color = colors.textPrimary
            )
            Text(
                text = if (option == AiModelChoice.ON_DEVICE) {
                    stringResource(body, rememberLocalModelSpec().displayName)
                } else {
                    stringResource(body)
                },
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp, lineHeight = 17.sp),
                color = colors.textSecondary
            )
            if (locked) {
                // Pro нет в тарифе: замок и где он есть.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        OmnixLockLineIcon,
                        contentDescription = null,
                        tint = colors.textTertiary,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = stringResource(R.string.omnix_tier_pro_locked),
                        style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                        color = colors.textTertiary
                    )
                }
            }
            val line = if (checking) stringResource(R.string.omnix_ai_model_checking) else status
            if (!line.isNullOrEmpty()) {
                Text(
                    text = line,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                    color = if (checking) colors.accentBrand else colors.textTertiary
                )
            }
        }
        // The radio mark: a lime dot inside a ring when selected.
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
}

@Composable
private fun localStatus(state: LocalModelState): String = when (state) {
    is LocalModelState.Ready -> stringResource(R.string.omnix_me_model_ready)
    is LocalModelState.Downloading -> stringResource(R.string.omnix_ai_model_downloading, state.progressPercent)
    is LocalModelState.NotInstalled, is LocalModelState.DownloadFailed -> stringResource(R.string.omnix_ai_model_not_downloaded)
    is LocalModelState.InsufficientMemory -> stringResource(R.string.omnix_ai_model_low_memory)
    is LocalModelState.Failed -> stringResource(R.string.omnix_ai_model_broken)
    LocalModelState.Loading -> stringResource(R.string.omnix_ai_model_checking)
    LocalModelState.NotInitialized -> ""
}

@Composable
private fun failureText(failure: AiModelActivation.Failed): String = when (failure.error) {
    AiModelActivationError.LOCAL_NOT_INSTALLED -> stringResource(R.string.omnix_ai_model_error_not_installed)
    AiModelActivationError.LOCAL_DOWNLOADING -> stringResource(R.string.omnix_ai_model_error_downloading)
    AiModelActivationError.LOCAL_NOT_ENOUGH_MEMORY -> stringResource(R.string.omnix_ai_model_error_memory)
    AiModelActivationError.LOCAL_LOAD_FAILED -> stringResource(
        R.string.omnix_ai_model_error_load,
        failure.detail.orEmpty().ifEmpty { "—" }
    )
    AiModelActivationError.SERVER_UNREACHABLE -> stringResource(R.string.omnix_ai_model_error_server)
    AiModelActivationError.PRO_NOT_IN_PLAN -> stringResource(R.string.omnix_tier_pro_locked)
}

/** Недоступный по тарифу вариант виден, но приглушён. */
private const val LOCKED_ALPHA = 0.55f
