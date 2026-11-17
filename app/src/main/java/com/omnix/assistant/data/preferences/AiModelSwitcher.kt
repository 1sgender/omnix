package com.omnix.assistant.data.preferences

import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelActivator
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.decision.AiModelSwitch
import com.omnix.assistant.agent.decision.AiProAccess
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.network.ServerHealthProbe
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Единственное место, где модель меняется: лист выбора в чате и голосовая
 * команда идут через одну проверку. Модель не готова — прежний выбор
 * остаётся, причина возвращается вызывающему.
 */
@Singleton
class AiModelSwitcher @Inject constructor(
    private val store: AiModelChoiceStore,
    private val localModelManager: LocalModelManager,
    private val serverHealth: ServerHealthProbe,
    private val licenseManager: LicenseManager
) : AiModelSwitch {

    private val activator = AiModelActivator(
        loadLocalModel = {
            withTimeoutOrNull(LOCAL_LOAD_TIMEOUT_MS) { localModelManager.initialize() }
                ?: LocalModelState.Failed("timeout")
        },
        serverReachable = { serverHealth.isReachable() },
        save = store::set,
        proAvailable = { AiProAccess.isAvailable(licenseManager.getLicenseInfo().entitlements) }
    )

    override fun selected(): AiModelChoice = store.choice.value

    override suspend fun switchTo(target: AiModelChoice): AiModelActivation =
        if (target == selected()) AiModelActivation.Applied else activator.activate(target)

    private companion object {
        /** Загрузка модели в память на медленном телефоне занимает десятки секунд. */
        const val LOCAL_LOAD_TIMEOUT_MS = 90_000L
    }
}
