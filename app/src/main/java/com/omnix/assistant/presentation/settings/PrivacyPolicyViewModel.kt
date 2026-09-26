package com.omnix.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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

/**
 * Состояние рабочих органов Privacy-экрана (блок 3 плана пересборки
 * фронта, 2026-09-26): политики подтверждений, доверенные контакты,
 * граница чтения экрана.
 */
data class PrivacyPolicyUiState(
    val callPolicy: CallConfirmationPolicy = CallConfirmationPolicy.ALWAYS,
    val messagingPolicy: MessagingConfirmationPolicy = MessagingConfirmationPolicy.ALWAYS,
    val trustedContacts: List<String> = emptyList(),
    val allowListMode: Boolean = false,
    val blockedPackages: List<String> = emptyList(),
    val allowedPackages: List<String> = emptyList()
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
    private val accessibilityStore: AccessibilityPrivacyStore
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
