package com.omnix.assistant.presentation.automations

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.agent.automation.model.AutomationActionLabel
import com.omnix.assistant.agent.automation.model.AutomationTriggerLabel
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.OmnixGroupDivider
import com.omnix.assistant.presentation.settings.OmnixSettingRow
import com.omnix.assistant.presentation.settings.OmnixSettingsGroup
import com.omnix.assistant.presentation.settings.OmnixToggleRow
import com.omnix.assistant.presentation.settings.SectionScaffold
import com.omnix.assistant.presentation.state.ConfirmationRequest
import java.text.DateFormat
import java.util.Date

/**
 * Automations (gap report 2026-09-26, block 1; owner plan 2026-09-26).
 *
 * The list is the same live Room stream the engine matches against: a rule
 * toggled off here stops firing because the matcher reads the same rows —
 * the screen never fakes state (§3). Deletion is destructive and asks
 * through the shared confirmation sheet, exactly like clearing history;
 * deleted default rules are remembered so the engine does not resurrect
 * them on the next system event.
 *
 * Creation stays voice-first by design: the empty state teaches the phrase
 * instead of hiding the capability behind a form (§41).
 */
@Composable
fun AutomationsScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: AutomationsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var deleteArmedRuleId by remember { mutableStateOf<String?>(null) }
    val deleteArmedRule = state.rules.firstOrNull { it.ruleId == deleteArmedRuleId }

    SectionScaffold(stringResource(R.string.omnix_voice_automations), modifier, onBack) {
        val spacing = OmnixTheme.spacing

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
                titleColor = if (state.briefingSucceeded == false) {
                    OmnixTheme.colors.stateError
                } else {
                    null
                },
                onClick = viewModel::runBriefingNow
            )
        }

        Spacer(Modifier.height(spacing.lg))

        if (state.rules.isEmpty()) {
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_voice_automations),
                    subtitle = stringResource(R.string.omnix_voice_automations_empty),
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

    if (deleteArmedRule != null) {
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_automations_delete_title),
                detail = stringResource(R.string.omnix_automations_delete_body, deleteArmedRule.name),
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

@Composable
private fun AutomationCard(
    rule: AutomationUiModel,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val spacing = OmnixTheme.spacing
    OmnixSettingsGroup {
        OmnixToggleRow(
            title = rule.name,
            subtitle = TriggerText(rule),
            checked = rule.isEnabled,
            onCheckedChange = onToggle,
            inset = true
        )
        OmnixGroupDivider()
        OmnixSettingRow(
            title = ActionsText(rule),
            value = TriggerStats(rule),
            inset = true
        )
        OmnixSettingRow(
            title = stringResource(R.string.omnix_automations_delete),
            enabled = true,
            inset = true,
            titleColor = OmnixTheme.colors.stateError,
            onClick = onDelete
        )
    }
}

@Composable
private fun TriggerText(rule: AutomationUiModel): String = when (rule.trigger) {
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

@Composable
private fun ActionsText(rule: AutomationUiModel): String {
    if (rule.actions.isEmpty()) {
        return stringResource(R.string.omnix_automations_no_actions)
    }
    // Явный цикл, а не joinToString{}: лямбба не является composable-контекстом,
    // stringResource внутри неё не компилируется.
    val parts = ArrayList<String>(rule.actions.size)
    for (action in rule.actions) {
        parts.add(
            when (action.label) {
                AutomationActionLabel.OPEN_APP ->
                    stringResource(R.string.omnix_automations_action_app, action.param.orEmpty())
                AutomationActionLabel.WEATHER -> stringResource(R.string.omnix_automations_action_weather)
                AutomationActionLabel.TIME -> stringResource(R.string.omnix_automations_action_time)
                AutomationActionLabel.MEMORY -> stringResource(R.string.omnix_automations_action_memory)
                AutomationActionLabel.VOLUME -> stringResource(R.string.omnix_automations_action_volume)
                AutomationActionLabel.MEDIA -> stringResource(R.string.omnix_automations_action_media)
                AutomationActionLabel.BRIEFING -> stringResource(R.string.omnix_automations_action_briefing)
                AutomationActionLabel.OTHER -> stringResource(R.string.omnix_automations_action_other)
            }
        )
    }
    return parts.joinToString(" + ")
}

@Composable
private fun TriggerStats(rule: AutomationUiModel): String {
    if (rule.lastTriggeredAt == null || rule.triggerCount <= 0) {
        return stringResource(R.string.omnix_automations_never_fired)
    }
    val formatted = remember(rule.lastTriggeredAt) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(rule.lastTriggeredAt))
    }
    return stringResource(R.string.omnix_automations_fired, rule.triggerCount, formatted)
}
