package com.omnix.server

import com.omnix.server.admin.AdminAccountRepository
import com.omnix.server.admin.AdminAuditLog
import com.omnix.server.admin.AdminAuthService
import com.omnix.server.admin.AdminHttpHandler
import com.omnix.server.admin.AdminPasswords
import com.omnix.server.admin.AdminQueries
import com.omnix.server.admin.AdminRole
import com.omnix.server.admin.AdminSecurityPolicy
import com.omnix.server.admin.AdminSessionRepository
import com.omnix.server.admin.AdminSettingsService
import com.omnix.server.admin.AdminSpaHandler
import com.omnix.server.admin.CostSettings
import com.omnix.server.admin.FeatureFlagService
import com.omnix.server.admin.ProviderCostEntry
import com.omnix.server.admin.ProviderRuntimeOverrides
import com.omnix.server.auth.ClientTier
import com.omnix.server.auth.TokenAuthenticator
import com.omnix.server.config.RateLimitConfig
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.provider.ProviderManager
import com.omnix.server.ratelimit.PostgresRateLimiter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * Full READ-SURFACE + operator management + UI page sweep (coverage + E2E §30):
 * Admin Login → Dashboard → User → Device → License → Setting change → Audit.
 */
class AdminControlPlaneSurfaceTest : PostgresTestSupport() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var accounts: AdminAccountRepository
    private lateinit var sessions: AdminSessionRepository
    private lateinit var settings: AdminSettingsService
    private lateinit var handler: AdminHttpHandler
    private lateinit var spa: AdminSpaHandler

    @Before
    fun build() {
        accounts = AdminAccountRepository(dataSource)
        sessions = AdminSessionRepository(dataSource)
        settings = AdminSettingsService(dataSource, json)
        val policy = AdminSecurityPolicy()
        val staticAuth = TokenAuthenticator(mapOf("d".repeat(64) to "ops")) { ClientTier.ADMIN }
        val auth = AdminAuthService(
            accounts = accounts,
            sessions = sessions,
            loginRateLimiter = PostgresRateLimiter(
                dataSource, "admin_login", RateLimitConfig(policy.loginMaxAttempts, policy.loginMaxAttempts)
            ),
            policy = policy
        )
        val queries = AdminQueries(dataSource)
        val flags = FeatureFlagService(dataSource)
        spa = AdminSpaHandler()
        handler = AdminHttpHandler(
            auth = auth, staticAuthenticator = staticAuth,
            accounts = accounts, sessions = sessions,
            audit = AdminAuditLog(dataSource), settings = settings, flags = flags,
            queries = queries, providerManager = mockk(relaxed = true),
            overrides = ProviderRuntimeOverrides(), json = json
        )
    }

    private fun api(method: String, path: String, token: String?, body: String = "") = runBlocking {
        handler.handle(
            HttpRequestContext(
                method = method, path = path, authorizationHeader = token?.let { "Bearer $it" },
                body = body, contentLength = body.length.toLong(), remoteAddress = "10.9.9.1"
            )
        )!!
    }

    private fun login(username: String, password: String): String {
        val r = api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}")
        assertEquals(200, r.status)
        return Json.parseToJsonElement(r.body).let { (it as kotlinx.serialization.json.JsonObject)["token"] }
            ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content }!!
    }

    @Test
    fun `operator management lifecycle is gated and audited`() {
        accounts.create("super1", AdminPasswords.hash("super-pass-12345"), AdminRole.SUPER_ADMIN, Instant.now())
        val token = login("super1", "super-pass-12345")

        // Создание SUPPORT-оператора.
        val created = api(
            "POST", "/v1/admin/admins", token,
            "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "support-pass-123" + "\",\"role\":\"SUPPORT\"}"
        )
        assertEquals("create body=${created.body}", 200, created.status)
        // VIEWER не может создавать операторов.
        accounts.create("seer", AdminPasswords.hash("viewer-pass-123"), AdminRole.VIEWER, Instant.now())
        val viewerToken = login("seer", "viewer-pass-123")
        assertEquals(403, api("POST", "/v1/admin/admins", viewerToken, "{\"username\":\"" + "x1" + "\",\"password\":\"" + "whatever-pass-1" + "\",\"role\":\"ADMIN\"}").status)
        // Список и смена статуса.
        assertEquals(200, api("GET", "/v1/admin/admins", token).status)
        val supporterId = accounts.findByUsername("supporter")!!.id
        assertEquals(200, api("POST", "/v1/admin/admins/$supporterId/set-status", token, """{"status":"DISABLED"}""").status)
        // DISABLED-оператор не может войти: passwordHashOf отдаёт хэш только
        // ACTIVE-аккаунтам, поэтому login даёт unified 401 (fail-closed).
        assertEquals(401, api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "support-pass-123" + "\"}").status)
        assertEquals(200, api("POST", "/v1/admin/admins/$supporterId/set-status", token, """{"status":"ACTIVE"}""").status)
        // Ротация пароля + старый пароль больше не работает.
        assertEquals(200, api("POST", "/v1/admin/admins/$supporterId/set-password", token, "{\"password\":\"" + "fresh-pass-12345" + "\"}").status)
        val relogin = api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "support-pass-123" + "\"}")
        assertEquals(401, relogin.status)
        assertEquals(200, api("POST", "/v1/admin/auth/login", null, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "fresh-pass-12345" + "\"}").status)
        // Дубликат username → 409.
        assertEquals(409, api("POST", "/v1/admin/admins", token, "{\"username\":\"" + "supporter" + "\",\"password\":\"" + "another-pass-123" + "\",\"role\":\"VIEWER\"}").status)
    }

    /**
     * ADMIN (MVP-дерево Licenses → active/expired): status-фильтр списка.
     * Неизвестный статус — 400 (не тихий «показать всё»).
     */
    /**
     * OBSERVABILITY: сквозной request id доступен оператору — /admin/logs
     * (CLOUD) возвращает request_id из ai_usage_records, по нему видно
     * клиент-серверный путь одного запроса.
     */
    @Test
    fun `cloud logs expose request id`() {
        val knownRequestId = "omx_01TESTFIXTUREREQUESTID000000"
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO ai_usage_records (request_id, client_id, provider, model, latency_ms, input_tokens, output_tokens, total_tokens, " +
                    "success, prompt_chars, response_chars, occurred_at) VALUES (?, '99999999-9999-9999-9999-999999999999', 'GROQ', 'm', 42, 10, 20, 30, TRUE, 5, 6, now())"
            ).use { ps ->
                ps.setString(1, knownRequestId)
                ps.executeUpdate()
            }
        }
        accounts.create("logseer", AdminPasswords.hash("logs-pass-1234567"), AdminRole.VIEWER, Instant.now())
        val token = login("logseer", "logs-pass-1234567")

        val logs = api("GET", "/v1/admin/logs?component=CLOUD", token)
        assertEquals(200, logs.status)
        assertTrue("request id in logs", logs.body.contains(knownRequestId))
    }

    @Test
    fun `licenses list filters by status and rejects unknown status`() {
        val (accountId, activeId) = seedBoth()
        val expiredId = seedExpiredLicense(accountId)
        accounts.create("filterer", AdminPasswords.hash("filter-pass-12345"), AdminRole.ADMIN, Instant.now())
        val token = login("filterer", "filter-pass-12345")

        // accountId общий для обеих лицензий и присутствует в каждой строке
        // JSON — поэтому сверяем именно поле "id", а не голый UUID.
        val expired = api("GET", "/v1/admin/licenses?status=EXPIRED", token)
        assertEquals(200, expired.status)
        assertTrue("expired id in EXPIRED filter", expired.body.contains("\"id\":\"$expiredId\""))
        assertFalse("active id not in EXPIRED filter", expired.body.contains("\"id\":\"$activeId\""))
        assertTrue(expired.body.contains("\"statusFilter\":\"EXPIRED\""))

        val active = api("GET", "/v1/admin/licenses?status=ACTIVE", token)
        assertEquals(200, active.status)
        assertTrue(active.body.contains("\"id\":\"$activeId\""))
        assertFalse(active.body.contains("\"id\":\"$expiredId\""))

        assertEquals(200, api("GET", "/v1/admin/licenses", token).status)
        assertEquals(400, api("GET", "/v1/admin/licenses?status=BOGUS", token).status)
    }

    /**
     * Control Plane — теперь SPA: старые server-rendered пути
     * (префикс /v1/admin/ui) редиректят на /admin; /admin отдаёт index.html (no-store); traversal
     * и несуществующие ассеты — 404. (См. AdminSpaHandler.)
     */
    @Test
    fun `spa serves index, redirects legacy ui paths and blocks traversal`() = runBlocking {
        assertEquals(301, spa.handle(HttpRequestContext("GET", "/v1/admin/ui/login", null, "", 0))!!.status)
        assertEquals(301, spa.handle(HttpRequestContext("GET", "/v1/admin/ui/dashboard", null, "", 0))!!.status)

        val index = spa.handle(HttpRequestContext("GET", "/admin", null, "", 0))!!
        assertEquals(200, index.status)
        assertTrue("index is html", index.headers["Content-Type"]!!.startsWith("text/html"))
        assertEquals("no-store", index.headers["Cache-Control"])

        // Клиентский роут без расширения → тоже index.
        assertEquals(200, spa.handle(HttpRequestContext("GET", "/admin/licenses", null, "", 0))!!.status)

        // Traversal и несуществующий ассет → 404.
        assertEquals(404, spa.handle(HttpRequestContext("GET", "/admin/../secret", null, "", 0))!!.status)
        assertEquals(404, spa.handle(HttpRequestContext("GET", "/admin/assets/missing.js", null, "", 0))!!.status)
        // Чужие маршруты не наши.
        assertNull(spa.handle(HttpRequestContext("GET", "/v1/license/redeem", null, "", 0)))
    }

    @Test
    fun `full read surface returns 200 over seeded data`() {
        val (accountId, licenseId) = seedBoth()
        seedOrder(accountId)
        seedUsage()
        accounts.create("reader", AdminPasswords.hash("reader-pass-12345"), AdminRole.ADMIN, Instant.now())
        val token = login("reader", "reader-pass-12345")

        assertEquals(200, api("GET", "/v1/admin/subscriptions", token).status)
        assertEquals(200, api("GET", "/v1/admin/devices", token).status)
        assertEquals(200, api("GET", "/v1/admin/licenses", token).status)
        assertEquals(200, api("GET", "/v1/admin/licenses/$licenseId", token).status)
        val devices = api("GET", "/v1/admin/devices", token)
        assertTrue(devices.body.contains(accountId.toString()))
        // Реальные записи в logs (cloud) и audit (действий пока нет → пусто, но 200).
        assertEquals(200, api("GET", "/v1/admin/logs?component=CLOUD", token).status)
        assertEquals(200, api("GET", "/v1/admin/audit", token).status)
        assertEquals(200, api("GET", "/v1/admin/users/$accountId", token).status)
        assertEquals(200, api("GET", "/v1/admin/dashboard", token).status)
        // Settings persistence: новый сервис должен прочитать сохранённое из БД.
        assertEquals(
            200,
            api("PUT", "/v1/admin/settings/cost", token, """{"providers":{"GEMINI":{"usdPerMillionInput":2.5,"usdPerMillionOutput":7.5}}}""").status
        )
        val reloaded = AdminSettingsService(dataSource, json)
        val reloadedCosts = reloaded.cost().providers["GEMINI"]
        assertEquals(2.5, reloadedCosts?.usdPerMillionInput!!, 1e-9)
        // Session housekeeping.
        sessions.purge(java.time.Duration.ofDays(1), Instant.now())
    }

    /**
     * issue/revoke лицензий из Control Plane под АДМИН-СЕССИЕЙ (раньше —
     * только static-токен). Роль без LICENSES_WRITE — как unauthorized.
     */
    @Test
    fun `license issue accepts admin session and enforces rbac`() = runBlocking {
        accounts.create("issuer", AdminPasswords.hash("issuer-pass-12345"), AdminRole.ADMIN, Instant.now())
        accounts.create("peon", AdminPasswords.hash("peon-pass-123456"), AdminRole.VIEWER, Instant.now())
        val session = login("issuer", "issuer-pass-12345")
        val viewerSession = login("peon", "peon-pass-123456")

        val licenseService = mockk<com.omnix.server.license.LicenseService>()
        every { licenseService.issue(any()) } returns com.omnix.server.license.IssuedLicense(
            licenseId = java.util.UUID.randomUUID(),
            code = "OMX-TESTC-ODETEST-CODETEST",
            status = com.omnix.server.license.LicenseStatus.ISSUED,
            planId = "free",
            issuedAt = Instant.now(),
            expiresAt = null
        )
        val billingHandler = com.omnix.server.http.LicenseBillingHttpHandler(
            authenticator = TokenAuthenticator(emptyMap()) { ClientTier.FREE },
            authorizer = com.omnix.server.auth.TierAuthorizer(),
            licenseService = licenseService,
            billingService = mockk(relaxed = true),
            paddleWebhookVerifier = mockk(relaxed = true),
            heleketWebhookVerifier = mockk(relaxed = true),
            redeemRateLimiter = mockk(relaxed = true),
            authenticatedRateLimiter = mockk(relaxed = true),
            webhookRateLimiter = mockk(relaxed = true),
            validation = com.omnix.server.config.ValidationConfig(),
            logger = mockk(relaxed = true),
            json = json
        )
        // Wire, как в Main: adminAuthService поверх static.
        val staticAuth = TokenAuthenticator(mapOf("d".repeat(64) to "ops")) { ClientTier.ADMIN }
        billingHandler.adminAuthService = AdminAuthService(
            accounts = accounts, sessions = sessions,
            loginRateLimiter = PostgresRateLimiter(
                dataSource, "admin_login",
                RateLimitConfig(AdminSecurityPolicy().loginMaxAttempts, AdminSecurityPolicy().loginMaxAttempts)
            ),
            policy = AdminSecurityPolicy()
        )

        fun issue(token: String) = billingHandler.handle(
            HttpRequestContext(
                method = "POST", path = "/v1/admin/licenses/issue",
                authorizationHeader = "Bearer $token",
                body = """{"plan_id":"free","one_time":true}""",
                contentLength = 30, remoteAddress = "10.9.9.3"
            )
        )!!

        // ADMIN-сессия с LICENSES_WRITE → 201, код в ответе.
        val ok = issue(session)
        assertEquals("body=${ok.body}", 201, ok.status)
        assertTrue("code returned once", ok.body.contains("OMX-TESTC-ODETEST-CODETEST"))
        // VIEWER (нет LICENSES_WRITE) → unauthorized.
        assertEquals(401, issue(viewerSession).status)
        // Static ADMIN-токен работает как раньше (обратная совместимость).
        assertEquals(201, issue("d".repeat(64)).status)
    }

    /* ── seeds ─────────────────────────────────────────────────────────────── */

    private fun seedBoth(): Pair<UUID, UUID> {
        val accountId = UUID.randomUUID()
        val licenseId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement("INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)").use { ps ->
                ps.setObject(1, accountId); ps.setString(2, "acc-$accountId"); ps.setTimestamp(3, now); ps.setTimestamp(4, now); ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO billing_plans (id, product_id, display_name, duration_days, amount_minor, currency, active, created_at, updated_at) " +
                    "VALUES ('pro_monthly','pro_monthly','Pro',30,990,'USD',TRUE,?,?) ON CONFLICT (id) DO NOTHING"
            ).use { ps -> ps.setTimestamp(1, now); ps.setTimestamp(2, now); ps.executeUpdate() }
            c.prepareStatement(
                "INSERT INTO licenses (id, code_hash, code_hint, status, billing_status, issued_at, starts_at, expires_at, product_id, plan_id, account_id, " +
                    "one_time, redeemed_at, metadata, created_at, updated_at) " +
                    "VALUES (?, decode('beef', 'hex'), 'BE', 'ACTIVE', 'PAID', ?, now() - interval '1 day', now() + interval '30 days', 'omnix', 'pro_monthly', ?, " +
                    "TRUE, now() - interval '1 day', '{}'::jsonb, ?, ?)"
            ).use { ps ->
                ps.setObject(1, licenseId); ps.setTimestamp(2, now); ps.setObject(3, accountId); ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO api_tokens (id, account_id, token_hash, status, issued_at, created_at) VALUES (?, ?, decode('c0ffee', 'hex'), 'ACTIVE', ?, ?)"
            ).use { ps -> ps.setObject(1, UUID.randomUUID()); ps.setObject(2, accountId); ps.setTimestamp(3, now); ps.setTimestamp(4, now); ps.executeUpdate() }
        }
        return accountId to licenseId
    }

    /**
     * ADMIN (Licenses active/expired): вторая лицензия со статусом EXPIRED —
     * для проверки status-фильтра списка.
     */
    private fun seedExpiredLicense(accountId: UUID): UUID {
        val licenseId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO licenses (id, code_hash, code_hint, status, billing_status, issued_at, starts_at, expires_at, product_id, plan_id, account_id, " +
                    "one_time, redeemed_at, metadata, created_at, updated_at) " +
                    "VALUES (?, decode('dead', 'hex'), 'DE', 'EXPIRED', 'PAID', ?, now() - interval '60 days', now() - interval '30 days', 'omnix', 'pro_monthly', ?, " +
                    "TRUE, now() - interval '60 days', '{}'::jsonb, ?, ?)"
            ).use { ps ->
                ps.setObject(1, licenseId); ps.setTimestamp(2, now); ps.setObject(3, accountId)
                ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
        }
        return licenseId
    }

    private fun seedOrder(accountId: UUID) {
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO billing_orders (id, account_id, plan_id, provider, status, amount_minor, currency, idempotency_key, paid_at, created_at, updated_at) " +
                    "VALUES (?, ?, 'pro_monthly', 'PADDLE', 'PAID', 1400, 'USD', ?, now(), ?, ?)"
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID()); ps.setObject(2, accountId)
                ps.setString(3, "idem-" + UUID.randomUUID()); ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                ps.executeUpdate()
            }
        }
    }

    private fun seedUsage() {
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO ai_usage_records (request_id, client_id, provider, model, latency_ms, input_tokens, output_tokens, total_tokens, " +
                    "success, prompt_chars, response_chars, occurred_at) VALUES (?, '22222222-2222-2222-2222-222222222222', 'GEMINI', 'm', 90, 500, 250, 750, TRUE, 11, 22, now())"
            ).use { ps -> ps.setString(1, "req-" + UUID.randomUUID()); ps.executeUpdate() }
        }
    }
}
