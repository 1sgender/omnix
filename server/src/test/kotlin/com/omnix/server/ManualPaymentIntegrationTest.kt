package com.omnix.server

import com.omnix.server.admin.AdminAccountRepository
import com.omnix.server.admin.AdminAuditLog
import com.omnix.server.admin.AdminAuditQuery
import com.omnix.server.admin.AdminHttpHandler
import com.omnix.server.admin.AdminPasswords
import com.omnix.server.admin.AdminQueries
import com.omnix.server.admin.AdminRole
import com.omnix.server.admin.AdminSecurityPolicy
import com.omnix.server.admin.AdminSessionRepository
import com.omnix.server.admin.AdminSettingsService
import com.omnix.server.admin.FeatureFlagService
import com.omnix.server.admin.ManualPaymentAdminApi
import com.omnix.server.admin.PaymentContact
import com.omnix.server.admin.PaymentSettings
import com.omnix.server.admin.ProviderRuntimeOverrides
import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.AuthenticatedClient
import com.omnix.server.auth.Authenticator
import com.omnix.server.auth.ClientTier
import com.omnix.server.auth.TierAuthorizer
import com.omnix.server.auth.TokenAuthenticator
import com.omnix.server.billing.BillingOrderStatus
import com.omnix.server.billing.JdbcBillingRepository
import com.omnix.server.billing.ManualCancelResult
import com.omnix.server.billing.ManualConfirmResult
import com.omnix.server.billing.ManualCreateResult
import com.omnix.server.billing.ManualOrder
import com.omnix.server.billing.ManualOrderRepository
import com.omnix.server.config.RateLimitConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import com.omnix.server.http.ManualPaymentHttpHandler
import com.omnix.server.license.BillingPlan
import com.omnix.server.license.JdbcLicenseRepository
import com.omnix.server.license.LicenseCrypto
import com.omnix.server.license.LicenseValidationOutcome
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.ratelimit.PostgresRateLimiter
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.random.Random

/**
 * Ручная оплата тарифов: заявка (MANUAL) → подтверждение/отмена в админке →
 * тариф выдан тем же путём, что и оплаченный заказ.
 */
class ManualPaymentIntegrationTest : PostgresTestSupport() {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val crypto = LicenseCrypto("test-license-pepper-32-bytes-minimum-value")
    private val deviceId = "device-abcdefgh"
    private val ttl = Duration.ofHours(72)
    private val staticAdminToken = "m".repeat(64)

    private lateinit var billing: JdbcBillingRepository
    private lateinit var orders: ManualOrderRepository
    private lateinit var licenses: JdbcLicenseRepository
    private lateinit var audit: AdminAuditLog
    private lateinit var settings: AdminSettingsService
    private lateinit var accounts: AdminAccountRepository
    private lateinit var admin: AdminHttpHandler
    private lateinit var proPlan: BillingPlan
    private lateinit var maxPlan: BillingPlan

    @Before
    fun setUp() {
        billing = JdbcBillingRepository(dataSource)
        orders = ManualOrderRepository(dataSource, billing)
        licenses = JdbcLicenseRepository(dataSource, crypto)
        audit = AdminAuditLog(dataSource)
        settings = AdminSettingsService(dataSource, json)
        accounts = AdminAccountRepository(dataSource)
        seedPlans()
        admin = buildAdmin(orders)
    }

    private fun buildAdmin(repo: ManualOrderRepository): AdminHttpHandler {
        val sessions = AdminSessionRepository(dataSource)
        val policy = AdminSecurityPolicy()
        return AdminHttpHandler(
            auth = com.omnix.server.admin.AdminAuthService(
                accounts = accounts,
                sessions = sessions,
                loginRateLimiter = PostgresRateLimiter(
                    dataSource, "admin_login", RateLimitConfig(policy.loginMaxAttempts, policy.loginMaxAttempts)
                ),
                policy = policy
            ),
            staticAuthenticator = TokenAuthenticator(mapOf(staticAdminToken to "ops-cli")) { ClientTier.ADMIN },
            accounts = accounts,
            sessions = sessions,
            audit = audit,
            settings = settings,
            flags = FeatureFlagService(dataSource),
            queries = AdminQueries(dataSource),
            providerManager = mockk(relaxed = true),
            overrides = ProviderRuntimeOverrides(),
            manualPayments = ManualPaymentAdminApi(repo, audit),
            json = json
        )
    }

