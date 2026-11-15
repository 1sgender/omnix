package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.payments.ManualPaymentApi
import com.omnix.assistant.core.payments.PaymentsResult
import com.omnix.assistant.core.payments.TariffsCatalog
import com.omnix.assistant.core.payments.needsLicenseRefresh
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Почему экран не смог загрузить каталог или создать заявку. */
enum class TariffsFailure { NoConnection, Server, NoLicense, Busy }

data class TariffsUiState(
    val loading: Boolean = true,
    val catalog: TariffsCatalog? = null,
    /** Тариф аккаунта сейчас (id плана лицензии); пусто — лицензия ещё не проверена. */
    val currentPlanId: String = "",
    /** План, по которому сейчас создаётся заявка. */
    val creatingPlanId: String? = null,
    val failure: TariffsFailure? = null
)

internal fun PaymentsResult<*>.toFailure(): TariffsFailure? = when (this) {
    is PaymentsResult.Ok -> null
    PaymentsResult.NoConnection -> TariffsFailure.NoConnection
    PaymentsResult.NoLicense -> TariffsFailure.NoLicense
    PaymentsResult.RateLimited -> TariffsFailure.Busy
    PaymentsResult.Unauthorized, PaymentsResult.Unavailable -> TariffsFailure.Server
}

/**
 * Экран «Тарифы»: каталог и контакты берутся с сервера. Когда владелец
 * подтвердил оплату, лицензия перечитывается сама — тариф меняется без
 * действий пользователя.
 */
@HiltViewModel
class TariffsViewModel @Inject constructor(
    private val api: ManualPaymentApi,
    private val licenseManager: LicenseManager
) : ViewModel() {

    private val _state = MutableStateFlow(TariffsUiState(currentPlanId = licenseManager.getLicenseInfo().planId))
    val state: StateFlow<TariffsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            licenseManager.licenseFlow.collect { info -> _state.update { it.copy(currentPlanId = info.planId) } }
        }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, failure = null) }
            when (val result = api.tariffs()) {
                is PaymentsResult.Ok -> {
                    _state.update { it.copy(loading = false, catalog = result.value, failure = null) }
                    if (needsLicenseRefresh(result.value.orders, licenseManager.getLicenseInfo().planId)) {
                        licenseManager.refreshFromServer()
                    }
                }
                else -> _state.update { it.copy(loading = false, failure = result.toFailure()) }
            }
        }
    }

    fun request(planId: String) {
        if (_state.value.creatingPlanId != null) return
        viewModelScope.launch {
            _state.update { it.copy(creatingPlanId = planId, failure = null) }
            when (val result = api.createOrder(planId)) {
                is PaymentsResult.Ok -> {
                    _state.update { it.copy(creatingPlanId = null) }
                    refresh()
                }
                else -> _state.update { it.copy(creatingPlanId = null, failure = result.toFailure()) }
            }
        }
    }
}
