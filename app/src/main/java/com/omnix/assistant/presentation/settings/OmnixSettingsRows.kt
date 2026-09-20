package com.omnix.assistant.presentation.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import com.omnix.assistant.presentation.components.OmnixChevronRightIcon
import com.omnix.assistant.presentation.components.OmnixHairline
import com.omnix.assistant.presentation.design.OmnixRadius
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * The settings row vocabulary (§25, §42).
 *
 * Four shapes only — navigation, toggle, choice and slider. Because every
 * settings screen is built from these, Settings shares one visual language
 * with the rest of the product instead of drifting into a stock preference
 * list.
 *
 * Each row states a human concept and, where useful, one line of plain
 * explanation. Permission identifiers, service names and provider names never
 * appear (§4).
 *
 * Press feedback is the iOS row highlight, not a ripple and not a scale: the
 * tapped row briefly fills with the elevated surface colour.
 *
 * @param inset   true inside [OmnixSettingsGroup]: content is padded to the
 *                card gutters while the tap target and highlight stay
 *                full-bleed within the card.
 * @param chevron true for rows that open another page. Opt-in: an action or
 *                choice row must never promise navigation.
 * @param leading optional slot before the title (a status dot, an icon).
 */
@Composable
fun OmnixSettingRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    enabled: Boolean = true,
    contentDescription: String? = null,
    onClick: (() -> Unit)? = null,
    inset: Boolean = false,
    chevron: Boolean = false,
    leading: (@Composable () -> Unit)? = null
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val rowBackground by animateColorAsState(
        targetValue = if (pressed && enabled) colors.surfaceElevated else Color.Transparent,
        animationSpec = tween(120),
        label = "omnixRowHighlight"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null && enabled) {
                    Modifier
                        .clickable(
                            interactionSource = interactionSource,
                            indication = null,
                            role = Role.Button,
                            onClick = onClick
                        )
                        .background(rowBackground)
                        .semantics {
                            // A spoken label describes the destination, not
                            // just the row's visible title.
                            if (contentDescription != null) {
                                this.contentDescription = contentDescription
                            }
                        }
                } else {
                    Modifier
                }
            )
            .then(
                if (inset) {
                    Modifier.padding(horizontal = spacing.md)
                } else {
                    Modifier
                }
            )
            .defaultMinSize(minHeight = spacing.touchTarget)
            .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.let {
            it()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OmnixTheme.typography.body,
                color = if (enabled) colors.textPrimary else colors.textDisabled
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = OmnixTheme.typography.caption,
                    color = colors.textTertiary
                )
            }
        }
        value?.let {
            Text(
                text = it,
                style = OmnixTheme.typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = spacing.sm)
            )
        }
        if (chevron && onClick != null && enabled) {
            OmnixChevronRightIcon(color = colors.textTertiary)
        }
    }
}

@Composable
fun OmnixToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = spacing.touchTarget)
            .padding(vertical = spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = OmnixTheme.typography.body,
                color = if (enabled) colors.textPrimary else colors.textDisabled
            )
            subtitle?.let {
                Text(
                    text = it,
                    style = OmnixTheme.typography.caption,
                    color = colors.textTertiary
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.background,
                checkedTrackColor = colors.actionPrimary,
                checkedBorderColor = colors.actionPrimary,
                uncheckedThumbColor = colors.textTertiary,
                uncheckedTrackColor = colors.surface,
                uncheckedBorderColor = colors.border
            )
        )
    }
}

/**
 * A slider for a genuinely continuous quantity, such as speaking speed.
 *
 * The numeric value is shown as a human label supplied by the caller
 * ("Normal", "Faster") rather than as a raw float.
 */
@Composable
fun OmnixSliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0.5f..2.0f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Column(modifier = modifier.fillMaxWidth().padding(vertical = spacing.xs)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = OmnixTheme.typography.body,
                color = colors.textPrimary
            )
            Text(
                text = valueLabel,
                style = OmnixTheme.typography.body,
                color = colors.textSecondary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = colors.actionPrimary,
                activeTrackColor = colors.actionPrimary,
                inactiveTrackColor = colors.border
            )
        )
    }
}

/** A section heading inside a settings screen. */
@Composable
fun OmnixSettingsSectionHeader(
    text: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = OmnixTheme.typography.overline,
        color = OmnixTheme.colors.textTertiary,
        modifier = modifier.padding(
            top = OmnixTheme.spacing.lg,
            bottom = OmnixTheme.spacing.xs
        )
    )
}

/**
 * A row that holds an editable value (§42).
 *
 * Used for the few settings that are genuinely free text — the access token
 * and the assistant's instructions. It keeps the same left-aligned label and
 * caption as every other row, so an editable setting does not look like a
 * foreign control.
 */
@Composable
fun OmnixTextFieldRow(
    title: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    placeholder: String? = null,
    isError: Boolean = false,
    errorText: String? = null,
    singleLine: Boolean = true,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailing: @Composable (() -> Unit)? = null
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors

    Column(modifier = modifier.fillMaxWidth().padding(vertical = spacing.sm)) {
        Text(
            text = title,
            style = OmnixTheme.typography.body,
            color = colors.textPrimary
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = OmnixTheme.typography.caption,
                color = colors.textTertiary
            )
        }

        Spacer(Modifier.height(spacing.xs))

        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            isError = isError,
            textStyle = OmnixTheme.typography.body,
            visualTransformation = visualTransformation,
            placeholder = placeholder?.let {
                {
                    Text(
                        text = it,
                        style = OmnixTheme.typography.body,
                        color = colors.textDisabled
                    )
                }
            },
            trailingIcon = trailing,
            shape = RoundedCornerShape(OmnixRadius.medium),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.textPrimary,
                unfocusedTextColor = colors.textPrimary,
                focusedBorderColor = colors.stateIdle,
                unfocusedBorderColor = colors.border,
                errorBorderColor = colors.stateError,
                cursorColor = colors.stateIdle,
                focusedContainerColor = colors.surface,
                unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface
            )
        )

        if (isError && errorText != null) {
            Text(
                text = errorText,
                style = OmnixTheme.typography.caption,
                color = colors.stateError,
                modifier = Modifier.padding(top = spacing.xxs)
            )
        }
    }
}

/**
 * A grouped settings card (§42): rows live inside one rounded surface, with
 * hairline insets between them supplied by the caller. This is the OMNIX
 * reading of the iOS grouped table — surface on background, radius 16,
 * a 6 % hairline, no shadows.
 *
 * Rows inside the group pass `inset = true` to [OmnixSettingRow] so their
 * content aligns to the card gutters while the tap target and the press
 * highlight run full-bleed to the card edges.
 */
@Composable
fun OmnixSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(OmnixTheme.radius.medium),
        color = OmnixTheme.colors.surface,
        border = BorderStroke(OmnixHairline, OmnixTheme.colors.border)
    ) {
        Column {
            content()
        }
    }
}
