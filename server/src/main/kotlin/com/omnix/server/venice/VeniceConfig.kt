package com.omnix.server.venice

/**
 * Настройки Venice AI (OpenAI-совместимый API, только для админа/владельца).
 *
 * Ключ живёт ТОЛЬКО в окружении сервера (`VENICE_API_KEY` из
 * deploy/.env.production, файла вне репозитория). Это обычный класс, а не
 * data class: у data class `toString()` вывел бы ключ в лог. Пустой ключ
 * означает «провайдер выключен».
 *
 * Адрес API нарочно НЕ настраивается через окружение: ключ нельзя отправить
 * на чужой хост правкой переменной. Подменяется он только в тестах.
 */
class VeniceConfig(
    apiKey: String?,
    adminApiKey: String? = null,
    /** Консервативный потолок админских вызовов Venice в минуту (защита от случайного перерасхода). */
    val requestsPerMinute: Int = DEFAULT_REQUESTS_PER_MINUTE,
    /** Потолок токенов ответа: запрос не может попросить больше. */
    val maxOutputTokens: Int = DEFAULT_MAX_OUTPUT_TOKENS,
    /** Повторы при 429/5xx и сетевых сбоях (сверх первой попытки). */
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val baseUrl: String = DEFAULT_BASE_URL,
    val connectTimeoutMs: Long = 4_000,
    val requestTimeoutMs: Long = 60_000,
    val retryBaseDelayMs: Long = 500,
    val retryMaxDelayMs: Long = 20_000,
    val modelsTtlMs: Long = 10 * 60 * 1000L
) {
    private val inferenceKey: String? = apiKey?.trim()?.takeIf { it.isNotEmpty() }
    private val adminKey: String? = adminApiKey?.trim()?.takeIf { it.isNotEmpty() }

    /** Нет ключа — Venice выключен и недоступен никому. */
    val enabled: Boolean get() = inferenceKey != null

    /** Ключ для чата и списка моделей. */
    internal fun inferenceKey(): String? = inferenceKey

    /** Ключ для /api_keys/rate_limits: отдельный админ-ключ, иначе тот же. */
    internal fun rateLimitsKey(): String? = adminKey ?: inferenceKey

    /** Все секреты, которые нельзя показывать в логах и ответах. */
    internal fun secrets(): List<String> = listOfNotNull(inferenceKey, adminKey)

    override fun toString(): String =
        "VeniceConfig(enabled=$enabled, adminKey=${adminKey != null}, requestsPerMinute=$requestsPerMinute, " +
            "maxOutputTokens=$maxOutputTokens, maxRetries=$maxRetries)"

    companion object {
        const val DEFAULT_BASE_URL = "https://api.venice.ai/api/v1"
        const val DEFAULT_REQUESTS_PER_MINUTE = 20
        const val DEFAULT_MAX_OUTPUT_TOKENS = 1_024
        const val DEFAULT_MAX_RETRIES = 3

        fun fromEnv(env: (String) -> String? = System::getenv): VeniceConfig {
            fun str(key: String) = env(key)?.takeIf { it.isNotBlank() }
            return VeniceConfig(
                apiKey = str("VENICE_API_KEY"),
                adminApiKey = str("VENICE_ADMIN_API_KEY"),
                requestsPerMinute = (str("VENICE_REQUESTS_PER_MINUTE")?.toIntOrNull() ?: DEFAULT_REQUESTS_PER_MINUTE)
                    .coerceIn(1, 600),
                maxOutputTokens = (str("VENICE_MAX_OUTPUT_TOKENS")?.toIntOrNull() ?: DEFAULT_MAX_OUTPUT_TOKENS)
                    .coerceIn(16, 32_000),
                maxRetries = (str("VENICE_MAX_RETRIES")?.toIntOrNull() ?: DEFAULT_MAX_RETRIES).coerceIn(0, 6)
            )
        }
    }
}
