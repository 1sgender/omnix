package com.omnix.assistant.agent.fast

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Диалог недостающих данных для SMS: «отправь смс Осману» → «Что написать Осману?» →
 * следующая реплика становится текстом → дальше обычное подтверждение SmsTool.
 *
 * Сам по себе ничего не отправляет: возвращает [Step.Send], а движок выполняет
 * `communication.sms` через ToolExecutor (политика подтверждения сохраняется).
 * Состояние — только в памяти и живёт [TTL_MS].
 */
@Singleton
class SmsDialogManager @Inject constructor() {

    sealed interface Step {
        object NotHandled : Step
        data class Ask(val prompt: String) : Step
        data class Send(val recipient: String, val message: String) : Step
        data class Cancelled(val text: String) : Step
    }

    private data class Pending(val recipient: String?, val expiresAt: Long)

    private val lock = Any()
    private var pending: Pending? = null

    /** Забыть незавершённый диалог (например, при смене разговора). */
    fun reset() = synchronized(lock) { pending = null }

    /**
     * [isOtherCommand] — реплика сама распознана как другая команда («позвони маме»):
     * тогда она не считается ответом на вопрос, а незавершённый диалог отбрасывается.
     */
    fun handle(
        text: String,
        nowMs: Long = System.currentTimeMillis(),
        isOtherCommand: Boolean = false
    ): Step = synchronized(lock) {
        val current = pending?.takeIf { it.expiresAt > nowMs && !isOtherCommand }
        pending = null

        val fresh = SmsCommandParser.parse(text)
        if (current != null && fresh == null) {
            return continuePending(current, text, nowMs)
        }
        if (fresh == null) return Step.NotHandled
        return intentToStep(fresh, nowMs)
    }

    private fun intentToStep(intent: SmsCommandParser.SmsIntent, nowMs: Long): Step {
        val recipient = intent.recipient ?: return ask(null, nowMs)
        val message = intent.message ?: return ask(recipient, nowMs)
        return Step.Send(recipient, message)
    }

    private fun continuePending(current: Pending, text: String, nowMs: Long): Step {
        val answer = text.trim()
        if (answer.isEmpty()) return ask(current.recipient, nowMs)
        if (isCancel(answer)) return Step.Cancelled("Хорошо, SMS не отправляю.")
        val recipient = current.recipient
        if (recipient == null) {
            // Ответ на «Кому?»: имя, возможно сразу с текстом («Осману привет»).
            val parsed = SmsCommandParser.parse("смс $answer") ?: return ask(null, nowMs)
            return intentToStep(parsed, nowMs)
        }
        return Step.Send(recipient, answer)
    }

    private fun ask(recipient: String?, nowMs: Long): Step {
        pending = Pending(recipient, nowMs + TTL_MS)
        return Step.Ask(if (recipient == null) "Кому отправить SMS?" else "Что написать $recipient?")
    }

    companion object {
        const val TTL_MS = 120_000L

        private val CANCEL_WORDS = setOf(
            "отмена", "отмени", "отменить", "не надо", "не нужно", "стоп", "нет", "передумал",
            "cancel", "stop", "never mind", "nevermind", "no", "vazgeç", "iptal", "bolmasyn",
            "ýok", "bekor", "kerek emes", "жоқ", "болдырмау"
        )

        fun isCancel(text: String): Boolean =
            text.lowercase().trim().trimEnd('.', '!', '?') in CANCEL_WORDS
    }
}
