package com.omnix.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Экран «Тарифы»: запрос каталога, контактов и своих заявок. */
@Serializable
data class TariffsRequest(
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class TariffPlanDto(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    /** Цена за период в минорных единицах (тмт * 100); 0 = бесплатный. */
    @SerialName("price_minor") val priceMinor: Long,
    @SerialName("currency") val currency: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("entitlements") val entitlements: PlanEntitlementsDto
)

@Serializable
data class PaymentContactDto(
    @SerialName("type") val type: String,
    @SerialName("value") val value: String,
    @SerialName("label") val label: String
)

@Serializable
data class ManualOrderDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("number") val number: String,
    @SerialName("plan_id") val planId: String,
    /** PENDING | PAID | CANCELED | EXPIRED */
    @SerialName("status") val status: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("currency") val currency: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("paid_at") val paidAt: String? = null
)

@Serializable
data class TariffsResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("plans") val plans: List<TariffPlanDto>,
    @SerialName("contacts") val contacts: List<PaymentContactDto>,
    @SerialName("order_ttl_hours") val orderTtlHours: Int,
    @SerialName("orders") val orders: List<ManualOrderDto>,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class ManualOrderRequest(
    @SerialName("plan_id") val planId: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class ManualOrderResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("order") val order: ManualOrderDto,
    /** true — заявка создана сейчас; false — показана уже открытая. */
    @SerialName("created") val created: Boolean,
    @SerialName("contacts") val contacts: List<PaymentContactDto>,
    @SerialName("order_ttl_hours") val orderTtlHours: Int,
    @SerialName("request_id") val requestId: String
)
