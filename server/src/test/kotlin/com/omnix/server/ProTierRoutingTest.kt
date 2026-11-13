package com.omnix.server

import com.omnix.server.api.AiExecutionRequest
import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ApiPrivacyLevel
import com.omnix.server.auth.AuthSource
import com.omnix.server.auth.AuthenticatedClient
import com.omnix.server.auth.ClientTier
import com.omnix.server.config.AiGenerationConfig
import com.omnix.server.config.CircuitBreakerConfig
import com.omnix.server.config.ExecutionPolicyConfig
import com.omnix.server.config.PrivacyPolicyConfig
import com.omnix.server.config.ProTierConfig
import com.omnix.server.config.ProviderConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.license.PlanQuotaGate
import com.omnix.server.license.ProBudgetGuard
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.observability.Metrics
import com.omnix.server.provider.AiProvider
import com.omnix.server.provider.DefaultProviderSelectionPolicy
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.ProviderHealthTracker
import com.omnix.server.provider.ProviderId
import com.omnix.server.provider.ProviderManager
import com.omnix.server.provider.ProviderResult
import com.omnix.server.router.AiRouter
import com.omnix.server.router.QUOTA_SCOPE_PRO
import com.omnix.server.router.RouterResult
import com.omnix.server.usage.AiUsageRecord
import com.omnix.server.usage.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Уровни Flash/Pro: цепочки, квоты, понижение по бюджету, учёт корзины. */
class ProTierRoutingTest {

    private fun cfg(id: ProviderId, priority: Int) = ProviderConfig(
        id = id,
        enabled = true,
        priority = priority,
        apiKey = "k",
        model = "m",
        baseUrl = "https://example.invalid",
        connectTimeoutMs = 1000,
        requestTimeoutMs = 2000
    )

    // Claude намеренно имеет самый высокий приоритет: Flash всё равно не должен его брать.
    private val configs = mapOf(
        ProviderId.ANTHROPIC to cfg(ProviderId.ANTHROPIC, 0),
        ProviderId.GROQ to cfg(ProviderId.GROQ, 1),
        ProviderId.GEMINI to cfg(ProviderId.GEMINI, 2)
    )

    private class Rig(
        val router: AiRouter,
        val usage: InMemoryUsageRepository
    )

    private fun rig(
        providers: List<AiProvider>,
        usage: InMemoryUsageRepository = InMemoryUsageRepository(),
        pro: ProTierConfig = ProTierConfig()
    ): Rig {
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val manager = ProviderManager(
            providers = providers,
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics()
        )
        val router = AiRouter(
            providerManager = manager,
            usageRepository = usage,
            validation = ValidationConfig(),
            privacyPolicy = PrivacyPolicyConfig(),
            generation = AiGenerationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics(),
            planQuotaGate = PlanQuotaGate(usage),
            proBudgetGuard = ProBudgetGuard(usage, pro)
        )
        return Rig(router, usage)
    }

    private fun client(planId: String) = AuthenticatedClient(
        clientId = "acct-1",
        tier = ClientTier.PRO,
        accountId = UUID.randomUUID(),
        authSource = AuthSource.LICENSE_TOKEN,
        planId = planId
    )

    private fun request(tier: String?) = AiExecutionRequest(
        text = "Расскажи, как приготовить плов",
        privacyLevel = ApiPrivacyLevel.NORMAL,
        tier = tier
    )

    private fun usageRecord(
        feature: String,
        provider: String = "ANTHROPIC",
        at: Instant = Instant.now(),
        inputTokens: Long? = 10,
        outputTokens: Long? = 10
    ) = AiUsageRecord(
        requestId = UUID.randomUUID().toString(),
        clientId = "acct-1",
        provider = provider,
        model = "m",
        latencyMs = 1,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        totalTokens = null,
        success = true,
        errorCode = null,
        promptChars = 1,
        responseChars = 1,
        timestamp = at,
        feature = feature
    )

