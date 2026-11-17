package com.omnix.assistant.ai

import com.omnix.assistant.agent.decision.AiTierPreference
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.data.remote.OmnixApiClient
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Реализация [AIClient] поверх OMNIX API (Этап 3).
 *
 * Заменила прежний `UniversalAIClient`, который ходил напрямую в Groq/Gemini/
 * OpenRouter с пользовательским ключом (BYOK). Теперь:
 *
 *  - ключей провайдеров на устройстве НЕТ;
 *  - выбор провайдера, fallback, retry и rate limiting — на сервере;
 *  - клиент лишь передаёт запрос и получает нормализованный ответ.
 *
 * Параметры [source], [effectivePrivacyLevel] и [requiresWeb] прокидываются в
 * перегрузке [completeWithContext]; базовый [complete] сохраняет прежнюю
 * сигнатуру, чтобы существующие вызывающие (LiveTranslatorEngine) не ломались.
 *
 * H-02 / Refactor #3: классификация приватности делается ОДИН раз в
 * [com.omnix.assistant.domain.usecases.SendPromptUseCase] (с полным контекстом
 * prompt+systemPrompt+history). [completeWithContext] доверяет пришедшему
 * [effectivePrivacyLevel] и только проверяет invariant (effective не должен
 * быть NORMAL при отключённом consent) как defense-in-depth на случай бага
 * в вызывающем коде. Повторный вызов PrivacyClassifier здесь и в репозитории
 * убран — он порождал дублирование и drift.
 */
class PrivacyCloudBlockedException(level: PrivacyLevel) :
    IllegalStateException("Cloud blocked by privacy classification: ${level.name}")

/** A client that preserves classified privacy metadata and per-request consent. */
interface ContextualCloudAIClient {
    suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        effectivePrivacyLevel: PrivacyLevel,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean = false,
        history: List<Message> = emptyList(),
        /** OBSERVABILITY: сквозной request id; пусто = OmnixApiClient сгенерирует omx-id. */
        requestId: String = ""
    ): Resource<String>
}

@Singleton
class OmnixApiAiClient @Inject constructor(
    private val apiClient: OmnixApiClient,
    /** Выбор Flash/Pro: только для запросов движка решений, не для legacy-вызовов (переводчик). */
    private val tierPreference: AiTierPreference = AiTierPreference.FLASH,
    /** Файлы картинок чата: по id из сообщения отдаёт готовый JPEG. */
    private val imageSource: com.omnix.assistant.domain.files.ChatImageSource =
        com.omnix.assistant.domain.files.ChatImageSource.NONE
) : AIClient, ContextualCloudAIClient {

    override suspend fun complete(
        prompt: String,
        systemPrompt: String,
        history: List<Message>
    ): Resource<String> = completeWithContext(
        prompt = prompt,
        systemPrompt = systemPrompt,
        source = "CHAT",
        // Legacy-вызов (в т.ч. переводчик) — пользователь явно инициировал
        // функцию в UI; считаем это NORMAL, пока вызывающий не передал
        // контекстный effective-уровень.
        effectivePrivacyLevel = PrivacyLevel.NORMAL,
        requiresWeb = false,
        cloudExplicitlyAllowed = true,
        history = history
    )

    /**
     * Полный вызов с контекстом решения.
     *
     * P2-cleanup (Этап 5): параметр выбора модели был удалён из контракта [AIClient],
     * т.к. выбор модели — исключительно server-side (пункт 29 ТЗ); клиент не
     * имеет права его навязывать.
     */
    override suspend fun completeWithContext(
        prompt: String,
        systemPrompt: String,
        source: String,
        effectivePrivacyLevel: PrivacyLevel,
        requiresWeb: Boolean,
        cloudExplicitlyAllowed: Boolean,
        history: List<Message>,
        requestId: String
    ): Resource<String> {
        // Defense-in-depth invariant: к нам не должен прийти запрос на облако,
        // где effective в {PRIVATE,SENSITIVE} без явного consent. Отправка
        // такого означала бы обход C-02 gate в вызывающем коде. Классификатор
        // здесь НЕ перезапускаем.
        val permitted = effectivePrivacyLevel == PrivacyLevel.NORMAL ||
            (effectivePrivacyLevel in setOf(PrivacyLevel.PRIVATE, PrivacyLevel.SENSITIVE) && cloudExplicitlyAllowed)
        if (!permitted) {
            return Resource.Error(
                PrivacyCloudBlockedException(effectivePrivacyLevel),
                "Облачная обработка запрещена политикой приватности"
            )
        }
        // Фото лежит в сообщении ссылкой: сам файл подставляем здесь, а в тексте
        // остаётся только вопрос.
        val decoded = com.omnix.assistant.domain.files.ChatImageCodec.decode(prompt)
        val images = decoded.imageIds.mapNotNull { id ->
            imageSource.base64Jpeg(id)?.let { com.omnix.assistant.data.remote.OmniAiImageDto("image/jpeg", it) }
        }
        if (decoded.imageIds.isNotEmpty() && images.isEmpty()) {
            return Resource.Error(
                IllegalStateException("IMAGE_MISSING"),
                "Фото больше нет на устройстве. Прикрепите его ещё раз."
            )
        }
        return apiClient.execute(
            text = if (images.isEmpty()) prompt else decoded.question.ifBlank { "Что на этом фото?" },
            images = images,
            source = source,
            privacyLevel = effectivePrivacyLevel.name,
            requiresWeb = requiresWeb,
            // Пустой systemPrompt нормализуем в null: отсутствие контекста —
            // это не «пустая строка» в контракте запроса.
            systemContext = systemPrompt.ifBlank { null },
            cloudExplicitlyAllowed = cloudExplicitlyAllowed,
            requestId = requestId,
            tier = tierPreference.requestedTier(),
            // CR-03: преобразуем доменные Message → DTO, исключаем SYSTEM
            // (systemPrompt отправляется отдельно и серверный базовый промпт
            // не должен дублироваться через историю).
            history = history
                // Только реплики пользователя и OMNIX: SYSTEM-строки и красные
                // ERROR-пузыри — служебные, сервер их не знает (роль "error"
                // раньше превращала каждый следующий ответ в INVALID_REQUEST).
                .filter { (it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT) && it.text.isNotBlank() }
                .map {
                    com.omnix.assistant.data.remote.MessageDto(
                        role = it.role.value,
                        // Фото из прошлых реплик в историю не идёт: только пометка.
                        content = com.omnix.assistant.domain.files.ChatImageCodec.forHistory(it.text)
                    )
                }
        )
    }
}
