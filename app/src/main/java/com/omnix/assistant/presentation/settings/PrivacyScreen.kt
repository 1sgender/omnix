package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
 */
@Composable
fun PrivacyScreen(
    microphoneAllowed: Boolean,
    historyStored: Boolean,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onManagePermissions: (() -> Unit)? = null,
    onDeleteHistory: (() -> Unit)? = null
) {
    val spacing = OmnixTheme.spacing
    var deleteArmed by remember { mutableStateOf(false) }

    SectionScaffold(stringResource(R.string.omnix_privacy_title), modifier, onBack) {
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
}
