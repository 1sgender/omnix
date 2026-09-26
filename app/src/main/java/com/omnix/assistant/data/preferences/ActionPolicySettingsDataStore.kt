package com.omnix.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.omnix.assistant.agent.policy.ActionPolicySettings
import com.omnix.assistant.agent.policy.ActionPolicySettingsProvider
import com.omnix.assistant.agent.policy.normalizeTrustedContacts
import com.omnix.assistant.agent.policy.parseCallPolicy
import com.omnix.assistant.agent.policy.parseMessagingPolicy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.actionPolicyDataStore: DataStore<Preferences> by
    preferencesDataStore(name = "omnix_action_policy")

/**
 * Персистентная реализация [ActionPolicySettingsProvider] на Preferences
 * DataStore (блок «Privacy-органы управления», план пересборки фронта
 * 2026-09-26). Заменяет in-memory дефолт в DI — движок [ActionPolicyEngine]
 * читает `current()` как раньше, контракт не менялся.
 *
 * Стратегия загрузки: StateFlow стартует с безопасными дефолтами (обе
 * политики ALWAYS, контактов нет) — до загрузки политика ведёт себя
 * строже, но никогда не слабее записанного. Загрузка — фон, `update()`
 * ждёт её через [loaded]: иначе стартовая загрузка могла бы перезаписать
 * правку, сделанную в первую секунду.
 *
 * Порядок записи: сначала DataStore, потом память — краш между шагами
 * оставит память консервативнее хранилища, а не наоборот.
 */
@Singleton
class DataStoreActionPolicySettingsProvider @Inject constructor(
    @ApplicationContext private val context: Context
) : ActionPolicySettingsProvider {

    private object Keys {
        val CALL_POLICY = stringPreferencesKey("policy.calls")
        val MESSAGING_POLICY = stringPreferencesKey("policy.messaging")
        val TRUSTED_CONTACTS = stringSetPreferencesKey("policy.trusted_contacts")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<Unit>()
    private val state = MutableStateFlow(ActionPolicySettings())

    override val settings: StateFlow<ActionPolicySettings> = state.asStateFlow()

    init {
        scope.launch {
            try {
                val prefs = context.actionPolicyDataStore.data
                    .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
                    .first()
                state.value = ActionPolicySettings(
                    callPolicy = parseCallPolicy(prefs[Keys.CALL_POLICY]),
                    messagingPolicy = parseMessagingPolicy(prefs[Keys.MESSAGING_POLICY]),
                    trustedContacts = normalizeTrustedContacts(prefs[Keys.TRUSTED_CONTACTS])
                )
            } catch (t: Throwable) {
                // Остаются дефолты (строже — лучше). Падение загрузки не должно
                // блокировать и запись: loaded завершится в finally.
            } finally {
                loaded.complete(Unit)
            }
        }
    }

    override fun current(): ActionPolicySettings = state.value

    override suspend fun update(transform: (ActionPolicySettings) -> ActionPolicySettings) {
        loaded.await()
        val next = transform(state.value)
        context.actionPolicyDataStore.edit { prefs ->
            prefs[Keys.CALL_POLICY] = next.callPolicy.name
            prefs[Keys.MESSAGING_POLICY] = next.messagingPolicy.name
            prefs[Keys.TRUSTED_CONTACTS] = next.trustedContacts
        }
        state.value = next
    }
}
