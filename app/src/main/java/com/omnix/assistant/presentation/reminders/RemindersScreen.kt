package com.omnix.assistant.presentation.reminders

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.R
import com.omnix.assistant.agent.reminder.ReminderEntity
import com.omnix.assistant.agent.reminder.ReminderLabels
import com.omnix.assistant.agent.reminder.ReminderManager
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.SectionScaffold
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class RemindersViewModel @Inject constructor(
    private val manager: ReminderManager
) : ViewModel() {

    /** null — список ещё читается; пустой — напоминаний нет. */
    val reminders: StateFlow<List<ReminderEntity>?> = manager.active
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun delete(id: Long) {
        viewModelScope.launch { manager.delete(id) }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}

/**
 * Экран «Напоминания» (плитка на главной): ближайшие сверху, у каждого
 * моно-подпись «СЕГОДНЯ 18:00 / ЗАВТРА 10:00» и кнопка «Удалить».
 */
@Composable
fun RemindersScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: RemindersViewModel = hiltViewModel()
) {
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    val colors = OmnixTheme.colors

    SectionScaffold(stringResource(R.string.omnix_reminders_title), modifier, onBack) {
        val items = reminders
        if (items != null && items.isEmpty()) {
            Text(
                text = stringResource(R.string.omnix_reminders_empty),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp, lineHeight = 20.sp),
                color = colors.textSecondary
            )
        }
        items?.forEach { reminder ->
            ReminderRow(reminder = reminder, onDelete = { viewModel.delete(reminder.id) })
            Spacer(Modifier.height(OmnixTheme.spacing.sm))
        }
    }
}

@Composable
private fun ReminderRow(reminder: ReminderEntity, onDelete: () -> Unit) {
    val colors = OmnixTheme.colors
    val context = LocalContext.current
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), shape)
            .padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = ReminderLabels.whenText(context, reminder.triggerAt).uppercase(Locale.getDefault()),
                style = OmnixMonoLabelStyle.copy(fontSize = 11.sp),
                color = colors.textTertiary
            )
            Text(
                text = reminder.text,
                style = TextStyle(fontFamily = OmnixSans, fontSize = 16.sp, lineHeight = 22.sp),
                color = colors.textPrimary
            )
        }
        OmnixTextButton(text = stringResource(R.string.omnix_reminder_delete), onClick = onDelete)
    }
}
