package com.omnix.assistant.presentation.settings

import com.omnix.assistant.agent.memory.dao.FactDao
import com.omnix.assistant.agent.memory.dao.MemoryDao
import com.omnix.assistant.agent.memory.dao.PreferenceDao
import com.omnix.assistant.agent.memory.entity.FactEntity
import com.omnix.assistant.agent.memory.entity.MemoryEntity
import com.omnix.assistant.agent.memory.model.MemoryTypeLabel
import com.omnix.assistant.agent.policy.ActionPolicySettings
import com.omnix.assistant.agent.policy.ActionPolicySettingsProvider
import com.omnix.assistant.agent.policy.CallConfirmationPolicy
import com.omnix.assistant.agent.policy.MessagingConfirmationPolicy
import com.omnix.assistant.agent.tools.accessibility.AccessibilityPrivacyStore
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Privacy-органы управления (блоки 3–4 плана пересборки фронта):
 * мержинг стримов в состояние и immediate-write мутации.
 *
 * Провайдер — настоящий фейк (тот же контракт, что в DI), сторы и DAO —
 * mockk: проверяем ВЫЗОВЫ (deleteMemoryById/deleteFact) и состояние,
 * а не хранилища.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PrivacyPolicyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private class FakeProvider(initial: ActionPolicySettings = ActionPolicySettings()) :
        ActionPolicySettingsProvider {
        private val state = MutableStateFlow(initial)
        override val settings: StateFlow<ActionPolicySettings> = state.asStateFlow()
        override fun current(): ActionPolicySettings = state.value
        override suspend fun update(transform: (ActionPolicySettings) -> ActionPolicySettings) {
            state.update(transform)
        }
    }

    private lateinit var provider: FakeProvider
    private lateinit var accessibilityStore: AccessibilityPrivacyStore
    private lateinit var memoryDao: MemoryDao
    private lateinit var factDao: FactDao
    private lateinit var preferenceDao: PreferenceDao

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        provider = FakeProvider(
            ActionPolicySettings(
                callPolicy = CallConfirmationPolicy.TRUSTED_ONLY,
                messagingPolicy = MessagingConfirmationPolicy.MONEY_ONLY,
                trustedContacts = setOf("Мама")
            )
        )
        accessibilityStore = mockk(relaxed = true)
        every { accessibilityStore.isAllowListMode() } returns false
        every { accessibilityStore.blockedPackages() } returns setOf("com.zzz", "com.aaa")
        every { accessibilityStore.allowedPackages() } returns emptySet()

        memoryDao = mockk(relaxed = true)
        val older = MemoryEntity(
            id = 1L, type = "FACT", content = "Любит тишины", createdAt = 100L
        )
        val newer = MemoryEntity(
            id = 2L, type = "SOMETHING", content = "Работает ночью", createdAt = 200L
        )
        every { memoryDao.getAllMemoriesStream() } returns flowOf(listOf(older, newer))

        factDao = mockk(relaxed = true)
        preferenceDao = mockk(relaxed = true)
        every { factDao.getAllFactsStream() } returns flowOf(
            listOf(
                FactEntity(factKey = "user.city", factValue = "Франкфурт"),
                FactEntity(factKey = "user.name", factValue = "Александр")
            )
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel() = PrivacyPolicyViewModel(
        policyProvider = provider,
        accessibilityStore = accessibilityStore,
        memoryDao = memoryDao,
        factDao = factDao,
        preferenceDao = preferenceDao
    )

    @Test
    fun `initial state merges provider settings and sorted accessibility lists`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(CallConfirmationPolicy.TRUSTED_ONLY, state.callPolicy)
        assertEquals(MessagingConfirmationPolicy.MONEY_ONLY, state.messagingPolicy)
        assertEquals(listOf("Мама"), state.trustedContacts)
        assertEquals(false, state.allowListMode)
        assertEquals(listOf("com.aaa", "com.zzz"), state.blockedPackages)
        assertTrue(state.allowedPackages.isEmpty())
    }

    @Test
    fun `memories stream sorts newest first and labels broken types as OTHER`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val memories = vm.uiState.value.memories
        assertEquals(2, memories.size)
        // Новее — первым; битый тип не валит список.
        assertEquals(2L, memories[0].id)
        assertEquals(MemoryTypeLabel.OTHER, memories[0].typeLabel)
        assertEquals(MemoryTypeLabel.FACT, memories[1].typeLabel)
    }

    @Test
    fun `facts stream maps and sorts by key`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        val facts = vm.uiState.value.facts
        assertEquals(listOf("user.city", "user.name"), facts.map { it.key })
        assertEquals("Александр", facts[1].value)
    }

    @Test
    fun `blank trusted contact is ignored`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.addTrustedContact("   ")
        advanceUntilIdle()

        assertEquals(listOf("Мама"), vm.uiState.value.trustedContacts)
    }

    @Test
    fun `duplicate contact is a no-op, new contact appends`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.addTrustedContact("Мама")
        advanceUntilIdle()
        assertEquals(listOf("Мама"), vm.uiState.value.trustedContacts)

        vm.addTrustedContact(" +49 170 000 00 00 ")
        advanceUntilIdle()
        assertEquals(listOf("Мама", "+49 170 000 00 00"), vm.uiState.value.trustedContacts)
    }

    @Test
    fun `policy taps write through to the provider`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.setCallPolicy(CallConfirmationPolicy.NEVER)
        vm.setMessagingPolicy(MessagingConfirmationPolicy.ALWAYS)
        advanceUntilIdle()

        assertEquals(CallConfirmationPolicy.NEVER, provider.current().callPolicy)
        assertEquals(MessagingConfirmationPolicy.ALWAYS, provider.current().messagingPolicy)
        assertEquals(CallConfirmationPolicy.NEVER, vm.uiState.value.callPolicy)
    }

    @Test
    fun `forget memory without key deletes only the shown row`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.forgetMemory(2L, keyName = null)
        advanceUntilIdle()

        coVerify(exactly = 1) { memoryDao.deleteMemoryById(2L) }
        coVerify(exactly = 0) { factDao.deleteFact(any()) }
        coVerify(exactly = 0) { preferenceDao.deletePreference(any()) }
    }

    @Test
    fun `forget memory with key removes the structured twins too`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.forgetMemory(1L, keyName = "user.name")
        advanceUntilIdle()

        coVerify(exactly = 1) { memoryDao.deleteMemoryById(1L) }
        coVerify(exactly = 1) { factDao.deleteFact("user.name") }
        coVerify(exactly = 1) { preferenceDao.deletePreference("user.name") }
    }

    @Test
    fun `remove fact deletes its memory twin by key`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.removeFact("user.name")
        advanceUntilIdle()

        coVerify(exactly = 1) { factDao.deleteFact("user.name") }
        coVerify(exactly = 1) { memoryDao.deleteMemoryByKey("user.name") }
        coVerify(exactly = 1) { preferenceDao.deletePreference("user.name") }
    }
}
