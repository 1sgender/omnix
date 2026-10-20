package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixChevronLineIcon
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.navigation.OmnixDestination
import java.util.Locale

/** One row of the Me lists: a two-letter glyph tile, a label, a value. */
data class MeRow(
    val glyph: String,
    val label: String,
    val value: String,
    val destination: OmnixDestination
)

/**
 * "Me" — design v2 canvas, artboard Me.
 *
 * The account card (initial, name, licence state, plan), two grouped lists
 * and the version at the bottom. Values are real: the connected headset's
 * name, the number of automations, the local model's state, the current
 * appearance. The version opens "About"; a long press on it opens
 * Diagnostics, where crash reports are exported.
 */
@Composable
fun MeScreen(
    userName: String,
    licenseActive: Boolean,
    plan: String,
    versionName: String,
    groupA: List<MeRow>,
    groupB: List<MeRow>,
    modifier: Modifier = Modifier,
    onOpen: (OmnixDestination) -> Unit = {},
    onVersionTap: () -> Unit = {},
    onVersionLongPress: () -> Unit = {}
) {
    val colors = OmnixTheme.colors
    // The version sits at the bottom of the screen when the lists are short
    // and after them when they scroll: a min-height column spread apart.
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = stringResource(R.string.omnix_me_title),
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 24.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 24.dp)
                )
                AccountCard(
                    userName = userName,
                    licenseActive = licenseActive,
                    plan = plan,
                    modifier = Modifier.padding(top = 20.dp)
                )
                Column(
                    modifier = Modifier.padding(top = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    RowGroup(rows = groupA, onOpen = onOpen)
                    RowGroup(rows = groupB, onOpen = onOpen)
                }
            }

            Text(
                text = stringResource(R.string.omnix_me_version, versionName),
                style = OmnixMonoLabelStyle.copy(letterSpacing = 0.04.em),
                color = colors.textTertiary,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 24.dp, bottom = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    // Tap: About. Long press: Diagnostics, where crash reports
                    // are exported (no visible row, as on the canvas).
                    .pointerInput(onVersionTap, onVersionLongPress) {
                        detectTapGestures(
                            onTap = { onVersionTap() },
                            onLongPress = { onVersionLongPress() }
                        )
                    }
                    .semantics { role = Role.Button }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun AccountCard(userName: String, licenseActive: Boolean, plan: String, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .background(colors.surfaceElevated, CircleShape)
                .clearAndSetSemantics {},
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = userName.trim().take(1).uppercase(Locale.getDefault()),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 18.sp),
                color = colors.textPrimary
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = userName,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
                color = colors.textPrimary,
                maxLines = 1
            )
            Text(
                text = stringResource(
                    if (licenseActive) R.string.omnix_me_license_active else R.string.omnix_me_license_inactive
                ),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                color = colors.textSecondary
            )
        }
        if (plan.isNotBlank()) {
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .border(BorderStroke(1.dp, colors.accentBrand), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = plan,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                    color = colors.accentBrand
                )
            }
        }
    }
}

@Composable
private fun RowGroup(rows: List<MeRow>, onOpen: (OmnixDestination) -> Unit) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
    ) {
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.textPrimary.copy(alpha = 0.06f))
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clickable(role = Role.Button) { onOpen(row.destination) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(colors.surfaceElevated, RoundedCornerShape(10.dp))
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = row.glyph,
                        style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                        color = colors.textPrimary
                    )
                }
                Text(
                    text = row.label,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f)
                )
                if (row.value.isNotEmpty()) {
                    Text(
                        text = row.value,
                        style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                        color = colors.textSecondary,
                        maxLines = 1
                    )
                }
                Icon(
                    OmnixChevronLineIcon,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

const val SECTION_VOICE = "voice"
const val SECTION_AI = "ai"
const val SECTION_LANGUAGE = "language"
const val SECTION_NOTIFICATIONS = "notifications"
const val SECTION_APPEARANCE = "appearance"
const val SECTION_ABOUT = "about"
const val SECTION_ADVANCED = "advanced"
const val SECTION_DIAGNOSTICS = "diagnostics"
