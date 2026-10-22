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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.localai.LocalModelSpec
import com.omnix.assistant.core.admin.AdminModelPin
import com.omnix.assistant.core.admin.AdminModelState
import com.omnix.assistant.core.admin.cloudModelLabel
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.localmodel.rememberLocalModelSpec
import com.omnix.assistant.voice.wakeword.phrase.WakeEngineKind
import com.omnix.assistant.voice.wakeword.phrase.WakePhrase

/**
 * Тест моделей (только админ): любая модель телефона или облака отвечает на
 * все запросы, под ответом подписано, кто ответил. Модель телефона меняется
 * после перезапуска: она выбирается при старте приложения.
 */
@Composable
fun AdminModelSection(
    state: AdminModelState,
    onPin: (AdminModelPin) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val activeSpec = rememberLocalModelSpec()
    val options = buildList {
        add(AdminModelPin.None to stringResource(R.string.omnix_admin_models_default))
        LocalModelSpec.ALL.forEach { spec ->
            add(AdminModelPin.Phone(spec.modelId) to stringResource(R.string.omnix_admin_answer_phone, spec.displayName))
        }
        state.cloudModels.forEach { model ->
            add(AdminModelPin.Cloud(model.provider) to cloudModelLabel(model.provider, model.model))
        }
    }
    val pin = state.pin
    val restartNeeded = pin is AdminModelPin.Phone && pin.specId != activeSpec.modelId

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.omnix_admin_models_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = stringResource(R.string.omnix_admin_models_body),
            style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp, lineHeight = 17.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
                .selectableGroup()
        ) {
            options.forEachIndexed { index, (option, label) ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.textPrimary.copy(alpha = 0.06f))
                    )
                }
                PinRow(title = label, selected = option == pin, onClick = { onPin(option) })
            }
        }
        if (restartNeeded) {
            Text(
                text = stringResource(R.string.omnix_admin_models_restart),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp, lineHeight = 19.sp),
                color = colors.stateWarning,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
    }
}

/**
 * Тест фразы вызова (только админ): новая фраза «Окей, Омни», одно «Омни» на
 * том же движке и прежняя модель «Omni» — чтобы сравнить на своём телефоне.
 * Действует со следующего запуска прослушивания.
 */
@Composable
fun AdminWakeSection(
    current: Pair<WakeEngineKind, WakePhrase>,
    onSelect: (WakeEngineKind, WakePhrase) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val options = listOf(
        Triple(WakeEngineKind.PHRASE, WakePhrase.OKAY_OMNI, stringResource(R.string.omnix_admin_wake_okay_omni)),
        Triple(WakeEngineKind.PHRASE, WakePhrase.OMNI, stringResource(R.string.omnix_admin_wake_omni)),
        Triple(WakeEngineKind.LEGACY_OMNI, WakePhrase.OKAY_OMNI, stringResource(R.string.omnix_admin_wake_legacy)),
    )
    // Прежняя модель не зависит от фразы: отмечаем её по движку.
    fun isSelected(engine: WakeEngineKind, phrase: WakePhrase) =
        engine == current.first && (engine == WakeEngineKind.LEGACY_OMNI || phrase == current.second)

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.omnix_admin_wake_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Text(
            text = stringResource(R.string.omnix_admin_wake_body),
            style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp, lineHeight = 17.sp),
            color = colors.textSecondary,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
                .selectableGroup()
        ) {
            options.forEachIndexed { index, (engine, phrase, label) ->
                if (index > 0) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(colors.textPrimary.copy(alpha = 0.06f))
                    )
                }
                PinRow(title = label, selected = isSelected(engine, phrase), onClick = { onSelect(engine, phrase) })
            }
        }
    }
}

@Composable
private fun PinRow(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = title,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
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
