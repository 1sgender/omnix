package com.omnix.assistant.agent.tools.media

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.omnix.assistant.agent.capability.DangerLevel
import com.omnix.assistant.agent.capability.DeviceCapability
import com.omnix.assistant.agent.capability.OmniCapability
import com.omnix.assistant.agent.capability.ToolCapabilityContract
import com.omnix.assistant.agent.core.CapabilityAwareTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.media.MusicIntentKind
import com.omnix.assistant.agent.media.MusicSearchOutcome
import com.omnix.assistant.agent.media.MusicSearchPlan
import com.omnix.assistant.agent.media.MusicSearchRunner
import com.omnix.assistant.agent.media.MusicSearchStep
import com.omnix.assistant.agent.media.MusicStepLauncher
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
 * Поиск песни по названию или исполнителю: «найди песню глаза не врут»,
 * «включи песню X», «поставь X».
 *
 * Без ключей и без сети со стороны OMNIX: открывается поиск в музыкальном
 * приложении (порядок и логика в [MusicSearchPlan]). Честность результата:
 * инструмент сообщает, ГДЕ открыл поиск, а не что песня играет, потому что
 * чужое приложение не отдаёт обратной связи.
 */
@Singleton
class MusicSearchTool @Inject constructor(
    @ApplicationContext private val context: Context
) : CapabilityAwareTool {

    override val toolId: String = "media.play_search"
    override val description: String =
        "Ищет и открывает песню или исполнителя в музыкальном приложении (Яндекс Музыка, YouTube Music, YouTube). " +
            "Для просьб «найди песню X», «включи песню X», «поставь X»; не для паузы, громкости и переключения треков"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true

    /** Название песни уходит стороннему приложению: секреты блокирует фильтр приватности. */
    override val mayDiscloseUserContentExternally: Boolean = true
    override val requiresForeground: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.CONTROL_MEDIA),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: OmniCapability = OmniCapability.Media

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("query") {
                put("type", "string")
                put("description", "Название песни и/или исполнитель, как сказал пользователь: «глаза не врут»")
            }
        }
        put("required", buildJsonArray { add("query") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val query = MusicSearchPlan.sanitize(arguments["query"]?.jsonPrimitive?.contentOrNull)
            ?: return ToolExecutionResult.failure("Не указано, какую песню искать", "MISSING_QUERY")

        return when (val outcome = MusicSearchRunner.run(query, AndroidStepLauncher(context))) {
            is MusicSearchOutcome.Opened -> ToolExecutionResult.success(
                summary = "Ищу «$query» в ${outcome.target.label}",
                data = buildJsonObject {
                    put("query", query)
                    put("target", outcome.target.name)
                }
            )
            MusicSearchOutcome.NothingInstalled -> ToolExecutionResult.failure(
                summary = "Не нашёл на телефоне ни музыкального приложения, ни браузера, чтобы искать «$query»",
                error = "NO_MUSIC_APP"
            )
        }
    }

    private class AndroidStepLauncher(private val context: Context) : MusicStepLauncher {
        override fun launch(step: MusicSearchStep): Boolean {
            val intent = when (step.kind) {
                MusicIntentKind.VIEW -> Intent(Intent.ACTION_VIEW, Uri.parse(step.uri))
                MusicIntentKind.MEDIA_PLAY_FROM_SEARCH -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                    putExtra(SearchManager.QUERY, step.query)
                    putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                }
                MusicIntentKind.SEARCH -> Intent(Intent.ACTION_SEARCH).apply {
                    putExtra("query", step.query)
                    putExtra(SearchManager.QUERY, step.query)
                }
            }
            step.packageName?.let { intent.setPackage(it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            return try {
                context.startActivity(intent)
                true
            } catch (_: ActivityNotFoundException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
