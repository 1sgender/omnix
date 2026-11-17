package com.omnix.assistant.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Единая нижняя шторка OMNIX: вместо стандартного Material-диалога и меню.
 * Тональная поверхность без контура, скругление 24dp сверху, тонкая «ручка»,
 * заголовок и пояснение слева, много воздуха. Все диалоги и контекстные меню
 * приложения строятся на ней, чтобы вид был один.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OmnixSheet(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = OmnixTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surfaceElevated,
        scrimColor = colors.scrim,
        shape = RoundedCornerShape(topStart = OmnixTheme.radius.large, topEnd = OmnixTheme.radius.large),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textDisabled)
            )
        },
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = OmnixTheme.typography.heading,
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            if (message != null) {
                Text(
                    text = message,
                    style = OmnixTheme.typography.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (title != null || message != null) {
                Box(Modifier.height(16.dp))
            }
            content()
        }
    }
}

/**
 * Строка-действие внутри [OmnixSheet]: плоская, 56dp, без рамки.
 * [danger] — красный текст, [muted] — приглушённый (отмена).
 */
@Composable
fun OmnixSheetAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    muted: Boolean = false
) {
    val colors = OmnixTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            text = text,
            style = OmnixTheme.typography.body,
            color = when {
                danger -> colors.stateError
                muted -> colors.textSecondary
                else -> colors.textPrimary
            },
            textAlign = TextAlign.Start
        )
    }
}

/** Метка раздела: моноширинный капс, как строка даты на главной. */
@Composable
fun OmnixSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(java.util.Locale.getDefault()),
        style = OmnixMonoLabelStyle,
        color = OmnixTheme.colors.textTertiary,
        modifier = modifier
    )
}