    private fun seedPlans() {
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            listOf(
                Triple("free", 0L, "OMNIX Free"),
                Triple("pro", 14_900L, "OMNIX Pro"),
                Triple("omnix", 29_900L, "OMNIX Max")
            ).forEach { (id, price, name) ->
                c.prepareStatement(
                    "INSERT INTO billing_plans (id, product_id, display_name, duration_days, amount_minor, currency, active, created_at, updated_at) " +
                        "VALUES (?, 'omnix', ?, 30, ?, 'TMT', TRUE, ?, ?)"
                ).use { ps ->
                    ps.setString(1, id); ps.setString(2, name); ps.setLong(3, price)
                    ps.setTimestamp(4, now); ps.setTimestamp(5, now)
                    ps.executeUpdate()
                }
            }
        }
        proPlan = checkNotNull(licenses.findPlan("pro"))
        maxPlan = checkNotNull(licenses.findPlan("omnix"))
    }

    /** Аккаунт с бессрочной бесплатной лицензией, привязанной к устройству [deviceId]. */
    private fun seedAccount(): UUID {
        val accountId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)"
            ).use { ps ->
                ps.setObject(1, accountId); ps.setString(2, "acc-$accountId")
                ps.setTimestamp(3, now); ps.setTimestamp(4, now)
                ps.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO licenses (id, code_hash, code_hint, status, billing_status, issued_at, starts_at, expires_at, " +
                    "product_id, plan_id, account_id, one_time, redeemed_at, redeemed_device_hash, metadata, created_at, updated_at) " +
                    "VALUES (?, ?, 'FREE', 'ACTIVE', 'GRANTED', ?, now() - interval '1 day', now() + interval '3650 days', " +
                    "'omnix', 'free', ?, TRUE, now() - interval '1 day', ?, '{}'::jsonb, ?, ?)"
            ).use { ps ->
                ps.setObject(1, UUID.randomUUID())
                ps.setBytes(2, ByteArray(32).also { java.security.SecureRandom().nextBytes(it) })
                ps.setTimestamp(3, now)
                ps.setObject(4, accountId)
                ps.setBytes(5, crypto.deviceHash(deviceId))
                ps.setTimestamp(6, now); ps.setTimestamp(7, now)
                ps.executeUpdate()
            }
        }
        return accountId
    }

    private fun create(accountId: UUID, plan: BillingPlan = proPlan, repo: ManualOrderRepository = orders): ManualOrder =
        when (val result = repo.createOrGet(accountId, plan, ttl, Instant.now())) {
            is ManualCreateResult.Created -> result.order
            is ManualCreateResult.Existing -> result.order
            ManualCreateResult.NoLicense -> error("no license")
        }

    private fun confirm(order: ManualOrder, paid: Long = order.amountMinor): ManualConfirmResult =
        orders.confirm(order.id, paid, "cash", "тест", "admin:tester", Instant.now())

    private fun activePlan(accountId: UUID): Pair<String, Instant> {
        val outcome = licenses.validate(accountId, deviceId) as LicenseValidationOutcome.Valid
        return outcome.planId to outcome.expiresAt
    }

    /* ── создание заявки ───────────────────────────────────────────────────── */

    @Test
    fun `order is created pending with human number amount and expiry`() {
        val accountId = seedAccount()
        val before = Instant.now()
        val result = orders.createOrGet(accountId, proPlan, ttl, before)
        val order = (result as ManualCreateResult.Created).order
        assertTrue(order.orderNumber.matches(Regex("OMX-\\d{4}")))
        assertEquals(BillingOrderStatus.PENDING, order.status)
        assertEquals(14_900L, order.amountMinor)
        assertEquals("TMT", order.currency)
        assertEquals("pro", order.planId)
        assertTrue(Duration.between(before.plus(ttl), order.expiresAt).abs() < Duration.ofSeconds(1))
    }

    @Test
    fun `repeat request returns the existing open order and never duplicates`() {
        val accountId = seedAccount()
        val first = create(accountId)
        val again = orders.createOrGet(accountId, proPlan, ttl, Instant.now())
        assertTrue(again is ManualCreateResult.Existing)
        assertEquals(first.id, (again as ManualCreateResult.Existing).order.id)
        assertEquals(1, orders.listForAccount(accountId, Instant.now()).size)
        // другой план = отдельная заявка
        val other = create(accountId, maxPlan)
        assertNotEquals(first.id, other.id)
        assertEquals(2, orders.listForAccount(accountId, Instant.now()).size)
    }

    @Test
    fun `concurrent requests for one account and plan create exactly one order`() {
        val accountId = seedAccount()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = List(8) { pool.submit<UUID> { create(accountId).id } }
            assertEquals(1, futures.map { it.get() }.toSet().size)
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `account without a license cannot create an order`() {
        val accountId = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        dataSource.connection.use { c ->
            c.prepareStatement(
                "INSERT INTO accounts (id, external_ref, status, created_at, updated_at) VALUES (?, ?, 'ACTIVE', ?, ?)"
            ).use { ps ->
                ps.setObject(1, accountId); ps.setString(2, "bare-$accountId")
                ps.setTimestamp(3, now); ps.setTimestamp(4, now)
                ps.executeUpdate()
            }
        }
        assertTrue(orders.createOrGet(accountId, proPlan, ttl, Instant.now()) is ManualCreateResult.NoLicense)
    }

    @Test
    fun `order numbers are unique across many orders`() {
        val numbers = List(120) { create(seedAccount()).orderNumber }
        assertEquals(numbers.size, numbers.toSet().size)
    }

    @Test
    fun `number collision is retried and falls back to another number`() {
        val scripted = ArrayDeque(listOf(1234, 1234, 1234, 5678))
        val fixed = object : Random() {
            override fun nextBits(bitCount: Int): Int = 0
            override fun nextInt(from: Int, until: Int): Int = scripted.removeFirstOrNull() ?: from
        }
        val repo = ManualOrderRepository(dataSource, billing, fixed)
        assertEquals("OMX-1234", create(seedAccount(), repo = repo).orderNumber)
        assertEquals("OMX-5678", create(seedAccount(), repo = repo).orderNumber)
    }

    /* ── срок жизни ────────────────────────────────────────────────────────── */

    @Test
    fun `order expires after ttl and a new one can be created`() {
        val accountId = seedAccount()
        val order = create(accountId)
        val later = order.expiresAt.plusSeconds(60)
        val listed = orders.listForAccount(accountId, later).single()
        assertEquals(BillingOrderStatus.EXPIRED, listed.status)
        val fresh = orders.createOrGet(accountId, proPlan, ttl, later)
        assertTrue(fresh is ManualCreateResult.Created)
        assertNotEquals(order.id, (fresh as ManualCreateResult.Created).order.id)
    }

    @Test
    fun `expired orders drop out of the dashboard pending counter`() {
        val accountId = seedAccount()
        val order = create(accountId)
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE billing_orders SET expires_at = now() - interval '1 hour' WHERE id = ?").use {
                it.setObject(1, order.id); it.executeUpdate()
            }
        }
        assertEquals(0L, AdminQueries(dataSource).dashboard().ordersPending)
        create(seedAccount())
        assertEquals(1L, AdminQueries(dataSource).dashboard().ordersPending)
    }

    /* ── подтверждение ─────────────────────────────────────────────────────── */

    @Test
    fun `confirm marks paid records details and activates the plan`() {
        val accountId = seedAccount()
        assertEquals("free", activePlan(accountId).first)
        val order = create(accountId)
        val start = Instant.now()
        val confirmed = (confirm(order, paid = 15_000) as ManualConfirmResult.Confirmed).order
        assertEquals(BillingOrderStatus.PAID, confirmed.status)
        assertEquals(15_000L, confirmed.paidAmountMinor)
        assertEquals("cash", confirmed.paymentMethod)
        assertEquals("admin:tester", confirmed.resolvedBy)
        assertNotNull(confirmed.paidAt)
        val (plan, expires) = activePlan(accountId)
        assertEquals("pro", plan)
        assertTrue(expires.isAfter(start.plus(29, ChronoUnit.DAYS)))
        assertTrue(expires.isBefore(start.plus(31, ChronoUnit.DAYS)))
    }

    @Test
    fun `confirming an active plan extends from its current end date`() {
        val accountId = seedAccount()
        confirm(create(accountId))
        val firstExpiry = activePlan(accountId).second
        confirm(create(accountId))
        assertEquals(firstExpiry.plus(30, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS), activePlan(accountId).second.truncatedTo(ChronoUnit.MILLIS))
        // одна лицензия плана pro, а не две
        val proLicenses = dataSource.connection.use { c ->
            c.prepareStatement("SELECT count(*) FROM licenses WHERE account_id = ? AND plan_id = 'pro'").use {
                it.setObject(1, accountId)
                it.executeQuery().use { r -> r.next(); r.getInt(1) }
            }
        }
        assertEquals(1, proLicenses)
    }

    @Test
    fun `max plan wins over pro and free after confirmation`() {
        val accountId = seedAccount()
        confirm(create(accountId))
        assertEquals("pro", activePlan(accountId).first)
        confirm(create(accountId, maxPlan))
        assertEquals("omnix", activePlan(accountId).first)
        assertEquals("omnix", licenses.activePlanId(accountId))
    }

    @Test
    fun `second confirm is rejected and does not extend twice`() {
        val accountId = seedAccount()
        val order = create(accountId)
        confirm(order)
        val expiry = activePlan(accountId).second
        val again = confirm(order)
        assertEquals(ManualConfirmResult.NotConfirmable(BillingOrderStatus.PAID), again)
        assertEquals(expiry, activePlan(accountId).second)
    }

    @Test
    fun `a late payment on an expired order can still be confirmed`() {
        val accountId = seedAccount()
        val order = create(accountId)
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE billing_orders SET expires_at = now() - interval '1 hour' WHERE id = ?").use {
                it.setObject(1, order.id); it.executeUpdate()
            }
        }
        assertEquals(BillingOrderStatus.EXPIRED, orders.listForAccount(accountId, Instant.now()).single().status)
        assertTrue(confirm(order) is ManualConfirmResult.Confirmed)
        assertEquals("pro", activePlan(accountId).first)
    }

    @Test
    fun `confirm of unknown order is not found`() {
        assertEquals(ManualConfirmResult.NotFound, orders.confirm(UUID.randomUUID(), 1, "cash", null, "a", Instant.now()))
    }

    /* ── отмена ────────────────────────────────────────────────────────────── */

    @Test
    fun `cancel closes the order and blocks later confirmation`() {
        val accountId = seedAccount()
        val order = create(accountId)
        val canceled = (orders.cancel(order.id, "не оплатил", "admin:tester", Instant.now()) as ManualCancelResult.Canceled).order
        assertEquals(BillingOrderStatus.CANCELED, canceled.status)
        assertEquals("не оплатил", canceled.resolutionNote)
        assertEquals(ManualConfirmResult.NotConfirmable(BillingOrderStatus.CANCELED), confirm(order))
        assertEquals(ManualCancelResult.NotCancelable(BillingOrderStatus.CANCELED), orders.cancel(order.id, "ещё раз", "a", Instant.now()))
        assertEquals("free", activePlan(accountId).first)
        // после отмены можно подать новую заявку
        assertNotEquals(order.id, create(accountId).id)
    }

    /* ── админ API: права, аудит, фильтры ──────────────────────────────────── */

    private fun call(token: String?, method: String, path: String, body: String = ""): HttpResponseContext = runBlocking {
        admin.handle(
            HttpRequestContext(
                method = method, path = path, authorizationHeader = token?.let { "Bearer $it" },
                body = body, contentLength = body.length.toLong(), remoteAddress = "10.0.0.9"
            )
        )!!
    }

    private fun obj(response: HttpResponseContext): JsonObject = json.parseToJsonElement(response.body) as JsonObject

    private fun sessionFor(role: AdminRole, name: String): String {
        accounts.create(name, AdminPasswords.hash("bootstrap-pass-123"), role, Instant.now())
        val login = call(null, "POST", "/v1/admin/auth/login", """{"username":"$name","password":"bootstrap-pass-123"}""")
        return (obj(login)["token"] as JsonPrimitive).content
    }

    @Test
    fun `admin lists filters and searches requests`() {
        val a = create(seedAccount())
        val b = create(seedAccount())
        orders.cancel(b.id, "тест", "a", Instant.now())
        val all = obj(call(staticAdminToken, "GET", "/v1/admin/payment-requests"))["requests"] as JsonArray
        assertEquals(2, all.size)
        val pending = obj(call(staticAdminToken, "GET", "/v1/admin/payment-requests?status=PENDING"))["requests"] as JsonArray
        assertEquals(1, pending.size)
        assertEquals(a.orderNumber, ((pending[0] as JsonObject)["number"] as JsonPrimitive).content)
        val found = obj(call(staticAdminToken, "GET", "/v1/admin/payment-requests?q=${b.orderNumber.takeLast(3)}"))["requests"] as JsonArray
        assertTrue(found.any { ((it as JsonObject)["number"] as JsonPrimitive).content == b.orderNumber })
        assertEquals(400, call(staticAdminToken, "GET", "/v1/admin/payment-requests?status=BOGUS").status)
    }

    @Test
    fun `admin confirm activates the plan and writes audit`() {
        val accountId = seedAccount()
        val order = create(accountId)
        val response = call(
            staticAdminToken, "POST", "/v1/admin/payment-requests/${order.id}/confirm",
            """{"paidAmountMinor":14900,"method":"bank_transfer","note":"перевод 14.11"}"""
        )
        assertEquals(200, response.status)
        assertEquals("pro", activePlan(accountId).first)
        val events = audit.find(AdminAuditQuery(action = "payment.confirm"))
        assertEquals(1, events.size)
        assertEquals(order.id.toString(), events[0].entityId)
        assertTrue(events[0].newValue.contains("bank_transfer"))
        assertTrue(events[0].newValue.contains("14900"))
        // повтор — конфликт, не двойное продление
        assertEquals(409, call(staticAdminToken, "POST", "/v1/admin/payment-requests/${order.id}/confirm", """{"paidAmountMinor":14900,"method":"cash"}""").status)
    }

    @Test
    fun `admin confirm validates amount and method`() {
        val order = create(seedAccount())
        val path = "/v1/admin/payment-requests/${order.id}/confirm"
        assertEquals(400, call(staticAdminToken, "POST", path, """{"method":"cash"}""").status)
        assertEquals(400, call(staticAdminToken, "POST", path, """{"paidAmountMinor":0,"method":"cash"}""").status)
        assertEquals(400, call(staticAdminToken, "POST", path, """{"paidAmountMinor":100,"method":"bitcoin"}""").status)
        assertEquals(404, call(staticAdminToken, "POST", "/v1/admin/payment-requests/${UUID.randomUUID()}/confirm", """{"paidAmountMinor":1,"method":"cash"}""").status)
    }

    @Test
    fun `admin cancel needs a reason and writes audit`() {
        val order = create(seedAccount())
        val path = "/v1/admin/payment-requests/${order.id}/cancel"
        assertEquals(400, call(staticAdminToken, "POST", path, """{"reason":"ab"}""").status)
        assertEquals(200, call(staticAdminToken, "POST", path, """{"reason":"клиент передумал"}""").status)
        val events = audit.find(AdminAuditQuery(action = "payment.cancel"))
        assertEquals(1, events.size)
        assertTrue(events[0].newValue.contains("клиент передумал"))
        assertEquals(BillingOrderStatus.CANCELED, checkNotNull(orders.find(order.id)).status)
    }

    @Test
    fun `viewer can read but cannot confirm or cancel and support can write`() {
        val order = create(seedAccount())
        val viewer = sessionFor(AdminRole.VIEWER, "viewer1")
        assertEquals(200, call(viewer, "GET", "/v1/admin/payment-requests").status)
        assertEquals(403, call(viewer, "POST", "/v1/admin/payment-requests/${order.id}/confirm", """{"paidAmountMinor":1,"method":"cash"}""").status)
        assertEquals(403, call(viewer, "POST", "/v1/admin/payment-requests/${order.id}/cancel", """{"reason":"abc"}""").status)
        assertEquals(401, call(null, "GET", "/v1/admin/payment-requests").status)
        assertEquals(BillingOrderStatus.PENDING, checkNotNull(orders.find(order.id)).status)
        val support = sessionFor(AdminRole.SUPPORT, "support1")
        assertEquals(200, call(support, "POST", "/v1/admin/payment-requests/${order.id}/cancel", """{"reason":"проверка"}""").status)
    }

    @Test
    fun `payment contacts are edited through settings and validated`() {
        val ok = call(
            staticAdminToken, "PUT", "/v1/admin/settings/payments",
            """{"contacts":[{"type":"telegram","value":"@omnix_pay","label":"Владелец"}],"orderTtlHours":48}"""
        )
        assertEquals(200, ok.status)
        assertEquals(48, settings.payments().orderTtlHours)
        assertEquals(PaymentContact("telegram", "@omnix_pay", "Владелец"), settings.payments().contacts.single())
        assertEquals(400, call(staticAdminToken, "PUT", "/v1/admin/settings/payments", """{"contacts":[{"type":"fax","value":"1"}]}""").status)
        assertEquals(400, call(staticAdminToken, "PUT", "/v1/admin/settings/payments", """{"orderTtlHours":0}""").status)
        assertEquals(1, audit.find(AdminAuditQuery(action = "settings.update")).size)
        // перезапуск подхватывает сохранённое значение поверх env-умолчаний
        val reloaded = AdminSettingsService(dataSource, json, PaymentSettings(contacts = emptyList()))
        assertEquals(48, reloaded.payments().orderTtlHours)
    }

    @Test
    fun `env contacts parse and bad env falls back to empty`() {
        val parsed = PaymentSettings.fromEnv("""[{"type":"phone","value":"+99361000000","label":"Звонок"}]""")
        assertEquals("phone", parsed.contacts.single().type)
        assertTrue(PaymentSettings.fromEnv("not json").contacts.isEmpty())
        assertTrue(PaymentSettings.fromEnv(null).contacts.isEmpty())
        assertTrue(PaymentSettings.fromEnv("""[{"type":"fax","value":"1"}]""").contacts.isEmpty())
    }

    /* ── API приложения ────────────────────────────────────────────────────── */

    private fun appHandler(accountId: UUID?): ManualPaymentHttpHandler = ManualPaymentHttpHandler(
        authenticator = object : Authenticator {
            override fun authenticate(authorizationHeader: String?): AuthResult =
                if (authorizationHeader == null) AuthResult.MissingCredentials
                else AuthResult.Success(AuthenticatedClient("app", ClientTier.PRO, accountId))
        },
        authorizer = TierAuthorizer(),
        orders = orders,
        paymentSettings = settings::payments,
        rateLimiter = PostgresRateLimiter(dataSource, "manual_payment", RateLimitConfig(1000, 1000)),
        validation = ValidationConfig(),
        logger = ConsoleStructuredLogger(sink = {}),
        json = json
    )

    private fun app(handler: ManualPaymentHttpHandler, path: String, body: String, auth: String? = "Bearer omx_x"): HttpResponseContext =
        handler.handle(
            HttpRequestContext(
                method = "POST", path = path, authorizationHeader = auth,
                body = body, contentLength = body.length.toLong(), remoteAddress = "10.0.0.5"
            )
        )!!

    @Test
    fun `app tariffs list free pro and max with prices limits and no contacts yet`() {
        val handler = appHandler(seedAccount())
        val response = app(handler, "/v1/billing/tariffs", "{}")
        assertEquals(200, response.status)
        val body = obj(response)
        val plans = (body["plans"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("free", "pro", "omnix"), plans.map { (it["id"] as JsonPrimitive).content })
        assertEquals(listOf("0", "14900", "29900"), plans.map { (it["price_minor"] as JsonPrimitive).content })
        assertEquals("300", ((plans[1]["entitlements"] as JsonObject)["daily_voice_ai"] as JsonPrimitive).content)
        assertTrue((body["contacts"] as JsonArray).isEmpty())
        assertEquals(72, (body["order_ttl_hours"] as JsonPrimitive).content.toInt())
        assertTrue((body["orders"] as JsonArray).isEmpty())
    }

    @Test
    fun `app creates an order once and shows contacts and later status`() {
        settings.updatePayments("t", PaymentSettings(contacts = listOf(PaymentContact("phone", "+99361000000", "Звонок"))))
        val accountId = seedAccount()
        val handler = appHandler(accountId)
        val first = app(handler, "/v1/billing/manual/order", """{"plan_id":"pro"}""")
        assertEquals(201, first.status)
        val firstBody = obj(first)
        val order = firstBody["order"] as JsonObject
        assertTrue(((order["number"] as JsonPrimitive).content).matches(Regex("OMX-\\d{4}")))
        assertEquals("PENDING", (order["status"] as JsonPrimitive).content)
        assertEquals("14900", (order["amount_minor"] as JsonPrimitive).content)
        assertEquals("+99361000000", (((firstBody["contacts"] as JsonArray)[0] as JsonObject)["value"] as JsonPrimitive).content)

        val second = app(handler, "/v1/billing/manual/order", """{"plan_id":"pro"}""")
        assertEquals(200, second.status)
        assertEquals((order["order_id"] as JsonPrimitive).content, ((obj(second)["order"] as JsonObject)["order_id"] as JsonPrimitive).content)

        confirm(checkNotNull(orders.find(UUID.fromString((order["order_id"] as JsonPrimitive).content))))
        val listed = (obj(app(handler, "/v1/billing/tariffs", "{}"))["orders"] as JsonArray).single() as JsonObject
        assertEquals("PAID", (listed["status"] as JsonPrimitive).content)
    }

    @Test
    fun `app rejects free plan unknown plan missing auth and static tokens`() {
        val handler = appHandler(seedAccount())
        assertEquals(400, app(handler, "/v1/billing/manual/order", """{"plan_id":"free"}""").status)
        assertEquals(400, app(handler, "/v1/billing/manual/order", """{"plan_id":"nope"}""").status)
        assertEquals(400, app(handler, "/v1/billing/manual/order", "not json").status)
        assertEquals(401, app(handler, "/v1/billing/tariffs", "{}", auth = null).status)
        assertEquals(403, app(appHandler(null), "/v1/billing/tariffs", "{}").status)
        assertNull(handler.handle(HttpRequestContext("POST", "/v1/other", null, "", 0)))
    }
}
