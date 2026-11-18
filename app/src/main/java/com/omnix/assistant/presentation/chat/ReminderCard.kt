package com.omnix.assistant.presentation.chat

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.reminder.ReminderEntity
import com.omnix.assistant.agent.reminder.ReminderLabels
import com.omnix.assistant.agent.reminder.ReminderStatus
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Карточка в ленте чата после «напомни…»: тонкая рамка, моно-подпись
 * «НАПОМИНАНИЕ · СЕГОДНЯ 18:00», текст и кнопки «Изменить» / «Отменить».
 * Показывает живое состояние записи: отменённое или выполненное напоминание
 * остаётся в ленте без кнопок. [reminder] null — запись удалили из списка.
 */
@Composable
internal fun ReminderCard(
    reminder: ReminderEntity?,
    onEdit: (hour: Int, minute: Int) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    val waiting = reminder != null && reminder.status == ReminderStatus.PENDING
    val tail = when {
        reminder == null || reminder.status == ReminderStatus.CANCELLED ->
            stringResource(R.string.omnix_reminder_status_cancelled)
        reminder.status == ReminderStatus.DONE -> stringResource(R.string.omnix_reminder_done)
        else -> ReminderLabels.whenText(context, reminder.triggerAt)
    }
    val caption = (stringResource(R.string.omnix_reminder_label) + " · " + tail).uppercase(Locale.getDefault())

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = caption,
            style = OmnixMonoLabelStyle.copy(fontSize = 10.sp),
            color = colors.textTertiary
        )
        if (reminder != null) {
            Text(
                text = reminder.text,
                style = TextStyle(fontFamily = OmnixSans, fontSize = 16.sp, lineHeight = 22.sp),
                color = if (waiting) colors.textPrimary else colors.textSecondary
            )
        }
        if (waiting && reminder != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OmnixSecondaryButton(
                    text = stringResource(R.string.omnix_reminder_edit),
                    onClick = {
                        val at = Instant.ofEpochMilli(reminder.triggerAt).atZone(ZoneId.systemDefault())
                        TimePickerDialog(
                            context,
                            { _, hour, minute -> onEdit(hour, minute) },
                            at.hour,
                            at.minute,
                            DateFormat.is24HourFormat(context)
                        ).show()
                    }
                )
                OmnixTextButton(text = stringResource(R.string.omnix_reminder_cancel), onClick = onCancel)
            }
        }
    }
}
