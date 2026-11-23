package com.omnix.server

import com.omnix.server.admin.AdminModelAccess
import com.omnix.server.api.AiExecutionRequest
import com.omnix.server.api.ApiPrivacyLevel
import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.AuthSource
import com.omnix.server.auth.AuthenticatedClient
import com.omnix.server.auth.Authenticator
import com.omnix.server.auth.ClientTier
import com.omnix.server.config.AiGenerationConfig
import com.omnix.server.config.CircuitBreakerConfig
import com.omnix.server.config.ExecutionPolicyConfig
import com.omnix.server.config.PrivacyPolicyConfig
import com.omnix.server.config.ProviderConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.observability.Metrics
import com.omnix.server.provider.DefaultProviderSelectionPolicy
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.HttpTransportResponse
import com.omnix.server.provider.ProviderHealthTracker
import com.omnix.server.provider.ProviderId
import com.omnix.server.provider.ProviderManager
import com.omnix.server.provider.TransportException
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.router.AiRouter
import com.omnix.server.router.RouterResult
import com.omnix.server.usage.InMemoryUsageRepository
import com.omnix.server.venice.VeniceClient
import com.omnix.server.venice.VeniceConfig
import com.omnix.server.venice.VeniceFailureKind
import com.omnix.server.venice.VeniceHttpHandler
import com.omnix.server.venice.VeniceMessage
import com.omnix.server.venice.VeniceResult
import com.omnix.server.venice.VeniceService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Venice AI только для админа (решение владельца): доступ, выключение без
 * ключа, маскирование ключа, повторы, потолки и невидимость для автовыбора.
 * Реальных вызовов Venice нет: сеть заменена фейковым транспортом.
 */
class VeniceAdminOnlyTest {

    private val secret = "VENICE_INFERENCE_KEY_unit-test-not-a-real-key-123"
    private val adminSecret = "VENICE_ADMIN_KEY_unit-test-not-a-real-admin-key-456"

    private val modelsBody = """
        {"object":"list","data":[
          {"id":"venice-uncensored-1-2","type":"text","model_spec":{"name":"Venice Uncensored","privacy":"private",
            "availableContextTokens":32768,"pricing":{"input":{"usd":0.5,"diem":0.5},"output":{"usd":2.0,"diem":2.0}}}},
          {"id":"e2ee-qwen-abliterated","type":"text","model_spec":{"name":"E2EE Qwen","privacy":"e2ee",
            "availableContextTokens":131072,"pricing":{"input":{"usd":1.0},"output":{"usd":3.0}}}},
          {"id":"llama-3.3-70b","type":"text","model_spec":{"name":"Llama","privacy":"anonymized","availableContextTokens":65536,
            "pricing":{"input":{"usd":0.7},"output":{"usd":2.8}}}},
          {"id":"flux-image","type":"image","model_spec":{"name":"Flux"}},
          {"id":"old-offline","type":"text","model_spec":{"name":"Old","offline":true}}
        ]}
    """.trimIndent()

    private val chatBody = """
        {"id":"x","model":"llama-3.3-70b","choices":[{"message":{"role":"assistant","content":"привет от venice"}}],
         "usage":{"prompt_tokens":1000,"completion_tokens":500,"total_tokens":1500}}
    """.trimIndent()

    private class Call(val method: String, val url: String, val headers: Map<String, String>, val body: String)

    /** Транспорт по сценарию: ответы выдаются по порядку для пути, последний повторяется. */
    private class FakeVeniceTransport : HttpTransport {
        val calls = CopyOnWriteArrayList<Call>()
        private val scripts = mutableMapOf<String, MutableList<Any>>()

        fun script(pathPart: String, vararg answers: Any) {
            scripts[pathPart] = answers.toMutableList()
        }

        fun count(pathPart: String) = calls.count { it.url.contains(pathPart) }

        private fun answer(method: String, url: String, headers: Map<String, String>, body: String): HttpTransportResponse {
            calls += Call(method, url, headers, body)
            val key = scripts.keys.firstOrNull { url.contains(it) } ?: error("unscripted $url")
            val queue = scripts.getValue(key)
            val next = if (queue.size > 1) queue.removeAt(0) else queue[0]
            if (next is TransportException) throw next
            return next as HttpTransportResponse
        }

