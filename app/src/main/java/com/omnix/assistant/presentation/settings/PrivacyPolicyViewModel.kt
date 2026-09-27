package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omnix.assistant.agent.memory.dao.FactDao
import com.omnix.assistant.agent.memory.dao.MemoryDao
import com.omnix.assistant.agent.memory.dao.PreferenceDao
import com.omnix.assistant.agent.memory.dao.ProcedureDao
import com.omnix.assistant.agent.memory.model.MemoryTypeLabel
import com.omnix.assistant.agent.memory.model.memoryTypeLabel
import com.omnix.assistant.agent.policy.ActionPolicySettingsProvider
import com.omnix.assistant.agent.policy.CallConfirmationPolicy
import com.omnix.assistant.agent.policy.MessagingConfirmationPolicy
import com.omnix.assistant.agent.tools.accessibility.AccessibilityPrivacyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Одна строка браузера памяти: содержимое, тип для подписи и ключ близнеца. */
data class MemoryEntryUi(
    val id: Long,
    val content: String,
    val typeLabel: MemoryTypeLabel,
    /** keyName записи: связывает воспоминание с его структурированным близнецом. */
    val keyName: String? = null
)

/** Структурированный факт (user.name → Александр). */
data class FactEntryUi(
    val key: String,
    val value: String
)

data class ProcedureEntryUi(
    val trigger: String,
    val executions: Int
)

/**
 * Состояние рабочих органов Privacy-экрана (блоки 3–4 плана пересборки
 * фронта, 2026-09-26): политики подтверждений, доверенные контакты,
 * граница чтения экрана, браузер памяти «Что помнит OMNIX».
 */
data class PrivacyPolicyUiState(
    val callPolicy: CallConfirmationPolicy = CallConfirmationPolicy.ALWAYS,
    val messagingPolicy: MessagingConfirmationPolicy = MessagingConfirmationPolicy.ALWAYS,
    val trustedContacts: List<String> = emptyList(),
    val allowListMode: Boolean = false,
    val blockedPackages: List<String> = emptyList(),
    val allowedPackages: List<String> = emptyList(),
    val memories: List<MemoryEntryUi> = emptyList(),
    val facts: List<FactEntryUi> = emptyList(),
    val procedures: List<ProcedureEntryUi> = emptyList()
)

/**
 * Каждая мутация — immediate-write (требование плана владельца: без
 * batch-кнопки «Сохранить»): политики и контакты пишутся в DataStore через
 * [ActionPolicySettingsProvider], граница чтения экрана — в
 * [AccessibilityPrivacyStore], который accessibility-сервис читает
 * синхронно из того же файла.
 *
 * Accessibility-стор не стримит изменения (SharedPreferences вне Hilt-графа
 * сервиса), поэтому после каждой мутации состояние перечитывается явно;
 * внешние записи (голосовые команды) подъедут при следующей мутации.
 */
