package com.omnix.server.provider

import com.omnix.server.config.ProviderConfig
import com.omnix.server.observability.StructuredLogger
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// =============================================================== DTO (Anthropic Messages API)

@Serializable
private data class AnthropicMessage(
    @SerialName("role") val role: String,
    @SerialName("content") val content: String
)

@Serializable
private data class AnthropicThinking(
    @SerialName("type") val type: String
)

@Serializable
private data class AnthropicRequest(
    @SerialName("model") val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    @SerialName("system") val system: String,
    @SerialName("messages") val messages: List<AnthropicMessage>,
    /**
     * Sonnet 5.5 нельзя выключить через `disabled` (400); `between_tools`
     * отключает размышления перед ответом: для чата без инструментов это
     * дешевле и быстрее, а стоимость Pro-ответа остаётся предсказуемой.
     */
    @SerialName("thinking") val thinking: AnthropicThinking? = null
)

@Serializable
private data class AnthropicContentBlock(
    @SerialName("type") val type: String,
    @SerialName("text") val text: String? = null
)

@Serializable
private data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null
)

@Serializable
private data class AnthropicResponse(
    @SerialName("content") val content: List<AnthropicContentBlock> = emptyList(),
    @SerialName("usage") val usage: AnthropicUsage? = null,
    @SerialName("stop_reason") val stopReason: String? = null
)

// =============================================================== Anthropic (Claude)

/**
 * Claude (Anthropic Messages API) — движок уровня Pro.
 *
 * Не участвует в обычном выборе Flash: роутер подключает его только для
 * запросов уровня Pro (см. [ProviderRequirements.chain]). Ключ идёт в
 * заголовке `x-api-key`, версия API фиксируется `anthropic-version`.
 *
 * Параметры семплирования (`temperature` и т. п.) намеренно НЕ отправляются:
 * новые модели Claude отвергают их значения, поведением управляет промпт.
 */
class AnthropicProvider(
    config: ProviderConfig,
    transport: HttpTransport,
    json: Json,
    logger: StructuredLogger? = null
) : BaseHttpProvider(config, transport, json, logger) {

    override val id = ProviderId.ANTHROPIC

    override val capabilities = CapabilityProfile(
        supportsChat = true,
        supportsWeb = false,
        supportsStreaming = false,
        supportsToolCalling = false
    )

    override fun endpointUrl() = config.baseUrl

    override fun headers(apiKey: String?) = mapOf(
        "x-api-key" to (apiKey ?: ""),
        "anthropic-version" to API_VERSION,
        "Content-Type" to "application/json"
    )

    override fun buildBody(request: ProviderRequest): String {
        val systemFromHistory = request.history
            .filter { it.role == "system" }
            .joinToString("\n\n") { it.content }
        val system = if (systemFromHistory.isBlank()) {
            request.systemPrompt
        } else {
            "${request.systemPrompt}\n\n$systemFromHistory"
        }

        val turns = request.history
            .filter { it.role == "user" || it.role == "assistant" }
            .map { AnthropicMessage(it.role, it.content) }
        val messages = normalize(trimHistory(turns) + AnthropicMessage("user", request.prompt))

        return json.encodeToString(
            AnthropicRequest.serializer(),
            AnthropicRequest(
                model = config.model,
                maxTokens = request.maxTokens,
                system = system,
                messages = messages,
                thinking = AnthropicThinking("between_tools")
            )
        )
    }

    override fun parseSuccess(body: String): ProviderResult {
        val parsed = json.decodeFromString(AnthropicResponse.serializer(), body)
        val text = parsed.content
            .filter { it.type == "text" }
            .joinToString("") { it.text.orEmpty() }
            .trim()

        if (text.isEmpty()) {
            return ProviderResult.Failure(
                kind = ProviderFailureKind.UNKNOWN,
                detail = "empty content (stop_reason=${parsed.stopReason ?: "-"})"
            )
        }

        val input = parsed.usage?.inputTokens
        val output = parsed.usage?.outputTokens
        return ProviderResult.Success(
            text = text,
            model = config.model,
            inputTokens = input,
            outputTokens = output,
            totalTokens = if (input != null && output != null) input + output else null
        )
    }

    /** Отбрасывает самые старые реплики, пока история не уложится в бюджет. */
    private fun trimHistory(history: List<AnthropicMessage>): List<AnthropicMessage> {
        if (history.isEmpty()) return emptyList()
        val work = ArrayDeque(history)
        val overheadPerMsg = 5
        while (work.isNotEmpty()) {
            val bytes = work.sumOf {
                json.encodeToString(AnthropicMessage.serializer(), it).toByteArray().size + overheadPerMsg
            }
            if (bytes <= HISTORY_BUDGET_BYTES) break
            work.removeFirst()
        }
        return work
    }

    /**
     * Messages API требует, чтобы диалог начинался с user; подряд идущие
     * реплики одной роли склеиваются.
     */
    private fun normalize(turns: List<AnthropicMessage>): List<AnthropicMessage> {
        val result = ArrayList<AnthropicMessage>(turns.size)
        for (turn in turns) {
            if (result.isEmpty() && turn.role != "user") continue
            val last = result.lastOrNull()
            if (last != null && last.role == turn.role) {
                result[result.lastIndex] = last.copy(content = last.content + "\n\n" + turn.content)
            } else {
                result += turn
            }
        }
        return result
    }

    companion object {
        const val API_VERSION = "2023-06-01"
    }
}
