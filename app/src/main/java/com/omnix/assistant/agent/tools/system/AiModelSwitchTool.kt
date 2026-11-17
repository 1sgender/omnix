package com.omnix.assistant.agent.tools.system

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.decision.AiModelActivation
import com.omnix.assistant.agent.decision.AiModelActivationError
import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.decision.AiModelSwitch
import com.omnix.assistant.agent.fast.AiModelVoiceCommand
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Голосовая смена модели: «переключись на Pro» → та же проверка, что в
 * листе выбора чата, и короткое подтверждение голосом на языке приложения.
 * Не получилось (Pro нет в тарифе, сервер молчит) — прежний выбор остаётся
 * и OMNIX говорит, почему. Это обычная реплика, а не авария.
 */
@Singleton
class AiModelSwitchTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val switch: AiModelSwitch
) : OmniTool {

    override val toolId: String = AiModelVoiceCommand.TOOL_ID
    override val description: String = "Меняет модель ИИ, которая отвечает: Flash (быстро) или Pro (умнее)"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    /** Проверка Pro/Flash ходит на сервер, модель на телефоне грузится долго. */
    override val executionTimeoutMs: Long = 20_000L

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("choice") {
                put("type", "string")
                put("description", "auto, on_device, cloud (Flash) или pro")
            }
        }
        put("required", buildJsonArray { add("choice") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val key = arguments["choice"]?.jsonPrimitive?.contentOrNull
        val target = AiModelChoice.entries.firstOrNull { it.storageKey == key }
            ?: return ToolExecutionResult.failure("Не указано, какую модель включить", "MISSING_CHOICE")
        val result = switch.switchTo(target)
        val text = when (result) {
            AiModelActivation.Applied ->
                context.getString(R.string.omnix_ai_model_voice_switched, nameOf(target))
            is AiModelActivation.Failed -> failureText(result)
        }
        // Итог озвучивается с точкой в конце (ExecutionDecisionEngine) — свою убираем.
        return ToolExecutionResult.success(
            summary = text.trim().trimEnd('.'),
            data = buildJsonObject {
                put("choice", (if (result == AiModelActivation.Applied) target else switch.selected()).storageKey)
                put("applied", result == AiModelActivation.Applied)
            }
        )
    }

    private fun failureText(failure: AiModelActivation.Failed): String = when (failure.error) {
        AiModelActivationError.PRO_NOT_IN_PLAN ->
            context.getString(R.string.omnix_ai_model_voice_pro_locked, nameOf(switch.selected()))
        AiModelActivationError.SERVER_UNREACHABLE -> context.getString(R.string.omnix_ai_model_error_server)
        AiModelActivationError.LOCAL_NOT_INSTALLED -> context.getString(R.string.omnix_ai_model_error_not_installed)
        AiModelActivationError.LOCAL_DOWNLOADING -> context.getString(R.string.omnix_ai_model_error_downloading)
        AiModelActivationError.LOCAL_NOT_ENOUGH_MEMORY -> context.getString(R.string.omnix_ai_model_error_memory)
        AiModelActivationError.LOCAL_LOAD_FAILED ->
            context.getString(R.string.omnix_ai_model_error_load, failure.detail.orEmpty().ifEmpty { "—" })
    }

    private fun nameOf(choice: AiModelChoice): String = context.getString(
        when (choice) {
            AiModelChoice.AUTO -> R.string.omnix_ai_model_auto
            AiModelChoice.ON_DEVICE -> R.string.omnix_ai_model_on_device
            AiModelChoice.CLOUD -> R.string.omnix_ai_model_cloud
            AiModelChoice.PRO -> R.string.omnix_tier_pro
        }
    )
}
