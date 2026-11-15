package com.omnix.server.admin

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Контакт владельца для ручной оплаты (телефон, мессенджер). */
@Serializable
data class PaymentContact(
    /** phone | telegram | imo | whatsapp */
    val type: String,
    val value: String,
    val label: String = ""
) {
    init {
        require(type in TYPES) { "contact type must be one of $TYPES" }
        require(value.isNotBlank() && value.length <= MAX_VALUE) { "contact value must be 1..$MAX_VALUE characters" }
        require(label.length <= MAX_LABEL) { "contact label must be at most $MAX_LABEL characters" }
    }

    companion object {
        val TYPES: Set<String> = setOf("phone", "telegram", "imo", "whatsapp")
        const val MAX_VALUE = 128
        const val MAX_LABEL = 64
    }
}

/**
 * Секция PAYMENTS: ручная оплата тарифов.
 * Контакты по умолчанию берутся из env PAYMENT_CONTACTS, правка в админке
 * (Настройки → payments) перекрывает env.
 */
@Serializable
data class PaymentSettings(
    val contacts: List<PaymentContact> = emptyList(),
    /** Срок жизни заявки, часы. */
    val orderTtlHours: Int = DEFAULT_TTL_HOURS
) {
    init {
        require(contacts.size <= MAX_CONTACTS) { "at most $MAX_CONTACTS contacts" }
        require(orderTtlHours in 1..MAX_TTL_HOURS) { "orderTtlHours must be in 1..$MAX_TTL_HOURS" }
    }

    companion object {
        const val DEFAULT_TTL_HOURS = 72
        const val MAX_TTL_HOURS = 720
        const val MAX_CONTACTS = 10

        /**
         * Разбор env PAYMENT_CONTACTS (JSON-список {type, value, label}).
         * Пусто или битый JSON → контактов нет (приложение покажет «появятся позже»).
         */
        fun fromEnv(raw: String?, json: Json = Json { ignoreUnknownKeys = true }): PaymentSettings {
            if (raw.isNullOrBlank()) return PaymentSettings()
            return runCatching {
                PaymentSettings(
                    contacts = json.decodeFromString(
                        kotlinx.serialization.builtins.ListSerializer(PaymentContact.serializer()),
                        raw
                    )
                )
            }.getOrDefault(PaymentSettings())
        }
    }
}
