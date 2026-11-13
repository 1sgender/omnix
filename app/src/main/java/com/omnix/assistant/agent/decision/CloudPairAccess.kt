package com.omnix.assistant.agent.decision

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.core.license.ClientPlanGate
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/** Какое облачное использование проверяется. */
enum class CloudUse {
    /** Текстовый чат (base_ai): достаточно ACTIVE-пары у аккаунта. */
    CHAT,

    /** Всё остальное облачное (голос, переводчик, инструменты из облака): нужна подключённая своя пара. */
    FULL
}

/**
 * Облачный ИИ с наушниками OMNIX (V012).
 *
 * Не второй источник истины: решения принимает [ClientPlanGate]
 * (isCloudChatUnlocked / isCloudVoiceUnlocked) по списку пар из
 * validate-ответа сервера; здесь лишь подставляются адреса реально
 * подключённых устройств. Офлайн-путь (локальный ИИ, сценарии) не трогается.
 *
 * Сервер подключение наушников видеть не может: он режет токен привязки,
 * только когда у аккаунта нет ни одной ACTIVE-пары. Разделение «без наушников
 * только чат» — клиентский гейт.
 */
fun interface CloudPairAccess {
    /** null — использование разрешено; иначе текст для пользователя. */
    fun blockedMessage(use: CloudUse): String?

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
    override fun blockedMessage(use: CloudUse): String? {
        val gate = planGate.get()
        val chatOpen = gate.isCloudChatUnlocked()
        return when {
            !chatOpen -> context.getString(R.string.omnix_pair_required_message)
            use == CloudUse.CHAT -> null
            gate.isCloudVoiceUnlocked(bluetoothAudioRouter.get().connectedAudioAddresses()) -> null
            else -> context.getString(R.string.omnix_pair_chat_only_message)
        }
    }
}
