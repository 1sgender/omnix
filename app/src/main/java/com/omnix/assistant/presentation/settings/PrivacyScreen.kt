package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.agent.memory.model.MemoryTypeLabel
import com.omnix.assistant.agent.policy.CallConfirmationPolicy
import com.omnix.assistant.agent.policy.MessagingConfirmationPolicy
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixCheckIcon
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest

/**
 * Privacy (§26, §42, §52).
 *
 * The point of this screen is reassurance through specificity: it states what
 * is kept, where it is kept, and what leaves the phone — in sentences a
 * non-technical person can act on. It never mentions providers, endpoints,
 * model names or token counts (§4).
 *
 * Every value shown here is read from real state; nothing is asserted that
 * the app does not actually enforce (§3). Deleting history is destructive and
 * irreversible, so it asks through the shared confirmation sheet first — and
 * it only exists as an action at all because the caller wires it to the real
 * clear-history path.
 *
 * Working controls (frontend-rebuild plan 2026-09-26): confirmation policies
 * and trusted contacts write through to the policy engine immediately — there
 * is no batch "Save" anywhere on this screen. Forced rules (money, deletions,
 * typing in other apps, automation actions) are stated as facts, never as
 * switches: the engine does not let anyone turn them off. The screen-reading
 * boundary edits the same store the accessibility service reads synchronously.
 */
