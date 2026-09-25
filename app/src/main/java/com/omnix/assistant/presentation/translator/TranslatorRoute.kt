package com.omnix.assistant.presentation.translator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Translation is a mode of OMNIX, not a separate app (§25).
 *
 * The route reuses the existing [LiveInterpreterViewModel] unchanged. The
 * mock of 2026-09-25 replaced the Core on this screen with the calm
 * translation ring, so the eight states are no longer painted here: listening
 * shows up as the live waveform (the real microphone level), thinking as the
 * settled result text.
 */
@Composable
fun TranslatorRoute(
    modifier: Modifier = Modifier,
    audioLevel: Float = 0f,
    onBack: () -> Unit = {},
    viewModel: LiveInterpreterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    val latest = state.history.firstOrNull()

    TranslatorScreen(
        modifier = modifier,
        onBack = onBack,
        active = state.isListening,
        audioLevel = audioLevel,
        sourceLanguage = (state.detectedSourceLanguage ?: state.sourceLanguage).displayName,
        targetLanguage = state.targetLanguage.displayName,
        // While speech is still arriving the partial text is the transcript;
        // once an item lands, the finished pair is shown instead.
        transcript = state.partialRecognizedText.ifBlank { latest?.originalText.orEmpty() },
        translation = latest?.translatedText.orEmpty(),
        onStart = viewModel::toggleListening,
        onStop = viewModel::toggleListening,
        onSwap = viewModel::swapLanguages
    )
}
