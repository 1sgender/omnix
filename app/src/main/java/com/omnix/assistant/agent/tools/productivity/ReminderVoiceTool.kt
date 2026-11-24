package com.omnix.assistant.agent.tools.productivity

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import com.omnix.assistant.agent.reminder.ReminderAnnouncer
import com.omnix.assistant.agent.reminder.ReminderManager
import com.omnix.assistant.agent.reminder.ReminderNotifier
import com.omnix.assistant.agent.reminder.ReminderVoiceCommand
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Голосовое «готово» / «отложи на 10 минут» для сработавшего напоминания.
 * Действует на самое свежее сработавшее напоминание. Если таких нет, честно
 * отвечает «нет активного напоминания» и ничего не меняет и не выдумывает.
 */
@Singleton
class ReminderVoiceTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ReminderManager,
    private val notifier: ReminderNotifier,
    private val announcer: ReminderAnnouncer
) : OmniTool {

    override val toolId: String = ReminderVoiceCommand.TOOL_ID
    override val description: String =
        "Отвечает на сработавшее напоминание: «готово» отмечает выполненным, " +
            "«отложи на 10 минут» переносит его"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = false

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(ReminderVoiceCommand.ARG_ACTION) {
                put("type", "string")
                put("description", "done (выполнено) или snooze (отложить)")
            }
            putJsonObject(ReminderVoiceCommand.ARG_MINUTES) {
                put("type", "integer")
                put("description", "На сколько минут отложить (по умолчанию 10)")
            }
        }
        put("required", buildJsonArray { add(ReminderVoiceCommand.ARG_ACTION) })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val action = arguments[ReminderVoiceCommand.ARG_ACTION]?.jsonPrimitive?.contentOrNull
        if (action != ReminderVoiceCommand.ACTION_DONE && action != ReminderVoiceCommand.ACTION_SNOOZE) {
            return ToolExecutionResult.failure("Не указано, что сделать с напоминанием", "MISSING_PARAM")
        }
        val target = manager.latestFired()
            ?: return reply(context.getString(R.string.omnix_reminder_voice_none), applied = false)

        // Озвучка самого напоминания больше не нужна: пользователь уже ответил.
        announcer.stop()
        if (action == ReminderVoiceCommand.ACTION_DONE) {
            manager.done(target.id)
            notifier.cancel(target.id)
            return reply(context.getString(R.string.omnix_reminder_voice_done, target.text), applied = true, id = target.id)
        }
        val minutes = (arguments[ReminderVoiceCommand.ARG_MINUTES] as? JsonPrimitive)?.intOrNull
            ?.takeIf { it in 1..ReminderVoiceCommand.MAX_SNOOZE_MINUTES }
            ?: ReminderVoiceCommand.DEFAULT_SNOOZE_MINUTES
        manager.snooze(target.id, minutes)
        notifier.cancel(target.id)
        val text = if (minutes % MINUTES_IN_HOUR == 0) {
            val hours = minutes / MINUTES_IN_HOUR
            context.getString(R.string.omnix_reminder_voice_snoozed_hours, hours, target.text)
        } else {
            context.getString(R.string.omnix_reminder_voice_snoozed_minutes, minutes, target.text)
        }
        return reply(text, applied = true, id = target.id)
    }

    /** Итог озвучивается с точкой в конце (ExecutionDecisionEngine): свою убираем. */
    private fun reply(text: String, applied: Boolean, id: Long? = null): ToolExecutionResult =
        ToolExecutionResult.success(
            summary = text.trim().trimEnd('.'),
            data = buildJsonObject {
                put("applied", applied)
                if (id != null) put("id", id)
            }
        )

    private companion object {
        const val MINUTES_IN_HOUR = 60
    }
}
