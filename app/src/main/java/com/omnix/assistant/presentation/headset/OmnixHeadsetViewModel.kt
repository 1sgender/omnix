package com.omnix.assistant.presentation.headset

import androidx.lifecycle.ViewModel
import com.omnix.assistant.core.admin.AdminModelState
import com.omnix.assistant.core.admin.AdminModelStore
import com.omnix.assistant.core.headset.ConnectedHeadset
import com.omnix.assistant.core.headset.HeadsetGateState
import com.omnix.assistant.core.headset.OmnixHeadsetGate
import com.omnix.assistant.core.headset.OmnixHeadsetRegistry
import com.omnix.assistant.core.headset.RegisteredHeadset
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Экран блокировки и админ-экран наушников OMNIX. */
@HiltViewModel
class OmnixHeadsetViewModel @Inject constructor(
    private val gate: OmnixHeadsetGate,
    private val registry: OmnixHeadsetRegistry,
    adminStore: AdminModelStore
) : ViewModel() {

    val gateState: StateFlow<HeadsetGateState> = gate.state
    val connected: StateFlow<List<ConnectedHeadset>> = gate.connected
    val registered: StateFlow<List<RegisteredHeadset>> = registry.headsets
    val enforceForAdmin: StateFlow<Boolean> = registry.enforceForAdmin
    val admin: StateFlow<AdminModelState> = adminStore.state

    fun refresh() = gate.refresh()

    fun register(headset: PairedHeadset) {
        registry.add(headset.address, headset.name)
        gate.refresh()
    }

    fun remove(address: String) = registry.remove(address)

    fun setEnforceForAdmin(enabled: Boolean) = registry.setEnforceForAdmin(enabled)
}
