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
 * The route feeds [TranslatorScreen] from [LiveInterpreterViewModel]: the
 * conversation, the language pair, whose earbud is whose and where the
 * translation sounds.
 */
@Composable
fun TranslatorRoute(
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    viewModel: LiveInterpreterViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val outputToPhone by viewModel.outputToPhone.collectAsState()

    TranslatorScreen(
        state = state,
        isOnline = isOnline,
        outputToPhone = outputToPhone,
        languages = LiveTranslatorEngine.SUPPORTED_LANGUAGES,
        modifier = modifier,
        onBack = onBack,
        onMyLanguage = viewModel::setMyLanguage,
        onTheirLanguage = viewModel::setTheirLanguage,
        onSwapEars = viewModel::swapEars,
        onOutputToPhone = viewModel::setOutputToPhone,
        onToggle = { if (state.running) viewModel.stop() else viewModel.start() },
        onEnd = viewModel::endConversation
    )
}
