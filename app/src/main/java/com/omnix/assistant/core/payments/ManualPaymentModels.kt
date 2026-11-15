package com.omnix.assistant.core.payments

import com.omnix.assistant.core.license.PlanEntitlements
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/** Тариф из каталога сервера: цена и лимиты не хранятся в приложении. */
@Serializable
data class TariffPlan(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("price_minor") val priceMinor: Long,
    @SerialName("currency") val currency: String,
    @SerialName("duration_days") val durationDays: Int,
    @SerialName("entitlements") val entitlements: PlanEntitlements = PlanEntitlements.FREE
) {
    val isFree: Boolean get() = priceMinor == 0L
}

/** Контакт владельца для оплаты. */
@Serializable
data class PaymentContact(
    @SerialName("type") val type: String,
    @SerialName("value") val value: String,
    @SerialName("label") val label: String = ""
) {
    val kind: ContactKind get() = ContactKind.fromServer(type)
}

enum class ContactKind {
    PHONE, TELEGRAM, IMO, WHATSAPP, OTHER;

    companion object {
        fun fromServer(raw: String): ContactKind = when (raw.lowercase(Locale.ROOT)) {
            "phone" -> PHONE
            "telegram" -> TELEGRAM
            "imo" -> IMO
            "whatsapp" -> WHATSAPP
            else -> OTHER
        }
    }
}

/** Статус заявки: что пользователь видит на экране. */
enum class ManualOrderStatus {
    PENDING, PAID, CANCELED, EXPIRED, UNKNOWN;

    /** Заявка закрыта: платить по ней уже нельзя. */
    val isFinal: Boolean get() = this != PENDING

    companion object {
        fun fromServer(raw: String): ManualOrderStatus = when (raw.uppercase(Locale.ROOT)) {
            "PENDING" -> PENDING
            "PAID" -> PAID
            "CANCELED" -> CANCELED
            "EXPIRED" -> EXPIRED
            else -> UNKNOWN
        }
    }
}

@Serializable
data class ManualOrderDto(
    @SerialName("order_id") val orderId: String,
    @SerialName("number") val number: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("status") val status: String,
    @SerialName("amount_minor") val amountMinor: Long,
    @SerialName("currency") val currency: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("paid_at") val paidAt: String? = null
) {
    fun toOrder() = ManualOrder(
        orderId = orderId,
        number = number,
        planId = planId,
        status = ManualOrderStatus.fromServer(status),
        amountMinor = amountMinor,
        currency = currency,
        expiresAtIso = expiresAt
    )
}

data class ManualOrder(
    val orderId: String,
    val number: String,
    val planId: String,
    val status: ManualOrderStatus,
    val amountMinor: Long,
    val currency: String,
    val expiresAtIso: String
)

@Serializable
data class TariffsResponse(
    @SerialName("plans") val plans: List<TariffPlan> = emptyList(),
    @SerialName("contacts") val contacts: List<PaymentContact> = emptyList(),
    @SerialName("order_ttl_hours") val orderTtlHours: Int = 72,
    @SerialName("orders") val orders: List<ManualOrderDto> = emptyList()
)

@Serializable
data class ManualOrderResponse(
    @SerialName("order") val order: ManualOrderDto,
    @SerialName("created") val created: Boolean = true,
    @SerialName("contacts") val contacts: List<PaymentContact> = emptyList(),
    @SerialName("order_ttl_hours") val orderTtlHours: Int = 72
)

/** Каталог тарифов, контакты и заявки аккаунта одним снимком. */
data class TariffsCatalog(
    val plans: List<TariffPlan>,
    val contacts: List<PaymentContact>,
    val orders: List<ManualOrder>
)

/** Созданная (или уже открытая) заявка вместе с контактами для оплаты. */
data class OrderTicket(
    val order: ManualOrder,
    val contacts: List<PaymentContact>
)

sealed interface PaymentsResult<out T> {
    data class Ok<T>(val value: T) : PaymentsResult<T>
    data object Unauthorized : PaymentsResult<Nothing>

    /** У аккаунта нет лицензии: сначала активация OMNIX. */
    data object NoLicense : PaymentsResult<Nothing>
    data object RateLimited : PaymentsResult<Nothing>
    data object Unavailable : PaymentsResult<Nothing>
    data object NoConnection : PaymentsResult<Nothing>
}

/** Цена для показа: «149 TMT», дробная часть только если она есть. */
fun formatPrice(amountMinor: Long, currency: String): String {
    val whole = amountMinor / 100
    val cents = amountMinor % 100
    val number = if (cents == 0L) whole.toString() else String.format(Locale.ROOT, "%d.%02d", whole, cents)
    return "$number $currency"
}

/**
 * Нужно ли перепроверить лицензию на сервере: заявка оплачена, а активный
 * тариф ещё не тот (подтверждение пришло, приложение о нём не знает).
 */
fun needsLicenseRefresh(orders: List<ManualOrder>, currentPlanId: String): Boolean =
    orders.any { it.status == ManualOrderStatus.PAID && it.planId != currentPlanId && planRank(it.planId) > planRank(currentPlanId) }

/** Порядок тарифов по старшинству: Free < Pro < Max (id `omnix` = Max). */
fun planRank(planId: String): Int = when (planId) {
    "omnix" -> 2
    "pro" -> 1
    else -> 0
}

/** Какую заявку показывать по плану: открытая важнее закрытых, иначе самая свежая из серверного списка. */
fun orderForPlan(orders: List<ManualOrder>, planId: String): ManualOrder? {
    val ofPlan = orders.filter { it.planId == planId }
    return ofPlan.firstOrNull { it.status == ManualOrderStatus.PENDING } ?: ofPlan.firstOrNull()
}
