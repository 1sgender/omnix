package com.omnix.assistant.presentation.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R

/**
 * About (§42).
 *
 * Account and access status in human terms. "OMNIX Access" is the product
 * word for what the codebase calls a licence; the user is never shown the
 * word "licence key", a token, or an expiry timestamp in epoch millis.
 *
 * Anything the build genuinely cannot do yet — self-service renewal — says so
 * plainly instead of offering a button that goes nowhere (§3). The access row
 * follows the same rule: until a manage destination exists, it states the
 * status instead of pretending to open something.
 */
@Composable
fun AboutScreen(
    accessState: AccessDisplayState,
    versionName: String,
    deviceCode: String?,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onManageAccount: (() -> Unit)? = null
) {
    SectionScaffold(stringResource(R.string.omnix_about_title), modifier, onBack) {
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.omnix_about_access),
                value = stringResource(
                    when (accessState) {
                        AccessDisplayState.Active -> R.string.omnix_about_access_active
                        AccessDisplayState.Expired -> R.string.omnix_about_access_expired
                        AccessDisplayState.NotConfigured -> R.string.omnix_about_access_not_configured
                    }
                ),
                inset = true,
                chevron = onManageAccount != null,
                onClick = onManageAccount
            )
            OmnixGroupDivider()

            // Renewal is not implemented in this build; saying so is honest and
            // costs the user nothing. A dead "Renew" button would not be.
            OmnixSettingRow(
                title = stringResource(R.string.omnix_about_renewal),
                value = stringResource(R.string.omnix_about_renewal_unavailable),
                enabled = false,
                inset = true
            )
            OmnixGroupDivider()

            OmnixSettingRow(
                title = stringResource(R.string.omnix_about_version),
                value = versionName,
                inset = true
            )

            deviceCode?.let { code ->
                OmnixGroupDivider()
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_about_device_code),
                    value = code,
                    inset = true
                )
            }
        }
    }
}

/** Access status, already reduced to what the UI needs to say. */
enum class AccessDisplayState { Active, Expired, NotConfigured }
