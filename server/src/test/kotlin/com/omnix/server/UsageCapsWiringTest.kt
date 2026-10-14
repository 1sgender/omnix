package com.omnix.server

import com.omnix.server.api.AiExecutionRequest
import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ApiPrivacyLevel
import com.omnix.server.auth.AuthenticatedClient
import com.omnix.server.auth.ClientTier
import com.omnix.server.config.AiGenerationConfig
import com.omnix.server.config.CircuitBreakerConfig
import com.omnix.server.config.ExecutionPolicyConfig
import com.omnix.server.config.PrivacyPolicyConfig
import com.omnix.server.config.ProviderConfig
import com.omnix.server.config.TokenCostConfig
import com.omnix.server.config.UsageLimitConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.observability.Metrics
import com.omnix.server.provider.AiProvider
import com.omnix.server.provider.DefaultProviderSelectionPolicy
import com.omnix.server.provider.ProviderHealthTracker
import com.omnix.server.provider.ProviderId
import com.omnix.server.provider.ProviderManager
import com.omnix.server.router.AiRouter
import com.omnix.server.router.RouterResult
import com.omnix.server.usage.AsyncUsageTracker
import com.omnix.server.usage.InMemoryUsageRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit OMX-003: the daily request/token caps are checked through the real
 * [AiRouter] path. AsyncUsageTrackerTest only calls accountFor() directly, which
 * is how the caps could be dead in production while their tests stayed green.
 */
class UsageCapsWiringTest {

    private val client = AuthenticatedClient(clientId = "client-a", tier = ClientTier.FREE)
    private val request = AiExecutionRequest(text = "hello", privacyLevel = ApiPrivacyLevel.NORMAL)

    private fun router(limits: UsageLimitConfig): AiRouter {
        // FakeAiProvider.ok reports 10 input + 20 output = 30 tokens per answer.
        val provider = FakeAiProvider.ok(ProviderId.GROQ)
        val configs = mapOf(
            ProviderId.GROQ to ProviderConfig(
                id = ProviderId.GROQ, enabled = true, priority = 1, apiKey = "k",
                model = "test-model", baseUrl = "https://example.invalid",
                connectTimeoutMs = 1000, requestTimeoutMs = 2000
            )
        )
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val metrics = Metrics()
        val logger = ConsoleStructuredLogger(sink = {})
        val manager = ProviderManager(
            providers = listOf<AiProvider>(provider),
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
            logger = logger,
            metrics = metrics
        )
        val tracker = AsyncUsageTracker(
            repository = InMemoryUsageRepository(),
            limits = limits,
            costs = TokenCostConfig(),
            logger = logger,
            metrics = metrics
        )
        return AiRouter(
            providerManager = manager,
            usageTracker = tracker,
            validation = ValidationConfig(),
            privacyPolicy = PrivacyPolicyConfig(),
            generation = AiGenerationConfig(),
            logger = logger,
            metrics = metrics
        )
    }

    @Test
    fun `daily request cap stops a client after the cap is reached`() = runBlocking {
        val router = router(UsageLimitConfig(perDayRequests = 2, perDayTokens = 0, perDayCostUsd = 0.0))

        assertTrue(router.execute(request, client, "r1") is RouterResult.Success)
        assertTrue(router.execute(request, client, "r2") is RouterResult.Success)

        val third = router.execute(request, client, "r3")
        assertTrue(third is RouterResult.Failure)
        assertEquals(ApiErrorCode.RATE_LIMITED, (third as RouterResult.Failure).code)
    }

    @Test
    fun `daily token cap stops a client once the reported tokens reach the cap`() = runBlocking {
        // 30 tokens per answer, cap 50: the second answer takes the total to 60.
        val router = router(UsageLimitConfig(perDayRequests = 0, perDayTokens = 50, perDayCostUsd = 0.0))

        assertTrue(router.execute(request, client, "t1") is RouterResult.Success)
        assertTrue(router.execute(request, client, "t2") is RouterResult.Success)

        val third = router.execute(request, client, "t3")
        assertTrue(third is RouterResult.Failure)
        assertEquals(ApiErrorCode.RATE_LIMITED, (third as RouterResult.Failure).code)
    }

    @Test
    fun `another client is not affected by the first client's cap`() = runBlocking {
        val router = router(UsageLimitConfig(perDayRequests = 1, perDayTokens = 0, perDayCostUsd = 0.0))
        val other = AuthenticatedClient(clientId = "client-b", tier = ClientTier.FREE)

        assertTrue(router.execute(request, client, "a1") is RouterResult.Success)
        assertTrue(router.execute(request, client, "a2") is RouterResult.Failure)
        assertTrue(router.execute(request, other, "b1") is RouterResult.Success)
    }
}