@Composable
fun PrivacyScreen(
    microphoneAllowed: Boolean,
    historyStored: Boolean,
    modifier: Modifier = Modifier,
    policyState: PrivacyPolicyUiState = PrivacyPolicyUiState(),
    onBack: (() -> Unit)? = null,
    onManagePermissions: (() -> Unit)? = null,
    onDeleteHistory: (() -> Unit)? = null,
    onCallPolicyChange: (CallConfirmationPolicy) -> Unit = {},
    onMessagingPolicyChange: (MessagingConfirmationPolicy) -> Unit = {},
    onAddTrustedContact: (String) -> Unit = {},
    onRemoveTrustedContact: (String) -> Unit = {},
    onScreenReaderModeChange: (Boolean) -> Unit = {},
    onBlockPackage: (String) -> Unit = {},
    onUnblockPackage: (String) -> Unit = {},
    onAllowPackage: (String) -> Unit = {},
    onRevokePackageAllowance: (String) -> Unit = {},
    onForgetMemory: (Long) -> Unit = {},
    onRemoveFact: (String) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    var deleteArmed by remember { mutableStateOf(false) }
    // Браузер памяти: удаление одного воспоминания/факта — деструктивное
    // действие, через общий confirmation sheet (вес тот же, что у удаления
    // истории: одно нажатие теряет данные безвозвратно).
    var forgetArmedMemory by remember { mutableStateOf<MemoryEntryUi?>(null) }
    var removeArmedFact by remember { mutableStateOf<FactEntryUi?>(null) }

    SectionScaffold(
        stringResource(R.string.omnix_privacy_title),
        modifier,
        onBack
    ) {
        OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_microphone),
                    value = stringResource(
                        if (microphoneAllowed) {
                            R.string.omnix_privacy_allowed
                        } else {
                            R.string.omnix_privacy_not_allowed
                        }
                    ),
                    inset = true
                )
                OmnixGroupDivider()

                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_voice_history),
                    value = stringResource(
                        if (historyStored) {
                            R.string.omnix_privacy_stored_on_device
                        } else {
                            R.string.omnix_privacy_not_stored
                        }
                    ),
                    inset = true
                )
                OmnixGroupDivider()

                // This reflects a real behaviour: the orchestrator asks for consent
                // before sending a request classified as private to the cloud.
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_cloud),
                    value = stringResource(R.string.omnix_privacy_cloud_controlled),
                    inset = true
                )
                OmnixGroupDivider()

                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_permissions),
                    value = stringResource(R.string.omnix_privacy_permissions_manage),
                    inset = true,
                    chevron = onManagePermissions != null,
                    onClick = onManagePermissions
                )
            }

        OmnixSettingsSectionHeader(
            text = stringResource(R.string.omnix_privacy_section_confirmations)
        )
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_calls),
                    inset = true
                )
                Column(modifier = Modifier.selectableGroup()) {
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_calls_always),
                        selected = policyState.callPolicy == CallConfirmationPolicy.ALWAYS,
                        onSelect = { onCallPolicyChange(CallConfirmationPolicy.ALWAYS) }
                    )
                    OmnixGroupDivider()
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_calls_trusted),
                        selected = policyState.callPolicy == CallConfirmationPolicy.TRUSTED_ONLY,
                        onSelect = { onCallPolicyChange(CallConfirmationPolicy.TRUSTED_ONLY) }
                    )
                    OmnixGroupDivider()
                    PolicyOption(
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
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_messages_always),
                        selected = policyState.messagingPolicy == MessagingConfirmationPolicy.ALWAYS,
                        onSelect = { onMessagingPolicyChange(MessagingConfirmationPolicy.ALWAYS) }
                    )
                    OmnixGroupDivider()
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_messages_money),
                        selected = policyState.messagingPolicy == MessagingConfirmationPolicy.MONEY_ONLY,
                        onSelect = { onMessagingPolicyChange(MessagingConfirmationPolicy.MONEY_ONLY) }
                    )
                    OmnixGroupDivider()
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_messages_never),
                        selected = policyState.messagingPolicy == MessagingConfirmationPolicy.NEVER,
                        onSelect = { onMessagingPolicyChange(MessagingConfirmationPolicy.NEVER) }
                    )
                }
                OmnixGroupDivider()
                // Forced rules are stated, not switched: the engine keeps them
                // on for money, deletions, accessibility typing and anything an
                // automation tries to run — by design, not by preference.
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_forced_title),
                    subtitle = stringResource(R.string.omnix_privacy_forced_body),
                    inset = true
                )
            }

        OmnixSettingsSectionHeader(
            text = stringResource(R.string.omnix_privacy_section_contacts)
        )
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_contacts_hint),
                    inset = true
                )
                if (policyState.trustedContacts.isEmpty()) {
                    OmnixSettingRow(
                        title = stringResource(R.string.omnix_privacy_contacts_empty),
                        inset = true
                    )
                } else {
                    policyState.trustedContacts.forEachIndexed { index, contact ->
                        if (index > 0) OmnixGroupDivider()
                        OmnixSettingRow(
                            title = contact,
                            inset = true,
                            trailing = {
                                OmnixTextButton(
                                    text = stringResource(R.string.omnix_privacy_remove),
                                    onClick = { onRemoveTrustedContact(contact) }
                                )
                            }
                        )
                    }
                }
                OmnixGroupDivider()
                AddRow(
                    fieldHint = stringResource(R.string.omnix_privacy_contact_add_hint),
                    addLabel = stringResource(R.string.omnix_privacy_add),
                    onAdd = onAddTrustedContact
                )
            }

        OmnixSettingsSectionHeader(
            text = stringResource(R.string.omnix_privacy_section_screen)
        )
            OmnixSettingsGroup {
                Column(modifier = Modifier.selectableGroup()) {
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_screen_mode_block),
                        subtitle = stringResource(R.string.omnix_privacy_screen_mode_block_hint),
                        selected = !policyState.allowListMode,
                        onSelect = { onScreenReaderModeChange(false) }
                    )
                    OmnixGroupDivider()
                    PolicyOption(
                        label = stringResource(R.string.omnix_privacy_screen_mode_allow),
                        subtitle = stringResource(R.string.omnix_privacy_screen_mode_allow_hint),
                        selected = policyState.allowListMode,
                        onSelect = { onScreenReaderModeChange(true) }
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_blocked_apps),
                    inset = true
                )
                if (policyState.blockedPackages.isEmpty()) {
                    OmnixSettingRow(
                        title = stringResource(R.string.omnix_privacy_blocked_empty),
                        inset = true
                    )
                } else {
                    policyState.blockedPackages.forEachIndexed { index, packageName ->
                        if (index > 0) OmnixGroupDivider()
                        OmnixSettingRow(
                            title = packageName,
                            inset = true,
                            trailing = {
                                OmnixTextButton(
                                    text = stringResource(R.string.omnix_privacy_remove),
                                    onClick = { onUnblockPackage(packageName) }
                                )
                            }
                        )
                    }
                }
                OmnixGroupDivider()
                AddRow(
                    fieldHint = stringResource(R.string.omnix_privacy_package_hint),
                    addLabel = stringResource(R.string.omnix_privacy_add),
                    onAdd = onBlockPackage
                )
            }
            Spacer(Modifier.height(spacing.sm))
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_allowed_apps),
                    inset = true
                )
                if (policyState.allowedPackages.isEmpty()) {
                    OmnixSettingRow(
                        title = stringResource(R.string.omnix_privacy_allowed_empty),
                        inset = true
                    )
                } else {
                    policyState.allowedPackages.forEachIndexed { index, packageName ->
                        if (index > 0) OmnixGroupDivider()
                        OmnixSettingRow(
                            title = packageName,
                            inset = true,
                            trailing = {
                                OmnixTextButton(
                                    text = stringResource(R.string.omnix_privacy_remove),
                                    onClick = { onRevokePackageAllowance(packageName) }
                                )
                            }
                        )
                    }
                }
                OmnixGroupDivider()
                AddRow(
                    fieldHint = stringResource(R.string.omnix_privacy_package_hint),
                    addLabel = stringResource(R.string.omnix_privacy_add),
                    onAdd = onAllowPackage
                )
            }

        OmnixSettingsSectionHeader(
            text = stringResource(R.string.omnix_privacy_section_memory)
        )
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_memories_hint),
                inset = true
            )
            if (policyState.memories.isEmpty()) {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_memories_empty),
                    inset = true
                )
            } else {
                policyState.memories.forEachIndexed { index, memory ->
                    if (index > 0) OmnixGroupDivider()
                    OmnixSettingRow(
                        title = memory.content,
                        value = memoryTypeText(memory.typeLabel),
                        inset = true,
                        trailing = {
                            OmnixTextButton(
                                text = stringResource(R.string.omnix_privacy_forget),
                                onClick = { forgetArmedMemory = memory }
                            )
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.sm))
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_facts_hint),
                inset = true
            )
            if (policyState.facts.isEmpty()) {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_facts_empty),
                    inset = true
                )
            } else {
                policyState.facts.forEachIndexed { index, fact ->
                    if (index > 0) OmnixGroupDivider()
                    OmnixSettingRow(
                        title = fact.key,
                        value = fact.value,
                        inset = true,
                        trailing = {
                            OmnixTextButton(
                                text = stringResource(R.string.omnix_privacy_remove),
                                onClick = { removeArmedFact = fact }
                            )
                        }
                    )
                }
            }
        }

        if (onDeleteHistory != null) {
            Spacer(Modifier.height(spacing.xl))
            OmnixTextButton(
                text = stringResource(R.string.omnix_privacy_delete_history),
                onClick = { deleteArmed = true }
            )
        }
    }

    // The same copy the chat and history use: one log, one question (§17).
    if (deleteArmed) {
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_chat_clear_confirm_title),
                detail = stringResource(R.string.omnix_chat_clear_confirm_body),
                confirmLabel = stringResource(R.string.omnix_privacy_delete_history),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = {
                deleteArmed = false
                onDeleteHistory?.invoke()
            },
            onCancel = { deleteArmed = false }
        )
    }

    // Memory browser: one entry, one question — the row the user sees is the
    // row that gets deleted (no semantic matching on the UI path).
    forgetArmedMemory?.let { memory ->
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_privacy_forget_confirm_title),
                detail = stringResource(R.string.omnix_privacy_forget_confirm_body, memory.content),
                confirmLabel = stringResource(R.string.omnix_privacy_forget),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = {
                onForgetMemory(memory.id)
                forgetArmedMemory = null
            },
            onCancel = { forgetArmedMemory = null }
        )
    }
    removeArmedFact?.let { fact ->
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_privacy_forget_confirm_title),
                detail = stringResource(R.string.omnix_privacy_forget_confirm_body, "${fact.key}: ${fact.value}"),
                confirmLabel = stringResource(R.string.omnix_privacy_remove),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = {
                onRemoveFact(fact.key)
                removeArmedFact = null
            },
            onCancel = { removeArmedFact = null }
        )
    }
}

