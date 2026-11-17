package com.omnix.assistant.agent.tools.productivity

import android.Manifest
import android.os.Build
import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import com.omnix.assistant.agent.reminder.ReminderAccess
import com.omnix.assistant.agent.reminder.ReminderLabels
import com.omnix.assistant.agent.reminder.ReminderManager
import com.omnix.assistant.agent.reminder.ReminderStatus
import com.omnix.assistant.agent.reminder.ReminderTimeParser
import com.omnix.assistant.core.permission.PermissionPrompts
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Напоминания: «напомни в 18:00 позвонить маме», «через 20 минут …»,
 * «завтра в 10 …». Напоминание хранится в Room и срабатывает уведомлением
 * с полноэкранным показом и голосом, даже если приложение закрыто.
 *
 * Фаза [execute] разбирает время и ставит будильник, [verify] читает
 * запись обратно: «поставил» говорится только если она на месте и ждёт.
 */
@Singleton
class ReminderTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val manager: ReminderManager,
    private val access: ReminderAccess
) : OmniTool {

    override val toolId: String = TOOL_ID
    override val description: String =
        "Ставит напоминание на время и сообщает о нём уведомлением и голосом: " +
            "«напомни в 18:00 позвонить маме», «через 20 минут напомни…», «завтра в 10 напомни…»"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    // Текст напоминания остаётся на телефоне.
    override val mayDiscloseUserContentExternally: Boolean = false

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("phrase") {
                put("type", "string")
                put("description", "Фраза пользователя целиком: «в 18:00 позвонить маме», «через 20 минут выключить плиту»")
            }
            putJsonObject("text") {
                put("type", "string")
                put("description", "О чём напомнить (если время передано отдельно)")
            }
            putJsonObject("time") {
                put("type", "string")
                put("description", "Когда напомнить: «18:00», «через 20 минут», «завтра в 10:00»")
            }
        }
        put("required", buildJsonArray { })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val phrase = arguments["phrase"]?.jsonPrimitive?.contentOrNull
            ?: listOfNotNull(
                arguments["time"]?.jsonPrimitive?.contentOrNull,
                arguments["text"]?.jsonPrimitive?.contentOrNull
            ).joinToString(" ")
        val now = System.currentTimeMillis()
        return when (val parsed = ReminderTimeParser.parse(phrase, now)) {
            ReminderTimeParser.Result.NoTime -> ToolExecutionResult.failure(
                context.getString(R.string.omnix_reminder_no_time),
                "MISSING_PARAM"
            )
            ReminderTimeParser.Result.NoText -> ToolExecutionResult.failure(
                context.getString(R.string.omnix_reminder_no_text),
                "MISSING_PARAM"
            )
            is ReminderTimeParser.Result.Ok -> schedule(parsed, now)
        }
    }

    private suspend fun schedule(parsed: ReminderTimeParser.Result.Ok, now: Long): ToolExecutionResult {
        val created = manager.create(parsed.text, parsed.triggerAt)
        val warnings = mutableListOf<String>()
        if (!created.exact) {
            // Точные будильники не разрешены: напоминание может опоздать.
            access.openExactAlarmSettingsOnce()
            warnings += context.getString(R.string.omnix_reminder_warn_inexact)
        }
        if (!access.notificationsEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                PermissionPrompts.request(listOf(Manifest.permission.POST_NOTIFICATIONS))
            }
            warnings += context.getString(R.string.omnix_reminder_warn_notifications)
        } else if (!access.canUseFullScreen()) {
            warnings += context.getString(R.string.omnix_reminder_warn_fullscreen)
        }
        val whenText = ReminderLabels.whenText(context, parsed.triggerAt, now)
        val main = context.getString(R.string.omnix_reminder_set, whenText, parsed.text)
        val summary = if (warnings.isEmpty()) main else main + " (" + warnings.joinToString("; ") + ")"
        return ToolExecutionResult.success(
            summary = summary,
            data = buildJsonObject {
                put("id", created.reminder.id)
                put("text", parsed.text)
                put("trigger_at", parsed.triggerAt)
                put("exact", created.exact)
            }
        )
    }

    override suspend fun verify(arguments: JsonObject, draft: ToolExecutionResult): ToolExecutionResult {
        val id = (draft.data?.get("id") as? JsonPrimitive)?.longOrNull
            ?: return ToolExecutionResult.failure(
                "Не удалось подтвердить напоминание: нет номера записи",
                "REMINDER_VERIFY_FAILED"
            )
        val stored = manager.get(id)
        return if (stored != null && stored.status == ReminderStatus.PENDING) {
            draft
        } else {
            ToolExecutionResult.failure(
                "Напоминание не сохранилось. Повторите, пожалуйста",
                "REMINDER_VERIFY_FAILED"
            )
        }
    }

    companion object {
        const val TOOL_ID = "productivity.reminder"
    }
}
