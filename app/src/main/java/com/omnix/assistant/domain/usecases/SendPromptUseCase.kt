package com.omnix.assistant.domain.usecases

import android.content.Context
import android.util.Log
import com.omnix.assistant.R
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.memory.manager.OmniMemoryManager
import com.omnix.assistant.agent.decision.ExecutionRequest
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.decision.RequestSource
import com.omnix.assistant.agent.pipeline.AgentPipeline
import com.omnix.assistant.agent.tools.accessibility.ScreenContentPrivacy
import com.omnix.assistant.core.request.RequestIds
import com.omnix.assistant.core.result.Resource
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.domain.models.PromptExecutionResult
import com.omnix.assistant.agent.decision.ExecutionType
import com.omnix.assistant.domain.chat.ChatAnswerOriginStore
import com.omnix.assistant.domain.models.handledOnDevice
import com.omnix.assistant.domain.repository.MessageRepository
import com.omnix.assistant.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject

/**
 * SendPromptUseCase — тонкая обвязка над [AgentPipeline].
 *
 * Здесь остаётся «диалоговая» часть: анафора, память, сохранение сообщений.
 * Вся агентская логика (FastCommandRouter → AgentCognitiveLoop →
 * PLAN/REPLAN → ToolDiscovery → ToolExecutor → Observation → VERIFY → SUCCESS)
 * — в едином конвейере [AgentPipeline].
 */
class SendPromptUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val settingsRepository: SettingsRepository,
    private val memoryManager: OmniMemoryManager,
    private val agentPipeline: AgentPipeline,
    private val localModelManager: LocalModelManager,
    private val answerOriginStore: ChatAnswerOriginStore
) {
    /**
     * Дедупликация офлайн-fallback-уведомлений: одна и та же причина сбоя
     * инициализации сообщает пользователю один раз за жизнь use case
     * (инстанс живёт в ViewModel), новая причина — новое сообщение.
     */
    private var lastNotifiedLocalFallbackReason: String? = null

    /**
     * @param source откуда пришёл запрос — голос (STT) или текстовый чат.
     *               Участвует в решении [com.omnix.assistant.agent.decision.ExecutionDecisionEngine].
     * @param privacyLevel только hint вызывающего слоя. По умолчанию UNKNOWN;
     *               локальный classifier обязан завершиться до routing.
     * @param cloudExplicitlyAllowed явное согласие пользователя на облачную
     *               обработку PRIVATE/SENSITIVE. ДОЛЖЕН быть true после того,
     *               как пользователь ответил «Да» на consent-карточку;
     *               в противном случае (и при effective != NORMAL) use case
     *               НЕ вызывает агентский конвейер и возвращает [Resource.NeedsConsent].
     */
    suspend operator fun invoke(
        userPrompt: String,
        source: RequestSource = RequestSource.CHAT,
        privacyLevel: PrivacyLevel = PrivacyLevel.UNKNOWN,
        cloudExplicitlyAllowed: Boolean = false,
        originTimestampMs: Long? = null,
        requestId: String = RequestIds.newId(),
        onSentence: ((String) -> Unit)? = null
    ): Resource<PromptExecutionResult> {
        val trimmedPrompt = userPrompt.trim()
        if (trimmedPrompt.isEmpty()) {
            return Resource.Error(IllegalArgumentException(context.getString(R.string.pustoy_zapros)), context.getString(R.string.zapros_ne_mozhet_byt_pustym))
        }

        // OBSERVABILITY: корреляция всего пути одного запроса. Текст НЕ
        // логируется (пункт 20 ТЗ) — только id и форма маршрута.
        Log.i("SendPrompt", "request started | requestId=$requestId | source=$source")

        // 1. Фиксируем последнюю реплику (для анафоры) и разрешаем контекст.
        memoryManager.workingMemory.setLastMessage(trimmedPrompt)
        val resolvedPrompt = memoryManager.workingMemory.resolveContextualQuery(trimmedPrompt)

        // 2. Сохраняем сообщение пользователя и обновляем память. Сообщение
        //    пользователя появляется в чате ДО consent-gate — пользователь
        //    видит, что его запрос услышан, и потом решает про облако.
        messageRepository.insertMessage(
            Message(
                role = MessageRole.USER,
                text = trimmedPrompt,
                timestamp = System.currentTimeMillis()
            )
        )
        memoryManager.processTurnGovernance(resolvedPrompt)
        memoryManager.workingMemory.updateEntityFromResponse(trimmedPrompt)

        // H-02 / Refactor #3: ЕДИНСТВЕННОЕ место на запросе, где вызывается
        // PrivacyClassifier.classifySafely с полным контекстом (текст +
        // systemPrompt + история). Результат кладётся в ExecutionRequest и
        // дальше используется ВСЕМИ downstream-слоями — никаких повторных
        // вызовов classifySafely в AIRepository / OmnixApiAiClient /
        // ChatViewModel / VoiceInteractionOrchestrator на том же payload.
        // Серверный AiRouter всё равно переклассифицирует запрос
        // (defense-in-depth — ему нельзя доверять клиенту); это оправдано.
        val history = messageRepository.getRecentMessages(limit = 10)
        val systemPrompt = settingsRepository.systemPromptFlow.first()

        // MEMORY: Query → Memory retrieval → Relevant memories only → AI.
        // В LLM уходит НЕ вся память и не «ничего»: OmniMemoryManager
        // выбирает top-K воспоминаний под ЭТОТ запрос (hybrid retrieval,
        // бюджет ~800 символов). Retrieval локальный (Room + featurize) —
        // дешевле и быстрее, чем отправка всего контекста в облако, и
        // убирает round-trip «LLM просит RecallMemoryTool → второй вызов».
        val memoryContext = memoryManager.buildPromptMemoryContext(resolvedPrompt)

        // Строим ExecutionRequest с единой контекстной классификацией:
        // текст + systemPrompt + тексты истории + память → один вызов classifySafely.
        val effectiveRequest = ExecutionRequest.withContextualClassification(
            text = resolvedPrompt,
            source = source,
            declaredLevel = privacyLevel,
            systemPrompt = systemPrompt,
            relatedContent = history.map(Message::text),
            history = history,
            cloudExplicitlyAllowed = cloudExplicitlyAllowed,
            originTimestampMs = originTimestampMs,
            memoryContext = memoryContext,
            requestId = requestId,
            onSentence = onSentence
        )

        val effective = effectiveRequest.effectivePrivacyLevel

        // C-02: PRIVATE/SENSITIVE без явного согласия — не ходим в агентский
        // конвейер (который полезет в сеть), а возвращаем NeedsConsent.
        // UI/voice показывают карточку/TTS-вопрос и вызывают use case повторно
        // с cloudExplicitlyAllowed=true.
        //
        // NORMAL идёт в pipeline; UNKNOWN после классификации — fail-closed
        // (isCloudRestricted == true), тоже потребует согласия.
        if (effective.isCloudRestricted && !cloudExplicitlyAllowed) {
            val promptResId = when (effective) {
                PrivacyLevel.SENSITIVE -> R.string.cloud_consent_sensitive_prompt
                else -> R.string.cloud_consent_private_prompt
            }
            return Resource.NeedsConsent(
                privacyLevel = effective,
                prompt = context.getString(promptResId),
                retryOnConsentArgs = Resource.NeedsConsent.RetryArgs(
                    userPrompt = trimmedPrompt,
                    source = source,
                    privacyLevel = privacyLevel
                )
            )
        }

        // 4. Единый агентский конвейер — вызывается только после privacy gate
        //    и получает запрос с УЖЕ заполненной privacyClassification.
        val result = agentPipeline.process(effectiveRequest)

        // 4. Сохраняем ответ ассистента.
        if (result is Resource.Success) {
            when (val exec = result.data) {
                is PromptExecutionResult.DirectAnswer -> {
                    // Accessibility Lockdown: экральный контент показывается
                    // и озвучивается, но НЕ сохраняется в историю — иначе
                    // getRecentMessages() вернёт его в следующий облачный
                    // запрос (relatedContent/history). См. ScreenContentPrivacy.
                    val persistedText = if (exec.containsScreenContent) {
                        ScreenContentPrivacy.PLACEHOLDER
                    } else {
                        exec.text
                    }
                    // Решение владельца 2026-09-21: локальная модель не
                    // стартовала и ответ пришёл из облака — один раз на причину
                    // сообщаем пользователю про офлайн-версию. SYSTEM-строка
                    // попадает ТОЛЬКО в историю чата (не озвучивается: голос
                    // и так занят основным ответом; SMS-канал не затрагивается).
                    exec.localFallbackReason?.let { reason ->
                        if (reason != lastNotifiedLocalFallbackReason) {
                            lastNotifiedLocalFallbackReason = reason
                            messageRepository.insertMessage(
                                Message(
                                    role = MessageRole.SYSTEM,
                                    text = context.getString(
                                        R.string.omnix_local_fallback_notice,
                                        reason
                                    ),
                                    timestamp = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                    saveAssistantMessage(persistedText, exec.executionType)
                    memoryManager.workingMemory.updateEntityFromResponse(persistedText)
                }
                is PromptExecutionResult.ConfirmationRequired -> {
                    saveAssistantMessage(exec.promptMessage, null)
                }
            }
        } else if (result is Resource.Error) {
            // Авария сохраняется СОБСТВЕННОЙ ролью (mock 2026-09-26), чтобы чат
            // отрисовал её ошибочным пузырём, а не обычным ответом OMNIX.
            saveErrorMessage(errorTextFor(result.message, result.exception))
        }

        return result
    }

    /**
     * Текст сообщения об ошибке. Сетевой сбой получает спокойную
     * формулировку из утверждённого мока — раньше в чат уходил «сырой»
     * текст исключения. При НЕскачанной офлайн-модели подсказка с местом
     * скачивания остаётся: это диагностическая часть, по ней пользователь
     * понимает, что именно не так и что делать.
     */
    private fun errorTextFor(message: String?, exception: Throwable?): String {
        val networkFailure = exception is IOException
        val offlineModelMissing = localModelManager.state.let {
            it is LocalModelState.NotInstalled || it is LocalModelState.DownloadFailed
        }
        val base = if (networkFailure) {
            context.getString(R.string.omnix_chat_network_error)
        } else {
            message ?: context.getString(R.string.oshibka_vypolneniya_zaprosa)
        }
        if (!networkFailure || !offlineModelMissing) return base
        return base + " " + context.getString(R.string.oflayn_model_ne_skachana)
    }

    /**
     * Сохраняет ответ ассистента и, если он получен локальным путём
     * (DEVICE_TOOL / LOCAL_AI), помечает id строки для on-device бейджа
     * в чате (audit 2026-09-26). Облачные ответы бейджа не получают —
     * облако = «норма».
     */
    private suspend fun saveAssistantMessage(text: String, executionType: ExecutionType?) {
        val messageId = messageRepository.insertMessage(
            Message(
                role = MessageRole.ASSISTANT,
                text = text,
                timestamp = System.currentTimeMillis()
            )
        )
        if (executionType.handledOnDevice) {
            answerOriginStore.markOnDevice(messageId)
        }
    }

    private suspend fun saveErrorMessage(text: String) {
        messageRepository.insertMessage(
            Message(
                role = MessageRole.ERROR,
                text = text,
                timestamp = System.currentTimeMillis()
            )
        )
    }
}
