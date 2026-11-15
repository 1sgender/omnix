package com.omnix.server.admin

import com.omnix.server.billing.ManualCancelResult
import com.omnix.server.billing.ManualConfirmResult
import com.omnix.server.billing.ManualOrder
import com.omnix.server.billing.ManualOrderRepository
import com.omnix.server.http.HttpRequestContext
import com.omnix.server.http.HttpResponseContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Clock
import java.util.UUID

/**
 * Админка: заявки на ручную оплату тарифов (страница «Заявки»).
 * Права проверяет вызывающий [AdminHttpHandler]: просмотр — SUBSCRIPTIONS_READ,
 * подтверждение и отмена — LICENSES_WRITE. Каждое изменение пишется в аудит.
 */
class ManualPaymentAdminApi(
    private val orders: ManualOrderRepository,
    private val audit: AdminAuditLog,
    private val clock: Clock = Clock.systemUTC()
) {
    companion object {
        private const val MAX_NOTE = 512
        private const val MIN_REASON = 3
        private const val MAX_REASON = 256
    }

    fun list(status: String?, query: String?, limit: Int, offset: Long): HttpResponseContext {
        val normalized = status?.uppercase()?.takeIf { it.isNotBlank() }
        if (normalized != null && normalized !in ManualOrderRepository.FILTER_STATUSES) {
            return failure(400, "unknown status filter")
        }
        val rows = orders.list(normalized, query, limit, offset, clock.instant())
        return ok(
            buildJsonObject {
                putJsonArray("requests") { rows.forEach { add(orderJson(it)) } }
            }
        )
    }

    fun confirm(
        principal: AdminPrincipal,
        request: HttpRequestContext,
        id: String,
        body: JsonObject?
    ): HttpResponseContext {
        val orderId = parseId(id) ?: return failure(404, "not_found")
        val paid = (body?.get("paidAmountMinor") as? JsonPrimitive)?.content?.toLongOrNull()
        if (paid == null || paid <= 0) return failure(400, "paidAmountMinor must be a positive integer")
        val method = (body?.get("method") as? JsonPrimitive)?.content?.trim().orEmpty()
        if (method !in ManualOrderRepository.PAYMENT_METHODS) return failure(400, "unknown payment method")
        val note = (body?.get("note") as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotEmpty() }
        if (note != null && note.length > MAX_NOTE) return failure(400, "note must be at most $MAX_NOTE characters")
        return when (val result = orders.confirm(orderId, paid, method, note, principal.actor, clock.instant())) {
            is ManualConfirmResult.Confirmed -> {
                audit.append(
                    actor = principal.actor, action = "payment.confirm", entityType = "ORDER",
                    entityId = orderId.toString(),
                    oldValue = buildJsonObject { put("status", "PENDING") }.toString(),
                    newValue = buildJsonObject {
                        put("number", result.order.orderNumber)
                        put("planId", result.order.planId)
                        put("accountId", result.order.accountId.toString())
                        put("expectedAmountMinor", result.order.amountMinor)
                        put("paidAmountMinor", paid)
                        put("method", method)
                        put("note", note)
                    }.toString(),
                    remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
                )
                ok(buildJsonObject { put("request", orderJson(result.order)) })
            }
            ManualConfirmResult.NotFound -> failure(404, "not_found")
            is ManualConfirmResult.NotConfirmable -> failure(409, "not_confirmable", result.status.name)
            ManualConfirmResult.FulfillmentFailed -> failure(409, "fulfillment_failed")
        }
    }

    fun cancel(
        principal: AdminPrincipal,
        request: HttpRequestContext,
        id: String,
        body: JsonObject?
    ): HttpResponseContext {
        val orderId = parseId(id) ?: return failure(404, "not_found")
        val reason = (body?.get("reason") as? JsonPrimitive)?.content?.trim().orEmpty()
        if (reason.length !in MIN_REASON..MAX_REASON) {
            return failure(400, "reason must be $MIN_REASON..$MAX_REASON characters")
        }
        return when (val result = orders.cancel(orderId, reason, principal.actor, clock.instant())) {
            is ManualCancelResult.Canceled -> {
                audit.append(
                    actor = principal.actor, action = "payment.cancel", entityType = "ORDER",
                    entityId = orderId.toString(),
                    oldValue = buildJsonObject { put("status", "PENDING") }.toString(),
                    newValue = buildJsonObject {
                        put("number", result.order.orderNumber)
                        put("planId", result.order.planId)
                        put("reason", reason)
                    }.toString(),
                    remoteAddress = request.remoteAddress, sessionId = principal.sessionId, requestId = null
                )
                ok(buildJsonObject { put("request", orderJson(result.order)) })
            }
            ManualCancelResult.NotFound -> failure(404, "not_found")
            is ManualCancelResult.NotCancelable -> failure(409, "not_cancelable", result.status.name)
        }
    }

    private fun parseId(id: String): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

    private fun orderJson(order: ManualOrder) = buildJsonObject {
        put("id", order.id.toString())
        put("number", order.orderNumber)
        put("accountId", order.accountId.toString())
        put("accountRef", order.accountRef)
        put("planId", order.planId)
        put("status", order.status.name)
        put("amountMinor", order.amountMinor)
        put("currency", order.currency)
        put("createdAt", order.createdAt.toString())
        put("expiresAt", order.expiresAt.toString())
        put("paidAt", order.paidAt?.toString())
        put("paidAmountMinor", order.paidAmountMinor)
        put("method", order.paymentMethod)
        put("note", order.resolutionNote)
        put("resolvedBy", order.resolvedBy)
    }

    private fun ok(body: JsonObject) = response(200, body)

    private fun failure(status: Int, error: String, detail: String? = null) =
        response(status, buildJsonObject { put("error", error); detail?.let { put("status", it) } })

    private fun response(status: Int, body: JsonObject) = HttpResponseContext(
        status, body.toString(),
        mapOf("Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff")
    )
}
