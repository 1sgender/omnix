package com.omnix.assistant.agent.tools.media

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.omnix.assistant.R
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
import com.omnix.assistant.agent.media.ymusic.MusicFailure
import com.omnix.assistant.agent.media.ymusic.MusicFallback
import com.omnix.assistant.agent.media.ymusic.MusicStart
import com.omnix.assistant.agent.media.ymusic.YandexMusicClient
import com.omnix.assistant.agent.media.ymusic.YandexMusicDecision
import com.omnix.assistant.agent.media.ymusic.YandexMusicPlayer
import com.omnix.assistant.agent.media.ymusic.YandexMusicSettingsRequest
import com.omnix.assistant.agent.media.ymusic.YandexMusicTokenStore
import com.omnix.assistant.agent.media.ymusic.YmPlayResult
import com.omnix.assistant.agent.media.ymusic.YmResult
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
 * Если в настройках есть токен Яндекс Музыки, трек играет внутри OMNIX
 * ([YandexMusicPlayer]); ответ «Играю …» даётся только после того, как плеер
 * реально запустил звук. Без токена инструмент просит вставить его и открывает
 * настройки. Если внутри сыграть не вышло (сеть, нет трека), открывается поиск в
 * приложении Яндекс Музыка или на её сайте ([MusicSearchPlan]); YouTube исключён.
 */
@Singleton
class MusicSearchTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokens: YandexMusicTokenStore,
    private val client: YandexMusicClient,
    private val player: YandexMusicPlayer
) : CapabilityAwareTool {

    override val toolId: String = "media.play_search"
    override val description: String =
        "Находит песню или исполнителя в Яндекс Музыке и играет её внутри OMNIX. " +
            "Для просьб «найди песню X», «включи песню X», «поставь X»; не для паузы, громкости и переключения треков"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = false

    /** Название песни уходит Яндекс Музыке: секреты блокирует фильтр приватности. */
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

        val token = tokens.token()
        if (YandexMusicDecision.start(token != null) == MusicStart.ASK_FOR_TOKEN || token == null) {
            openTokenSettings()
            return ToolExecutionResult.failure(context.getString(R.string.ymusic_need_token), "NO_YANDEX_TOKEN")
        }

        val failure = when (val found = client.search(query, token)) {
            is YmResult.Ok -> when (val played = player.play(found.value)) {
                is YmPlayResult.Started -> {
                    val track = played.track
                    val summary = if (track.artists.isEmpty()) {
                        context.getString(R.string.ymusic_now_playing_no_artist, track.title)
                    } else {
                        context.getString(R.string.ymusic_now_playing, track.title, track.artistLine)
                    }
                    return ToolExecutionResult.success(
                        summary = summary,
                        data = buildJsonObject {
                            put("query", query)
                            put("track", track.title)
                            put("artist", track.artistLine)
                            put("target", "OMNIX_PLAYER")
                        }
                    )
                }
                is YmPlayResult.Failed -> played.failure
            }
            YmResult.Unauthorized -> MusicFailure.UNAUTHORIZED
            YmResult.NoResults -> MusicFailure.NO_RESULTS
            YmResult.Unavailable -> MusicFailure.UNAVAILABLE
        }

        return when (YandexMusicDecision.fallback(failure)) {
            MusicFallback.OPEN_TOKEN_SETTINGS -> {
                openTokenSettings()
                ToolExecutionResult.failure(context.getString(R.string.ymusic_token_rejected), "YANDEX_TOKEN_REJECTED")
            }
            MusicFallback.OPEN_EXTERNAL_SEARCH -> openExternalSearch(query)
        }
    }

    private fun openExternalSearch(query: String): ToolExecutionResult =
        when (val outcome = MusicSearchRunner.run(query, AndroidStepLauncher(context))) {
            is MusicSearchOutcome.Opened -> ToolExecutionResult.success(
                summary = context.getString(R.string.ymusic_fallback_search, query),
                data = buildJsonObject {
                    put("query", query)
                    put("target", outcome.target.name)
                }
            )
            MusicSearchOutcome.NothingInstalled -> ToolExecutionResult.failure(
                summary = "Не получилось включить «$query»: нет сети или трека, а приложения Яндекс Музыка и браузера на телефоне не нашлось",
                error = "NO_MUSIC_APP"
            )
        }

    /** Поднимает приложение и просит открыть раздел настроек Яндекс Музыки. */
    private fun openTokenSettings() {
        YandexMusicSettingsRequest.request()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            context.startActivity(launch)
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }

    private class AndroidStepLauncher(private val context: Context) : MusicStepLauncher {
        override fun launch(step: MusicSearchStep): Boolean {
            val intent = when (step.kind) {
                MusicIntentKind.VIEW -> Intent(Intent.ACTION_VIEW, Uri.parse(step.uri))
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
