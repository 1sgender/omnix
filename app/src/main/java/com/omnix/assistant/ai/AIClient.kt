package com.omnix.assistant.ai

import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.models.Message

/**
 * Контракт облачного AI на стороне Android.
 *
 * Этап 3: единственная реализация — [OmnixApiAiClient], которая обращается
 * ТОЛЬКО к OMNIX API. Клиент не знает, какой провайдер (Groq/Gemini/
 * OpenRouter) выполнит запрос, и не хранит их ключи. Выбор модели —
 * исключительно server-side (пункт 29 ТЗ), поэтому в контракте НЕТ
 * параметра выбора модели клиентом.
 *
 * ```
 * AIClient → OMNIX API → AI Router → Provider Manager → Provider
 * ```
 *
 * @param systemPrompt контекст ассистента (Tool Discovery и т. п.).
 *        Сервер ДОПОЛНЯЕТ им свой базовый prompt, а не заменяет.
 */
interface AIClient {
    suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String>
}
