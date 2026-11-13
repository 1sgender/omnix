package com.omnix.assistant.presentation.devices

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.core.admin.AdminModelStore
import com.omnix.assistant.core.clip.ClipPairGate
import com.omnix.assistant.core.clip.ClipPairOutcome
import com.omnix.assistant.core.clip.ClipPairService
import com.omnix.assistant.core.clip.ConnectedPair
import com.omnix.assistant.core.license.ClipPairInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import com.omnix.assistant.voice.audio.BluetoothAudioState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

data class ClipPairsUiState(
    val pairs: List<ClipPairInfo> = emptyList(),
    /** Нормализованный адрес подключённых наушников (для отметки «подключена»). */
    val connectedAddress: String? = null,
    /** Подключена свободная зарегистрированная пара — предложить добавить. */
    val offer: ConnectedPair.Available? = null,
    val confirmRelease: ClipPairInfo? = null,
    val isOperator: Boolean = false,
    val operatorSerial: String = "",
    val busy: Boolean = false,
    val message: ClipPairOutcome? = null,
    /** «Pro на 7 дней — до …»: только что выданный пробный период. */
    val trialUntil: Instant? = null
)

/**
 * «Мои наушники»: свои пары (из validate-ответа), предложение добавить
 * новую подключённую пару, отвязка своей пары и режим оператора для
 * аккаунтов-админов (регистрация пары при упаковке).
 */
@HiltViewModel
class ClipPairsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val service: ClipPairService,
    private val licenseManager: LicenseManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClipPairsUiState(isOperator = AdminModelStore.isAdmin(context)))
    val uiState: StateFlow<ClipPairsUiState> = _uiState.asStateFlow()

    /** Адрес, для которого уже спрашивали сервер, — не дёргать его на каждый сигнал. */
    private var checkedAddress: String? = null

    init {
        viewModelScope.launch {
            licenseManager.licenseFlow.collect { info ->
                _uiState.update { it.copy(pairs = info.clipBinding.pairs, isOperator = AdminModelStore.isAdmin(context)) }
            }
        }
        viewModelScope.launch {
            bluetoothAudioRouter.audioState.collect { state ->
                val address = if (state is BluetoothAudioState.Connected) service.connectedAddress() else null
                _uiState.update { it.copy(connectedAddress = address, offer = if (address == null) null else it.offer) }
                if (address != null && address != checkedAddress) {
                    checkedAddress = address
                    checkConnected()
                }
            }
        }
    }

    private suspend fun checkConnected() {
        // Только активированный аккаунт: первая привязка идёт с экрана активации.
        if (!licenseManager.isActivatedAndValid()) return
        when (val pair = service.connectedPair()) {
            is ConnectedPair.Available -> _uiState.update { it.copy(offer = pair) }
            is ConnectedPair.Own, ConnectedPair.None, ConnectedPair.NotOmnix -> _uiState.update { it.copy(offer = null) }
        }
    }

    fun isConnected(pair: ClipPairInfo): Boolean {
        val connected = _uiState.value.connectedAddress ?: return false
        return ClipPairGate.normalize(pair.btAddress) == connected
    }

    fun addOffered() = runAction({ it.copy(offer = null) }) {
        service.bindConnected(withAccount = true).also { result ->
            if (result.trialUntil != null) _uiState.update { it.copy(trialUntil = result.trialUntil) }
        }.outcome
    }

    fun dismissOffer() = _uiState.update { it.copy(offer = null) }

    fun askRelease(pair: ClipPairInfo) = _uiState.update { it.copy(confirmRelease = pair) }

    fun cancelRelease() = _uiState.update { it.copy(confirmRelease = null) }

    fun confirmRelease() {
        val pair = _uiState.value.confirmRelease ?: return
        runAction({ it.copy(confirmRelease = null) }) {
            service.release(pair.clipSerial).also { checkedAddress = null }
        }
    }

    fun onOperatorSerialChanged(value: String) =
        _uiState.update { it.copy(operatorSerial = value.filter { c -> c.isLetterOrDigit() || c in "-_." }.take(64)) }

    fun registerConnected() {
        val serial = _uiState.value.operatorSerial.trim()
        if (serial.isEmpty()) return
        val name = (bluetoothAudioRouter.audioState.value as? BluetoothAudioState.Connected)?.deviceName
        runAction({ it }) {
            service.operatorRegister(serial, name).also { outcome ->
                if (outcome == ClipPairOutcome.REGISTERED) _uiState.update { it.copy(operatorSerial = "") }
            }
        }
    }

    fun clearMessage() = _uiState.update { it.copy(message = null) }

    private fun runAction(before: (ClipPairsUiState) -> ClipPairsUiState, action: suspend () -> ClipPairOutcome) {
        if (_uiState.value.busy) return
        _uiState.update { before(it).copy(busy = true, message = null) }
        viewModelScope.launch {
            val outcome = action()
            _uiState.update { it.copy(busy = false, message = outcome) }
        }
    }
}
