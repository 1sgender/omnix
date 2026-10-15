package com.omnix.assistant.presentation.translator

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnix.assistant.agent.translator.LiveTranslatorEngine

/**
 * Translation is a mode of OMNIX, not a separate app (§25).
 *
 * The route feeds [TranslatorScreen] from the existing
 * [LiveInterpreterViewModel]: the phrases, the phrase still being heard, the
 * language pair and where the translation sounds.
 */
@Composable
fun TranslatorRoute(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: LiveInterpreterViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    TranslatorScreen(
        state = state,
        isOnline = isOnline,
        languages = LiveTranslatorEngine.SUPPORTED_LANGUAGES,
        modifier = modifier,
        onBack = onBack,
        onSwap = viewModel::swapLanguages,
        onMyLanguage = viewModel::setTargetLanguage,
        onPartnerLanguage = viewModel::setSourceLanguage,
        onOutputToPhone = viewModel::setOutputToPhone,
        onPause = { if (state.isListening) viewModel.toggleListening() },
        onSpeak = { if (!state.isListening) viewModel.toggleListening() },
        onEnd = viewModel::endConversation
    )
}
