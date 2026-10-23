package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest

/**
 * «Память» — отдельный экран (отзыв владельца на v344: раздел был неудобным).
 * Раньше воспоминания лежали внутри «Приватности», а факты о пользователе
 * («Имя», «Город») прятались под «Дополнительно». Теперь всё, что OMNIX
 * помнит, видно сразу, и запомнить можно не только голосом.
 *
 * Удаление — через общий лист подтверждения: одна запись, один вопрос,
 * удаляется ровно та строка, которую видит пользователь.
 */
@Composable
fun MemoryScreen(
    state: PrivacyPolicyUiState,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onAddMemory: (String) -> Unit = {},
    onForgetMemory: (Long, String?) -> Unit = { _, _ -> },
    onRemoveFact: (String) -> Unit = {},
    onRemoveProcedure: (String) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    var forgetArmedMemory by remember { mutableStateOf<MemoryEntryUi?>(null) }
    var removeArmedFact by remember { mutableStateOf<FactEntryUi?>(null) }
    var removeArmedProcedure by remember { mutableStateOf<ProcedureEntryUi?>(null) }

    SectionScaffold(stringResource(R.string.omnix_me_row_memory), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_memory_intro),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary,
            modifier = Modifier.padding(bottom = spacing.sm)
        )

        OmnixSettingsGroup {
            InsetBox {
                AddRow(
                    fieldHint = stringResource(R.string.omnix_memory_add_hint),
                    addLabel = stringResource(R.string.omnix_memory_add),
                    onAdd = onAddMemory
                )
            }
        }

        OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_memory_section_about))
        OmnixSettingsGroup {
            if (state.facts.isEmpty()) {
                OmnixSettingRow(title = stringResource(R.string.omnix_privacy_facts_empty), inset = true)
            } else {
                state.facts.forEachIndexed { index, fact ->
                    if (index > 0) OmnixGroupDivider()
                    OmnixSettingRow(
                        title = fact.value,
                        subtitle = factKeyLabel(fact.key),
                        inset = true,
                        trailing = {
                            OmnixTextButton(
                                text = stringResource(R.string.omnix_privacy_forget),
                                onClick = { removeArmedFact = fact }
                            )
                        }
                    )
                }
            }
        }

        OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_memory_section_saved))
        OmnixSettingsGroup {
            if (state.memories.isEmpty()) {
                OmnixSettingRow(title = stringResource(R.string.omnix_privacy_memories_empty), inset = true)
            } else {
                state.memories.forEachIndexed { index, memory ->
                    if (index > 0) OmnixGroupDivider()
                    OmnixSettingRow(
                        title = memory.content,
                        subtitle = memoryTypeText(memory.typeLabel),
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

        // Сценарии появляются только когда они есть: пустой раздел с непонятным
        // словом «процедуры» только путал.
        if (state.procedures.isNotEmpty()) {
            OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_memory_section_habits))
            OmnixSettingsGroup {
                state.procedures.forEachIndexed { index, procedure ->
                    if (index > 0) OmnixGroupDivider()
                    OmnixSettingRow(
                        title = procedure.trigger,
                        inset = true,
                        trailing = {
                            OmnixTextButton(
                                text = stringResource(R.string.omnix_privacy_remove),
                                onClick = { removeArmedProcedure = procedure }
                            )
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.lg))
    }

    forgetArmedMemory?.let { memory ->
        ForgetSheet(
            detail = memory.content,
            confirmLabel = stringResource(R.string.omnix_privacy_forget),
            onConfirm = {
                onForgetMemory(memory.id, memory.keyName)
                forgetArmedMemory = null
            },
            onCancel = { forgetArmedMemory = null }
        )
    }
    removeArmedFact?.let { fact ->
        ForgetSheet(
            detail = "${factKeyLabel(fact.key)}: ${fact.value}",
            confirmLabel = stringResource(R.string.omnix_privacy_forget),
            onConfirm = {
                onRemoveFact(fact.key)
                removeArmedFact = null
            },
            onCancel = { removeArmedFact = null }
        )
    }
    removeArmedProcedure?.let { procedure ->
        ForgetSheet(
            detail = procedure.trigger,
            confirmLabel = stringResource(R.string.omnix_privacy_remove),
            onConfirm = {
                onRemoveProcedure(procedure.trigger)
                removeArmedProcedure = null
            },
            onCancel = { removeArmedProcedure = null }
        )
    }
}

@Composable
private fun InsetBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.padding(horizontal = OmnixTheme.spacing.md)
    ) { content() }
}

@Composable
private fun ForgetSheet(detail: String, confirmLabel: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    ConfirmationSheet(
        request = ConfirmationRequest(
            title = stringResource(R.string.omnix_privacy_forget_confirm_title),
            detail = stringResource(R.string.omnix_privacy_forget_confirm_body, detail),
            confirmLabel = confirmLabel,
            cancelLabel = stringResource(R.string.omnix_cancel),
            voiceEnabled = false
        ),
        onConfirm = onConfirm,
        onCancel = onCancel
    )
}
