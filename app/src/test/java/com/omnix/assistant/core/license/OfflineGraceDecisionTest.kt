package com.omnix.assistant.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистое решение офлайн-льготы: недоступность сервера — не вердикт против
 * лицензии (решение владельца 2026-09-21).
 */
class OfflineGraceDecisionTest {

    @Test
    fun `unexpired activated cache grants offline grace`() {
        val cached = LicenseInfo(
            isActivated = true,
            planId = "phase3-pro",
            expiryDate = 100L,
            isExpired = false
        )

        val decision = offlineGraceDecision(cached, LicenseRefreshResult.ServiceUnavailable)

        assertTrue(decision is LicenseRefreshResult.OfflineGrace)
        assertEquals("phase3-pro", (decision as LicenseRefreshResult.OfflineGrace).licenseInfo.planId)
    }

    @Test
    fun `expired cache keeps the failure result`() {
        val cached = LicenseInfo(isActivated = true, isExpired = true)

        val decision = offlineGraceDecision(cached, LicenseRefreshResult.ServiceUnavailable)

        assertEquals(LicenseRefreshResult.ServiceUnavailable, decision)
    }

    @Test
    fun `never activated cache keeps the failure result`() {
        val cached = LicenseInfo(isActivated = false, isExpired = false)

        val decision = offlineGraceDecision(cached, LicenseRefreshResult.RateLimited)

        assertEquals(LicenseRefreshResult.RateLimited, decision)
    }
}
