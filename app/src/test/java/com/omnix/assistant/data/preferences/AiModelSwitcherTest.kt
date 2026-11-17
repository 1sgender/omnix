package com.omnix.assistant.data.preferences

import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelActivationError
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.PlanEntitlements
import com.omnix.assistant.core.network.ServerHealthProbe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Выбор модели из чата и голосом: сохраняется только проверенный вариант. */
class AiModelSwitcherTest {

    private val choice = MutableStateFlow(AiModelChoice.CLOUD)
    private val store = mockk<AiModelChoiceStore> {
        every { this@mockk.choice } returns this@AiModelSwitcherTest.choice
        coEvery { set(any()) } answers { this@AiModelSwitcherTest.choice.value = firstArg() }
    }

    private fun switcher(proPerDay: Int, serverUp: Boolean = true): AiModelSwitcher {
        val health = mockk<ServerHealthProbe> { coEvery { isReachable(any()) } returns serverUp }
        val license = mockk<LicenseManager> {
            every { getLicenseInfo() } returns mockk<LicenseInfo> {
                every { entitlements } returns PlanEntitlements(dailyProAi = proPerDay)
            }
        }
        return AiModelSwitcher(store, mockk<LocalModelManager>(relaxed = true), health, license)
    }

    @Test
    fun `pro is saved and applies to the next messages when the plan has it`() = runBlocking {
        val result = switcher(proPerDay = 20).switchTo(AiModelChoice.PRO)
        assertEquals(AiModelActivation.Applied, result)
        assertEquals(AiModelChoice.PRO, choice.value)
        coVerify(exactly = 1) { store.set(AiModelChoice.PRO) }
    }

    @Test
    fun `pro is refused on free and the old choice stays`() = runBlocking {
        val result = switcher(proPerDay = 0).switchTo(AiModelChoice.PRO)
        assertEquals(AiModelActivation.Failed(AiModelActivationError.PRO_NOT_IN_PLAN), result)
        assertEquals(AiModelChoice.CLOUD, choice.value)
        coVerify(exactly = 0) { store.set(any()) }
    }

    @Test
    fun `unreachable server keeps the old choice`() = runBlocking {
        val result = switcher(proPerDay = 20, serverUp = false).switchTo(AiModelChoice.PRO)
        assertEquals(AiModelActivation.Failed(AiModelActivationError.SERVER_UNREACHABLE), result)
        assertEquals(AiModelChoice.CLOUD, choice.value)
    }

    @Test
    fun `choosing the current model does nothing`() = runBlocking {
        val result = switcher(proPerDay = 20).switchTo(AiModelChoice.CLOUD)
        assertEquals(AiModelActivation.Applied, result)
        coVerify(exactly = 0) { store.set(any()) }
    }
}
