package com.omnix.assistant.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.omnix.assistant.R
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Title row for a sub-screen, with the back affordance (§20).
 *
 * Sub-screens are reached from Me, so they need a way out that does not rely
 * on the system gesture alone. The control is a 44 dp target with a spoken
 * label, and it never competes with the Core: no filled bar, no elevation.
 */
@Composable
fun OmnixScreenHeader(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val spacing = OmnixTheme.spacing
    val colors = OmnixTheme.colors
    val backLabel = stringResource(R.string.omnix_nav_back)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            // «Трафарет»: круглая кнопка с рамкой, стрелка в цвет текста.
            androidx.compose.foundation.layout.Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .border(BorderStroke(1.dp, colors.border), CircleShape)
                    .clickable(role = Role.Button, onClick = onBack)
                    .semantics { contentDescription = backLabel }
            ) {
                OmnixBackIcon(color = colors.textPrimary, size = 20.dp)
            }
            Spacer(Modifier.width(spacing.md))
        }

        // Заголовок не переносится по слогам: одна строка, при нехватке
        // ширины шрифт уменьшается ступенями (до 18 sp), затем многоточие.
        val baseStyle = OmnixTheme.typography.screenTitle
        var shrink by remember(title) { mutableStateOf(1f) }
        Text(
            text = title,
            style = baseStyle.copy(
                fontSize = baseStyle.fontSize * shrink,
                lineHeight = baseStyle.lineHeight * shrink
            ),
            color = colors.textPrimary,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { result ->
                if (result.hasVisualOverflow && shrink > 0.65f) shrink -= 0.07f
            },
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * A borderless, circular tap target that satisfies the 44 dp minimum even
 * when the glyph inside it is small (§32).
 */
@Composable
fun OmnixIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val spacing = OmnixTheme.spacing
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier
            .size(spacing.touchTarget)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = androidx.compose.ui.graphics.Color.Transparent
    ) {
        androidx.compose.foundation.layout.Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(spacing.xs)
        ) {
            content()
        }
    }
}