/**
 * Подпись типа воспоминания: токен из домена → string-ресурс. Сырые
 * enum-имена (FACT/PREFERENCE/…) пользователю не показываются.
 */
@Composable
private fun memoryTypeText(label: MemoryTypeLabel): String = when (label) {
    MemoryTypeLabel.FACT -> stringResource(R.string.omnix_memory_type_fact)
    MemoryTypeLabel.PREFERENCE -> stringResource(R.string.omnix_memory_type_preference)
    MemoryTypeLabel.EPISODIC -> stringResource(R.string.omnix_memory_type_episodic)
    MemoryTypeLabel.PROCEDURAL -> stringResource(R.string.omnix_memory_type_procedural)
    MemoryTypeLabel.OTHER -> stringResource(R.string.omnix_memory_type_other)
}

/**
 * One selectable line of a policy: the visible checkmark and the spoken
 * "selected" state are the same fact, stated twice (§55) — mirrors
 * AppearanceOption.
 */
@Composable
private fun PolicyOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    subtitle: String? = null
) {
    OmnixSettingRow(
        title = label,
        subtitle = subtitle,
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

/**
 * Inline "add" row: a quiet field with the design system's text style and
 * a text-button commit. Committing clears the field — the new entry appears
 * in the list above immediately (immediate write, no batch save).
 */
@Composable
private fun AddRow(
    fieldHint: String,
    addLabel: String,
    onAdd: (String) -> Unit
) {
    val colors = OmnixTheme.colors
    var text by remember { mutableStateOf("") }
    val commit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = OmnixTheme.typography.subheadline.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.textPrimary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { commit() }),
            decorationBox = { innerField ->
                Box(modifier = Modifier.fillMaxWidth()) {
                    if (text.isEmpty()) {
                        Text(
                            text = fieldHint,
                            style = OmnixTheme.typography.subheadline,
                            color = colors.textTertiary
                        )
                    }
                    innerField()
                }
            },
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 44.dp)
        )
        OmnixTextButton(
            text = addLabel,
            onClick = commit,
            enabled = text.isNotBlank()
        )
    }
}
