package com.omnix.server

import com.omnix.server.admin.AdminAccountRepository
import com.omnix.server.admin.AdminAuditLog
import com.omnix.server.admin.AdminAuthService
import com.omnix.server.admin.AdminLoginResult
import com.omnix.server.admin.AdminPasswords
import com.omnix.server.admin.AdminRole
import com.omnix.server.admin.AdminSecurityPolicy
import com.omnix.server.admin.AdminSessionRepository
import com.omnix.server.api.ApiErrorResponse
import com.omnix.server.auth.ClientTier
import com.omnix.server.auth.CompositeAuthenticator
import com.omnix.server.auth.LicenseTokenAuthenticator
import com.omnix.server.auth.TierAuthorizer
import com.omnix.server.auth.TokenAuthenticator
import com.omnix.server.billing.BillingService
import com.omnix.server.billing.JdbcBillingRepository
import com.omnix.server.clip.BtAddress
import com.omnix.server.clip.ClipAdminHttpHandler
import com.omnix.server.clip.ClipAttestationService
import com.omnix.server.clip.ClipHttpHandler
import com.omnix.server.clip.ClipPairConfig
import com.omnix.server.clip.ClipPairRegistry
import com.omnix.server.clip.JdbcClipDeviceRepository
import com.omnix.server.config.RateLimitConfig
import com.omnix.server.config.ValidationConfig
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import com.omnix.server.http.LicenseBillingHttpHandler
import com.omnix.server.license.BillingPlan
import com.omnix.server.license.JdbcLicenseRepository
import com.omnix.server.license.LicenseCrypto
import com.omnix.server.license.LicenseService
import com.omnix.server.observability.ConsoleStructuredLogger
import com.omnix.server.ratelimit.PostgresRateLimiter
import com.omnix.server.ratelimit.SlidingWindowRateLimiter
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * V012: реестр готовых пар OMNIX Clip по BT-адресу и каталог моделей.
 *
 * Привязка PROVISIONED-пары без кода создаёт аккаунт + лицензию тарифа пары
 * (по умолчанию «free») + токен; повтор тем же аккаунтом идемпотентен; чужая
 * и REVOKED пары — отказ; адрес нормализуется; несколько пар на аккаунт с
 * лимитом; отвязка владельцем (RELEASED) позволяет передать пару; действия
 * админки — по правам и с аудитом; скрытая модель клиенту не отдаётся.
 */
class ClipPairRegistryIntegrationTest : PostgresTestSupport() {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val staticAdminToken = "c".repeat(64)

    private lateinit var licenseService: LicenseService
    private lateinit var licenseHandler: LicenseBillingHttpHandler
    private lateinit var clipHandler: ClipHttpHandler
    private lateinit var adminHandler: ClipAdminHttpHandler
    private lateinit var adminAuth: AdminAuthService
    private lateinit var adminAccounts: AdminAccountRepository

