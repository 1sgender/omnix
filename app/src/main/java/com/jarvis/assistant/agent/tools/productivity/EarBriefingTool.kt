package com.jarvis.assistant.agent.tools.productivity

import com.jarvis.assistant.agent.briefing.ProactiveEarBriefingEngine
import com.jarvis.assistant.agent.core.JarvisTool
import com.jarvis.assistant.agent.core.ToolCategory
import com.jarvis.assistant.agent.model.ToolExecutionResult
import com.jarvis.assistant.agent.model.ToolRisk
import com.jarvis.assistant.core.license.ClientPlanGate
import com.jarvis.assistant.core.license.ClientQuotaFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EarBriefingTool @Inject constructor(
    private val briefingEngine: ProactiveEarBriefingEngine,
    private val planGate: ClientPlanGate
) : JarvisTool {

    override val toolId: String = "productivity.ear_briefing"
    override val description: String = "Формирует персональный голосовой аудио-брифинг в наушник: время, заряд, статус систем, погода и планы"
    override val category: ToolCategory = ToolCategory.PRODUCTIVITY
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val briefingText = briefingEngine.generateBriefing()
        // Квота ear-минут (часть 2): озвучка идёт выше по стеку, здесь —
        // честная оценка (~800 символов/мин русской речи, минимум 1).
        // Превышение → брифинг не отдаём (говорить «вот брифинг» без речи
        // было бы враньём), ToolExecutor квоту agent action не спишет:
        // failure не потребляет.
        val minutes = ((briefingText.length + 799) / 800).coerceAtLeast(1)
        if (!planGate.tryConsume(ClientQuotaFeature.EAR_MINUTES, minutes)) {
            return ToolExecutionResult.failure(
                summary = "Дневной лимит голосовых брифингов исчерпан. " +
                    "Продолжим завтра, сэр.",
                error = ClientPlanGate.ERROR_PLAN_LIMIT
            )
        }
        return ToolExecutionResult.success(
            summary = briefingText,
            data = buildJsonObject {
                put("briefing", briefingText)
            }
        )
    }
}
