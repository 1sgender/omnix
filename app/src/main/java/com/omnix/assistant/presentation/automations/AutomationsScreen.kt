package com.omnix.assistant.presentation.automations

import android.app.TimePickerDialog
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import java.util.Locale
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.agent.automation.model.AutomationActionLabel
import com.omnix.assistant.agent.automation.model.AutomationTriggerLabel
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixCheckIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.OmnixGroupDivider
import com.omnix.assistant.presentation.settings.OmnixSettingRow
import com.omnix.assistant.presentation.settings.OmnixSettingsGroup
import com.omnix.assistant.presentation.settings.OmnixSettingsSectionHeader
import com.omnix.assistant.presentation.settings.OmnixTextFieldRow
import com.omnix.assistant.presentation.settings.SectionScaffold
import com.omnix.assistant.presentation.state.ConfirmationRequest
import java.text.DateFormat
import java.util.Date

/**
 * Automations (gap report 2026-09-26, block 1; owner report on v344).
 *
 * The list is the same live Room stream the engine matches against: a rule
 * toggled off here stops firing because the matcher reads the same rows —
 * the screen never fakes state (§3). Deletion asks through the shared
 * confirmation sheet; deleted built-in rules are remembered so the engine
 * does not resurrect them.
 *
 * v344 could only create rules by voice; «Новая автоматизация» now opens a
 * two-question form — when and what to do — with only actions the engine runs
 * without extra permissions.
 */
@Composable
fun AutomationsScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onDescribe: ((String) -> Unit)? = null,
    viewModel: AutomationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteArmedRuleId by remember { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    val deleteArmedRule = state.rules.firstOrNull { it.ruleId == deleteArmedRuleId }

    SectionScaffold(stringResource(R.string.omnix_voice_automations), modifier, onBack) {
        val spacing = OmnixTheme.spacing

        // Протокол можно описать словами: чат поймёт и создаст его.
        if (onDescribe != null) {
            var described by rememberSaveable { mutableStateOf("") }
            OutlinedTextField(
                value = described,
                onValueChange = { described = it.take(300) },
                placeholder = { Text(stringResource(R.string.omnix_protocols_describe_hint)) },
                minLines = 2,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(spacing.sm))
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_protocols_describe_button),
                onClick = {
                    onDescribe(described)
                    described = ""
                },
                enabled = described.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(spacing.md))
        }

        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_automations_new),
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(spacing.md))

        // The ear briefing is the flagship demo action: one tap, offline,
        // plan-gated through the same tool the voice path uses.
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_automations_briefing_now),
                subtitle = when {
                    state.briefingRunning -> stringResource(R.string.omnix_automations_briefing_running)
                    state.briefingOutcome != null -> state.briefingOutcome
                    else -> stringResource(R.string.omnix_automations_briefing_body)
                },
                enabled = !state.briefingRunning,
                value = if (state.briefingSucceeded == true) {
                    stringResource(R.string.omnix_automations_briefing_ok)
                } else {
                    null
                },
                inset = true,
                chevron = true,
                titleColor = if (state.briefingSucceeded == false) OmnixTheme.colors.stateError else null,
                onClick = viewModel::runBriefingNow
            )
        }

        Spacer(Modifier.height(spacing.lg))

        if (state.rules.isEmpty()) {
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_voice_automations_empty),
                    inset = true
                )
            }
        } else {
            state.rules.forEach { rule ->
                AutomationCard(
                    rule = rule,
                    onToggle = { enabled -> viewModel.toggleRule(rule.ruleId, enabled) },
                    onDelete = { deleteArmedRuleId = rule.ruleId }
                )
                Spacer(Modifier.height(spacing.sm))
            }
        }
    }

    if (creating) {
        NewAutomationSheet(
            onCreate = { draft, name ->
                viewModel.createRule(draft, name)
                creating = false
            },
            onDismiss = { creating = false }
        )
    }

    state.limitReached?.let { max ->
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_automations_limit_title),
                detail = stringResource(R.string.omnix_automations_limit_body, max),
                confirmLabel = stringResource(R.string.omnix_automations_limit_ok),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = viewModel::dismissLimit,
            onCancel = viewModel::dismissLimit
        )
    }

    if (deleteArmedRule != null) {
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_automations_delete_title),
                detail = stringResource(R.string.omnix_automations_delete_body, ruleName(deleteArmedRule)),
                confirmLabel = stringResource(R.string.omnix_automations_delete_confirm),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = {
                viewModel.deleteRule(deleteArmedRule.ruleId)
                deleteArmedRuleId = null
            },
            onCancel = { deleteArmedRuleId = null }
        )
    }
}

