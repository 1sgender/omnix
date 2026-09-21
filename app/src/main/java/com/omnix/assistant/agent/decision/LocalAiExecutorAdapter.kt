package com.omnix.assistant.agent.decision

import android.util.Log
import com.omnix.assistant.agent.localai.LocalAi
import com.omnix.assistant.agent.localai.LocalAiResult
import com.omnix.assistant.agent.memory.procedural.WorkflowExecutor
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local AI как execution backend движка решений (Этап 2).
 *
 * Реализует СУЩЕСТВУЮЩИЙ порт [LocalAiExecutor] — сам
 * [ExecutionDecisionEngine] не изменён ни на строку.
 *
 * Порядок внутри локального слоя:
 *
 * ```
 * 1. Процедурная память (WorkflowExecutor) — детерминированные офлайн-макросы
 *    пользователя («сон», «работа»). Мгновенно, без инференса.
 * 2. Локальная LLM (LocalAi) — свободный вопрос-ответ офлайн.
 * ```
 *
 * Маппинг исходов в контракт Этапа 1:
 *
 * ```
 * LocalAiResult.Success          → LocalAiOutcome.Handled   → ExecutionType.LOCAL_AI
 * LocalAiResult.Unsupported      → LocalAiOutcome.Uncertain → Cloud AI / Agent
 * LocalAiResult.FailedToFallback → LocalAiOutcome.Fallback  → Cloud AI + сообщение пользователю
 * LocalAiResult.Error            → LocalAiOutcome.Failed    → ExecutionResult.Error
 * ```
 *
 * Почему генерационный `Error` НЕ эскалируется в облако: движок Этапа 1
 * намеренно детерминирован (пункт 16 ТЗ) — один проход по цепочке без
 * повторов; упавшая ПОСЛЕ старта генерация — честная ошибка, а не повод
 * молча отправить, возможно приватный, запрос в сеть.
 *
 * Исключение — провал ИНИЦИАЛИЗАЦИИ (решение владельца 2026-09-21):
 * запрос не должен погибать из-за того, что локальная модель не завелась.
 * Он уходит в облако по обычному privacy-гейту (приватный без согласия
 * всё равно блокируется), а пользователь видит сообщение про
 * офлайн-версию с причиной сбоя.
 */
@Singleton
class CompositeLocalAiExecutor @Inject constructor(
    private val workflowExecutor: WorkflowExecutor,
    private val localAi: LocalAi
) : LocalAiExecutor {

    private companion object {
        const val TAG = "DecisionEngine"
    }

    /**
     * Локальный слой офлайн: ни процедурная память, ни on-device модель
     * не имеют доступа в интернет. Движок использует это, чтобы пропустить
     * локальный путь при `requiresWeb == true`.
     */
    override val hasWebCapability: Boolean = false

    override suspend fun tryHandle(request: ExecutionRequest): LocalAiOutcome {
        // ---------------------------------------------- 1. Процедурная память
        val workflowResult = workflowExecutor.tryExecuteWorkflow(request.text)
        if (workflowResult != null) {
            Log.d(TAG, "local layer = PROCEDURAL_MEMORY")
            return if (workflowResult.isSuccess) {
                LocalAiOutcome.Handled("${workflowResult.summary}, сэр.")
            } else {
                LocalAiOutcome.Failed(workflowResult.summary)
            }
        }

        // ------------------------------------------------- 2. Локальная модель
        return when (val result = localAi.execute(request)) {
            is LocalAiResult.Success -> {
                Log.d(TAG, "local layer = ON_DEVICE_LLM | ${result.metrics.toLogString()}")
                LocalAiOutcome.Handled(result.text)
            }

            is LocalAiResult.Unsupported -> {
                Log.d(TAG, "local layer declined: ${result.reason}")
                LocalAiOutcome.Uncertain
            }

            is LocalAiResult.FailedToFallback -> {
                // Решение владельца 2026-09-21: инициализация модели провалилась —
                // запрос уходит в облако, причина доезжает до пользователя.
                Log.w(TAG, "local layer failed to start | ${result.reason} — облачный fallback")
                LocalAiOutcome.Fallback(result.reason)
            }

            is LocalAiResult.Error -> {
                Log.w(TAG, "local layer error | chars=${result.message.length}")
                LocalAiOutcome.Failed(result.message)
            }
        }
    }
}
