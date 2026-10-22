package com.omnix.assistant.core.admin

import android.content.Context
import android.content.SharedPreferences
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.ExecutionType
import com.omnix.assistant.agent.localai.LocalModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class AdminModelState(
    val isAdmin: Boolean = false,
    val cloudModels: List<AdminCloudModel> = emptyList(),
    val pin: AdminModelPin = AdminModelPin.None
)

/**
 * Состояние теста моделей, переживающее перезапуск: флаг «админ» и список
 * облачных моделей из последнего validate, закреплённая модель. Обычные
 * SharedPreferences, а не зашифрованные: секретов здесь нет, а решает всё
 * равно сервер (клиентский флаг лишь показывает меню).
 *
 * Модель телефона выбирается при сборке DI-графа, до Hilt, поэтому её
 * закрепление читается синхронно — [pinnedPhoneSpec].
 */
@Singleton
class AdminModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localSpec: LocalModelSpec
) : AdminModelTesting {

    private val prefs: SharedPreferences = prefsOf(context)
    private val _state = MutableStateFlow(read(prefs))
    val state: StateFlow<AdminModelState> = _state.asStateFlow()

    /** Подпись последнего облачного ответа — до сохранения этого ответа в чат. */
    @Volatile
    private var lastCloudLabel: String? = null

    override fun pin(): AdminModelPin = _state.value.let { if (it.isAdmin) it.pin else AdminModelPin.None }

    override fun onLicenseChecked(adminCloudModels: List<AdminCloudModel>?) {
        val current = _state.value
        val next = if (adminCloudModels == null) {
            AdminModelState()
        } else {
            val pin = current.pin
            // Провайдер пропал с сервера (нет ключа) — закрепление снимается.
            val stalePin = pin is AdminModelPin.Cloud && adminCloudModels.none { it.provider == pin.provider }
            current.copy(
                isAdmin = true,
                cloudModels = adminCloudModels,
                pin = if (stalePin) AdminModelPin.None else pin
            )
        }
        if (next != current) save(next)
    }

    fun setPin(pin: AdminModelPin) {
        val current = _state.value
        if (!current.isAdmin || pin == current.pin) return
        save(current.copy(pin = pin))
    }

    override fun onCloudAnswer(provider: String?, model: String?) {
        if (!_state.value.isAdmin || provider.isNullOrBlank()) return
        lastCloudLabel = cloudModelLabel(provider, model)
    }

    override fun answerLabel(executionType: ExecutionType?): String? {
        if (!_state.value.isAdmin) return null
        return when (executionType) {
            ExecutionType.CLOUD_AI -> lastCloudLabel.also { lastCloudLabel = null }
                ?: context.getString(R.string.omnix_admin_answer_cloud)
            ExecutionType.LOCAL_AI -> context.getString(R.string.omnix_admin_answer_phone, localSpec.displayName)
            else -> null
        }
    }

    private fun save(state: AdminModelState) {
        prefs.edit()
            .putBoolean(KEY_ADMIN, state.isAdmin)
            .putString(KEY_CLOUD_MODELS, json.encodeToString(MODELS, state.cloudModels))
            .putString(KEY_PIN, state.pin.storageKey)
            .apply()
        _state.value = state
    }

    companion object {
        private const val PREFS_NAME = "omnix_admin_models"
        private const val KEY_ADMIN = "is_admin"
        private const val KEY_CLOUD_MODELS = "cloud_models"
        private const val KEY_PIN = "pin"
        private val MODELS = ListSerializer(AdminCloudModel.serializer())
        private val json = Json { ignoreUnknownKeys = true }

        private fun prefsOf(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        private fun read(prefs: SharedPreferences): AdminModelState {
            if (!prefs.getBoolean(KEY_ADMIN, false)) return AdminModelState()
            val models = runCatching {
                json.decodeFromString(MODELS, prefs.getString(KEY_CLOUD_MODELS, null) ?: "[]")
            }.getOrDefault(emptyList())
            return AdminModelState(
                isAdmin = true,
                cloudModels = models,
                pin = AdminModelPin.fromStorageKey(prefs.getString(KEY_PIN, null))
            )
        }

        /** Был ли аккаунт админом при последней проверке лицензии. */
        fun isAdmin(context: Context): Boolean = read(prefsOf(context)).isAdmin

        /** Модель телефона, закреплённая админом; null — как у всех. */
        fun pinnedPhoneSpec(context: Context): LocalModelSpec? {
            val pin = read(prefsOf(context)).pin as? AdminModelPin.Phone ?: return null
            return LocalModelSpec.ALL.firstOrNull { it.modelId == pin.specId }
        }
    }
}