/** Одна карточка: переключатель с «когда → что», ниже статистика и «Удалить». */
@Composable
private fun AutomationCard(
    rule: AutomationUiModel,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val colors = OmnixTheme.colors
    val isTime = rule.trigger == AutomationTriggerLabel.TIME_SCHEDULE && !rule.triggerParam.isNullOrBlank()
    OmnixSettingsGroup {
        // «Трафарет»: слева «когда» (время крупно или условие моноширинным),
        // в центре имя и действия чипами, справа переключатель.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.width(64.dp)) {
                if (isTime) {
                    Text(
                        text = rule.triggerParam.orEmpty(),
                        style = OmnixTheme.typography.headline,
                        color = if (rule.isEnabled) colors.textPrimary else colors.textTertiary
                    )
                } else {
                    Text(
                        text = triggerText(rule).uppercase(Locale.getDefault()),
                        style = OmnixMonoLabelStyle.copy(fontSize = 10.sp),
                        color = colors.textTertiary,
                        maxLines = 3
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = ruleName(rule),
                    style = OmnixTheme.typography.headline.copy(fontSize = 15.sp),
                    color = if (rule.isEnabled) colors.textPrimary else colors.textSecondary
                )
                ActionChips(actionLabels(rule))
            }
            Switch(
                checked = rule.isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colors.background,
                    checkedTrackColor = colors.actionPrimary,
                    checkedBorderColor = colors.actionPrimary,
                    uncheckedThumbColor = colors.textTertiary,
                    uncheckedTrackColor = colors.surface,
                    uncheckedBorderColor = colors.border
                )
            )
        }
        OmnixGroupDivider()
        OmnixSettingRow(
            title = triggerStats(rule),
            inset = true,
            titleColor = OmnixTheme.colors.textTertiary,
            trailing = {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_automations_delete_confirm),
                    onClick = onDelete
                )
            }
        )
    }
}

/** Встроенные правила хранят русское имя в БД; на экране — перевод. */
@Composable
private fun ruleName(rule: AutomationUiModel): String = when (rule.ruleId) {
    "default_morning_schedule" -> stringResource(R.string.omnix_automations_default_morning)
    "default_morning_headphones" -> stringResource(R.string.omnix_automations_default_morning_headphones)
    "default_home_wifi" -> stringResource(R.string.omnix_automations_default_home)
    "default_battery_low" -> stringResource(R.string.omnix_automations_default_battery)
    "default_headphones_disconnected" -> stringResource(R.string.omnix_automations_default_pause)
    else -> if (rule.isDefault) stringResource(R.string.omnix_automations_builtin) else rule.name
}

@Composable
private fun triggerText(rule: AutomationUiModel): String = when (rule.trigger) {
    AutomationTriggerLabel.TIME_SCHEDULE ->
        stringResource(R.string.omnix_automations_trigger_time, rule.triggerParam.orEmpty())
    AutomationTriggerLabel.HEADPHONES_CONNECTED ->
        stringResource(R.string.omnix_automations_trigger_headphones_on)
    AutomationTriggerLabel.HEADPHONES_DISCONNECTED ->
        stringResource(R.string.omnix_automations_trigger_headphones_off)
    AutomationTriggerLabel.BATTERY_LOW ->
        stringResource(R.string.omnix_automations_trigger_battery)
    AutomationTriggerLabel.WIFI_CONNECTED ->
        stringResource(R.string.omnix_automations_trigger_wifi)
    AutomationTriggerLabel.VOICE_MACRO ->
        stringResource(R.string.omnix_automations_trigger_voice)
    AutomationTriggerLabel.UNKNOWN ->
        stringResource(R.string.omnix_automations_trigger_unknown)
}