    @Before
    fun buildHandlers() {
        val crypto = LicenseCrypto("test-license-pepper-32-bytes-minimum-value")
        val licenseRepository = JdbcLicenseRepository(dataSource, crypto)
        licenseService = LicenseService(licenseRepository, crypto)
        licenseService.upsertPlan(BillingPlan("free", "omnix-free", "OMNIX Free", 3650, 0, "TMT"))
        licenseService.upsertPlan(BillingPlan("omnix", "omnix-clip", "OMNIX Clip", 30, 19_900, "TMT"))
        licenseService.upsertPlan(BillingPlan("pro", "omnix-pro", "OMNIX Pro", 30, 39_900, "TMT"))
        val staticAuth = TokenAuthenticator(mapOf(staticAdminToken to "ops-cli")) { ClientTier.ADMIN }
        val auth = CompositeAuthenticator(staticAuth, LicenseTokenAuthenticator(licenseService))
        val pairConfig = ClipPairConfig(maxPairsPerAccount = 2, pairRequired = true)
        val registry = ClipPairRegistry(dataSource, crypto, pairConfig)
        licenseHandler = LicenseBillingHttpHandler(
            authenticator = auth,
            authorizer = TierAuthorizer(),
            licenseService = licenseService,
            billingService = BillingService(JdbcBillingRepository(dataSource), licenseRepository, listOf()),
            paddleWebhookVerifier = com.omnix.server.billing.PaddleWebhookVerifier(
                com.omnix.server.billing.PaddleBillingConfig("api-key", "webhook-secret"), json
            ),
            heleketWebhookVerifier = com.omnix.server.billing.HeleketWebhookVerifier(
                com.omnix.server.billing.HeleketBillingConfig(
                    "merchant", "heleket-secret",
                    "https://api.example.com/v1/billing/webhooks/heleket",
                    "https://example.com/return",
                    "https://example.com/success"
                ), json
            ),
            redeemRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 1_000)),
            authenticatedRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 1_000)),
            webhookRateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 1_000)),
            validation = ValidationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            json = json,
            clipDevices = JdbcClipDeviceRepository(dataSource),
            clipPairConfig = pairConfig
        )
        val audit = AdminAuditLog(dataSource)
        clipHandler = ClipHttpHandler(
            authenticator = auth,
            authorizer = TierAuthorizer(),
            attestationService = ClipAttestationService(JdbcClipDeviceRepository(dataSource)),
            rateLimiter = SlidingWindowRateLimiter(RateLimitConfig(100, 1_000)),
            validation = ValidationConfig(),
            logger = ConsoleStructuredLogger(sink = {}),
            json = json,
            pairRegistry = registry,
            isOperatorAccount = { false },
            adminAudit = audit
        )
        adminAccounts = AdminAccountRepository(dataSource)
        val policy = AdminSecurityPolicy()
        adminAuth = AdminAuthService(
            accounts = adminAccounts,
            sessions = AdminSessionRepository(dataSource),
            loginRateLimiter = PostgresRateLimiter(
                dataSource, "admin_login", RateLimitConfig(policy.loginMaxAttempts, policy.loginMaxAttempts)
            ),
            policy = policy
        )
        adminHandler = ClipAdminHttpHandler(adminAuth, staticAuth, registry, audit, json)
    }

    /* ---------------------------------------------------------------- helpers */

    private fun request(
        path: String,
        token: String? = null,
        body: String = "",
        method: String = "POST",
        rawQuery: String? = null
    ) = HttpRequestContext(
        method = method,
        path = path,
        authorizationHeader = token?.let { "Bearer $it" },
        body = body,
        contentLength = body.toByteArray().size.toLong(),
        remoteAddress = "127.0.0.1",
        rawQuery = rawQuery
    )

    private fun admin(method: String, path: String, body: String = "", token: String = staticAdminToken, rawQuery: String? = null) =
        checkNotNull(adminHandler.handle(request(path, token, body, method, rawQuery)))

    private fun clip(path: String, token: String? = null, body: String = "", method: String = "POST") =
        checkNotNull(clipHandler.handle(request(path, token, body, method)))

    private fun obj(response: HttpResponseContext): JsonObject = json.parseToJsonElement(response.body).jsonObject

    private fun errorCode(response: HttpResponseContext): String =
        json.decodeFromString(ApiErrorResponse.serializer(), response.body).error.code

    private fun register(serial: String, address: String, extra: String = ""): HttpResponseContext =
        admin("POST", ClipAdminHttpHandler.PREFIX, """{"clipSerial":"$serial","btAddress":"$address"$extra}""")

    private fun bind(address: String, deviceId: String, token: String? = null) =
        clip(ClipHttpHandler.PATH_PAIR_BIND, token, """{"bt_address":"$address","device_id":"$deviceId","request_id":"bind-1"}""")

    private fun tokenOf(response: HttpResponseContext): String = obj(response)["access_token"]!!.jsonPrimitive.content

    private fun validate(token: String, deviceId: String): HttpResponseContext = runBlocking {
        checkNotNull(
            licenseHandler.handle(
                request(LicenseBillingHttpHandler.PATH_VALIDATE, token, """{"device_id":"$deviceId"}""")
            )
        )
    }

    private fun entryId(serial: String): String =
        obj(admin("GET", ClipAdminHttpHandler.PREFIX, rawQuery = "q=$serial"))["earbuds"]!!.jsonArray
            .first().jsonObject["id"]!!.jsonPrimitive.content

    private fun auditCount(action: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM admin_audit_log WHERE action = ?").use {
            it.setString(1, action)
            it.executeQuery().use { result -> result.next(); result.getInt(1) }
        }
    }

    private fun sessionToken(username: String, role: AdminRole): String {
        adminAccounts.create(username, AdminPasswords.hash("operator-pass-123"), role, Instant.now())
        val result = adminAuth.login(username, "operator-pass-123", "10.0.0.1")
        return (result as AdminLoginResult.Success).rawToken
    }

    /* ------------------------------------------------------------------ tests */

    @Test
    fun `address normalization accepts common formats and rejects placeholders`() {
        assertEquals("AA:BB:CC:DD:EE:01", BtAddress.normalize("aa-bb-cc-dd-ee-01"))
        assertEquals("AA:BB:CC:DD:EE:01", BtAddress.normalize(" aabbccddee01 "))
        assertEquals("AA:BB:CC:DD:EE:01", BtAddress.normalize("AA:BB:CC:DD:EE:01"))
        assertNull(BtAddress.normalize("02:00:00:00:00:00"))
        assertNull(BtAddress.normalize("00:00:00:00:00:00"))
        assertNull(BtAddress.normalize("AA:BB:CC:DD:EE"))
        assertNull(BtAddress.normalize("GG:BB:CC:DD:EE:01"))
        assertEquals("AA:••:••:••:EE:01", BtAddress.mask("AA:BB:CC:DD:EE:01"))

        assertEquals(201, register("OMX-N-1", "aa-bb-cc-dd-ee-01").status)
        // Тот же адрес в другой записи — дубль, а не вторая пара.
        val duplicate = register("OMX-N-2", "AABBCCDDEE01")
        assertEquals(409, duplicate.status)
        assertEquals("duplicate_address", obj(duplicate)["error"]!!.jsonPrimitive.content)
        assertEquals(400, register("OMX-N-3", "02:00:00:00:00:00").status)
        // В списке админки адрес маскирован.
        val listed = admin("GET", ClipAdminHttpHandler.PREFIX).body
        assertFalse(listed.contains("AA:BB:CC:DD:EE:01"))
        assertTrue(listed.contains("AA:••:••:••:EE:01"))
        // Привязка принимает адрес в любом из форматов.
        assertEquals(200, bind("aa:bb:cc:dd:ee:01", "device-norm-0001").status)
    }

    @Test
    fun `new buyer binds provisioned pair without code and gets free plan access`() {
        assertEquals(201, register("OMX-B-1", "10:20:30:40:50:01").status)
        val bound = bind("10:20:30:40:50:01", "device-buyer-0001")
        assertEquals(200, bound.status)
        val body = obj(bound)
        assertEquals("free", body["plan_id"]!!.jsonPrimitive.content)
        assertEquals("true", body["bound_now"]!!.jsonPrimitive.content)
        val token = tokenOf(bound)

        val validated = validate(token, "device-buyer-0001")
        assertEquals(200, validated.status)
        val clipState = obj(validated)["clip"]!!.jsonObject
        assertEquals("true", clipState["pair_required"]!!.jsonPrimitive.content)
        val pairs = clipState["pairs"]!!.jsonArray
        assertEquals(1, pairs.size)
        assertEquals("10:20:30:40:50:01", pairs[0].jsonObject["bt_address"]!!.jsonPrimitive.content)
        // AI-путь (с устройством) пускает, пока пара ACTIVE.
        assertNotNull(licenseService.authenticateAccessToken(token, "device-buyer-0001"))
        // Тариф берётся из записи реестра, если задан.
        assertEquals(201, register("OMX-B-2", "10:20:30:40:50:02", ""","planId":"omnix"""").status)
        assertEquals("omnix", obj(bind("10:20:30:40:50:02", "device-buyer-0002"))["plan_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `repeat bind by same account is idempotent and foreign or revoked pairs are refused`() {
        register("OMX-I-1", "10:20:30:40:51:01")
        val first = bind("10:20:30:40:51:01", "device-owner-0001")
        val token = tokenOf(first)

        val again = bind("10:20:30:40:51:01", "device-owner-0001", token)
        assertEquals(200, again.status)
        assertEquals("false", obj(again)["bound_now"]!!.jsonPrimitive.content)
        // Переустановка без токена с того же телефона — тоже повтор.
        val reinstall = bind("10:20:30:40:51:01", "device-owner-0001")
        assertEquals(200, reinstall.status)
        assertEquals("false", obj(reinstall)["bound_now"]!!.jsonPrimitive.content)
        // Лицензии не выдаются повторно: базовая free + пробный Pro первой привязки.
        val licenses = dataSource.connection.use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM licenses").use { it.executeQuery().use { r -> r.next(); r.getInt(1) } }
        }
        assertEquals(2, licenses)

        // Чужой телефон без токена и чужой аккаунт — отказ.
        val stranger = bind("10:20:30:40:51:01", "device-strange-01")
        assertEquals(403, stranger.status)
        assertEquals("CLIP_OWNER_MISMATCH", errorCode(stranger))
        register("OMX-I-2", "10:20:30:40:51:02")
        val otherToken = tokenOf(bind("10:20:30:40:51:02", "device-other-0001"))
        val foreign = bind("10:20:30:40:51:01", "device-other-0001", otherToken)
        assertEquals("CLIP_OWNER_MISMATCH", errorCode(foreign))

        // REVOKED — отказ всегда, и AI-путь владельца закрывается.
        assertEquals(200, admin("POST", "${ClipAdminHttpHandler.PREFIX}/${entryId("OMX-I-1")}/revoke", """{"reason":"stolen"}""").status)
        val revoked = bind("10:20:30:40:51:01", "device-owner-0001", token)
        assertEquals(403, revoked.status)
        assertEquals("CLIP_REVOKED", errorCode(revoked))
        assertNull(licenseService.authenticateAccessToken(token, "device-owner-0001"))
        // validate (без устройства на уровне токена) продолжает работать: клиент видит аккаунт.
        assertEquals(200, validate(token, "device-owner-0001").status)

        // Неизвестная пара.
        assertEquals("CLIP_UNKNOWN", errorCode(bind("10:20:30:40:59:99", "device-owner-0001")))
    }

    private fun trialOf(response: HttpResponseContext): String? =
        obj(response)["trial_expires_at"]?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.jsonPrimitive?.content

    @Test
    fun `first ever binding grants a seven day pro trial once per pair and once per account`() {
        register("OMX-T-1", "10:20:30:40:57:01")
        register("OMX-T-2", "10:20:30:40:57:02")
        val first = bind("10:20:30:40:57:01", "device-trial-0001")
        assertEquals(200, first.status)
        val trialEnd = Instant.parse(checkNotNull(trialOf(first)))
        val days = java.time.Duration.between(Instant.now(), trialEnd).toHours()
        assertTrue("trial ~7 days, got $days h", days in 167..168)
        val token = tokenOf(first)
        // Во время пробного validate отдаёт pro, базовый тариф пары остаётся free.
        assertEquals("free", obj(first)["plan_id"]!!.jsonPrimitive.content)
        assertEquals("pro", obj(validate(token, "device-trial-0001"))["plan_id"]!!.jsonPrimitive.content)
        assertEquals("pro", licenseService.activePlanId(accountOf(token)))

        // Повтор той же привязки — без нового пробного.
        assertNull(trialOf(bind("10:20:30:40:57:01", "device-trial-0001", token)))
        // Вторая пара тому же аккаунту — без пробного.
        assertNull(trialOf(bind("10:20:30:40:57:02", "device-trial-0001", token)))

        // Отвязка и привязка той же пары другим аккаунтом — пара уже давала пробный.
        assertEquals(200, clip(ClipHttpHandler.PATH_PAIR_RELEASE, token, """{"clip_serial":"OMX-T-1"}""").status)
        val other = bind("10:20:30:40:57:01", "device-trial-0002")
        assertEquals(200, other.status)
        assertNull(trialOf(other))
        assertEquals("free", obj(validate(tokenOf(other), "device-trial-0002"))["plan_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no trial when the account already has an active paid plan`() {
        // Пара с платным тарифом в реестре: аккаунт сразу на omnix — пробный не нужен.
        register("OMX-P-1", "10:20:30:40:58:01", ""","planId":"omnix"""")
        val bound = bind("10:20:30:40:58:01", "device-paid-0001")
        assertEquals(200, bound.status)
        assertNull(trialOf(bound))
        assertEquals("omnix", obj(validate(tokenOf(bound), "device-paid-0001"))["plan_id"]!!.jsonPrimitive.content)
        // Флаг пары не сгорел: пробный этой парой не выдан.
        val flag = dataSource.connection.use { c ->
            c.prepareStatement("SELECT trial_granted_at FROM clip_devices WHERE clip_serial = 'OMX-P-1'").use {
                it.executeQuery().use { r -> r.next(); r.getObject(1) }
            }
        }
        assertNull(flag)
    }

    private fun accountOf(token: String): UUID = checkNotNull(licenseService.authenticateAccessToken(token)).accountId

    @Test
    fun `account may own several pairs up to the limit and status reports them`() {
        register("OMX-M-1", "10:20:30:40:52:01")
        register("OMX-M-2", "10:20:30:40:52:02")
        register("OMX-M-3", "10:20:30:40:52:03")
        val token = tokenOf(bind("10:20:30:40:52:01", "device-multi-0001"))

        // Новая зарегистрированная пара видна как AVAILABLE, своя — OWNED.
        val available = clip(ClipHttpHandler.PATH_PAIR_STATUS, token, """{"bt_address":"10:20:30:40:52:02"}""")
        assertEquals("AVAILABLE", obj(available)["state"]!!.jsonPrimitive.content)
        val owned = clip(ClipHttpHandler.PATH_PAIR_STATUS, token, """{"bt_address":"10:20:30:40:52:01"}""")
        assertEquals("OWNED", obj(owned)["state"]!!.jsonPrimitive.content)

        // Вторая пара — к тому же аккаунту, без кода.
        val second = bind("10:20:30:40:52:02", "device-multi-0001", token)
        assertEquals(200, second.status)
        val pairs = obj(validate(token, "device-multi-0001"))["clip"]!!.jsonObject["pairs"]!!.jsonArray
        assertEquals(2, pairs.size)

        // Лимит (в тесте 2) — третья не привязывается.
        val third = bind("10:20:30:40:52:03", "device-multi-0001", token)
        assertEquals(409, third.status)
        assertEquals("CLIP_PAIR_LIMIT", errorCode(third))

        // Чужая пара для другого аккаунта — UNAVAILABLE без подробностей.
        register("OMX-M-4", "10:20:30:40:52:04")
        val otherToken = tokenOf(bind("10:20:30:40:52:04", "device-multi-0002"))
        val foreign = obj(clip(ClipHttpHandler.PATH_PAIR_STATUS, otherToken, """{"bt_address":"10:20:30:40:52:01"}"""))
        assertEquals("UNAVAILABLE", foreign["state"]!!.jsonPrimitive.content)
        assertNull(foreign["clip_serial"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
    }

    @Test
    fun `owner release makes pair transferable and closes AI path without other pairs`() {
        register("OMX-R-1", "10:20:30:40:53:01")
        val token = tokenOf(bind("10:20:30:40:53:01", "device-release-01"))
        val released = clip(ClipHttpHandler.PATH_PAIR_RELEASE, token, """{"clip_serial":"OMX-R-1"}""")
        assertEquals(200, released.status)
        assertTrue(released.body.contains("RELEASED"))
        // Без ACTIVE-пар AI-путь закрыт, validate — нет (можно привязать заново).
        assertNull(licenseService.authenticateAccessToken(token, "device-release-01"))
        assertEquals(200, validate(token, "device-release-01").status)

        // Новый владелец может привязать отпущенную пару.
        val newOwner = bind("10:20:30:40:53:01", "device-release-02")
        assertEquals(200, newOwner.status)
        // Чужую пару отпустить нельзя.
        assertEquals(404, clip(ClipHttpHandler.PATH_PAIR_RELEASE, token, """{"clip_serial":"OMX-R-1"}""").status)
    }

    @Test
    fun `admin unbind and revoke are permission checked and audited`() {
        register("OMX-A-1", "10:20:30:40:54:01")
        bind("10:20:30:40:54:01", "device-admin-0001")
        val id = entryId("OMX-A-1")
        assertEquals(1, auditCount("clip.register"))

        // VIEWER видит список, но не меняет; SUPPORT тоже не меняет.
        val viewer = sessionToken("viewer1", AdminRole.VIEWER)
        assertEquals(200, admin("GET", ClipAdminHttpHandler.PREFIX, token = viewer).status)
        assertEquals(403, admin("POST", "${ClipAdminHttpHandler.PREFIX}/$id/unbind", """{"reason":"x"}""", token = viewer).status)
        val support = sessionToken("support1", AdminRole.SUPPORT)
        assertEquals(403, admin("POST", "${ClipAdminHttpHandler.PREFIX}/$id/revoke", """{"reason":"x"}""", token = support).status)
        assertEquals(
            403,
            admin("POST", ClipAdminHttpHandler.PREFIX, """{"clipSerial":"OMX-A-3","btAddress":"10:20:30:40:54:03"}""", token = viewer).status
        )
        assertEquals(401, admin("GET", ClipAdminHttpHandler.PREFIX, token = "nope").status)

        // Причина обязательна.
        assertEquals(400, admin("POST", "${ClipAdminHttpHandler.PREFIX}/$id/unbind", "{}").status)

        val unbound = admin("POST", "${ClipAdminHttpHandler.PREFIX}/$id/unbind", """{"reason":"возврат"}""")
        assertEquals(200, unbound.status)
        assertEquals("PROVISIONED", obj(unbound)["earbud"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals(1, auditCount("clip.unbind"))
        // Повторная отвязка PROVISIONED — 409.
        assertEquals(409, admin("POST", "${ClipAdminHttpHandler.PREFIX}/$id/unbind", """{"reason":"again"}""").status)

        val revoked = admin("POST", "${ClipAdminHttpHandler.PREFIX}/$id/revoke", """{"reason":"брак"}""")
        assertEquals(200, revoked.status)
        assertEquals(1, auditCount("clip.revoke"))
        val filtered = obj(admin("GET", ClipAdminHttpHandler.PREFIX, rawQuery = "status=REVOKED"))["earbuds"]!!.jsonArray
        assertEquals(listOf("OMX-A-1"), filtered.map { it.jsonObject["clipSerial"]!!.jsonPrimitive.content })
    }

    @Test
    fun `bulk registration reports each line`() {
        val response = admin(
            "POST", "${ClipAdminHttpHandler.PREFIX}/bulk",
            """{"lines":"OMX-K-1;10:20:30:40:55:01\nOMX-K-2;10-20-30-40-55-02\nbroken line\nOMX-K-1;10:20:30:40:55:03"}"""
        )
        assertEquals(200, response.status)
        val body = obj(response)
        assertEquals(2, body["registered"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, body["failed"]!!.jsonPrimitive.content.toInt())
        val results = body["results"] as JsonArray
        assertEquals("duplicate_serial", results[3].jsonObject["error"]!!.jsonPrimitive.content)
        assertEquals(2, auditCount("clip.register"))
    }

    @Test
    fun `model catalog is managed by admin and hidden models are not served to clients`() {
        val created = admin(
            "POST", "${ClipAdminHttpHandler.PREFIX}/models",
            """{"id":"clip-1-black","displayName":"OMNIX Clip","colorName":"Чёрный","colorHex":"#111111","btNamePatterns":["OMNIX Clip"]}"""
        )
        assertEquals(201, created.status)
        admin(
            "POST", "${ClipAdminHttpHandler.PREFIX}/models",
            """{"id":"clip-1-white","displayName":"OMNIX Clip","colorName":"Белый","colorHex":"#f5f5f5"}"""
        )
        assertEquals(409, admin("POST", "${ClipAdminHttpHandler.PREFIX}/models", """{"id":"clip-1-white","displayName":"X","colorName":"Y","colorHex":"#000000"}""").status)
        assertEquals(400, admin("POST", "${ClipAdminHttpHandler.PREFIX}/models", """{"id":"bad","displayName":"X","colorName":"Y","colorHex":"red"}""").status)
        assertEquals(2, auditCount("clip.model.create"))

        // Скрыть белую.
        val hidden = admin(
            "PUT", "${ClipAdminHttpHandler.PREFIX}/models/clip-1-white",
            """{"displayName":"OMNIX Clip","colorName":"Белый","colorHex":"#F5F5F5","status":"HIDDEN"}"""
        )
        assertEquals(200, hidden.status)
        assertEquals(1, auditCount("clip.model.update"))

        val catalog = clip(ClipHttpHandler.PATH_MODELS, method = "GET")
        assertEquals(200, catalog.status)
        assertEquals("public, max-age=600", catalog.headers["Cache-Control"])
        val ids = obj(catalog)["models"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(listOf("clip-1-black"), ids)
        // Только публичные поля: шаблоны имени и статус клиенту не уходят.
        assertFalse(catalog.body.contains("bt_name_patterns"))
        assertFalse(catalog.body.contains("HIDDEN"))
        // Админка видит обе.
        assertEquals(2, obj(admin("GET", "${ClipAdminHttpHandler.PREFIX}/models"))["models"]!!.jsonArray.size)

        // Пара с моделью: модель приходит в ответе привязки; неизвестная модель — отказ регистрации.
        assertEquals(400, register("OMX-C-0", "10:20:30:40:56:00", ""","modelId":"no-such"""").status)
        register("OMX-C-1", "10:20:30:40:56:01", ""","modelId":"clip-1-black"""")
        val bound = obj(bind("10:20:30:40:56:01", "device-model-0001"))
        assertEquals("Чёрный", bound["model"]!!.jsonObject["color_name"]!!.jsonPrimitive.content)
        // Пара скрытой модели привязывается, но модель клиенту не раскрывается.
        register("OMX-C-2", "10:20:30:40:56:02", ""","modelId":"clip-1-white"""")
        val hiddenBound = obj(bind("10:20:30:40:56:02", "device-model-0002"))
        assertTrue(hiddenBound["model"] == null || hiddenBound["model"] is kotlinx.serialization.json.JsonNull)
    }
}
