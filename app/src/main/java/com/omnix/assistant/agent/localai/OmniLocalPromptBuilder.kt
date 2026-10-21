package com.omnix.assistant.agent.localai

import com.omnix.assistant.agent.decision.ExecutionRequest
import com.omnix.assistant.agent.decision.RequestSource
import com.omnix.assistant.core.constants.AppConstants
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Сборка промпта для локальной модели.
 *
 * Формат — ChatML-шаблон Qwen2.5-Instruct (`<|im_start|>` / `<|im_end|>`).
 * MediaPipe НЕ подставляет служебные токены сам, поэтому шаблон формируется
 * здесь. Если модель заменят на другую — меняется только этот класс.
 *
 * System prompt намеренно жёстко ограничивает модель (пункты 10-12 ТЗ):
 * она не должна утверждать, что выполнила действие на устройстве, и не должна
 * выдумывать актуальные данные.
 */
@Singleton
class OmniLocalPromptBuilder @Inject constructor() : LocalPromptBuilder {

    private companion object {
        const val IM_START = "<|im_start|>"
        const val IM_END = "<|im_end|>"
        const val MEMORY_INTRO =
            "Справка о пользователе. Используй её, только если вопрос об этом; не пересказывай её и не отвечай ею на приветствие:"

        val SYSTEM_PROMPT = """
            Ты OMNIX — локальный офлайн AI-ассистент на устройстве пользователя.

            Правила:
            1. Отвечай кратко и по существу: 1-3 предложения. Ответ будет озвучен вслух.
            2. Отвечай на языке пользователя (обычно русский).
            3. Ты НЕ управляешь устройством. Никогда не утверждай, что открыл приложение,
               позвонил, отправил сообщение или изменил настройки — этим занимается
               отдельная система инструментов.
            4. У тебя НЕТ доступа в интернет. Не выдумывай новости, курсы валют, погоду,
               цены и другие актуальные данные. Если вопрос требует свежей информации,
               прямо скажи, что нужен доступ в сеть.
            5. Если не знаешь ответа — честно скажи об этом.
            6. Без markdown, списков и спецсимволов — только обычный текст.
        """.trimIndent()
    }

    override fun build(request: ExecutionRequest): String {
        val styleHint = when (request.source) {
            RequestSource.VOICE -> "Ответ будет озвучен голосом: говори особенно кратко."
            RequestSource.CHAT -> "Ответ отобразится текстом в чате."
        }

        // Контекст (пункт 14 ТЗ): system prompt + retrieval-память + запрос.
        // История диалога локальной модели НЕ отправляется (контекст 1280
        // токенов); из памяти — только retrieved-блок ≤800 символов, поэтому
        // офлайн-модель тоже знает long-term факты («как зовут дочь?»).
        // Память — в system-блоке как справка, а не в реплике пользователя:
        // там 0.5B-модель принимала её за вопрос и пересказывала дословно
        // («salam» → «Имя пользователя: Иван, Майк.», скриншот владельца 2026-10-21).
        return buildString {
            append(IM_START).append("system\n")
            append(AppConstants.IDENTITY_PROMPT).append("\n")
            append(SYSTEM_PROMPT).append("\n")
            append(styleHint)
            val memory = request.memoryContext.trim()
            if (memory.isNotBlank()) {
                append("\n").append(MEMORY_INTRO).append("\n").append(memory)
            }
            append(IM_END).append("\n")
            append(IM_START).append("user\n")
            append(request.text.trim()).append(IM_END).append("\n")
            append(IM_START).append("assistant\n")
        }
    }
}
