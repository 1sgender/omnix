package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixOnDeviceLineIcon
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import java.util.Locale

/** What the subscription card says about the licence. */
enum class ProfileLicense { ACTIVE, INACTIVE, EXPIRED }

/**
 * Profile (design «Трафарет»): the initial in an inverted circle, the name
 * Omni calls you (edited in place), the subscription card in ink and the
 * phone this licence is bound to. Opened from the account card in "Me".
 */
@Composable
fun ProfileScreen(
    userName: String,
    plan: String,
    license: ProfileLicense,
    expiryText: String?,
    deviceName: String,
    onSaveName: (String) -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val colors = OmnixTheme.colors
    val placeholder = stringResource(R.string.omnix_me_no_name)
    val shownName = userName.trim().ifBlank { placeholder }
    var editing by remember { mutableStateOf(false) }
    var draft by remember(userName) { mutableStateOf(userName) }

    SectionScaffold(stringResource(R.string.omnix_profile_title), modifier, onBack) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .background(colors.actionPrimary, CircleShape)
                    .clearAndSetSemantics {},
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = shownName.take(1).uppercase(Locale.getDefault()),
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 38.sp),
                    color = colors.onActionPrimary
                )
            }

            if (editing) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it.take(NAME_MAX) },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = OmnixSans,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 22.sp,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center
                    ),
                    cursorBrush = SolidColor(colors.textPrimary),
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .fillMaxWidth()
                        .border(BorderStroke(1.dp, colors.textPrimary), RoundedCornerShape(14.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
                Text(
                    text = stringResource(R.string.omnix_profile_name_hint),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 6.dp)
                )
            } else {
                Text(
                    text = shownName,
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 26.sp, lineHeight = 32.sp),
                    color = colors.textPrimary,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            Text(
                text = stringResource(if (editing) R.string.omnix_profile_save else R.string.omnix_profile_edit),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
                color = colors.textPrimary,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button) {
                        if (editing) onSaveName(draft)
                        editing = !editing
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }

        SubscriptionCard(plan = plan, license = license, expiryText = expiryText)

        Text(
            text = stringResource(R.string.omnix_profile_devices),
            style = OmnixMonoLabelStyle,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 4.dp, top = 24.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(20.dp))
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .background(colors.surfaceElevated, RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(OmnixOnDeviceLineIcon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = deviceName,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp),
                    color = colors.textPrimary
                )
                Text(
                    text = stringResource(R.string.omnix_profile_this_phone),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                    color = colors.textTertiary
                )
            }
        }
    }
}

/** The subscription in ink: plan, state and, when known, the end date. */
@Composable
private fun SubscriptionCard(plan: String, license: ProfileLicense, expiryText: String?) {
    val colors = OmnixTheme.colors
    val status = when (license) {
        ProfileLicense.ACTIVE -> expiryText?.let { stringResource(R.string.omnix_profile_active_until, it) }
            ?: stringResource(R.string.omnix_profile_active)
        ProfileLicense.INACTIVE -> stringResource(R.string.omnix_profile_inactive)
        ProfileLicense.EXPIRED -> stringResource(R.string.omnix_profile_expired)
    }
    Column(
        modifier = Modifier
            .padding(top = 24.dp)
            .fillMaxWidth()
            .background(colors.actionPrimary, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.omnix_profile_subscription),
            style = OmnixMonoLabelStyle,
            color = colors.onActionPrimary.copy(alpha = 0.6f)
        )
        Text(
            text = if (plan.isBlank()) "Omni" else "Omni $plan",
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
            color = colors.onActionPrimary,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = status,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
            color = colors.onActionPrimary.copy(alpha = 0.7f)
        )
    }
}

private const val NAME_MAX = 40