        override suspend fun post(
            url: String, headers: Map<String, String>, body: String, connectTimeoutMs: Long, requestTimeoutMs: Long
        ) = answer("POST", url, headers, body)

        override suspend fun get(
            url: String, headers: Map<String, String>, connectTimeoutMs: Long, requestTimeoutMs: Long
        ) = answer("GET", url, headers, "")
    }

    private val logLines = CopyOnWriteArrayList<String>()
    private val logger = ConsoleStructuredLogger(sink = { logLines += it })
    private var now = 1_700_000_000_000L
    private val sleeps = mutableListOf<Long>()

    private fun config(
        key: String? = secret,
        rpm: Int = 20,
        maxTokens: Int = 1024,
        retries: Int = 3,
        admin: String? = null
    ) = VeniceConfig(apiKey = key, adminApiKey = admin, requestsPerMinute = rpm, maxOutputTokens = maxTokens, maxRetries = retries)

    private fun client(cfg: VeniceConfig, transport: HttpTransport) =
        VeniceClient(cfg, transport, logger, clock = { now }, sleeper = { sleeps += it })

    private fun service(cfg: VeniceConfig, transport: HttpTransport) =
        VeniceService(cfg, client(cfg, transport), logger, Metrics(), clock = { now })

    private fun ok(body: String, headers: Map<String, String> = emptyMap()) = HttpTransportResponse(200, body, headers)

    private fun transportWithModels() = FakeVeniceTransport().apply {
        script("/models", ok(modelsBody))
        script("/chat/completions", ok(chatBody))
    }

    // ---------------------------------------------------------------- модели

    @Test
    fun `models list keeps only live text models with privacy price and uncensored flag, cached`() = runBlocking {
        val t = transportWithModels()
        val c = client(config(), t)

        val first = (c.listModels() as VeniceResult.Success).value
        assertEquals(listOf("e2ee-qwen-abliterated", "llama-3.3-70b", "venice-uncensored-1-2"), first.map { it.id })
        val unc = first.first { it.id == "venice-uncensored-1-2" }
        assertEquals("private", unc.privacy)
        assertEquals(32768L, unc.contextTokens)
        assertEquals(0.5, unc.inputUsdPerMillion!!, 1e-9)
        assertEquals(2.0, unc.outputUsdPerMillion!!, 1e-9)
        assertTrue(unc.uncensored)
        assertEquals("e2ee", first.first { it.id == "e2ee-qwen-abliterated" }.privacy)
        assertTrue(first.first { it.id == "e2ee-qwen-abliterated" }.uncensored)
        assertFalse(first.first { it.id == "llama-3.3-70b" }.uncensored)

        c.listModels()
        assertEquals("кэш: второй список без запроса", 1, t.count("/models"))
        assertTrue(t.calls.single().url.endsWith("/models?type=text"))
        assertTrue(t.calls.single().url.startsWith("https://api.venice.ai/api/v1"))
    }

    // ---------------------------------------------------------------- выключен без ключа

    @Test
    fun `without a key venice is disabled and never touches the network`() = runBlocking {
        val t = transportWithModels()
        listOf(null, "", "   ").forEach { blank ->
            val cfg = config(key = blank)
            assertFalse(cfg.enabled)
            val svc = service(cfg, t)
            assertFalse(svc.enabled)
            val r = svc.chat("r", "llama-3.3-70b", listOf(VeniceMessage("user", "hi")), 100, 0.5)
            assertEquals(VeniceFailureKind.DISABLED, (r as VeniceResult.Failure).kind)
            assertEquals(VeniceFailureKind.DISABLED, (svc.models() as VeniceResult.Failure).kind)
        }
        assertFalse(VeniceConfig.fromEnv { null }.enabled)
        assertFalse(VeniceConfig.fromEnv { if (it == "VENICE_API_KEY") "" else null }.enabled)
        assertTrue(VeniceConfig.fromEnv { if (it == "VENICE_API_KEY") secret else null }.enabled)
        assertEquals(0, t.calls.size)
    }

    @Test
    fun `env defaults are conservative`() {
        val cfg = VeniceConfig.fromEnv { null }
        assertEquals(20, cfg.requestsPerMinute)
        assertEquals(1024, cfg.maxOutputTokens)
        assertEquals("https://api.venice.ai/api/v1", cfg.baseUrl)
        val tuned = VeniceConfig.fromEnv { if (it == "VENICE_REQUESTS_PER_MINUTE") "5" else null }
        assertEquals(5, tuned.requestsPerMinute)
    }

