package com.omnix.assistant.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement as LayoutArrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixAtomIcon
import com.omnix.assistant.presentation.components.OmnixHairline
import com.omnix.assistant.presentation.components.OmnixHistoryIcon
import com.omnix.assistant.presentation.components.OmnixIcons
import com.omnix.assistant.presentation.components.OmnixMeIcon
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * The OMNIX navigation bar: `History | OMNIX | Me` (§20, §44, §45).
 *
 * The centre is deliberately a Home tab, not a second Core. The large Core on
 * Home is the single live state indicator; duplicating it in navigation made
 * its purpose ambiguous (status or action). The quiet wordmark tab retains a
 * clear way home without competing with that indicator.
 *
 * The active tab is unmistakable (mock 2026-09-25): white and bold against
 * the dimmed, regular-weight rest — "where am I" in one glance. Secondary
 * screens keep their parent tab lit via [tabForRoute]: OMNIX's modes keep
 * the centre tab, everything reached from Me keeps Me.
 */
@Composable
fun OmnixNavigationBar(
    currentTab: OmnixDestination?,
    onNavigate: (OmnixDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing

    // A floating pill rather than a full-width bar: it keeps navigation
    // detached from the screen edge and stops the chrome competing with Home.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = spacing.md, vertical = spacing.xs)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(OmnixTheme.radius.pill))
                .background(colors.surface)
                .border(
                    width = OmnixHairline,
                    color = colors.border,
                    shape = RoundedCornerShape(OmnixTheme.radius.pill)
                )
                .padding(horizontal = spacing.sm, vertical = spacing.xs),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            NavigationItem(
                label = stringResource(R.string.omnix_nav_history),
                selected = currentTab == OmnixDestination.History,
                onClick = { onNavigate(OmnixDestination.History) },
                modifier = Modifier.weight(1f),
                icon = { tint -> OmnixHistoryIcon(color = tint) }
            )

            HomeNavigationItem(
                selected = currentTab == OmnixDestination.Home,
                onClick = { onNavigate(OmnixDestination.Home) },
                modifier = Modifier.weight(1f)
            )

            NavigationItem(
                label = stringResource(R.string.omnix_nav_me),
                contentDescription = stringResource(R.string.omnix_a11y_open_settings),
                selected = currentTab == OmnixDestination.Me,
                onClick = { onNavigate(OmnixDestination.Me) },
                modifier = Modifier.weight(1f),
                icon = { tint -> OmnixMeIcon(color = tint) }
            )
        }
    }
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
    val tint = if (selected) OmnixTheme.colors.textPrimary else OmnixTheme.colors.textTertiary
    Column(
        modifier = modifier
            .height(OmnixTheme.spacing.touchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics {
                if (contentDescription != null) {
                    this.contentDescription = contentDescription
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = LayoutArrangement.Center
    ) {
        icon(tint)
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            // The active tab is white and bold, the rest dimmed (mock
            // 2026-09-25): position reads in one glance.
            style = OmnixTheme.typography.overline.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
            color = tint,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Centre of the bar: the OMNIX atom mark over the "Home" label (mock
 * 2026-09-26). The wordmark reading "OMNIX" used to duplicate the sender
 * labels on the chat screen; now the mark carries the brand and the label
 * the destination, in the same icon-plus-label shape as the two neighbour
 * tabs.
 */
@Composable
private fun HomeNavigationItem(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val tint = if (selected) colors.textPrimary else colors.textTertiary
    val description = stringResource(R.string.omnix_nav_home)

    Column(
        modifier = modifier
            .height(OmnixTheme.spacing.touchTarget)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = LayoutArrangement.Center
    ) {
        OmnixAtomIcon(
            color = tint,
            size = OmnixIcons.NavSize
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.omnix_nav_home),
            // The active tab is white and bold, the rest dimmed (mock
            // 2026-09-25): position reads in one glance.
            style = OmnixTheme.typography.overline.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            ),
            color = tint,
            textAlign = TextAlign.Center
        )
    }
}
