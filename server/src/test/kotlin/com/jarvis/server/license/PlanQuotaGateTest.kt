package com.jarvis.server.license

import com.jarvis.server.auth.AuthSource
import com.jarvis.server.auth.AuthenticatedClient
import com.jarvis.server.auth.ClientTier
import com.jarvis.server.usage.AiUsageRecord
import com.jarvis.server.usage.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Дневные квоты тарифа: гейт + подсчёт корзин.
 * Чистый unit-тест без Postgres (InMemoryUsageRepository).
 */
class PlanQuotaGateTest {

    private val fixedNow = Instant.parse("2026-09-13T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private fun record(
        clientId: String,
        feature: String?,
        success: Boolean = true,
        at: Instant = fixedNow
    ) = AiUsageRecord(
        requestId = UUID.randomUUID().toString(),
        clientId = clientId,
        provider = "groq",
        model = "test",
        latencyMs = 10,
        inputTokens = 5,
        outputTokens = 5,
        totalTokens = 10,
        success = success,
        errorCode = null,
        promptChars = 10,
        responseChars = 10,
        timestamp = at,
        feature = feature
    )

    private fun client(planId: String?) = AuthenticatedClient(
        clientId = "acct-1",
        tier = ClientTier.PRO,
        accountId = UUID.randomUUID(),
        authSource = AuthSource.LICENSE_TOKEN,
        planId = planId
    )

    @Test
    fun `free voice quota allows under limit`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(29) { usage.record(record("acct-1", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)

        assertTrue(gate.check(client("free"), PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Allowed)
    }

    @Test
    fun `free voice quota limits at boundary`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(30) { usage.record(record("acct-1", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)

        val verdict = gate.check(client("free"), PlanFeature.VOICE_AI)
        assertTrue(verdict is PlanQuotaGate.Verdict.Limited)
        val limited = verdict as PlanQuotaGate.Verdict.Limited
        assertEquals(30, limited.limit)
        assertEquals(30, limited.used)
        assertTrue(limited.retryAfterSeconds > 0)
    }

    @Test
    fun `buckets are independent per feature`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(30) { usage.record(record("acct-1", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)

        // Голос исчерпан, чат свободен.
        assertTrue(gate.check(client("free"), PlanFeature.BASE_AI) is PlanQuotaGate.Verdict.Allowed)
    }

    @Test
    fun `failed requests do not consume quota`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(100) { usage.record(record("acct-1", "voice_ai", success = false)) }
        val gate = PlanQuotaGate(usage, clock)

        assertTrue(gate.check(client("free"), PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Allowed)
    }

    @Test
    fun `legacy rows without feature count conservatively`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(30) { usage.record(record("acct-1", null)) }
        val gate = PlanQuotaGate(usage, clock)

        assertTrue(gate.check(client("free"), PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Limited)
        assertTrue(gate.check(client("free"), PlanFeature.BASE_AI) is PlanQuotaGate.Verdict.Allowed)
    }

    @Test
    fun `pro quota is larger than free`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(100) { usage.record(record("acct-1", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)

        assertTrue(gate.check(client("pro"), PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Allowed)
        assertTrue(gate.check(client("free"), PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Limited)
    }

    @Test
    fun `unknown plan falls back to free limits`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(30) { usage.record(record("acct-1", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)

        assertTrue(
            gate.check(client("earclip-monthly"), PlanFeature.VOICE_AI)
                is PlanQuotaGate.Verdict.Limited
        )
    }

    @Test
    fun `static operator tokens bypass quotas`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(5000) { usage.record(record("ops", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)
        val ops = AuthenticatedClient(
            clientId = "ops",
            tier = ClientTier.INTERNAL,
            authSource = AuthSource.STATIC,
            planId = null
        )

        assertTrue(gate.check(ops, PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Allowed)
    }

    @Test
    fun `static free token is still quota-capped`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(30) { usage.record(record("test-free", "voice_ai")) }
        val gate = PlanQuotaGate(usage, clock)
        val free = AuthenticatedClient(
            clientId = "test-free",
            tier = ClientTier.FREE,
            authSource = AuthSource.STATIC,
            planId = null
        )

        assertTrue(gate.check(free, PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Limited)
    }

    @Test
    fun `yesterday usage does not count`() = runBlocking {
        val usage = InMemoryUsageRepository()
        val yesterday = LocalDate.now(clock).minusDays(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600)
        repeat(100) { usage.record(record("acct-1", "voice_ai", at = yesterday)) }
        val gate = PlanQuotaGate(usage, clock)

        assertTrue(gate.check(client("free"), PlanFeature.VOICE_AI) is PlanQuotaGate.Verdict.Allowed)
    }
}
