package com.omnix.server.http

import com.omnix.server.admin.PaymentSettings
import com.omnix.server.api.ApiErrorCode
import com.omnix.server.api.ApiErrorResponse
import com.omnix.server.api.ManualOrderDto
import com.omnix.server.api.ManualOrderRequest
import com.omnix.server.api.ManualOrderResponse
import com.omnix.server.api.PaymentContactDto
import com.omnix.server.api.TariffPlanDto
import com.omnix.server.api.TariffsRequest
import com.omnix.server.api.TariffsResponse
import com.omnix.server.api.toDto
import com.omnix.server.auth.AuthResult
import com.omnix.server.auth.Authenticator
import com.omnix.server.auth.Authorizer
import com.omnix.server.auth.Permission
import com.omnix.server.billing.ManualCreateResult
import com.omnix.server.billing.ManualOrder
import com.omnix.server.billing.ManualOrderRepository
import com.omnix.server.config.ValidationConfig
import com.omnix.server.license.BillingPlan
import com.omnix.server.license.PlanCatalog
import com.omnix.server.observability.StructuredLogger
import com.omnix.server.ratelimit.RateLimitDecision
import com.omnix.server.ratelimit.RateLimiter
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * Приложение: экран «Тарифы» и заявки на ручную оплату (пока нет договора с банком).
 *
 * - POST /v1/billing/tariffs      — каталог планов (Free/Pro/Max) с лимитами и ценами,
 *   контакты владельца, свои заявки со статусами;
 * - POST /v1/billing/manual/order — создать заявку на план (или получить уже открытую).
 *
 * Тариф после подтверждения оператором применяется при следующей проверке лицензии.
 */