@HiltViewModel
class PrivacyPolicyViewModel @Inject constructor(
    private val policyProvider: ActionPolicySettingsProvider,
    private val accessibilityStore: AccessibilityPrivacyStore,
    private val memoryDao: MemoryDao,
    private val factDao: FactDao,
    private val preferenceDao: PreferenceDao,
    private val procedureDao: ProcedureDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PrivacyPolicyUiState())
    val uiState: StateFlow<PrivacyPolicyUiState> = _uiState.asStateFlow()

    init {
        refreshAccessibility()
        viewModelScope.launch {
            policyProvider.settings.collectLatest { settings ->
                _uiState.update {
                    it.copy(
                        callPolicy = settings.callPolicy,
                        messagingPolicy = settings.messagingPolicy,
                        trustedContacts = settings.trustedContacts.toList()
                    )
                }
            }
        }
        // Браузер памяти (блок 4): те же живые Room-стримы, по которым
        // движок памяти читает воспоминания и факты.
        viewModelScope.launch {
            memoryDao.getAllMemoriesStream().collectLatest { entries ->
                _uiState.update { state ->
                    state.copy(
                        memories = entries
                            .sortedByDescending { it.createdAt }
                            .map { memory ->
                                MemoryEntryUi(
                                    id = memory.id,
                                    content = memory.content,
                                    typeLabel = memoryTypeLabel(memory.type),
                                    keyName = memory.keyName
                                )
                            }
                    )
                }
            }
        }
        viewModelScope.launch {
            factDao.getAllFactsStream().collectLatest { entries ->
                _uiState.update { state ->
                    state.copy(
                        facts = entries
                            .sortedBy { it.factKey }
                            .map { fact -> FactEntryUi(key = fact.factKey, value = fact.factValue) }
                    )
                }
            }
        }
        viewModelScope.launch {
            procedureDao.getAllProceduresStream().collectLatest { entries ->
                _uiState.update { state ->
                    state.copy(
                        procedures = entries
                            .sortedBy { it.triggerPhrase }
                            .map { proc -> ProcedureEntryUi(trigger = proc.triggerPhrase, executions = proc.executionCount) }
                    )
                }
            }
        }
    }

    fun setCallPolicy(policy: CallConfirmationPolicy) {
        viewModelScope.launch {
            policyProvider.update { it.copy(callPolicy = policy) }
        }
    }

    fun setMessagingPolicy(policy: MessagingConfirmationPolicy) {
        viewModelScope.launch {
            policyProvider.update { it.copy(messagingPolicy = policy) }
        }
    }

    /** Пустая строка после trim игнорируется; дубль не создаётся. */
    fun addTrustedContact(raw: String) {
        val contact = raw.trim()
        if (contact.isEmpty()) return
        viewModelScope.launch {
            policyProvider.update { settings ->
                if (contact in settings.trustedContacts) settings
                else settings.copy(trustedContacts = settings.trustedContacts + contact)
            }
        }
    }

    fun removeTrustedContact(contact: String) {
        viewModelScope.launch {
            policyProvider.update { settings ->
                settings.copy(trustedContacts = settings.trustedContacts - contact)
            }
        }
    }

    fun setScreenReaderAllowList(enabled: Boolean) {
        accessibilityStore.setAllowListMode(enabled)
        refreshAccessibility()
    }

    fun blockPackage(raw: String) {
        accessibilityStore.blockPackage(raw)
        refreshAccessibility()
    }

    fun unblockPackage(packageName: String) {
        accessibilityStore.unblockPackage(packageName)
        refreshAccessibility()
    }

    fun allowPackage(raw: String) {
        accessibilityStore.allowPackage(raw)
        refreshAccessibility()
    }

    fun revokePackageAllowance(packageName: String) {
        accessibilityStore.revokeAllowance(packageName)
        refreshAccessibility()
    }

    /**
     * Точное удаление одного воспоминания по id (браузер памяти):
     * без семантического матчинга — строка, которую видит пользователь,
     * и есть строка, которая удаляется. Если у записи есть ключ, уходят и
     * структурированные близнецы (факт/предпочтение) — та же семантика,
     * что у голосового «забудь»: юнит знания удаляется целиком, а не
     * наполовину (иначе ответы продолжали бы идти через копию в memories).
     */
    fun forgetMemory(memoryId: Long, keyName: String?) {
        viewModelScope.launch {
            memoryDao.deleteMemoryById(memoryId)
            if (keyName != null) {
                factDao.deleteFact(keyName)
                preferenceDao.deletePreference(keyName)
            }
        }
    }

    /** Удаление факта — вместе с его близнецом-воспоминанием по ключу. */
    fun removeFact(factKey: String) {
        viewModelScope.launch {
            factDao.deleteFact(factKey)
            memoryDao.deleteMemoryByKey(factKey)
            preferenceDao.deletePreference(factKey)
        }
    }

    /**
     * Удаление записанной процедуры по триггеру. В отличие от фактов у
     * процедуры нет близнецов в других таблицах — сущность самостоятельная.
     */
    fun removeProcedure(trigger: String) {
        viewModelScope.launch {
            procedureDao.deleteProcedure(trigger)
        }
    }

    private fun refreshAccessibility() {
        _uiState.update {
            it.copy(
                allowListMode = accessibilityStore.isAllowListMode(),
                blockedPackages = accessibilityStore.blockedPackages().sorted(),
                allowedPackages = accessibilityStore.allowedPackages().sorted()
            )
        }
    }
}
