package com.omnix.server.search

import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private const val MAX_TITLE_CHARS = 160
private const val MAX_SNIPPET_CHARS = 400
private const val MAX_ANSWER_CHARS = 1_200

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://") || value.startsWith("http://")

/**
 * Serper.dev (env SERPER_API_KEY): результаты Google, около $1 за 1000 запросов
 * (в пять раз дешевле Brave). Отдаёт ссылки, короткие выдержки и, когда Google
 * показывает, готовый ответ (answerBox). Текст запроса и ключ в логи не пишем.
 */
class SerperSearchProvider(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_URL,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
    private val connectTimeoutMs: Long = 2_000,
    private val requestTimeoutMs: Long = 6_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SearchProvider {

    override suspend fun search(query: String, lang: String?): SearchOutcome {
        val body = buildJsonObject {
            put("q", query)
            put("num", maxResults)
            serperLanguage(lang)?.let { put("hl", it) }
        }.toString()
        val response = try {
            transport.post(
                url = baseUrl,
                headers = mapOf("X-API-KEY" to apiKey, "Content-Type" to "application/json"),
                body = body,
                connectTimeoutMs = connectTimeoutMs,
                requestTimeoutMs = requestTimeoutMs
            )
        } catch (e: TransportException) {
            return SearchOutcome.Failure(e.kind)
        }
        val status = response.status
        return when {
            status == 401 || status == 403 -> SearchOutcome.Failure(ProviderFailureKind.AUTH)
            // У Serper закончились кредиты: 400 "Not enough credits"; для цепочки это сбой доступа.
            status == 402 -> SearchOutcome.Failure(ProviderFailureKind.AUTH)
            status == 429 -> SearchOutcome.Failure(ProviderFailureKind.RATE_LIMITED)
            status == 400 || status == 404 || status == 422 -> SearchOutcome.Failure(ProviderFailureKind.BAD_REQUEST)
            status >= 500 -> SearchOutcome.Failure(ProviderFailureKind.SERVER_ERROR)
            status !in 200..299 -> SearchOutcome.Failure(ProviderFailureKind.UNKNOWN)
            else -> parse(response.body)
        }
    }

    private fun parse(body: String): SearchOutcome {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }
            .getOrElse { return SearchOutcome.Failure(ProviderFailureKind.UNKNOWN) }
        val items = runCatching {
            root["organic"]?.jsonArray?.mapNotNull { element ->
                val obj = element.jsonObject
                val url = obj["link"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (!isHttpUrl(url)) return@mapNotNull null
                SearchResultItem(
                    title = cleanText(obj["title"]?.jsonPrimitive?.contentOrNull, MAX_TITLE_CHARS),
                    url = url,
                    snippet = cleanText(obj["snippet"]?.jsonPrimitive?.contentOrNull, MAX_SNIPPET_CHARS)
                )
            }
        }.getOrElse { return SearchOutcome.Failure(ProviderFailureKind.UNKNOWN) }
            .orEmpty().take(maxResults)
        val box = root["answerBox"]?.runCatching { jsonObject }?.getOrNull()
        val answer = listOf("answer", "snippet")
            .firstNotNullOfOrNull { key -> box?.get(key)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }
            ?.let { cleanText(it, MAX_ANSWER_CHARS) }
        return SearchOutcome.Success(items, answer)
    }

    companion object {
        const val DEFAULT_URL = "https://google.serper.dev/search"
        const val DEFAULT_MAX_RESULTS = 5

        private val SUPPORTED = setOf(
            "ru", "en", "tr", "de", "fr", "es", "ar", "it", "pt", "zh", "ja", "ko", "uk", "kk", "uz"
        )

        /** Код языка интерфейса Google (hl); для неизвестных (tk и др.) не задаём. */
        fun serperLanguage(lang: String?): String? =
            lang?.trim()?.lowercase()?.take(2)?.takeIf { it in SUPPORTED }
    }
}

/**
 * Кэш ответов поиска: одинаковый запрос в течение [ttlMillis] не оплачивается
 * повторно. Кэшируются только успешные непустые ответы; сбои не запоминаются.
 * Ключ: запрос в нижнем регистре без лишних пробелов + язык.
 */
class CachedSearchProvider(
    private val delegate: SearchProvider,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val clock: Clock = Clock.systemUTC()
) : SearchProvider {

    private data class CachedEntry(val storedAt: Long, val value: SearchOutcome.Success)

    private val entries = object : LinkedHashMap<String, CachedEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedEntry>?): Boolean =
            size > maxEntries
    }

    override suspend fun search(query: String, lang: String?): SearchOutcome {
        val key = cacheKey(query, lang)
        val now = clock.millis()
        synchronized(entries) { entries[key] }?.let { hit ->
            if (now - hit.storedAt < ttlMillis) return hit.value
        }
        val outcome = delegate.search(query, lang)
        if (outcome is SearchOutcome.Success && (outcome.results.isNotEmpty() || !outcome.answer.isNullOrBlank())) {
            synchronized(entries) { entries[key] = CachedEntry(now, outcome) }
        }
        return outcome
    }

    companion object {
        const val DEFAULT_TTL_MILLIS = 12L * 60 * 60 * 1000
        const val DEFAULT_MAX_ENTRIES = 2_000

        fun cacheKey(query: String, lang: String?): String =
            query.trim().lowercase().replace(Regex("\\s+"), " ") + "|" + (lang?.trim()?.lowercase().orEmpty())
    }
}

/**
 * Дневной лимит веб-поисков на клиента, считается на сервере (UTC-сутки):
 * лимит из тарифа нельзя обойти правкой приложения. Счётчик в памяти: при
 * перезапуске сервера обнуляется, это допустимо (лимит защитный).
 */
class SearchDailyLimiter(private val clock: Clock = Clock.systemUTC()) {

    sealed interface Decision {
        data object Allowed : Decision
        data class Exceeded(val retryAfterSeconds: Long) : Decision
    }

    private var day: Long = -1
    private val counts = HashMap<String, Int>()

    @Synchronized
    fun tryAcquire(clientId: String, dailyLimit: Int): Decision {
        val now = Instant.now(clock)
        val today = now.atOffset(ZoneOffset.UTC).toLocalDate().toEpochDay()
        if (today != day) {
            counts.clear()
            day = today
        }
        val used = counts[clientId] ?: 0
        if (used >= dailyLimit) {
            val nextMidnight = (today + 1) * SECONDS_PER_DAY
            return Decision.Exceeded((nextMidnight - now.epochSecond).coerceAtLeast(1))
        }
        counts[clientId] = used + 1
        return Decision.Allowed
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
