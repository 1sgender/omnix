package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.omnix.assistant.core.payments.planRank
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import java.util.Locale

/**
 * Карточка текущего тарифа на экране «Я»: название, что включено, остаток
 * дневных лимитов и вход в экран тарифов («Купить» на Free, «Сменить тариф» на платном).
 */
@Composable
fun PlanCard(
    state: MePlanState,
    onOpenTariffs: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val paid = planRank(state.planId) > 0
    val name = planDisplayName(state.planId)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.plan_card_label).uppercase(Locale.getDefault()),
                style = OmnixMonoLabelStyle,
                color = colors.textTertiary
            )
            Text(
                text = name,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
                color = colors.textPrimary
            )
            Text(
                text = stringResource(if (paid) R.string.plan_card_paid_sub else R.string.plan_card_free_sub),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                color = colors.textSecondary
            )
        }
        if (state.meters.isNotEmpty()) {
            Text(
                text = stringResource(R.string.plan_card_meters_title).uppercase(Locale.getDefault()),
                style = OmnixMonoLabelStyle,
                color = colors.textTertiary
            )
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                state.meters.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEach { meter -> MeterCell(meter, Modifier.weight(1f)) }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
        }
        OmnixPrimaryButton(
            text = stringResource(if (paid) R.string.plan_card_change else R.string.plan_card_buy),
            onClick = onOpenTariffs,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MeterCell(meter: PlanMeter, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(meterLabel(meter.kind)),
            style = OmnixMonoLabelStyle.copy(fontSize = 11.sp),
            color = colors.textTertiary,
            maxLines = 1
        )
        Text(
            text = if (meter.left == null) {
                stringResource(R.string.plan_meter_per_day, meter.limit)
            } else {
                stringResource(R.string.plan_meter_left, meter.left, meter.limit)
            },
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = colors.textPrimary,
            maxLines = 1
        )
        meter.fraction?.let { fraction ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textPrimary.copy(alpha = 0.08f))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fraction)
                        .height(3.dp)
                        .background(colors.accentBrand, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

internal fun meterLabel(kind: PlanMeterKind): Int = when (kind) {
    PlanMeterKind.FLASH -> R.string.plan_meter_flash
    PlanMeterKind.SEARCH -> R.string.plan_meter_search
    PlanMeterKind.TRANSLATION -> R.string.plan_meter_translation
    PlanMeterKind.EAR -> R.string.plan_meter_ear
}

/** Название тарифа для интерфейса: id плана с заглавной, пустой id — Free. */
@Composable
internal fun planDisplayName(planId: String): String = when (planRank(planId)) {
    2 -> stringResource(R.string.plan_name_max)
    1 -> stringResource(R.string.plan_name_pro)
    else -> stringResource(R.string.plan_name_free)
}
