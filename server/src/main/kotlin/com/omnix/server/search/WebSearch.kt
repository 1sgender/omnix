package com.omnix.server.search

import com.omnix.server.observability.StructuredLogger
import com.omnix.server.provider.ApiKeyPool
import com.omnix.server.provider.HttpTransport
import com.omnix.server.provider.ProviderFailureKind
import com.omnix.server.provider.TransportException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLEncoder

/**
 * Веб-поиск на сервере (телефон в Туркменистане часто не достаёт до
 * DuckDuckGo и Википедии). Телефон присылает запрос, сервер возвращает
 * несколько результатов и, если провайдер умеет, готовый краткий ответ.
 * Текст запроса и ключи в логи не пишем.
 */
data class SearchResultItem(val title: String, val url: String, val snippet: String)

sealed interface SearchOutcome {
    data class Success(val results: List<SearchResultItem>, val answer: String? = null) : SearchOutcome
    data class Failure(val kind: ProviderFailureKind) : SearchOutcome
}

/** Провайдер поиска. Реализации: [BraveSearchProvider], [GeminiGroundedSearchProvider]. */
interface SearchProvider {
    suspend fun search(query: String, lang: String?): SearchOutcome
}

/** Brave Search API (env BRAVE_SEARCH_API_KEY). */
class BraveSearchProvider(
    private val transport: HttpTransport,
    private val apiKey: String,
    private val baseUrl: String = DEFAULT_URL,
    private val maxResults: Int = DEFAULT_MAX_RESULTS,
    private val connectTimeoutMs: Long = 2_000,
    private val requestTimeoutMs: Long = 6_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SearchProvider {

    override suspend fun search(query: String, lang: String?): SearchOutcome {
        val params = buildList {
            add("q" to query)
            add("count" to maxResults.toString())
            add("safesearch" to "moderate")
            add("text_decorations" to "false")
            braveLanguage(lang)?.let { add("search_lang" to it) }
        }.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val response = try {
            transport.get(
                url = "$baseUrl?$params",
                headers = mapOf("X-Subscription-Token" to apiKey, "Accept" to "application/json"),
                connectTimeoutMs = connectTimeoutMs,
                requestTimeoutMs = requestTimeoutMs
            )
        } catch (e: TransportException) {
            return SearchOutcome.Failure(e.kind)
        }
        val status = response.status
        return when {
            status == 401 || status == 403 -> SearchOutcome.Failure(ProviderFailureKind.AUTH)
            status == 429 -> SearchOutcome.Failure(ProviderFailureKind.RATE_LIMITED)
            status == 400 || status == 404 || status == 422 -> SearchOutcome.Failure(ProviderFailureKind.BAD_REQUEST)
            status >= 500 -> SearchOutcome.Failure(ProviderFailureKind.SERVER_ERROR)
            status !in 200..299 -> SearchOutcome.Failure(ProviderFailureKind.UNKNOWN)
            else -> parse(response.body)
        }
    }

    private fun parse(body: String): SearchOutcome {
        val items = runCatching {
            json.parseToJsonElement(body).jsonObject["web"]?.jsonObject?.get("results")?.jsonArray
                ?.mapNotNull { element ->
                    val obj = element.jsonObject
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (!isHttpUrl(url)) return@mapNotNull null
                    SearchResultItem(
                        title = cleanText(obj["title"]?.jsonPrimitive?.contentOrNull, MAX_TITLE_CHARS),
                        url = url,
                        snippet = cleanText(obj["description"]?.jsonPrimitive?.contentOrNull, MAX_SNIPPET_CHARS)
                    )
                }
        }.getOrElse { return SearchOutcome.Failure(ProviderFailureKind.UNKNOWN) }
        return SearchOutcome.Success(items.orEmpty().take(maxResults))
    }

    companion object {
        const val DEFAULT_URL = "https://api.search.brave.com/res/v1/web/search"
        const val DEFAULT_MAX_RESULTS = 5

        private val SUPPORTED = setOf("ru", "en", "tr", "de", "fr", "es", "ar", "it", "pt", "zh", "ja", "ko")

        /** Brave знает не все языки OMNIX (tk, kk, uz нет): для них язык не задаём. */
        fun braveLanguage(lang: String?): String? =
            lang?.trim()?.lowercase()?.take(2)?.takeIf { it in SUPPORTED }
    }
}

/**
 * Gemini с поиском Google (grounding): модель сама ищет и отвечает коротким
 * текстом, источники приходят в groundingMetadata. Ключи из того же пула,
 * что и у обычного Gemini (GEMINI_API_KEY через запятую).
 */
class GeminiGroundedSearchProvider(
    private val transport: HttpTransport,
    private val keys: ApiKeyPool,
    private val model: String,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val connectTimeoutMs: Long = 2_000,
    private val requestTimeoutMs: Long = 12_000,
    private val json: Json = Json { ignoreUnknownKeys = true }
) : SearchProvider {

    override suspend fun search(query: String, lang: String?): SearchOutcome {
        if (keys.isEmpty) return SearchOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED)
        val body = buildBody(query, lang)
        var last: SearchOutcome = SearchOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED)
        repeat(minOf(keys.size, MAX_KEY_ATTEMPTS)) {
            val key = keys.current() ?: return last
            val outcome = attempt(key, body)
            if (outcome is SearchOutcome.Failure && outcome.kind == ProviderFailureKind.RATE_LIMITED) {
                keys.reportRateLimited(key)
                last = outcome
            } else {
                if (outcome is SearchOutcome.Success) keys.reportSuccess(key)
                return outcome
            }
        }
        return last
    }

    private suspend fun attempt(key: String, body: String): SearchOutcome {
        val response = try {
            transport.post(
                url = "$baseUrl/$model:generateContent",
                headers = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"),
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
            status == 429 -> SearchOutcome.Failure(ProviderFailureKind.RATE_LIMITED)
            status == 400 || status == 404 || status == 422 -> SearchOutcome.Failure(ProviderFailureKind.BAD_REQUEST)
            status >= 500 -> SearchOutcome.Failure(ProviderFailureKind.SERVER_ERROR)
            status !in 200..299 -> SearchOutcome.Failure(ProviderFailureKind.UNKNOWN)
            else -> parse(response.body)
        }
    }

    private fun buildBody(query: String, lang: String?): String {
        val language = lang?.trim()?.takeIf { it.isNotEmpty() }?.let { " Язык ответа: $it." }.orEmpty()
        val prompt = "Найди в интернете и ответь кратко, по фактам, не больше трёх предложений.$language " +
            "Не выдумывай: если данных нет, так и скажи. Запрос: $query"
        val request = buildJsonObject {
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put("parts", buildJsonArray { add(buildJsonObject { put("text", prompt) }) })
                        }
                    )
                }
            )
            put("tools", buildJsonArray { add(buildJsonObject { put("google_search", JsonObject(emptyMap())) }) })
            put("generationConfig", buildJsonObject { put("temperature", 0.2) })
        }
        return json.encodeToString(JsonObject.serializer(), request)
    }

    private fun parse(body: String): SearchOutcome {
        val candidate = runCatching {
            json.parseToJsonElement(body).jsonObject["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
        }.getOrNull() ?: return SearchOutcome.Failure(ProviderFailureKind.UNKNOWN)
        val answer = runCatching {
            candidate["content"]?.jsonObject?.get("parts")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
        }.getOrNull()?.trim()
        if (answer.isNullOrEmpty()) return SearchOutcome.Failure(ProviderFailureKind.UNKNOWN)
        val sources = runCatching {
            candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray
                ?.mapNotNull { chunk ->
                    val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
                    val url = web["uri"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                    if (!isHttpUrl(url)) return@mapNotNull null
                    SearchResultItem(
                        title = cleanText(web["title"]?.jsonPrimitive?.contentOrNull, MAX_TITLE_CHARS),
                        url = url,
                        snippet = ""
                    )
                }
        }.getOrNull().orEmpty().distinctBy { it.url }.take(MAX_SOURCES)
        return SearchOutcome.Success(sources, cleanText(answer, MAX_ANSWER_CHARS))
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        /** grounding с google_search есть у Gemini 2.x и новее; у 1.5 другой формат инструмента. */
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        private const val MAX_KEY_ATTEMPTS = 3
        private const val MAX_SOURCES = 5
    }
}

/** Цепочка поисковиков: следующий вызывается только при сбое предыдущего. */
class ChainedSearchProvider(
    private val providers: List<Pair<String, SearchProvider>>,
    private val logger: StructuredLogger? = null
) : SearchProvider {

    init {
        require(providers.isNotEmpty()) { "search chain must not be empty" }
    }

    override suspend fun search(query: String, lang: String?): SearchOutcome {
        var last: SearchOutcome = SearchOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED)
        for ((name, provider) in providers) {
            val outcome = try {
                provider.search(query, lang)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                SearchOutcome.Failure(ProviderFailureKind.UNKNOWN)
            }
            if (outcome is SearchOutcome.Success && (outcome.results.isNotEmpty() || !outcome.answer.isNullOrBlank())) {
                logger?.info("search answered", "provider" to name, "results" to outcome.results.size.toString())
                return outcome
            }
            last = outcome
            val kind = (outcome as? SearchOutcome.Failure)?.kind?.name ?: "EMPTY"
            logger?.warn("search provider failed", "provider" to name, "kind" to kind)
        }
        return last
    }

    companion object {
        /**
         * Порядок из SEARCH_PROVIDERS ("brave,gemini"); пустое значение =
         * "brave,gemini". Неизвестные и ненастроенные имена пропускаются.
         * null, если не осталось ни одного провайдера.
         */
        fun fromConfig(
            order: String?,
            available: Map<String, SearchProvider>,
            logger: StructuredLogger? = null
        ): SearchProvider? {
            val names = (order ?: DEFAULT_ORDER).split(",").map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }.distinct()
            val chain = names.mapNotNull { name -> available[name]?.let { name to it } }
            return when (chain.size) {
                0 -> null
                1 -> chain.single().second
                else -> ChainedSearchProvider(chain, logger)
            }
        }

        const val DEFAULT_ORDER = "brave,gemini"
    }
}

private const val MAX_TITLE_CHARS = 160
private const val MAX_SNIPPET_CHARS = 400
private const val MAX_ANSWER_CHARS = 1_200

private fun isHttpUrl(value: String): Boolean =
    value.startsWith("https://") || value.startsWith("http://")

private val HTML_TAG = Regex("<[^>]*>")

/** Brave отдаёт &lt;strong&gt; и сущности даже при text_decorations=false: убираем разметку. */
internal fun cleanText(raw: String?, maxChars: Int): String {
    if (raw == null) return ""
    val plain = HTML_TAG.replace(raw, "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace(Regex("\\s+"), " ").trim()
    return if (plain.length > maxChars) plain.take(maxChars).trimEnd() + "…" else plain
}

