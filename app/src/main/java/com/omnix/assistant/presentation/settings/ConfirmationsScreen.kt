package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.omnix.assistant.R
import com.omnix.assistant.agent.policy.CallConfirmationPolicy
import com.omnix.assistant.agent.policy.MessagingConfirmationPolicy
import com.omnix.assistant.presentation.components.OmnixCheckIcon
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Подтверждения: когда OMNIX спрашивает, прежде чем позвонить или написать.
 * Перенесено из PrivacyScreen без изменения логики: политики и доверенные
 * контакты пишутся сразу, без общей кнопки «Сохранить».
 */
@Composable
fun ConfirmationsScreen(
    modifier: Modifier = Modifier,
    policyState: PrivacyPolicyUiState = PrivacyPolicyUiState(),
    onBack: (() -> Unit)? = null,
    onCallPolicyChange: (CallConfirmationPolicy) -> Unit = {},
    onMessagingPolicyChange: (MessagingConfirmationPolicy) -> Unit = {},
    onAddTrustedContact: (String) -> Unit = {},
    onRemoveTrustedContact: (String) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    SectionScaffold(stringResource(R.string.omnix_confirmations_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_confirmations_intro),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(spacing.sm))
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_calls),
                inset = true
            )
            Column(modifier = Modifier.selectableGroup()) {
                ConfirmationOption(
                    label = stringResource(R.string.omnix_privacy_calls_always),
                    selected = policyState.callPolicy == CallConfirmationPolicy.ALWAYS,
                    onSelect = { onCallPolicyChange(CallConfirmationPolicy.ALWAYS) }
                )
                OmnixGroupDivider()
                ConfirmationOption(
                    label = stringResource(R.string.omnix_privacy_calls_trusted),
                    selected = policyState.callPolicy == CallConfirmationPolicy.TRUSTED_ONLY,
                    onSelect = { onCallPolicyChange(CallConfirmationPolicy.TRUSTED_ONLY) }
                )
                OmnixGroupDivider()
                ConfirmationOption(
                    label = stringResource(R.string.omnix_privacy_calls_never),
                    selected = policyState.callPolicy == CallConfirmationPolicy.NEVER,
                    onSelect = { onCallPolicyChange(CallConfirmationPolicy.NEVER) }
                )
            }
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_messages),
                inset = true
            )
            Column(modifier = Modifier.selectableGroup()) {
                ConfirmationOption(
                    label = stringResource(R.string.omnix_privacy_messages_always),
                    selected = policyState.messagingPolicy == MessagingConfirmationPolicy.ALWAYS,
                    onSelect = { onMessagingPolicyChange(MessagingConfirmationPolicy.ALWAYS) }
                )
                OmnixGroupDivider()
                ConfirmationOption(
                    label = stringResource(R.string.omnix_privacy_messages_money),
                    selected = policyState.messagingPolicy == MessagingConfirmationPolicy.MONEY_ONLY,
                    onSelect = { onMessagingPolicyChange(MessagingConfirmationPolicy.MONEY_ONLY) }
                )
                OmnixGroupDivider()
                ConfirmationOption(
                    label = stringResource(R.string.omnix_privacy_messages_never),
                    selected = policyState.messagingPolicy == MessagingConfirmationPolicy.NEVER,
                    onSelect = { onMessagingPolicyChange(MessagingConfirmationPolicy.NEVER) }
                )
            }
            OmnixGroupDivider()
            // Обязательные правила названы фактом, а не переключателем.
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_forced_title),
                subtitle = stringResource(R.string.omnix_privacy_forced_body),
                inset = true
            )
            OmnixGroupDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_contacts_hint),
                inset = true
            )
            EntryListGroup(
                entries = policyState.trustedContacts,
                emptyText = stringResource(R.string.omnix_privacy_contacts_empty),
                removeLabel = stringResource(R.string.omnix_privacy_remove),
                onRemove = onRemoveTrustedContact,
                rowTitle = { it }
            )
            OmnixGroupDivider()
            AddRow(
                fieldHint = stringResource(R.string.omnix_privacy_contact_add_hint),
                addLabel = stringResource(R.string.omnix_privacy_add),
                onAdd = onAddTrustedContact
            )
        }
    }
}

/** Одна строка выбора: галочка и озвучиваемое «выбрано» — один и тот же факт. */
@Composable
private fun ConfirmationOption(label: String, selected: Boolean, onSelect: () -> Unit) {
    OmnixSettingRow(
        title = label,
        inset = true,
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
    ) {
        if (selected) {
            OmnixCheckIcon(color = OmnixTheme.colors.textPrimary)
        }
    }
}
