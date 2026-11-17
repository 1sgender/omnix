package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.agent.memory.model.MemoryTypeLabel
import com.omnix.assistant.presentation.components.ConfirmationSheet
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
    onBack: (() -> Unit)? = null,
    onManagePermissions: (() -> Unit)? = null,
    onDeleteHistory: (() -> Unit)? = null,
    onOpenConfirmations: (() -> Unit)? = null
) {
    val spacing = OmnixTheme.spacing
    var deleteArmed by remember { mutableStateOf(false) }
    val openPolicy = rememberOpenPrivacyPolicy()

    SectionScaffold(
        stringResource(R.string.omnix_privacy_title),
        modifier,
        onBack
    ) {
        PrivacyWhereCards()
        Spacer(Modifier.height(spacing.xl))
        OmnixFlatList {
            OmnixFlatDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_confirmations),
                subtitle = stringResource(R.string.omnix_privacy_confirmations_body),
                chevron = onOpenConfirmations != null,
                onClick = onOpenConfirmations
            )
            OmnixFlatDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_microphone),
                value = stringResource(
                    if (microphoneAllowed) R.string.omnix_privacy_allowed else R.string.omnix_privacy_not_allowed
                )
            )
            OmnixFlatDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_voice_history),
                value = stringResource(
                    if (historyStored) R.string.omnix_privacy_stored_on_device else R.string.omnix_privacy_not_stored
                )
            )
            OmnixFlatDivider()
            // Реальное поведение: перед отправкой «приватного» запроса в облако
            // оркестратор спрашивает согласие.
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_cloud),
                value = stringResource(R.string.omnix_privacy_cloud_controlled)
            )
            OmnixFlatDivider()
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_permissions),
                value = stringResource(R.string.omnix_privacy_permissions_manage),
                chevron = onManagePermissions != null,
                onClick = onManagePermissions
            )
            OmnixFlatDivider()
            // Публичная политика на сервере OMNIX (адрес берётся из сборки).
            OmnixSettingRow(
                title = stringResource(R.string.omnix_privacy_policy_link),
                subtitle = stringResource(R.string.omnix_privacy_policy_link_body),
                chevron = true,
                onClick = openPolicy
            )
            OmnixFlatDivider()
            if (onDeleteHistory != null) {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_privacy_delete_history),
                    titleColor = OmnixTheme.colors.stateError,
                    onClick = { deleteArmed = true }
                )
                OmnixFlatDivider()
            }
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

}

/**
 * Одна строка-список с кнопкой удаления: пустое состояние или записи с
 * разделителями. Общий вид для контактов, пакетов, воспоминаний и фактов —
 * один и тот же жест удаления везде.
 */
@Composable
internal fun <T> EntryListGroup(
    entries: List<T>,
    emptyText: String,
    removeLabel: String,
    onRemove: (T) -> Unit,
    rowTitle: (T) -> String,
    rowValue: ((T) -> String)? = null
) {
    if (entries.isEmpty()) {
        OmnixSettingRow(title = emptyText, inset = true)
    } else {
        entries.forEachIndexed { index, entry ->
            if (index > 0) OmnixGroupDivider()
            OmnixSettingRow(
                title = rowTitle(entry),
                value = rowValue?.invoke(entry),
                inset = true,
                trailing = {
                    OmnixTextButton(
                        text = removeLabel,
                        onClick = { onRemove(entry) }
                    )
                }
            )
        }
    }
}

/**
 * Подпись типа воспоминания: токен из домена → string-ресурс. Сырые
 * enum-имена (FACT/PREFERENCE/…) пользователю не показываются.
 */
@Composable
internal fun memoryTypeText(label: MemoryTypeLabel): String = when (label) {
    MemoryTypeLabel.FACT -> stringResource(R.string.omnix_memory_type_fact)
    MemoryTypeLabel.PREFERENCE -> stringResource(R.string.omnix_memory_type_preference)
    MemoryTypeLabel.EPISODIC -> stringResource(R.string.omnix_memory_type_episodic)
    MemoryTypeLabel.PROCEDURAL -> stringResource(R.string.omnix_memory_type_procedural)
    MemoryTypeLabel.OTHER -> stringResource(R.string.omnix_memory_type_other)
}

/**
 * Inline "add" row: a quiet field with the design system's text style and
 * a text-button commit. Committing clears the field — the new entry appears
 * in the list above immediately (immediate write, no batch save).
 */
@Composable
internal fun AddRow(
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

/** Подпись факта для людей: «Имя», а не внутренний ключ user.name. */
@Composable
internal fun factKeyLabel(key: String): String = when (key) {
    "user.name" -> stringResource(R.string.omnix_fact_name)
    "user.city" -> stringResource(R.string.omnix_fact_city)
    "user.car" -> stringResource(R.string.omnix_fact_car)
    "user.wishlist" -> stringResource(R.string.omnix_fact_wishlist)
    "user.occupation" -> stringResource(R.string.omnix_fact_occupation)
    "user.preference" -> stringResource(R.string.omnix_fact_preference)
    "family.mother" -> stringResource(R.string.omnix_fact_mother)
    "family.father" -> stringResource(R.string.omnix_fact_father)
    "family.wife" -> stringResource(R.string.omnix_fact_wife)
    "family.husband" -> stringResource(R.string.omnix_fact_husband)
    else -> key
}

/**
 * Где живут данные: одна тональная панель без контура, две колонки
 * («на телефоне» и «в облаке») через тонкую вертикальную черту.
 */
@Composable
private fun PrivacyWhereCards() {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(WhereShape)
            .background(colors.surface)
    ) {
        WhereColumn(
            label = stringResource(R.string.omnix_privacy_where_device),
            body = stringResource(R.string.omnix_privacy_where_device_body),
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .padding(vertical = 16.dp)
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.border)
        )
        WhereColumn(
            label = stringResource(R.string.omnix_privacy_where_cloud),
            body = stringResource(R.string.omnix_privacy_where_cloud_body),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun WhereColumn(label: String, body: String, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        Text(text = label.uppercase(java.util.Locale.getDefault()), style = OmnixMonoLabelStyle, color = colors.textTertiary)
        Text(text = body, style = OmnixTheme.typography.subheadline, color = colors.textPrimary)
    }
}

private val WhereShape = RoundedCornerShape(24.dp)
