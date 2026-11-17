package com.omnix.assistant.presentation.reminders

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.R
import com.omnix.assistant.agent.reminder.ReminderAnnouncer
import com.omnix.assistant.agent.reminder.ReminderEntity
import com.omnix.assistant.agent.reminder.ReminderManager
import com.omnix.assistant.agent.reminder.ReminderNotifier
import com.omnix.assistant.agent.reminder.ReminderTimeFormat
import com.omnix.assistant.presentation.components.OmnixBellLineIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.design.OmnixAppearance
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

/**
 * Экран сработавшего напоминания поверх блокировки: колокольчик в кольце,
 * время, текст крупно и две кнопки. Открывается полноэкранным уведомлением
 * (экран включается сам) или по нажатию на уведомление.
 */
@AndroidEntryPoint
class ReminderAlertActivity : ComponentActivity() {

    private val viewModel: ReminderAlertViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        enableEdgeToEdge()
        viewModel.load(intent.getLongExtra(EXTRA_ID, 0L))
        setContent {
            // Экран напоминания всегда тёмный: ночью он не должен слепить.
            OmnixTheme(appearance = OmnixAppearance.Dark) {
                ReminderAlertScreen(viewModel = viewModel, onClose = ::finish)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        viewModel.load(intent.getLongExtra(EXTRA_ID, 0L))
    }

    companion object {
        const val EXTRA_ID = "reminder_id"
    }
}

@HiltViewModel
class ReminderAlertViewModel @Inject constructor(
    private val manager: ReminderManager,
    private val notifier: ReminderNotifier,
    private val announcer: ReminderAnnouncer
) : ViewModel() {

    private val _state = MutableStateFlow<AlertState>(AlertState.Loading)
    val state: StateFlow<AlertState> = _state.asStateFlow()

    sealed interface AlertState {
        data object Loading : AlertState
        data object Missing : AlertState
        data class Shown(val reminder: ReminderEntity) : AlertState
    }

    fun load(id: Long) {
        viewModelScope.launch {
            val reminder = manager.get(id)
            _state.value = if (reminder == null) AlertState.Missing else AlertState.Shown(reminder)
        }
    }

    fun done(id: Long, onFinished: () -> Unit) = finish(id, onFinished) { manager.done(id) }

    fun snooze(id: Long, onFinished: () -> Unit) = finish(id, onFinished) { manager.snooze(id) }

    private fun finish(id: Long, onFinished: () -> Unit, change: suspend () -> Unit) {
        viewModelScope.launch {
            change()
            notifier.cancel(id)
            announcer.stop()
            onFinished()
        }
    }
}

@Composable
private fun ReminderAlertScreen(viewModel: ReminderAlertViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = OmnixTheme.colors
    when (val current = state) {
        ReminderAlertViewModel.AlertState.Loading -> Box(Modifier.fillMaxSize().background(colors.background))
        ReminderAlertViewModel.AlertState.Missing -> {
            Box(Modifier.fillMaxSize().background(colors.background))
            LaunchedEffect(Unit) { onClose() }
        }
        is ReminderAlertViewModel.AlertState.Shown -> {
            val reminder = current.reminder
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(colors.background)
                    .padding(horizontal = 28.dp, vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(132.dp)
                        .border(1.dp, colors.border, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = OmnixBellLineIcon,
                        contentDescription = null,
                        tint = colors.textPrimary,
                        modifier = Modifier.size(52.dp)
                    )
                }
                Spacer(Modifier.height(32.dp))
                Text(
                    text = (
                        ReminderTimeFormat.clock(reminder.triggerAt) + " · " +
                            stringResource(R.string.omnix_reminder_label)
                        ).uppercase(Locale.getDefault()),
                    style = OmnixMonoLabelStyle.copy(fontSize = 12.sp),
                    color = colors.textTertiary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = reminder.text,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 30.sp, lineHeight = 38.sp),
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(48.dp))
                OmnixPrimaryButton(
                    text = stringResource(R.string.omnix_reminder_done),
                    onClick = { viewModel.done(reminder.id, onClose) },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OmnixSecondaryButton(
                    text = stringResource(R.string.omnix_reminder_snooze),
                    onClick = { viewModel.snooze(reminder.id, onClose) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
