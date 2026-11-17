package com.omnix.assistant.presentation.settings

import com.omnix.assistant.core.license.ClientQuotaFeature
import com.omnix.assistant.core.license.PlanAiUsage
import com.omnix.assistant.core.license.PlanEntitlements
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlanMetersTest {

    private val pro = PlanEntitlements(
        dailyBaseAi = 150,
        dailyWebSearch = 30,
        dailyTranslationUnits = 100,
        dailyEarMinutes = 45
    )

    @Test
    fun `limits come from entitlements and remaining from the gate`() {
        val meters = buildPlanMeters(pro, null) { feature ->
            when (feature) {
                ClientQuotaFeature.WEB_SEARCH -> 12
                ClientQuotaFeature.TRANSLATION_UNITS -> 100
                ClientQuotaFeature.EAR_MINUTES -> 0
                else -> -1
            }
        }
        assertEquals(listOf(150, 30, 100, 45), meters.map { it.limit })
        assertEquals(listOf(null, 12, 100, 0), meters.map { it.left })
    }

    @Test
    fun `flash remaining uses server usage and never goes negative`() {
        val usage = PlanAiUsage(usedToday = PlanAiUsage.UsedToday(baseAi = 200))
        val flash = buildPlanMeters(pro, usage) { 0 }.first { it.kind == PlanMeterKind.FLASH }
        assertEquals(0, flash.left)
        val partial = buildPlanMeters(pro, PlanAiUsage(usedToday = PlanAiUsage.UsedToday(baseAi = 50))) { 0 }
            .first { it.kind == PlanMeterKind.FLASH }
        assertEquals(100, partial.left)
    }

    @Test
    fun `fraction is null when unknown and clamped otherwise`() {
        assertNull(PlanMeter(PlanMeterKind.FLASH, 10, null).fraction)
        assertNull(PlanMeter(PlanMeterKind.SEARCH, 0, 0).fraction)
        assertEquals(0.5f, PlanMeter(PlanMeterKind.SEARCH, 10, 5).fraction!!, 0.001f)
    }
}
