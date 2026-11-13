package com.omnix.assistant.presentation.activation

import android.content.Context
import android.widget.Toast
import com.omnix.assistant.presentation.devices.formatTrialDate
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.R
import com.omnix.assistant.core.clip.ClipPairOutcome
import com.omnix.assistant.core.clip.ClipPairService
import com.omnix.assistant.core.license.ActivationResult
import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActivationUiState(
    val inputCode: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val licenseInfo: LicenseInfo? = null,
    val isActivated: Boolean = false
)

@HiltViewModel
class ActivationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val licenseManager: LicenseManager,
    /** null — только в юнит-тестах экрана кода (без Bluetooth-стека). */
    private val clipPairs: ClipPairService? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ActivationUiState(
        isActivated = licenseManager.isActivatedAndValid(),
        licenseInfo = licenseManager.getLicenseInfo()
    ))
    val uiState: StateFlow<ActivationUiState> = _uiState.asStateFlow()

    init {
        observeLicense()
    }

    private fun observeLicense() {
        viewModelScope.launch {
            licenseManager.licenseFlow.collectLatest { info ->
                _uiState.update {
                    it.copy(
                        licenseInfo = info,
                        isActivated = info.isActivated && !info.isExpired
                    )
                }
            }
        }
    }

    fun onCodeChanged(newCode: String) {
        // Код — десять символов в ячейках (шесть у ранее выпущенных кодов); всё
        // остальное ввод отсеивает, здесь — защита от вставки.
        _uiState.update { it.copy(inputCode = sanitizeActivationInput(newCode), errorMessage = null) }
    }

    /**
     * Активация без кода: подключённые наушники OMNIX, зарегистрированные
     * при упаковке, сами дают доступ (сервер создаёт аккаунт и лицензию).
     */
    fun activateWithPair() {
        val service = clipPairs ?: return
        if (_uiState.value.isLoading) return
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val result = service.bindConnected(withAccount = false)
            val outcome = result.outcome
            result.trialUntil?.let { until ->
                // Экран сразу уходит дальше — пробный показываем тостом.
                Toast.makeText(context, context.getString(R.string.omnix_pair_trial_until, formatTrialDate(until)), Toast.LENGTH_LONG).show()
            }
            _uiState.update {
                if (outcome == ClipPairOutcome.ADDED) {
                    it.copy(
                        isLoading = false,
                        isActivated = licenseManager.isActivatedAndValid(),
                        licenseInfo = licenseManager.getLicenseInfo()
                    )
                } else {
                    it.copy(isLoading = false, errorMessage = context.getString(outcome.messageRes))
                }
            }
        }
    }

    fun activate() {
        val code = _uiState.value.inputCode.trim()
        if (code.isBlank()) {
            _uiState.update { it.copy(errorMessage = context.getString(R.string.pozhaluysta_vvedite_kod)) }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        viewModelScope.launch {
            when (val result = licenseManager.activateWithCode(code)) {
                is ActivationResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            successMessage = result.message,
                            isActivated = true,
                            licenseInfo = result.licenseInfo
                        )
                    }
                }
                is ActivationResult.InvalidCode -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.reason
                        )
                    }
                }
                is ActivationResult.ServiceUnavailable -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
                is ActivationResult.AlreadyExpired -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }
}
