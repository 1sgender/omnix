package com.omnix.assistant.agent.tools.system

import android.os.Build
import com.omnix.assistant.agent.core.OmniTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GetDeviceInfoTool @Inject constructor() : OmniTool {

    override val toolId: String = "system.device_info"
    override val description: String = "Возвращает модель устройства, производителя и версию Android"
    override val category: ToolCategory = ToolCategory.SYSTEM
    override val riskLevel: ToolRisk = ToolRisk.SAFE
    override val isOffline: Boolean = true
    override val supportsParallel: Boolean = true

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { }
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val model = Build.MODEL
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val androidVersion = Build.VERSION.RELEASE
        val sdk = Build.VERSION.SDK_INT

        val summary = "Устройство $manufacturer $model, Android $androidVersion (API $sdk)"
        val dataObj = buildJsonObject {
            put("manufacturer", manufacturer)
            put("model", model)
            put("android_version", androidVersion)
            put("api_level", sdk)
        }

        return ToolExecutionResult.success(summary = summary, data = dataObj)
    }
}
