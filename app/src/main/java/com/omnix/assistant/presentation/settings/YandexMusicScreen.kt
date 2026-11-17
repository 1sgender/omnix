package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.agent.media.ymusic.YandexMusicTokenStore
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/** Раздел «Я → Яндекс Музыка»: токен для встроенного плеера. */
const val SECTION_YANDEX_MUSIC = "ymusic"

@HiltViewModel
class YandexMusicViewModel @Inject constructor(
    private val store: YandexMusicTokenStore
) : ViewModel() {

    private val _hasToken = MutableStateFlow(store.hasToken())

    /** Сам токен в интерфейс не отдаётся: только факт, что он сохранён. */
    val hasToken: StateFlow<Boolean> = _hasToken.asStateFlow()

    /** false — введено не похожее на токен значение. */
    fun save(raw: String): Boolean {
        val ok = store.save(raw)
        _hasToken.value = store.hasToken()
        return ok
    }

    fun clear() {
        store.clear()
        _hasToken.value = false
    }
}

@Composable
internal fun YandexMusicScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: YandexMusicViewModel = hiltViewModel()
) {
    val hasToken by viewModel.hasToken.collectAsStateWithLifecycle()
    // Не rememberSaveable: токен не должен оседать в сохранённом состоянии экрана.
    var input by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }

    SectionScaffold(stringResource(R.string.ymusic_settings_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.ymusic_settings_hint),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(OmnixTheme.spacing.sm))
        OmnixSettingsGroup {
            OmnixSettingRow(
                title = stringResource(R.string.ymusic_token_label),
                subtitle = stringResource(if (hasToken) R.string.ymusic_token_saved else R.string.ymusic_token_missing),
                inset = true,
                trailing = {
                    if (hasToken) {
                        OmnixTextButton(
                            text = stringResource(R.string.ymusic_token_remove),
                            onClick = { viewModel.clear() }
                        )
                    }
                }
            )
        }
        Spacer(Modifier.height(OmnixTheme.spacing.sm))
        OmnixTextFieldRow(
            title = stringResource(R.string.ymusic_token_input_title),
            value = input,
            onValueChange = { input = it; invalid = false },
            placeholder = stringResource(R.string.ymusic_token_placeholder),
            isError = invalid,
            errorText = stringResource(R.string.ymusic_token_invalid),
            visualTransformation = PasswordVisualTransformation()
        )
        OmnixPrimaryButton(
            text = stringResource(R.string.ymusic_token_save),
            onClick = {
                if (viewModel.save(input)) {
                    input = ""
                } else {
                    invalid = true
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(OmnixTheme.spacing.md))
        Text(
            text = stringResource(R.string.ymusic_unofficial_warning),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textTertiary
        )
    }
}
