package com.jarvis.server.api

import com.jarvis.server.license.PlanCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Маппинг PlanLimits → validate-ответ. Чистый unit-тест без Postgres:
 * клиентский enforcement зависит от точности этих полей.
 */
class EntitlementsDtoTest {

    @Test
    fun `free entitlements match approved matrix`() {
        val dto = PlanCatalog.forPlanId(PlanCatalog.FREE).toDto()

        assertEquals(30, dto.dailyVoiceAi)
        assertEquals(50, dto.dailyBaseAi)
        assertEquals(5, dto.dailyAgentActions)
        assertEquals(10, dto.dailyWebSearch)
        assertEquals(15, dto.dailyTranslationUnits)
        assertEquals(10, dto.dailyEarMinutes)
        assertEquals(3, dto.maxAutomations)
        assertFalse(dto.screenReading)
        assertFalse(dto.uiControl)
        assertFalse(dto.priorityRouting)
        assertFalse(dto.premiumModels)
        assertEquals(1, dto.maxClips)
    }

    @Test
    fun `omnix entitlements match approved matrix`() {
        val dto = PlanCatalog.forPlanId(PlanCatalog.OMNIX).toDto()

        assertEquals(500, dto.dailyVoiceAi)
        assertEquals(500, dto.dailyBaseAi)
        assertEquals(100, dto.dailyAgentActions)
        assertEquals(100, dto.dailyWebSearch)
        assertEquals(300, dto.dailyTranslationUnits)
        assertEquals(120, dto.dailyEarMinutes)
        assertEquals(Int.MAX_VALUE, dto.maxAutomations)
        assertTrue(dto.screenReading)
        assertTrue(dto.uiControl)
        assertFalse(dto.priorityRouting)
        assertFalse(dto.premiumModels)
        assertEquals(1, dto.maxClips)
    }

    @Test
    fun `pro entitlements match approved matrix`() {
        val dto = PlanCatalog.forPlanId(PlanCatalog.PRO).toDto()

        assertEquals(2000, dto.dailyVoiceAi)
        assertEquals(2000, dto.dailyBaseAi)
        assertEquals(500, dto.dailyAgentActions)
        assertEquals(500, dto.dailyWebSearch)
        assertEquals(1000, dto.dailyTranslationUnits)
        assertEquals(600, dto.dailyEarMinutes)
        assertEquals(Int.MAX_VALUE, dto.maxAutomations)
        assertTrue(dto.screenReading)
        assertTrue(dto.uiControl)
        assertTrue(dto.priorityRouting)
        assertTrue(dto.premiumModels)
        assertEquals(2, dto.maxClips)
    }

    @Test
    fun `unknown plan maps to free entitlements`() {
        val dto = PlanCatalog.forPlanId("earclip-monthly").toDto()
        val free = PlanCatalog.forPlanId(PlanCatalog.FREE).toDto()

        assertEquals(free, dto)
    }
}
