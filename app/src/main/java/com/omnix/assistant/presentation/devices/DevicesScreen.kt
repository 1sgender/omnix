package com.omnix.assistant.presentation.devices

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixScreenHeader
import com.omnix.assistant.agent.capability.CapabilityStatus
import com.omnix.assistant.presentation.components.OmnixDivider
import com.omnix.assistant.presentation.components.OmnixPanel
import com.omnix.assistant.presentation.settings.OmnixSettingRow
import com.omnix.assistant.presentation.components.OmnixStatusDot
import com.omnix.assistant.presentation.components.clipDotColor
import com.omnix.assistant.presentation.components.clipLabel
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark
import com.omnix.assistant.presentation.state.ClipCapability
import com.omnix.assistant.presentation.state.ClipState

/**
 * Headphones — design v2 canvas, artboard Devices.
 *
 * The status card (mark, name, connection, battery), the headphone switches
 * and the two actions. Every value is a fact the system reports: the battery
 * tile appears only when the headset reports a level, and it is one tile
 * because the headset reports one number.
 *
 * The canvas also draws a sound-mode selector (noise cancelling,
 * transparency) and an ear-sensor switch. Both need the Omnix firmware; this
 * build cannot drive them, so they are not shown as dead controls.
 *
 * Below the canvas content stays "On this phone" — the capability map the
 * agent plans with — because it is the only place permissions for calls, SMS
 * and the rest are granted.
 */
@Composable
fun DevicesScreen(
    clip: ClipState,
    modifier: Modifier = Modifier,
    wakeWordEnabled: Boolean = true,
    translatorToHeadphones: Boolean = true,
    capabilities: List<CapabilityGroupUi> = emptyList(),
    onWakeWordChange: (Boolean) -> Unit = {},
    onTranslatorToHeadphonesChange: (Boolean) -> Unit = {},
    onFind: () -> Unit = {},
    onOpenSystemBluetooth: () -> Unit = {},
    onRequestPermissions: (List<String>) -> Unit = {},
    onBack: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // Подэкран «Я» и плашки на «Главной» (с 2026-10-21 не вкладка): со стрелкой назад.
        OmnixScreenHeader(
            title = stringResource(R.string.omnix_devices_title),
            onBack = onBack,
            modifier = Modifier.padding(top = 8.dp)
        )

        StatusCard(clip = clip, modifier = Modifier.padding(top = 16.dp))

        SwitchGroup(
            wakeWordEnabled = wakeWordEnabled,
            translatorToHeadphones = translatorToHeadphones,
            onWakeWordChange = onWakeWordChange,
            onTranslatorToHeadphonesChange = onTranslatorToHeadphonesChange,
            modifier = Modifier.padding(top = 24.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlineButton(
                text = stringResource(R.string.omnix_devices_find),
                // With Bluetooth off there is nothing to search with: the
                // system switch comes first.
                onClick = if (clip == ClipState.BluetoothOff) onOpenSystemBluetooth else onFind,
                modifier = Modifier.weight(1f)
            )
            // Android gives apps no way to drop a headset connection; the
            // system Bluetooth page is where it is done.
            OutlineButton(
                text = stringResource(R.string.omnix_devices_disconnect),
                onClick = onOpenSystemBluetooth,
                modifier = Modifier.weight(1f)
            )
        }

        // Только то, что ждёт действия: «Доступно» человеку ничего не говорит
        // (замечание владельца 2026-10-21), а серое «нельзя» — тем более.
        val needAction = capabilities.filter { capabilityDot(it.status) == CapabilityDotToken.ATTENTION }
        if (needAction.isNotEmpty()) {
            Spacer(Modifier.height(32.dp))
            Text(
                text = stringResource(R.string.omnix_devices_on_this_phone),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = OmnixTheme.colors.textSecondary
            )
            Spacer(Modifier.height(10.dp))
            OmnixPanel {
                needAction.forEachIndexed { index, group ->
                    if (index > 0) OmnixDivider()
                    CapabilityGroupRow(group = group, onRequestPermissions = onRequestPermissions)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StatusCard(clip: ClipState, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    val connected = clip is ClipState.Connected || clip is ClipState.BatteryLow
    val name = when (clip) {
        is ClipState.Connected -> clip.deviceName
        is ClipState.BatteryLow -> clip.deviceName
        is ClipState.Connecting -> clip.deviceName
        is ClipState.Disconnected -> clip.lastDeviceName.orEmpty()
        is ClipState.ConnectionFailed -> clip.deviceName.orEmpty()
        else -> ""
    }.ifBlank { stringResource(R.string.omnix_devices_clip_name) }
    val battery = when (clip) {
        is ClipState.Connected -> (clip.battery as? ClipCapability.Available)?.value
        is ClipState.BatteryLow -> clip.percent
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(24.dp))
            .padding(start = 20.dp, top = 24.dp, end = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        OmnixRingMark(
            color = if (connected) colors.textPrimary else colors.textTertiary,
            width = 132.dp,
            height = 100.dp,
            strokeUnits = 2.4f
        )
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = name,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 18.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(if (clip is ClipState.Connected) colors.accentBrand else clipDotColor(clip), CircleShape)
                )
                Text(
                    text = clipLabel(clip),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                    color = colors.textSecondary
                )
            }
        }
        battery?.let { BatteryTile(percent = it) }
    }
}

/** The battery the headset reports, on the screen ground inside the card. */
@Composable
private fun BatteryTile(percent: Int) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.background)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.omnix_percent, percent),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
            color = colors.textPrimary
        )
        Text(
            text = stringResource(R.string.omnix_devices_battery),
            style = OmnixMonoLabelStyle.copy(letterSpacing = 0.04.em),
            color = colors.textSecondary
        )
    }
}

@Composable
private fun SwitchGroup(
    wakeWordEnabled: Boolean,
    translatorToHeadphones: Boolean,
    onWakeWordChange: (Boolean) -> Unit,
    onTranslatorToHeadphonesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
    ) {
        SwitchRow(
            title = stringResource(R.string.omnix_devices_wake_title),
            subtitle = stringResource(R.string.omnix_devices_wake_sub),
            checked = wakeWordEnabled,
            onChange = onWakeWordChange
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.textPrimary.copy(alpha = 0.06f))
        )
        SwitchRow(
            title = stringResource(R.string.omnix_devices_translate_title),
            subtitle = stringResource(R.string.omnix_devices_translate_sub),
            checked = translatorToHeadphones,
            onChange = onTranslatorToHeadphonesChange
        )
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp), color = colors.textPrimary)
            Text(text = subtitle, style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp), color = colors.textSecondary)
        }
        // The canvas switch: 52×32 track, 26 knob; lime with a dark knob when on.
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (checked) colors.accentBrand else colors.textPrimary.copy(alpha = 0.16f))
                .padding(3.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(26.dp)
                    .background(if (checked) colors.background else colors.textPrimary, CircleShape)
            )
        }
    }
}

@Composable
private fun OutlineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    Box(
        modifier = modifier
            .height(52.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(BorderStroke(1.dp, colors.textPrimary.copy(alpha = 0.12f)), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 14.sp),
            color = colors.textPrimary,
            maxLines = 1
        )
    }
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
