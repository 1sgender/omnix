package com.omnix.assistant.presentation.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.ClipStatusBar
import com.omnix.assistant.presentation.components.OmnixDivider
import com.omnix.assistant.presentation.components.OmnixEmptyState
import com.omnix.assistant.presentation.components.OmnixPanel
import com.omnix.assistant.presentation.components.OmnixStatusDot
import com.omnix.assistant.presentation.components.clipDotColor
import com.omnix.assistant.presentation.components.clipLabel
import com.omnix.assistant.presentation.components.OmnixScreenHeader
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.OmnixSettingRow
import com.omnix.assistant.agent.capability.CapabilityStatus
import com.omnix.assistant.presentation.state.ClipCapability
import com.omnix.assistant.presentation.state.ClipState
import java.text.DateFormat
import java.util.Date

/**
 * Devices — Clip-centric, not a Bluetooth manager (§23, §40).
 *
 * The screen shows the Clip first and only mentions other audio devices when
 * they matter. Every row is a fact the system actually reports: when the
 * device does not expose its battery level, the row says so instead of
 * showing an invented percentage (§3, §33).
 */
@Composable
fun DevicesScreen(
    clip: ClipState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    capabilities: List<CapabilityGroupUi> = emptyList(),
    onBack: (() -> Unit)? = null,
    onConnect: () -> Unit = {},
    onOpenSystemBluetooth: () -> Unit = {},
    onRequestPermissions: (List<String>) -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Spacer(Modifier.height(spacing.lg))

        OmnixScreenHeader(
            title = stringResource(R.string.omnix_devices_title),
            onBack = onBack
        )

        // Whatever the Clip reports, it is the whole point of this screen —
        // so it owns the space and sits at its optical centre instead of
        // hanging under the header (§9).
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(spacing.lg))

                when (clip) {
                    is ClipState.Connected -> ConnectedClip(clip)

                    is ClipState.BatteryLow -> ConnectedClip(
                        ClipState.Connected(
                            deviceName = clip.deviceName,
                            battery = ClipCapability.Available(clip.percent)
                        )
                    )

                    ClipState.BluetoothOff -> OmnixEmptyState(
                        title = stringResource(R.string.omnix_error_bt_off_title),
                        description = stringResource(R.string.omnix_error_bt_off_body),
                        actionLabel = stringResource(R.string.omnix_error_bt_off_action),
                        onAction = onOpenSystemBluetooth
                    )

                    is ClipState.Connecting, ClipState.Searching -> ClipStatusBar(
                        clip = clip,
                        isOnline = isOnline
                    )

                    else -> OmnixEmptyState(
                        title = stringResource(R.string.omnix_devices_empty_title),
                        description = stringResource(R.string.omnix_devices_empty_body),
                        actionLabel = stringResource(R.string.omnix_devices_connect),
                        onAction = onConnect
                    )
                }

                if (clip is ClipState.Disconnected && clip.lastSeenMillis != null) {
                    Spacer(Modifier.height(spacing.lg))
                    LastSeenRow(clip.lastSeenMillis)
                }

                // «На этом телефоне» — вторым блоком после Clip (план
                // пересборки фронта 2026-09-26): та же карта возможностей,
                // по которой агент планирует действия. Доступно — зелёная
                // точка; можно включить — жёлтая (тап по PERMISSION_REQUIRED
                // открывает системный диалог); нельзя — серая.
                if (capabilities.isNotEmpty()) {
                    Spacer(Modifier.height(spacing.xl))
                    Text(
                        text = stringResource(R.string.omnix_devices_on_this_phone),
                        style = OmnixTheme.typography.overline,
                        color = OmnixTheme.colors.textTertiary
                    )
                    Spacer(Modifier.height(spacing.xs))
                    OmnixPanel {
                        capabilities.forEachIndexed { index, group ->
                            if (index > 0) OmnixDivider()
                            CapabilityGroupRow(
                                group = group,
                                onRequestPermissions = onRequestPermissions
                            )
                        }
                    }
                }

                Spacer(Modifier.height(spacing.xl))
            }
        }
    }
}