    // ---------------------------------------------------------------- повторы

    @Test
    fun `429 is retried no earlier than the reset header, then succeeds`() = runBlocking {
        val t = FakeVeniceTransport()
        val resetEpochSec = now / 1000 + 4 // через 4 секунды
        t.script(
            "/chat/completions",
            HttpTransportResponse(429, "{}", mapOf("x-ratelimit-reset-requests" to resetEpochSec.toString())),
            ok(chatBody)
        )
        val r = client(config(), t).chat("llama-3.3-70b", listOf(VeniceMessage("user", "hi")), 100, 0.5)
        assertTrue(r is VeniceResult.Success)
        assertEquals(2, t.calls.size)
        assertEquals("ждём не меньше сброса лимита", listOf(4000L), sleeps)
    }

    @Test
    fun `5xx is retried with exponential backoff`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script(
            "/chat/completions",
            HttpTransportResponse(500, "x"), HttpTransportResponse(503, "x"), ok(chatBody)
        )
        val r = client(config(), t).chat("llama-3.3-70b", listOf(VeniceMessage("user", "hi")), 100, 0.5)
        assertTrue(r is VeniceResult.Success)
        assertEquals(listOf(500L, 1000L), sleeps)
        assertEquals(3, t.calls.size)
    }

    @Test
    fun `retries stop after the configured number and report upstream failure`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script("/chat/completions", HttpTransportResponse(503, "x"))
        val r = client(config(retries = 2), t).chat("m", listOf(VeniceMessage("user", "hi")), 100, 0.5)
        assertEquals(VeniceFailureKind.UPSTREAM, (r as VeniceResult.Failure).kind)
        assertEquals(3, t.calls.size)
    }

    @Test
    fun `network failure is retried`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script("/chat/completions", TransportException(ProviderFailureKind.CONNECTION, "IOException"), ok(chatBody))
        val r = client(config(), t).chat("m", listOf(VeniceMessage("user", "hi")), 100, 0.5)
        assertTrue(r is VeniceResult.Success)
        assertEquals(2, t.calls.size)
    }

    @Test
    fun `429 with a reset too far away is not waited for`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script("/chat/completions", HttpTransportResponse(429, "{}", mapOf("retry-after" to "3600")))
        val r = client(config(), t).chat("m", listOf(VeniceMessage("user", "hi")), 100, 0.5)
        val failure = r as VeniceResult.Failure
        assertEquals(VeniceFailureKind.RATE_LIMITED, failure.kind)
        assertEquals(3600L, failure.retryAfterSeconds)
        assertEquals(1, t.calls.size)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `401 400 and 402 are never retried`() = runBlocking {
        listOf(401 to VeniceFailureKind.AUTH, 400 to VeniceFailureKind.BAD_REQUEST, 402 to VeniceFailureKind.PAYMENT)
            .forEach { (status, kind) ->
                val t = FakeVeniceTransport()
                t.script("/chat/completions", HttpTransportResponse(status, "{\"error\":\"nope\"}"))
                val r = client(config(), t).chat("m", listOf(VeniceMessage("user", "hi")), 100, 0.5)
                assertEquals(kind, (r as VeniceResult.Failure).kind)
                assertEquals("status $status", 1, t.calls.size)
            }
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun `local error budget stops calls before venice blocks the model`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script("/chat/completions", HttpTransportResponse(400, "{}"))
        val c = client(config(), t)
        repeat(VeniceClient.ERROR_BUDGET_LIMIT) { c.chat("m", listOf(VeniceMessage("user", "hi")), 10, 0.5) }
        val blocked = c.chat("m", listOf(VeniceMessage("user", "hi")), 10, 0.5) as VeniceResult.Failure
        assertEquals(VeniceFailureKind.ERROR_BUDGET, blocked.kind)
        assertEquals(VeniceClient.ERROR_BUDGET_LIMIT, t.calls.size)
        now += 31_000 // окно 30 с прошло
        t.script("/chat/completions", ok(chatBody))
        assertTrue(c.chat("m", listOf(VeniceMessage("user", "hi")), 10, 0.5) is VeniceResult.Success)
    }

    // ---------------------------------------------------------------- потолки и расход

    @Test
    fun `per-minute limit blocks extra requests and releases after a minute`() = runBlocking {
        val t = transportWithModels()
        val svc = service(config(rpm = 2), t)
        suspend fun call() = svc.chat("r", "llama-3.3-70b", listOf(VeniceMessage("user", "hi")), 100, 0.5)
        assertTrue(call() is VeniceResult.Success)
        assertTrue(call() is VeniceResult.Success)
        val third = call() as VeniceResult.Failure
        assertEquals(VeniceFailureKind.RATE_LIMITED, third.kind)
        assertNotNull(third.retryAfterSeconds)
        assertEquals("третий вызов до Venice не дошёл", 2, t.count("/chat/completions"))
        now += 61_000
        assertTrue(call() is VeniceResult.Success)
    }

    @Test
    fun `max tokens is capped and unknown models are rejected without a call`() = runBlocking {
        val t = transportWithModels()
        val svc = service(config(maxTokens = 256), t)
        svc.chat("r", "llama-3.3-70b", listOf(VeniceMessage("user", "hi")), 100_000, 0.5)
        val body = t.calls.last { it.url.contains("/chat/completions") }.body
        assertTrue(body, body.contains("\"max_tokens\":256"))
        assertTrue(body, body.contains("\"stream\":false"))

        val image = svc.chat("r", "flux-image", listOf(VeniceMessage("user", "hi")), 10, 0.5) as VeniceResult.Failure
        val weird = svc.chat("r", "../admin", listOf(VeniceMessage("user", "hi")), 10, 0.5) as VeniceResult.Failure
        assertEquals(VeniceFailureKind.BAD_REQUEST, image.kind)
        assertEquals(VeniceFailureKind.BAD_REQUEST, weird.kind)
        assertEquals(1, t.count("/chat/completions"))
    }

    @Test
    fun `usage tokens and cost are counted from the response`() = runBlocking {
        val t = transportWithModels()
        val metrics = Metrics()
        val cfg = config()
        val svc = VeniceService(cfg, client(cfg, t), logger, metrics, clock = { now })
        val r = svc.chat("r", "llama-3.3-70b", listOf(VeniceMessage("user", "hi")), 100, 0.5) as VeniceResult.Success
        // 1000 * 0.7/1M + 500 * 2.8/1M = 0.0007 + 0.0014
        assertEquals(0.0021, r.value.costUsd!!, 1e-9)
        val u = svc.usage()
        assertEquals(1L, u.requests)
        assertEquals(1000L, u.inputTokens)
        assertEquals(500L, u.outputTokens)
        assertEquals(0.0021, u.costUsd, 1e-6)
        assertEquals(1000L, metrics.snapshot()["venice_input_tokens_total"])
        assertEquals(2100L, metrics.snapshot()["venice_cost_microusd_total"])
    }

    // ---------------------------------------------------------------- ключ не утекает

    @Test
    fun `key is sent to venice only as bearer and never reaches logs or results`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script("/models", ok(modelsBody))
        // Венис «эхом» вернул бы ключ в ошибке: он всё равно не должен уйти дальше.
        t.script("/chat/completions", HttpTransportResponse(400, "{\"error\":\"bad $secret\"}"))
        t.script("/api_keys/rate_limits", ok("{\"echo\":\"$adminSecret\",\"balances\":{\"USD\":4.2}}"))
        val cfg = config(admin = adminSecret)
        val svc = service(cfg, t)

        val failure = svc.chat("r", "llama-3.3-70b", listOf(VeniceMessage("user", "секретный текст")), 10, 0.5)
        val limits = svc.rateLimits()

        assertEquals("Bearer $secret", t.calls.first { it.url.contains("/chat/completions") }.headers["Authorization"])
        assertEquals("Bearer $adminSecret", t.calls.first { it.url.contains("rate_limits") }.headers["Authorization"])
        val everything = failure.toString() + limits.toString() + logLines.joinToString("\n") + cfg.toString()
        assertFalse(everything.contains(secret))
        assertFalse(everything.contains("unit-test-not-a-real"))
        assertFalse("текст запроса не логируется", everything.contains("секретный текст"))
    }

    // ---------------------------------------------------------------- HTTP: только админ

    private val adminId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val proId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val omnixId = UUID.fromString("33333333-3333-3333-3333-333333333333")

    private class FakeAuth(private val clients: Map<String, AuthenticatedClient>) : Authenticator {
        override fun authenticate(authorizationHeader: String?): AuthResult {
            val token = authorizationHeader?.removePrefix("Bearer ")?.trim()
            if (authorizationHeader == null) return AuthResult.MissingCredentials
            return clients[token]?.let { AuthResult.Success(it) } ?: AuthResult.InvalidCredentials
        }
    }

    private fun lic(id: UUID, plan: String, tier: ClientTier = ClientTier.PRO) =
        AuthenticatedClient("acct-$plan", tier, id, AuthSource.LICENSE_TOKEN, plan)

    private val auth = FakeAuth(
        mapOf(
            "admin-token" to lic(adminId, "pro"),
            "pro-token" to lic(proId, "pro"),
            "omnix-token" to lic(omnixId, "omnix"),
            "free-token" to lic(UUID.randomUUID(), "free", ClientTier.FREE),
            // Статический ADMIN-токен без аккаунта: это не админ теста моделей.
            "static-admin" to AuthenticatedClient("ops", ClientTier.ADMIN, null, AuthSource.STATIC)
        )
    )
    private val access = AdminModelAccess(setOf(adminId), emptyList())

    private fun handler(cfg: VeniceConfig, t: HttpTransport) =
        VeniceHttpHandler(auth, access, service(cfg, t), logger, Json)

    private fun get(path: String, token: String?) = HttpRequestContext(
        method = "GET", path = path, authorizationHeader = token?.let { "Bearer $it" }, body = "", contentLength = 0
    )

    @Test
    fun `admin sees the model list, everyone else gets nothing as if the endpoint does not exist`() = runBlocking {
        val t = transportWithModels()
        val h = handler(config(), t)

        val resp = h.handle(get("/v1/venice/models", "admin-token"))!!
        assertEquals(200, resp.status)
        assertTrue(resp.body, resp.body.contains("venice-uncensored-1-2"))
        assertTrue(resp.body.contains("\"privacy\":\"e2ee\""))
        assertFalse(resp.body.contains(secret))
        assertFalse("картинки в списке нет", resp.body.contains("flux-image"))

        for (token in listOf("pro-token", "omnix-token", "free-token", "static-admin", "garbage", null)) {
            assertNull("token=$token", h.handle(get("/v1/venice/models", token)))
            assertNull("token=$token", h.handle(get("/v1/venice/limits", token)))
        }
        assertEquals("до Venice дошёл только запрос админа", 1, t.count("/models"))
    }

    @Test
    fun `admin cannot use other methods or unknown venice paths`() = runBlocking {
        val h = handler(config(), transportWithModels())
        assertNull(h.handle(get("/v1/venice/chat", "admin-token")))
        assertNull(h.handle(get("/v1/venice/models", "admin-token").copy(method = "POST")))
        assertNull(h.handle(get("/v1/other", "admin-token")))
    }

    @Test
    fun `without a key even the admin gets a clear 503 and the network stays untouched`() = runBlocking {
        val t = transportWithModels()
        val h = handler(config(key = null), t)
        val resp = h.handle(get("/v1/venice/models", "admin-token"))!!
        assertEquals(503, resp.status)
        assertTrue(resp.body.contains("VENICE_API_KEY"))
        assertNull("не-админу по-прежнему как будто эндпоинта нет", h.handle(get("/v1/venice/models", "pro-token")))
        assertEquals(0, t.calls.size)
    }

    @Test
    fun `limits endpoint explains a missing admin key and returns local limits and last rate headers`() = runBlocking {
        val t = FakeVeniceTransport()
        t.script("/models", ok(modelsBody, mapOf("x-ratelimit-remaining-requests" to "42", "content-type" to "x")))
        t.script("/api_keys/rate_limits", HttpTransportResponse(401, "{}"))
        val h = handler(config(), t)
        h.handle(get("/v1/venice/models", "admin-token"))

        val denied = h.handle(get("/v1/venice/limits", "admin-token"))!!
        assertEquals(200, denied.status)
        assertTrue(denied.body, denied.body.contains("VENICE_ADMIN_KEY_REQUIRED"))
        assertTrue(denied.body.contains("Нужен админ-ключ Venice для просмотра лимитов"))
        assertTrue(denied.body.contains("\"x-ratelimit-remaining-requests\":\"42\""))
        assertFalse(denied.body.contains("content-type"))
        assertTrue(denied.body.contains("\"requestsPerMinuteLimit\":20"))
        assertTrue(denied.body.contains("\"maxOutputTokens\":1024"))
        assertFalse(denied.body.contains(secret))
        assertEquals(1, t.count("rate_limits"))

        t.script("/api_keys/rate_limits", ok("{\"data\":{\"balances\":{\"USD\":12.5}}}"))
        val granted = h.handle(get("/v1/venice/limits", "admin-token"))!!
        assertTrue(granted.body, granted.body.contains("\"USD\":12.5"))
    }

    // ---------------------------------------------------------------- роутер: автовыбор и чат

    private fun providerCfg(id: ProviderId, priority: Int) = ProviderConfig(
        id = id, enabled = true, priority = priority, apiKey = "k", model = "m",
        baseUrl = "https://example.invalid", connectTimeoutMs = 1000, requestTimeoutMs = 2000
    )

    private class RouterRig(val router: AiRouter, val groq: FakeAiProvider)

    private fun routerRig(veniceService: VeniceService?): RouterRig {
        val configs = mapOf(ProviderId.GROQ to providerCfg(ProviderId.GROQ, 1))
        val groq = FakeAiProvider.ok(ProviderId.GROQ, "ответ groq")
        val health = ProviderHealthTracker(CircuitBreakerConfig())
        val manager = ProviderManager(
            providers = listOf(groq),
            configs = configs,
            health = health,
            policy = ExecutionPolicyConfig(maxRetriesPerProvider = 0),
            selectionPolicy = DefaultProviderSelectionPolicy(configs, health),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics()
        )
        val router = AiRouter(
            providerManager = manager,
            usageRepository = InMemoryUsageRepository(),
            validation = ValidationConfig(),
            privacyPolicy = PrivacyPolicyConfig(),
            generation = AiGenerationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            metrics = Metrics(),
            adminAccess = AdminModelAccess(setOf(adminId), configs.values.toList()),
            veniceService = veniceService
        )
        return RouterRig(router, groq)
    }

    private fun request(adminProvider: String?) = AiExecutionRequest(
        text = "Расскажи про плов",
        privacyLevel = ApiPrivacyLevel.NORMAL,
        adminProvider = adminProvider
    )

    @Test
    fun `admin pinned venice model is answered by venice and labelled`() = runBlocking {
        val t = transportWithModels()
        val rig = routerRig(service(config(), t))
        val result = rig.router.execute(request("venice:llama-3.3-70b"), lic(adminId, "pro"), "req-1")
        result as RouterResult.Success
        assertEquals("привет от venice", result.text)
        assertEquals("VENICE", result.provider)
        assertEquals("llama-3.3-70b", result.model)
        assertEquals(0, rig.groq.calls.get())
    }

    @Test
    fun `non-admin of any plan cannot reach venice through the chat endpoint`() = runBlocking {
        val t = transportWithModels()
        val rig = routerRig(service(config(), t))
        for (c in listOf(lic(proId, "pro"), lic(omnixId, "omnix"), lic(UUID.randomUUID(), "free", ClientTier.FREE))) {
            val result = rig.router.execute(request("venice:llama-3.3-70b"), c, "req-x") as RouterResult.Success
            assertEquals("ответ groq", result.text)
            assertNull("не-админу провайдера не называют", result.provider)
        }
        assertEquals(0, t.calls.size)
    }

    @Test
    fun `automatic selection never picks venice and a disabled venice is skipped for admin too`() = runBlocking {
        val t = transportWithModels()
        val rig = routerRig(service(config(), t))
        val auto = rig.router.execute(request(null), lic(adminId, "pro"), "req-a") as RouterResult.Success
        assertEquals("ответ groq", auto.text)
        assertEquals("GROQ", auto.provider)
        assertTrue("Venice не является ProviderId, цепочке выбора его взять неоткуда", ProviderId.entries.none { it.name.contains("VENICE", true) })

        val offRig = routerRig(service(config(key = null), t))
        val off = offRig.router.execute(request("venice:llama-3.3-70b"), lic(adminId, "pro"), "req-b") as RouterResult.Success
        assertEquals("ответ groq", off.text)
        assertEquals(0, t.calls.size)
    }
}
