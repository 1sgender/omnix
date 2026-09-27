package com.omnix.assistant.presentation.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.agent.capability.CapabilityStatus
import com.omnix.assistant.agent.capability.DeviceCapabilityRegistry
import com.omnix.assistant.agent.capability.OmniCapability
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Одна строка снимка «На этом телефоне»: группа + её агрегированный статус.
 * Порядок — [OmniCapability.all] (порядок документации слоя).
 */
data class CapabilityGroupUi(
    val capability: OmniCapability,
    val status: CapabilityStatus
)

/**
 * Capability-снимок для экрана Devices (блок 6 плана пересборки фронта,
 * 2026-09-26): «что OMNIX умеет на ЭТОМ телефоне» из
 * [DeviceCapabilityRegistry.snapshotByGroup] — той же карты, которой
 * пользуется агент при планировании. Публичный API не дублируется.
 *
 * Статусы живые (разрешения/железо), поэтому снимок пересчитывается на
 * каждый ON_RESUME экрана — вернувшись из системного диалога разрешений,
 * пользователь видит обновлённые строки без перезапуска.
 */
@HiltViewModel
class DevicesCapabilitiesViewModel @Inject constructor(
    private val registry: DeviceCapabilityRegistry
) : ViewModel() {

    private val _snapshot = MutableStateFlow<List<CapabilityGroupUi>>(emptyList())
    val snapshot: StateFlow<List<CapabilityGroupUi>> = _snapshot.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        // snapshotByGroup() — синхронные быстрые проверки (permission cache,
        // PackageManager, статусы сервисов); отдельный диспатчер не нужен.
        viewModelScope.launch {
            val taken = registry.snapshotByGroup()
            _snapshot.value = OmniCapability.all.map { group ->
                CapabilityGroupUi(
                    capability = group,
                    status = taken[group] ?: CapabilityStatus.Unsupported(
                        "Группа не реализована"
                    )
                )
            }
        }
    }
}