class ManualPaymentHttpHandler(
    private val authenticator: Authenticator,
    private val authorizer: Authorizer,
    private val orders: ManualOrderRepository,
    private val paymentSettings: () -> PaymentSettings,
    private val rateLimiter: RateLimiter,
    private val validation: ValidationConfig,
    private val logger: StructuredLogger,
    private val json: Json,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        const val PATH_TARIFFS = "/v1/billing/tariffs"
        const val PATH_MANUAL_ORDER = "/v1/billing/manual/order"
        private val PATHS = setOf(PATH_TARIFFS, PATH_MANUAL_ORDER)
        private val SHOWN_PLANS = listOf(PlanCatalog.FREE, PlanCatalog.PRO, PlanCatalog.OMNIX)
        private val PAID_PLANS = setOf(PlanCatalog.PRO, PlanCatalog.OMNIX)
        private const val FREE_CURRENCY = "TMT"
    }

    fun handle(request: HttpRequestContext): HttpResponseContext? {
        if (request.path !in PATHS) return null
        val fallbackRequestId = UUID.randomUUID().toString()
        if (request.method != "POST") return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId, 405)
        if (request.contentLength > validation.maxBodyBytes ||
            request.body.toByteArray(StandardCharsets.UTF_8).size > validation.maxBodyBytes
        ) {
            return error(ApiErrorCode.PAYLOAD_TOO_LARGE, fallbackRequestId)
        }
        return try {
            val client = (authenticator.authenticate(request.authorizationHeader) as? AuthResult.Success)?.client
                ?: return error(ApiErrorCode.UNAUTHORIZED, fallbackRequestId)
            val accountId = client.accountId ?: return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
            if (!authorizer.isAllowed(client, Permission.CREATE_BILLING_CHECKOUT)) {
                return error(ApiErrorCode.FORBIDDEN, fallbackRequestId)
            }
            rateLimit("tariffs:$accountId", fallbackRequestId)?.let { return it }
            when (request.path) {
                PATH_TARIFFS -> tariffs(request, accountId, fallbackRequestId)
                else -> createOrder(request, accountId, fallbackRequestId)
            }
        } catch (failure: Exception) {
            logger.error(
                "manual payment request failed",
                "path" to request.path,
                "requestId" to fallbackRequestId,
                "error" to failure.javaClass.simpleName
            )
            error(ApiErrorCode.INTERNAL_ERROR, fallbackRequestId)
        }
    }

    private fun tariffs(request: HttpRequestContext, accountId: UUID, fallbackRequestId: String): HttpResponseContext {
        val parsed = decode(request.body, TariffsRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        val settings = paymentSettings()
        val byId = orders.activePlans().associateBy(BillingPlan::id)
        val plans = SHOWN_PLANS.mapNotNull { id ->
            byId[id]?.toTariffDto() ?: if (id == PlanCatalog.FREE) freeTariff() else null
        }
        return ok(
            200,
            TariffsResponse.serializer(),
            TariffsResponse(
                success = true,
                plans = plans,
                contacts = settings.contacts.map { PaymentContactDto(it.type, it.value, it.label) },
                orderTtlHours = settings.orderTtlHours,
                orders = orders.listForAccount(accountId, clock.instant()).map { it.toDto() },
                requestId = requestId
            )
        )
    }

    private fun createOrder(request: HttpRequestContext, accountId: UUID, fallbackRequestId: String): HttpResponseContext {
        val parsed = decode(request.body, ManualOrderRequest.serializer())
            ?: return error(ApiErrorCode.INVALID_REQUEST, fallbackRequestId)
        val requestId = validRequestId(parsed.requestId) ?: fallbackRequestId
        if (parsed.planId !in PAID_PLANS) return error(ApiErrorCode.INVALID_REQUEST, requestId)
        val plan = orders.activePlans().firstOrNull { it.id == parsed.planId && it.amountMinor > 0 }
            ?: return error(ApiErrorCode.INVALID_REQUEST, requestId)
        val settings = paymentSettings()
        val result = orders.createOrGet(accountId, plan, Duration.ofHours(settings.orderTtlHours.toLong()), clock.instant())
        val (order, created) = when (result) {
            is ManualCreateResult.Created -> result.order to true
            is ManualCreateResult.Existing -> result.order to false
            ManualCreateResult.NoLicense -> return error(ApiErrorCode.LICENSE_NOT_REDEEMABLE, requestId)
        }
        logger.info("manual order", "requestId" to requestId, "created" to created.toString())
        return ok(
            if (created) 201 else 200,
            ManualOrderResponse.serializer(),
            ManualOrderResponse(
                success = true,
                order = order.toDto(),
                created = created,
                contacts = settings.contacts.map { PaymentContactDto(it.type, it.value, it.label) },
                orderTtlHours = settings.orderTtlHours,
                requestId = requestId
            )
        )
    }

    private fun BillingPlan.toTariffDto() = TariffPlanDto(
        id = id,
        name = displayName,
        priceMinor = amountMinor,
        currency = currency,
        durationDays = durationDays,
        entitlements = PlanCatalog.forPlanId(id).toDto()
    )

    private fun freeTariff() = TariffPlanDto(
        id = PlanCatalog.FREE,
        name = "OMNIX Free",
        priceMinor = 0,
        currency = FREE_CURRENCY,
        durationDays = 0,
        entitlements = PlanCatalog.forPlanId(PlanCatalog.FREE).toDto()
    )

    private fun ManualOrder.toDto() = ManualOrderDto(
        orderId = id.toString(),
        number = orderNumber,
        planId = planId,
        status = status.name,
        amountMinor = amountMinor,
        currency = currency,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt.toString(),
        paidAt = paidAt?.toString()
    )

    private fun rateLimit(key: String, requestId: String): HttpResponseContext? = when (val result = rateLimiter.check(key)) {
        RateLimitDecision.Allowed -> null
        is RateLimitDecision.Limited -> error(
            ApiErrorCode.RATE_LIMITED,
            requestId,
            headers = mapOf("Retry-After" to result.retryAfterSeconds.toString())
        )
    }

    private fun <T> decode(body: String, serializer: KSerializer<T>): T? =
        runCatching { json.decodeFromString(serializer, body) }.getOrNull()

    private fun validRequestId(value: String?): String? =
        value?.takeIf { it.isNotBlank() && it.length <= 64 && it.none(Char::isISOControl) }

    private fun <T> ok(status: Int, serializer: KSerializer<T>, value: T) = HttpResponseContext(
        status,
        json.encodeToString(serializer, value),
        headers = mapOf("Cache-Control" to "no-store")
    )

    private fun error(
        code: ApiErrorCode,
        requestId: String,
        statusOverride: Int? = null,
        headers: Map<String, String> = emptyMap()
    ) = HttpResponseContext(
        statusOverride ?: code.httpStatus,
        json.encodeToString(ApiErrorResponse.serializer(), code.toResponse(requestId)),
        headers
    )
}
