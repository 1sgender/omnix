package com.omnix.assistant.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ClipState

/**
 * The Clip status line that sits under the OMNIX wordmark on Home (§9, §23).
 *
 * It answers one question — "can OMNIX hear me right now?" — and nothing else.
 * A disconnected device is quiet grey, a connection in progress or Bluetooth
 * switch-off is calm amber, and red is reserved for a failed connection.
 *
 * When the bar is tappable it becomes a capsule chip: the touch target was
 * previously invisible, and a chip states the affordance — elevated surface,
 * hairline, the press spring. Without an [onClick] it stays a quiet centred
 * line (used inside the Devices screen's state slot).
 */
@Composable
fun ClipStatusBar(
    clip: ClipState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing

    val label = clipLabel(clip)
    val dotColor = clipDotColor(clip)
    val description = stringResource(R.string.omnix_a11y_clip_status, label)
    val interactionSource = remember { MutableInteractionSource() }
    val chipShape = RoundedCornerShape(OmnixTheme.radius.pill)

    Row(
        modifier = modifier
            .then(
                if (onClick != null) {
                    Modifier
                        .omnixPressScale(interactionSource)
                        .clip(chipShape)
                        .background(colors.surfaceElevated)
                        .border(width = OmnixHairline, color = colors.border, shape = chipShape)
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            onClick = onClick
                        )
                        .padding(horizontal = spacing.md, vertical = spacing.xs)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.xs)
                }
            )
            .defaultMinSize(minHeight = spacing.touchTarget)
            .semantics { contentDescription = description },
        horizontalArrangement = Arrangement.spacedBy(spacing.xs, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OmnixStatusDot(color = dotColor)
        Text(
            text = label,
            style = OmnixTheme.typography.status,
            color = colors.textSecondary
        )
        if (!isOnline) {
            Text(
                text = "·",
                style = OmnixTheme.typography.status,
                color = colors.textDisabled
            )
            Text(
                text = stringResource(R.string.omnix_status_offline),
                style = OmnixTheme.typography.status,
                color = colors.textDisabled
            )
        }
    }
}

/** Human copy for each Clip state — the only place this mapping exists. */
@Composable
fun clipLabel(clip: ClipState): String = when (clip) {
    is ClipState.Connected -> stringResource(R.string.omnix_status_clip_connected)
    is ClipState.Connecting -> stringResource(R.string.omnix_status_clip_connecting)
    ClipState.Searching -> stringResource(R.string.omnix_status_clip_searching)
    ClipState.BluetoothOff -> stringResource(R.string.omnix_status_bluetooth_off)
    is ClipState.ConnectionFailed -> stringResource(R.string.omnix_error_clip_failed_title)
    is ClipState.BatteryLow -> stringResource(R.string.omnix_clip_battery_low_title)
    is ClipState.Disconnected -> stringResource(R.string.omnix_status_clip_disconnected)
    ClipState.Unknown -> stringResource(R.string.omnix_status_clip_disconnected)
}

/**
 * Colour used for the Clip dot; exposed for reuse on the Devices screen.
 *
 * The status is not an alarm: only a concrete connection failure uses the
 * error colour. In particular, Bluetooth being switched off is actionable but
 * not an application error, so it uses the neutral warm status colour.
 */
@Composable
fun clipDotColor(clip: ClipState): Color = when (clip) {
    is ClipState.Connected -> OmnixTheme.colors.stateIdle
    is ClipState.Connecting, ClipState.Searching,
    ClipState.BluetoothOff,
    is ClipState.BatteryLow -> OmnixTheme.colors.stateRecognizing
    is ClipState.ConnectionFailed -> OmnixTheme.colors.stateError
    is ClipState.Disconnected,
    ClipState.Unknown -> OmnixTheme.colors.textDisabled
}
