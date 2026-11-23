package com.omnix.assistant.core.admin

import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.decision.ExecutionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Тест моделей админом (решение владельца 2026-10-21): админ — аккаунт из
 * ADMIN_ACCOUNT_IDS на сервере — может закрепить любую модель, на телефоне
 * или в облаке, и видит под ответом, кто ответил. Остальным это недоступно:
 * облачного провайдера сервер закрепляет только для админа, а флаг «админ»
 * приходит из validate-ответа лицензии.
 */

/** Облачная модель, которую сервер разрешил админу (validate → admin_models). */
@Serializable
data class AdminCloudModel(
    @SerialName("provider") val provider: String,
    @SerialName("model") val model: String
)

/** Что админ закрепил для теста. */
sealed interface AdminModelPin {
    /** Ничего: модели выбираются как у всех. */
    data object None : AdminModelPin

    /** Модель на телефоне ([com.omnix.assistant.agent.localai.LocalModelSpec.modelId]). */
    data class Phone(val specId: String) : AdminModelPin

    /** Облачный провайдер: GROQ / GEMINI / OPENROUTER. */
    data class Cloud(val provider: String) : AdminModelPin

    val storageKey: String
        get() = when (this) {
            None -> ""
            is Phone -> PHONE_PREFIX + specId
            is Cloud -> CLOUD_PREFIX + provider
        }

    /** Маршрут, без которого закреплённая модель не ответит; null — решает выбор пользователя. */
    val routing: AiModelChoice?
        get() = when (this) {
            None -> null
            is Phone -> AiModelChoice.ON_DEVICE
            is Cloud -> AiModelChoice.CLOUD
        }

    companion object {
        private const val PHONE_PREFIX = "phone:"
        private const val CLOUD_PREFIX = "cloud:"

        fun fromStorageKey(raw: String?): AdminModelPin = when {
            raw == null -> None
            raw.startsWith(PHONE_PREFIX) && raw.length > PHONE_PREFIX.length -> Phone(raw.removePrefix(PHONE_PREFIX))
            raw.startsWith(CLOUD_PREFIX) && raw.length > CLOUD_PREFIX.length -> Cloud(raw.removePrefix(CLOUD_PREFIX))
            else -> None
        }
    }
}

/** Точка, через которую маршрутизация, сеть и чат узнают о тесте моделей. */
interface AdminModelTesting {
    /** Закреплённая модель; [AdminModelPin.None] — не админ или ничего не закреплено. */
    fun pin(): AdminModelPin

    /** Validate лицензии прошёл: список облачных моделей админа, null — не админ. */
    fun onLicenseChecked(adminCloudModels: List<AdminCloudModel>?)

    /** Облачный ответ пришёл; провайдера и модель сервер называет только админу. */
    fun onCloudAnswer(provider: String?, model: String?)

    /** Подпись «кто ответил» для сохранённого ответа; null — не админ или не модель. */
    fun answerLabel(executionType: ExecutionType?): String?

    companion object {
        /** Для тестов и мест без DI: теста моделей нет. */
        val OFF: AdminModelTesting = object : AdminModelTesting {
            override fun pin(): AdminModelPin = AdminModelPin.None
            override fun onLicenseChecked(adminCloudModels: List<AdminCloudModel>?) = Unit
            override fun onCloudAnswer(provider: String?, model: String?) = Unit
            override fun answerLabel(executionType: ExecutionType?): String? = null
        }
    }
}

/** Имя провайдера для людей: GROQ → Groq. */
fun providerDisplayName(provider: String): String = when (provider.uppercase()) {
    "GROQ" -> "Groq"
    "GEMINI" -> "Gemini"
    "OPENROUTER" -> "OpenRouter"
    else -> provider
}

/** «Groq · openai/gpt-oss-120b»; без модели — только провайдер. */
fun cloudModelLabel(provider: String, model: String?): String =
    if (model.isNullOrBlank()) providerDisplayName(provider) else "${providerDisplayName(provider)} · $model"