    @Test
    fun `pro request on pro plan is answered by claude and counted in the pro bucket`() = runBlocking {
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC, "от claude")
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI, "от gemini")
        val rig = rig(listOf(claude, gemini))

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertTrue(result is RouterResult.Success)
        result as RouterResult.Success
        assertEquals("от claude", result.text)
        assertEquals("pro", result.tier)
        assertEquals(0, gemini.calls.get())
        assertEquals("pro_ai", rig.usage.all().single().feature)
    }

    @Test
    fun `pro falls back to gemini as flash and does not spend pro quota`() = runBlocking {
        val claude = FakeAiProvider.failing(ProviderId.ANTHROPIC, ProviderFailureKind.SERVER_ERROR, 529)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI, "от gemini")
        val rig = rig(listOf(claude, gemini))

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertTrue(result is RouterResult.Success)
        result as RouterResult.Success
        assertEquals("от gemini", result.text)
        assertEquals("flash", result.tier)
        assertEquals("base_ai", rig.usage.all().single { it.success }.feature)
    }

    @Test
    fun `free plan cannot use pro and no provider is called`() = runBlocking {
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI)
        val rig = rig(listOf(claude, gemini))

        val result = rig.router.execute(request("pro"), client("free"), "req-1")

        assertTrue(result is RouterResult.Failure)
        assertEquals(ApiErrorCode.QUOTA_EXCEEDED, (result as RouterResult.Failure).code)
        // Приложение по этим полям пишет «обновится через N ч» и предлагает Flash.
        assertEquals(QUOTA_SCOPE_PRO, result.quotaScope)
        assertTrue((result.retryAfterSeconds ?: 0L) > 0L)
        assertEquals(0, claude.calls.get())
        assertEquals(0, gemini.calls.get())
    }

    @Test
    fun `flash request never uses claude even when it has top priority`() = runBlocking {
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC, "от claude")
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "от groq")
        val rig = rig(listOf(claude, groq))

        val result = rig.router.execute(request(null), client("pro"), "req-1")

        assertTrue(result is RouterResult.Success)
        result as RouterResult.Success
        assertEquals("от groq", result.text)
        assertEquals("flash", result.tier)
        assertEquals(0, claude.calls.get())
    }

    @Test
    fun `unknown tier value behaves as flash`() = runBlocking {
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC)
        val groq = FakeAiProvider.ok(ProviderId.GROQ)
        val rig = rig(listOf(claude, groq))

        val result = rig.router.execute(request("ultra"), client("pro"), "req-1")

        assertTrue(result is RouterResult.Success)
        assertEquals(0, claude.calls.get())
    }

    @Test
    fun `pro daily quota is enforced`() = runBlocking {
        val usage = InMemoryUsageRepository()
        repeat(20) { usage.record(usageRecord("pro_ai")) }
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC)
        val rig = rig(listOf(claude), usage)

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertEquals(ApiErrorCode.QUOTA_EXCEEDED, (result as RouterResult.Failure).code)
        assertEquals(0, claude.calls.get())
    }

    @Test
    fun `pro monthly quota is enforced even when the day is free`() = runBlocking {
        val usage = InMemoryUsageRepository()
        // 250 ответов за этот месяц, но ни одного сегодня: суточная квота свободна.
        val monthStart = java.time.LocalDate.now(Clock.systemUTC()).withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        val dayStart = java.time.LocalDate.now(Clock.systemUTC())
            .atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
        if (!monthStart.isBefore(dayStart)) return@runBlocking // 1-е число: «вчера» в этом месяце нет
        repeat(250) { usage.record(usageRecord("pro_ai", at = dayStart.minusSeconds(60).coerceAtLeast(monthStart))) }
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC)
        val rig = rig(listOf(claude), usage)

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertEquals(ApiErrorCode.QUOTA_EXCEEDED, (result as RouterResult.Failure).code)
    }

    @Test
    fun `budget threshold downgrades pro to flash without calling claude`() = runBlocking {
        val usage = InMemoryUsageRepository()
        // 1M входных + 800K выходных = 2 + 8 = $10 при бюджете $10 и пороге 90%.
        usage.record(usageRecord("pro_ai", inputTokens = 1_000_000, outputTokens = 800_000).copy(clientId = "other"))
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC)
        val gemini = FakeAiProvider.ok(ProviderId.GEMINI, "от gemini")
        val rig = rig(
            listOf(claude, gemini),
            usage,
            ProTierConfig(monthlyBudgetUsd = 10.0, downgradeAtPercent = 90)
        )

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertTrue(result is RouterResult.Success)
        result as RouterResult.Success
        assertEquals("flash", result.tier)
        assertEquals("от gemini", result.text)
        assertEquals(0, claude.calls.get())
    }

    @Test
    fun `budget below threshold keeps pro`() = runBlocking {
        val usage = InMemoryUsageRepository()
        usage.record(usageRecord("pro_ai", inputTokens = 1_000_000, outputTokens = 100_000).copy(clientId = "other"))
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC, "от claude")
        val rig = rig(
            listOf(claude),
            usage,
            ProTierConfig(monthlyBudgetUsd = 10.0, downgradeAtPercent = 90)
        )

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertEquals("pro", (result as RouterResult.Success).tier)
    }

    @Test
    fun `zero budget means unlimited`() = runBlocking {
        val usage = InMemoryUsageRepository()
        usage.record(usageRecord("pro_ai", inputTokens = 900_000_000, outputTokens = 900_000_000).copy(clientId = "other"))
        val claude = FakeAiProvider.ok(ProviderId.ANTHROPIC, "от claude")
        val rig = rig(listOf(claude), usage, ProTierConfig(monthlyBudgetUsd = 0.0))

        val result = rig.router.execute(request("pro"), client("pro"), "req-1")

        assertEquals("pro", (result as RouterResult.Success).tier)
    }

    private fun Instant.coerceAtLeast(min: Instant): Instant = if (isBefore(min)) min else this
}
