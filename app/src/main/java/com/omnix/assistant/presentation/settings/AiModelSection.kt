package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelActivationError
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.license.AiQuotaRemaining
import com.omnix.assistant.core.license.QuotaLine
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Раздел «ИИ-модель» в «Я»: только сведения тарифа — сколько ответов
 * осталось на сегодня и что уходит в облако. Сама модель выбирается в чате
 * (чип у поля ввода) или голосом «переключись на Pro».
 */
@Composable
fun AiModelInfoSection(
    remaining: AiQuotaRemaining,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        QuotaCard(remaining = remaining)
        PrivacyCloudCard()
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

/** Состояние модели на телефоне одной строкой (лист выбора модели в чате). */
@Composable
internal fun localStatus(state: LocalModelState): String = when (state) {
    is LocalModelState.Ready -> stringResource(R.string.omnix_me_model_ready)
    is LocalModelState.Downloading -> stringResource(R.string.omnix_ai_model_downloading, state.progressPercent)
    is LocalModelState.NotInstalled, is LocalModelState.DownloadFailed -> stringResource(R.string.omnix_ai_model_not_downloaded)
    is LocalModelState.InsufficientMemory -> stringResource(R.string.omnix_ai_model_low_memory)
    is LocalModelState.Failed -> stringResource(R.string.omnix_ai_model_broken)
    LocalModelState.Loading -> stringResource(R.string.omnix_ai_model_checking)
    LocalModelState.NotInitialized -> ""
}

/** Почему выбранная модель не включилась. */
@Composable
internal fun failureText(failure: AiModelActivation.Failed): String = when (failure.error) {
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
