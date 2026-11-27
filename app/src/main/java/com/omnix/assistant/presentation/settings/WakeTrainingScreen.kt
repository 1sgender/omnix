package com.omnix.assistant.presentation.settings

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.voice.wakeword.WakeWordEngine
import com.omnix.assistant.voice.wakeword.personal.PersonalWakeStore
import com.omnix.assistant.voice.wakeword.personal.WakeTrainer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

const val SECTION_WAKE_TRAINING = "wake_training"

/** Что сейчас показывает экран тренировки. */
data class WakeTrainingState(
    val positives: Int = 0,
    val negatives: Int = 0,
    val filterReady: Boolean = false,
    val recording: Boolean = false,
    /** null — попыток ещё не было; true/false — последняя засчитана или нет. */
    val lastAccepted: Boolean? = null,
    val backgroundRunning: Boolean = false,
    val backgroundElapsedMs: Long = 0,
    val backgroundHits: Int = 0,
    val error: Boolean = false
)

@HiltViewModel
class WakeTrainingViewModel @Inject constructor(
    private val trainer: WakeTrainer,
    private val store: PersonalWakeStore,
    private val wakeWordEngine: WakeWordEngine
) : ViewModel() {

    private val _state = MutableStateFlow(WakeTrainingState())
    val state: StateFlow<WakeTrainingState> = _state.asStateFlow()

    private var job: Job? = null
    private var engineWasRunning = false

    init {
        refreshCounts()
    }

    /** Экран открыт: фоновый «Омни» уступает микрофон тренировке. */
    fun onShown() {
        engineWasRunning = wakeWordEngine.isRunning()
        wakeWordEngine.stop()
    }

    /** Экран закрыт: вернуть фоновый «Омни», если он работал. */
    fun onHidden() {
        job?.cancel()
        if (engineWasRunning) wakeWordEngine.start()
    }

    fun recordAttempt() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(recording = true, error = false) }
            try {
                val attempt = trainer.recordAttempt()
                _state.update { it.copy(recording = false, lastAccepted = attempt.accepted) }
            } catch (e: CancellationException) {
                _state.update { it.copy(recording = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(recording = false, error = true) }
            }
            refreshCounts()
        }
    }

    fun toggleBackgroundTest() {
        if (_state.value.backgroundRunning) {
            job?.cancel()
            _state.update { it.copy(backgroundRunning = false) }
            refreshCounts()
            return
        }
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            _state.update { it.copy(backgroundRunning = true, backgroundElapsedMs = 0, backgroundHits = 0, error = false) }
            try {
                trainer.backgroundTest(BACKGROUND_TEST_MS) { elapsed, hits ->
                    _state.update { it.copy(backgroundElapsedMs = elapsed, backgroundHits = hits) }
                }
                _state.update { it.copy(backgroundRunning = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(backgroundRunning = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(backgroundRunning = false, error = true) }
            }
            refreshCounts()
        }
    }

    fun reset() {
        job?.cancel()
        store.clear()
        _state.update { WakeTrainingState() }
        refreshCounts()
    }

    fun share(context: Context) {
        val zip = trainer.exportZip() ?: return
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files-provider", zip)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun refreshCounts() {
        _state.update {
            it.copy(
                positives = store.positiveCount(),
                negatives = store.negativeCount(),
                filterReady = store.verifier() != null
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        job?.cancel()
    }

    companion object {
        /** Фоновая проверка — полчаса обычной жизни. */
        const val BACKGROUND_TEST_MS = 30 * 60 * 1000L

        /** Сколько попыток тренировки советуем. */
        const val TARGET_ATTEMPTS = 15
    }
}

@Composable
internal fun WakeTrainingScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: WakeTrainingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    DisposableEffect(Unit) {
        viewModel.onShown()
        onDispose { viewModel.onHidden() }
    }
    val spacing = OmnixTheme.spacing

    SectionScaffold(stringResource(R.string.omnix_wake_training_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_wake_training_hint),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = if (state.filterReady) {
                stringResource(R.string.omnix_wake_training_filter_on, state.positives, state.negatives)
            } else {
                stringResource(
                    R.string.omnix_wake_training_filter_off,
                    state.positives,
                    (PersonalWakeStore.MIN_POSITIVES - state.positives).coerceAtLeast(0)
                )
            },
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textPrimary
        )

        OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_wake_training_record_header))
        OmnixPrimaryButton(
            text = stringResource(
                if (state.recording) R.string.omnix_wake_training_recording else R.string.omnix_wake_training_record,
                state.positives,
                WakeTrainingViewModel.TARGET_ATTEMPTS
            ),
            onClick = viewModel::recordAttempt,
            enabled = !state.recording && !state.backgroundRunning,
            modifier = Modifier.fillMaxWidth()
        )
        state.lastAccepted?.let { ok ->
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = stringResource(if (ok) R.string.omnix_wake_training_accepted else R.string.omnix_wake_training_rejected),
                style = OmnixTheme.typography.caption,
                color = OmnixTheme.colors.textSecondary
            )
        }

        OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_wake_training_background_header))
        Text(
            text = stringResource(R.string.omnix_wake_training_background_hint),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(spacing.xs))
        OmnixPrimaryButton(
            text = if (state.backgroundRunning) {
                stringResource(
                    R.string.omnix_wake_training_background_stop,
                    (state.backgroundElapsedMs / 60_000).toInt(),
                    state.backgroundHits
                )
            } else {
                stringResource(R.string.omnix_wake_training_background_start)
            },
            onClick = viewModel::toggleBackgroundTest,
            enabled = !state.recording,
            modifier = Modifier.fillMaxWidth()
        )

        if (state.error) {
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = stringResource(R.string.omnix_wake_training_error),
                style = OmnixTheme.typography.caption,
                color = OmnixTheme.colors.textSecondary
            )
        }

        OmnixSettingsSectionHeader(text = stringResource(R.string.omnix_wake_training_data_header))
        OmnixTextButton(
            text = stringResource(R.string.omnix_wake_training_share),
            onClick = { viewModel.share(context) }
        )
        OmnixTextButton(
            text = stringResource(R.string.omnix_wake_training_reset),
            onClick = viewModel::reset
        )
    }
}