@Composable
private fun ConnectedClip(clip: ClipState.Connected) {
    val spacing = OmnixTheme.spacing

    OmnixPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OmnixStatusDot(color = clipDotColor(clip))
            Spacer(Modifier.width(spacing.xs))
            Column {
                Text(
                    text = clip.deviceName.ifBlank {
                        stringResource(R.string.omnix_devices_clip_name)
                    },
                    style = OmnixTheme.typography.headline,
                    color = OmnixTheme.colors.textPrimary
                )
                Text(
                    text = clipLabel(clip),
                    style = OmnixTheme.typography.caption,
                    color = OmnixTheme.colors.textSecondary
                )
            }
        }

        Spacer(Modifier.height(spacing.md))
        OmnixDivider()
        Spacer(Modifier.height(spacing.md))

        // Battery: shown only when the device genuinely reports it.
        DetailRow(
            label = stringResource(R.string.omnix_devices_battery),
            value = when (val battery = clip.battery) {
                is ClipCapability.Available -> stringResource(
                    R.string.omnix_percent, battery.value
                )
                ClipCapability.Unavailable -> stringResource(
                    R.string.omnix_devices_battery_unavailable
                )
                ClipCapability.ComingSoon -> stringResource(R.string.omnix_coming_soon)
                ClipCapability.NotConfigured -> stringResource(R.string.omnix_not_configured)
            }
        )

        DetailRow(
            label = stringResource(R.string.omnix_devices_connection),
            value = stringResource(R.string.omnix_devices_connection_active)
        )

        Spacer(Modifier.height(spacing.md))

        // "Find my Clip" is a real product feature that this build cannot
        // perform, so it is stated as such rather than shown as a dead button.
        Text(
            text = stringResource(R.string.omnix_clip_find_coming_soon),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textDisabled
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = OmnixTheme.spacing.xxs),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textSecondary
        )
        Text(
            text = value,
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textPrimary
        )
    }
}

@Composable
private fun LastSeenRow(lastSeenMillis: Long) {
    val formatted = remember(lastSeenMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
            .format(Date(lastSeenMillis))
    }
    Text(
        text = stringResource(R.string.omnix_clip_last_seen, formatted),
        style = OmnixTheme.typography.caption,
        color = OmnixTheme.colors.textTertiary
    )
}

/**
 * Одна группа возможностей: точка-статус, локализованное имя и подпись
 * статуса. Только PERMISSION_REQUIRED нажимаемо — тап открывает системный
 * диалог разрешений (план владельца); жёлтая точка без давления.
 */
@Composable
private fun CapabilityGroupRow(
    group: CapabilityGroupUi,
    onRequestPermissions: (List<String>) -> Unit
) {
    val colors = OmnixTheme.colors
    val status = group.status
    val labelToken = capabilityStatusLabel(status)
    val subtitle: String? = when (status) {
        is CapabilityStatus.PermissionRequired ->
            stringResource(R.string.omnix_capability_grant_hint)
        is CapabilityStatus.UserActionRequired -> status.reason
        is CapabilityStatus.Unsupported -> status.reason
        is CapabilityStatus.Available -> null
    }
    OmnixSettingRow(
        title = capabilityGroupTitle(group.capability.id),
        subtitle = subtitle,
        value = capabilityStatusText(labelToken),
        inset = true,
        onClick = if (status is CapabilityStatus.PermissionRequired) {
            { onRequestPermissions(status.permissions) }
        } else {
            null
        }
    ) {
        OmnixStatusDot(color = when (capabilityDot(status)) {
            CapabilityDotToken.OK -> colors.stateSuccess
            CapabilityDotToken.ATTENTION -> colors.stateWarning
            CapabilityDotToken.OFF -> colors.textDisabled
        })
    }
}

@Composable
private fun capabilityGroupTitle(id: String): String = when (capabilityGroupToken(id)) {
    CapabilityGroupToken.BLUETOOTH -> stringResource(R.string.omnix_capability_bluetooth)
    CapabilityGroupToken.WIFI -> stringResource(R.string.omnix_capability_wifi)
    CapabilityGroupToken.BRIGHTNESS -> stringResource(R.string.omnix_capability_brightness)
    CapabilityGroupToken.SCREENSHOT -> stringResource(R.string.omnix_capability_screenshot)
    CapabilityGroupToken.APPS -> stringResource(R.string.omnix_capability_apps)
    CapabilityGroupToken.SMS -> stringResource(R.string.omnix_capability_sms)
    CapabilityGroupToken.CALL -> stringResource(R.string.omnix_capability_call)
    CapabilityGroupToken.MEDIA -> stringResource(R.string.omnix_capability_media)
    CapabilityGroupToken.ACCESSIBILITY -> stringResource(R.string.omnix_capability_accessibility)
    CapabilityGroupToken.LOCATION -> stringResource(R.string.omnix_capability_location)
    CapabilityGroupToken.OTHER -> stringResource(R.string.omnix_capability_other)
}

@Composable
private fun capabilityStatusText(token: CapabilityStatusLabel): String = when (token) {
    CapabilityStatusLabel.AVAILABLE -> stringResource(R.string.omnix_capability_status_available)
    CapabilityStatusLabel.PERMISSION_REQUIRED -> stringResource(R.string.omnix_capability_status_permission)
    CapabilityStatusLabel.USER_ACTION_REQUIRED -> stringResource(R.string.omnix_capability_status_user_action)
    CapabilityStatusLabel.UNSUPPORTED -> stringResource(R.string.omnix_capability_status_unsupported)
}
