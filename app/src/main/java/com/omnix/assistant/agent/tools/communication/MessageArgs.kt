package com.omnix.assistant.agent.tools.communication

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Чтение получателя и текста из аргументов вызова. Облачная модель не всегда называет поля
 * так, как в схеме (`recipient`/`message`): без запасных имён подтверждение говорило
 * «Отправить контакту…», а инструмент отвечал MISSING_RECIPIENT, хотя пользователь назвал имя.
 */
object MessageArgs {

    private val RECIPIENT_KEYS = listOf(
        "recipient", "contact", "contact_name", "contactName", "name", "to", "receiver",
        "phone", "phone_number", "phoneNumber", "number", "who"
    )
    private val MESSAGE_KEYS = listOf("message", "text", "body", "content", "sms", "message_text")

    fun recipient(arguments: JsonObject): String = firstNonBlank(arguments, RECIPIENT_KEYS)

    fun message(arguments: JsonObject): String = firstNonBlank(arguments, MESSAGE_KEYS)

    private fun firstNonBlank(arguments: JsonObject, keys: List<String>): String {
        for (key in keys) {
            val value = (arguments[key] as? JsonPrimitive)?.contentOrNull?.trim()
            if (!value.isNullOrEmpty()) return value
        }
        return ""
    }
}
