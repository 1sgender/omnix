package com.omnix.assistant.agent.decision

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.core.license.ClientPlanGate
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Облачный ИИ только с подключёнными наушниками OMNIX (V012).
 *
 * Не второй источник истины: решение принимает [ClientPlanGate.isCloudAiUnlocked]
 * по списку пар из validate-ответа сервера; здесь лишь подставляются адреса
 * реально подключённых устройств. Офлайн-путь (локальный ИИ, сценарии) не
 * трогается — закрыто только облако.
 */
fun interface CloudPairAccess {
    /** null — облако разрешено; иначе текст для пользователя. */
    fun blockedMessage(): String?

    companion object {
        val OPEN = CloudPairAccess { null }
    }
}

@Singleton
class ClipPairCloudAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    // Provider: BluetoothAudioRouter тянет движок автоматизаций → tools; без
    // Provider граф Dagger мог бы замкнуться через облачный исполнитель.
    private val planGate: Provider<ClientPlanGate>,
    private val bluetoothAudioRouter: Provider<BluetoothAudioRouter>
) : CloudPairAccess {
    override fun blockedMessage(): String? =
        if (planGate.get().isCloudAiUnlocked(bluetoothAudioRouter.get().connectedAudioAddresses())) {
            null
        } else {
            context.getString(R.string.omnix_pair_required_message)
        }
}