/** Действия чипами в рамке; переносятся на новую строку, если не помещаются. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionChips(labels: List<String>) {
    val colors = OmnixTheme.colors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                style = OmnixTheme.typography.caption2,
                color = colors.textSecondary,
                modifier = Modifier
                    .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(8.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun actionLabels(rule: AutomationUiModel): List<String> {
    if (rule.actions.isEmpty()) {
        return listOf(stringResource(R.string.omnix_automations_no_actions))
    }
    val parts = ArrayList<String>(rule.actions.size)
    for (action in rule.actions) {
        parts.add(actionLabel(action.label, action.param))
    }
    return parts
}

@Composable
private fun actionLabel(label: AutomationActionLabel, param: String?): String = when (label) {
    AutomationActionLabel.OPEN_APP -> stringResource(R.string.omnix_automations_action_app, param.orEmpty())
    AutomationActionLabel.WEATHER -> stringResource(R.string.omnix_automations_action_weather)
    AutomationActionLabel.TIME -> stringResource(R.string.omnix_automations_action_time)
    AutomationActionLabel.MEMORY -> stringResource(R.string.omnix_automations_action_memory)
    AutomationActionLabel.VOLUME -> stringResource(R.string.omnix_automations_action_volume)
    AutomationActionLabel.MEDIA -> stringResource(R.string.omnix_automations_action_media)
    AutomationActionLabel.BRIEFING -> stringResource(R.string.omnix_automations_action_briefing)
    AutomationActionLabel.OTHER -> stringResource(R.string.omnix_automations_action_other)
}

@Composable
private fun triggerStats(rule: AutomationUiModel): String {
    if (rule.lastTriggeredAt == null || rule.triggerCount <= 0) {
        return stringResource(R.string.omnix_automations_never_fired)
    }
    val formatted = remember(rule.lastTriggeredAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(rule.lastTriggeredAt))
    }
    return pluralStringResource(
        R.plurals.omnix_automations_fired,
        rule.triggerCount,
        rule.triggerCount,
        formatted
    )
}

/** Форма «Новая автоматизация»: когда → что сделать → «Создать». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewAutomationSheet(
    onCreate: (AutomationDraft, String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val context = LocalContext.current
    var draft by remember { mutableStateOf(AutomationDraft()) }

    val whenLabel = draftTriggerText(draft)
    val doLabel = draftActionText(draft)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = OmnixTheme.radius.large, topEnd = OmnixTheme.radius.large)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.lg)
                .padding(bottom = spacing.xxl),
            verticalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            Text(
                text = stringResource(R.string.omnix_automations_new),
                style = OmnixTheme.typography.heading,
                color = colors.textPrimary
            )

            OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_automations_new_when))
            OmnixSettingsGroup {
                DraftTrigger.entries.forEachIndexed { index, trigger ->
                    if (index > 0) OmnixGroupDivider()
                    ChoiceRow(
                        label = draftTriggerText(draft.copy(trigger = trigger), withTime = false),
                        selected = draft.trigger == trigger,
                        onSelect = { draft = draft.copy(trigger = trigger) }
                    )
                }
                if (draft.trigger == DraftTrigger.TIME) {
                    OmnixGroupDivider()
                    OmnixSettingRow(
                        title = stringResource(R.string.omnix_automations_new_time, draft.time),
                        inset = true,
                        chevron = true,
                        onClick = {
                            TimePickerDialog(
                                context,
                                { _, hour, minute -> draft = draft.copy(hour = hour, minute = minute) },
                                draft.hour,
                                draft.minute,
                                AndroidDateFormat.is24HourFormat(context)
                            ).show()
                        }
                    )
                }
            }

            OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_automations_new_do))
            OmnixSettingsGroup {
                DraftAction.entries.forEachIndexed { index, action ->
                    if (index > 0) OmnixGroupDivider()
                    ChoiceRow(
                        label = draftActionText(draft.copy(action = action), withValue = false),
                        selected = draft.action == action,
                        onSelect = { draft = draft.copy(action = action) }
                    )
                }
            }

            when (draft.action) {
                DraftAction.VOLUME -> {
                    Text(
                        text = stringResource(R.string.omnix_automations_new_volume_value, draft.volumePercent),
                        style = OmnixTheme.typography.subheadline,
                        color = colors.textPrimary,
                        modifier = Modifier.padding(top = spacing.sm)
                    )
                    Slider(
                        value = draft.volumePercent.toFloat(),
                        onValueChange = { draft = draft.copy(volumePercent = it.toInt()) },
                        valueRange = 0f..100f,
                        steps = 9,
                        colors = SliderDefaults.colors(
                            thumbColor = colors.accentBrand,
                            activeTrackColor = colors.accentBrand
                        )
                    )
                }
                DraftAction.OPEN_APP -> OmnixTextFieldRow(
                    title = stringResource(R.string.omnix_automations_new_action_app),
                    value = draft.appName,
                    onValueChange = { draft = draft.copy(appName = it) },
                    placeholder = stringResource(R.string.omnix_automations_new_app_hint),
                    modifier = Modifier.padding(top = spacing.sm)
                )
                else -> Unit
            }

            Spacer(Modifier.height(spacing.md))
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_automations_new_create),
                onClick = { onCreate(draft, "$whenLabel → $doLabel") },
                enabled = draft.isComplete,
                modifier = Modifier.fillMaxWidth()
            )
            OmnixSecondaryButton(
                text = stringResource(R.string.omnix_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun draftTriggerText(draft: AutomationDraft, withTime: Boolean = true): String = when (draft.trigger) {
    DraftTrigger.TIME -> if (withTime) {
        stringResource(R.string.omnix_automations_trigger_time, draft.time)
    } else {
        stringResource(R.string.omnix_automations_trigger_time, "…")
    }
    DraftTrigger.HEADPHONES_ON -> stringResource(R.string.omnix_automations_trigger_headphones_on)
    DraftTrigger.HEADPHONES_OFF -> stringResource(R.string.omnix_automations_trigger_headphones_off)
    DraftTrigger.WIFI -> stringResource(R.string.omnix_automations_trigger_wifi)
    DraftTrigger.BATTERY_LOW -> stringResource(R.string.omnix_automations_trigger_battery)
}

@Composable
private fun draftActionText(draft: AutomationDraft, withValue: Boolean = true): String = when (draft.action) {
    DraftAction.BRIEFING -> stringResource(R.string.omnix_automations_briefing_now)
    DraftAction.PAUSE_MEDIA -> stringResource(R.string.omnix_automations_new_action_pause)
    DraftAction.VOLUME -> if (withValue) {
        stringResource(R.string.omnix_automations_new_volume_value, draft.volumePercent)
    } else {
        stringResource(R.string.omnix_automations_new_action_volume)
    }
    DraftAction.OPEN_APP -> if (withValue && draft.appName.isNotBlank()) {
        stringResource(R.string.omnix_automations_action_app, draft.appName.trim())
    } else {
        stringResource(R.string.omnix_automations_new_action_app)
    }
}

/** Строка выбора с галочкой; «выбрано» и видно, и озвучивается (§55). */
@Composable
private fun ChoiceRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    OmnixSettingRow(
        title = label,
        inset = true,
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
    ) {
        if (selected) OmnixCheckIcon(color = OmnixTheme.colors.textPrimary)
    }
}
