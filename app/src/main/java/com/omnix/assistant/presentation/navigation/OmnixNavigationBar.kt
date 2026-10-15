package com.omnix.assistant.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixChatBubbleLineIcon
import com.omnix.assistant.presentation.components.OmnixHeadphonesLineIcon
import com.omnix.assistant.presentation.components.OmnixPersonLineIcon
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.firstrun.OmnixRingMark

/**
 * The OMNIX tab bar: `Home | Chat | Headphones | Me` — design v2 canvas, the
 * bar under every artboard.
 *
 * Full width on the screen ground with a hairline on top; four equal tabs,
 * each an icon over a label. The active tab has the lime icon and the paper
 * label, the rest are dimmed. Secondary screens keep their parent tab lit via
 * [tabForRoute]: OMNIX's modes keep Home, everything reached from Me keeps Me.
 */
@Composable
fun OmnixNavigationBar(
    currentTab: OmnixDestination?,
    onNavigate: (OmnixDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.textPrimary.copy(alpha = 0.06f))
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 8.dp, end = 8.dp)
                .navigationBarsPadding()
                .padding(bottom = 8.dp)
        ) {
            NavigationItem(
                label = stringResource(R.string.omnix_nav_home),
                selected = currentTab == OmnixDestination.Home,
                onClick = { onNavigate(OmnixDestination.Home) },
                modifier = Modifier.weight(1f)
            ) { tint ->
                OmnixRingMark(
                    color = tint,
                    width = 22.dp,
                    height = 16.dp,
                    strokeUnits = 3.6f,
                    modifier = Modifier.clearAndSetSemantics {}
                )
            }

            NavigationItem(
                label = stringResource(R.string.omnix_nav_chat),
                contentDescription = stringResource(R.string.omnix_a11y_open_chat),
                selected = currentTab == OmnixDestination.Chat,
                onClick = { onNavigate(OmnixDestination.Chat) },
                modifier = Modifier.weight(1f)
            ) { tint -> TabIcon(OmnixChatBubbleLineIcon, tint) }

            NavigationItem(
                label = stringResource(R.string.omnix_nav_headphones),
                selected = currentTab == OmnixDestination.Devices,
                onClick = { onNavigate(OmnixDestination.Devices) },
                modifier = Modifier.weight(1f)
            ) { tint -> TabIcon(OmnixHeadphonesLineIcon, tint) }

            NavigationItem(
                label = stringResource(R.string.omnix_nav_me),
                contentDescription = stringResource(R.string.omnix_a11y_open_settings),
                selected = currentTab == OmnixDestination.Me,
                onClick = { onNavigate(OmnixDestination.Me) },
                modifier = Modifier.weight(1f)
            ) { tint -> TabIcon(OmnixPersonLineIcon, tint) }
        }
    }
}

@Composable
private fun TabIcon(icon: ImageVector, tint: Color) {
    Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
}

@Composable
private fun NavigationItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    icon: @Composable (Color) -> Unit
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = modifier
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            }
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(modifier = Modifier.height(22.dp), contentAlignment = Alignment.Center) {
            icon(if (selected) colors.accentBrand else colors.textSecondary)
        }
        Text(
            text = label,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp, lineHeight = 16.sp),
            color = if (selected) colors.textPrimary else colors.textSecondary,
            maxLines = 1
        )
    }
}
