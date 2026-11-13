package com.omnix.assistant.agent.decision

import com.omnix.assistant.core.license.PlanEntitlements
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Выбор Local / Flash / Pro: доступность Pro по тарифу и что уходит серверу. */
class AiTierTest {

    private val free = PlanEntitlements.FREE
    private val pro = PlanEntitlements(dailyVoiceAi = 300, dailyBaseAi = 300, dailyProAi = 20, monthlyProAi = 250)
    private val max = PlanEntitlements(dailyVoiceAi = 1000, dailyBaseAi = 1000, dailyProAi = 50, monthlyProAi = 600)

    @Test
    fun `pro is locked on free and open on pro and max`() {
        assertFalse(AiProAccess.isAvailable(free))
        assertTrue(AiProAccess.isAvailable(pro))
        assertTrue(AiProAccess.isAvailable(max))
    }

    @Test
    fun `pro is requested only when chosen and in the plan`() {
        assertEquals(AiTier.PRO, AiProAccess.requestedTier(AiModelChoice.PRO, pro))
        assertEquals(AiTier.PRO, AiProAccess.requestedTier(AiModelChoice.PRO, max))
        // Тариф понизили, а выбор остался Pro — просим Flash, а не отказ.
        assertEquals(AiTier.FLASH, AiProAccess.requestedTier(AiModelChoice.PRO, free))
        for (choice in listOf(AiModelChoice.AUTO, AiModelChoice.ON_DEVICE, AiModelChoice.CLOUD)) {
            assertEquals("choice=$choice", AiTier.FLASH, AiProAccess.requestedTier(choice, max))
        }
    }

    @Test
    fun `pro and flash skip the phone model while auto and local do not`() {
        assertTrue(AiModelChoice.PRO.cloudOnly)
        assertTrue(AiModelChoice.CLOUD.cloudOnly)
        assertFalse(AiModelChoice.AUTO.cloudOnly)
        assertFalse(AiModelChoice.ON_DEVICE.cloudOnly)
    }

    @Test
    fun `pro choice survives restart and needs a pro plan`() {
        assertEquals(AiModelChoice.PRO, AiModelChoice.fromStorageKey("pro"))
        assertEquals(AiModelRequirement.PRO_PLAN, AiModelChoiceCatalog.requirementOf(AiModelChoice.PRO))
        // Каталог перечисляет каждый вариант ровно один раз.
        assertEquals(AiModelChoice.entries.toSet(), AiModelChoiceCatalog.options.map { it.first }.toSet())
    }

    @Test
    fun `served tier is pro only when the server says so`() {
        assertEquals(AiTier.PRO, AiTier.fromWire("pro"))
        assertEquals(AiTier.PRO, AiTier.fromWire(" PRO "))
        assertEquals(AiTier.FLASH, AiTier.fromWire("flash"))
        assertEquals(AiTier.FLASH, AiTier.fromWire(null))
        assertEquals(AiTier.FLASH, AiTier.fromWire("ultra"))
    }

    @Test
    fun `old server without pro fields gives fail closed zero`() {
        val json = Json { ignoreUnknownKeys = true }
        val old = json.decodeFromString(PlanEntitlements.serializer(), """{"daily_voice_ai":300,"daily_base_ai":300}""")
        assertEquals(0, old.dailyProAi)
        assertEquals(0, old.monthlyProAi)
        assertFalse(AiProAccess.isAvailable(old))

        val fresh = json.decodeFromString(
            PlanEntitlements.serializer(),
            """{"daily_voice_ai":1000,"daily_base_ai":1000,"daily_pro_ai":50,"monthly_pro_ai":600}"""
        )
        assertEquals(50, fresh.dailyProAi)
        assertEquals(600, fresh.monthlyProAi)
        assertTrue(AiProAccess.isAvailable(fresh))
    }

    @Test
    fun `default tier preference asks for flash and has no pro`() {
        assertEquals(AiTier.FLASH, AiTierPreference.FLASH.requestedTier())
        assertFalse(AiTierPreference.FLASH.proInPlan())
    }
}
