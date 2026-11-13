package com.omnix.assistant.presentation.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.core.license.ClipModelInfo
import com.omnix.assistant.core.license.ClipPairInfo
import com.omnix.assistant.presentation.components.OmnixDivider
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * «Мои наушники»: пары OMNIX аккаунта (модель и цвет — из серверного
 * каталога, без моделей в коде), предложение добавить новую подключённую
 * пару, отвязка своей пары и режим оператора для аккаунтов-админов.
 */
@Composable
fun MyEarbudsSection(
    modifier: Modifier = Modifier,
    viewModel: ClipPairsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val colors = OmnixTheme.colors

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.omnix_pair_my_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color = colors.textSecondary
        )
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
        ) {
            if (state.pairs.isEmpty()) {
                Text(
                    text = stringResource(R.string.omnix_pair_none_bound),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
            }
            state.pairs.forEachIndexed { index, pair ->
                if (index > 0) OmnixDivider()
                PairRow(
                    pair = pair,
                    connected = viewModel.isConnected(pair),
                    enabled = !state.busy,
                    onRelease = { viewModel.askRelease(pair) }
                )
            }
        }

        state.message?.let { outcome ->
            Text(
                text = stringResource(outcome.messageRes),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
        }

        state.trialUntil?.let { until ->
            Text(
                text = stringResource(R.string.omnix_pair_trial_until, formatTrialDate(until)),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = colors.accentBrand,
                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
            )
        }

        if (state.isOperator) {
            OperatorPanel(
                serial = state.operatorSerial,
                busy = state.busy,
                onSerialChange = viewModel::onOperatorSerialChanged,
                onRegister = viewModel::registerConnected
            )
        }
    }

    state.offer?.let { offer ->
        AlertDialog(
            onDismissRequest = viewModel::dismissOffer,
            title = { Text(stringResource(R.string.omnix_pair_my_title)) },
            text = { Text(stringResource(R.string.omnix_pair_found_new, offer.model?.label ?: offer.clipSerial)) },
            confirmButton = {
                TextButton(onClick = viewModel::addOffered) { Text(stringResource(R.string.omnix_pair_add)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissOffer) { Text(stringResource(R.string.omnix_pair_cancel)) }
            }
        )
    }

    state.confirmRelease?.let { pair ->
        AlertDialog(
            onDismissRequest = viewModel::cancelRelease,
            title = { Text(stringResource(R.string.omnix_pair_release)) },
            text = { Text(stringResource(R.string.omnix_pair_release_confirm, pairTitle(pair))) },
            confirmButton = {
                TextButton(onClick = viewModel::confirmRelease) { Text(stringResource(R.string.omnix_pair_release)) }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelRelease) { Text(stringResource(R.string.omnix_pair_cancel)) }
            }
        )
    }
}

private fun pairTitle(pair: ClipPairInfo): String = pair.model?.label ?: pair.clipSerial

@Composable
private fun PairRow(pair: ClipPairInfo, connected: Boolean, enabled: Boolean, onRelease: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ModelSwatch(pair.model)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = pairTitle(pair), style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp), color = colors.textPrimary)
            Text(
                text = if (connected) {
                    stringResource(R.string.omnix_pair_connected_badge)
                } else {
                    lastSeenText(pair.lastSeenAt)
                },
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = if (connected) colors.accentBrand else colors.textSecondary
            )
        }
        OmnixTextButton(text = stringResource(R.string.omnix_pair_release), onClick = onRelease, enabled = enabled)
    }
}

/** Дата конца пробного Pro в локали телефона. */
internal fun formatTrialDate(instant: Instant): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withZone(ZoneId.systemDefault())
        .format(instant)

@Composable
private fun lastSeenText(raw: String?): String {
    val formatted = raw?.let { value ->
        runCatching {
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withZone(ZoneId.systemDefault())
                .format(Instant.parse(value))
        }.getOrNull()
    }
    return if (formatted == null) {
        stringResource(R.string.omnix_pair_never_seen)
    } else {
        stringResource(R.string.omnix_pair_last_seen, formatted)
    }
}

/** Цвет пары из каталога (HEX с сервера); без модели — нейтральный кружок. */
@Composable
private fun ModelSwatch(model: ClipModelInfo?) {
    val colors = OmnixTheme.colors
    val fill = model?.colorHex?.let { hex ->
        runCatching { Color(hex.toColorInt()) }.getOrNull()
    } ?: colors.surfaceFilled
    Box(
        modifier = Modifier
            .size(20.dp)
            .background(fill, CircleShape)
            .border(BorderStroke(1.dp, colors.border), CircleShape)
    )
}

@Composable
private fun OperatorPanel(
    serial: String,
    busy: Boolean,
    onSerialChange: (String) -> Unit,
    onRegister: () -> Unit
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier
            .padding(top = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = stringResource(R.string.omnix_pair_operator_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            color = colors.textPrimary
        )
        OutlinedTextField(
            value = serial,
            onValueChange = onSerialChange,
            label = { Text(stringResource(R.string.omnix_pair_operator_serial)) },
            singleLine = true,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        )
        OmnixSecondaryButton(
            text = stringResource(R.string.omnix_pair_operator_register),
            onClick = onRegister,
            enabled = !busy && serial.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
