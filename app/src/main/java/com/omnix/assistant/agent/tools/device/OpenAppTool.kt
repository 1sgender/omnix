package com.omnix.assistant.agent.tools.device

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.omnix.assistant.R
import com.omnix.assistant.core.permission.UserActionNotifier
import com.omnix.assistant.agent.apps.AppResolution
import com.omnix.assistant.agent.apps.AppResolver
import com.omnix.assistant.agent.capability.DangerLevel
import com.omnix.assistant.agent.capability.DeviceCapability
import com.omnix.assistant.agent.capability.OmniCapability
import com.omnix.assistant.agent.capability.ToolCapabilityContract
import com.omnix.assistant.agent.core.CapabilityAwareTool
import com.omnix.assistant.agent.core.ToolCategory
import com.omnix.assistant.agent.model.ToolExecutionResult
import com.omnix.assistant.agent.model.ToolRisk
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement
import com.omnix.assistant.agent.tools.accessibility.AccessibilityStatusReader
import com.omnix.assistant.agent.tools.accessibility.AccessibilityWizardRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open App Tool v0.2.
 *
 * Разрешение имени приложения вынесено в [AppResolver]:
 * normalize → exact → alias → fuzzy → package name.
 *
 * Честные состояния вместо прежнего «открыл магазин = success»:
 *  - приложение найдено и запущено       → SUCCESS
 *  - приложение известно, но не стоит    → FAILURE (APP_NOT_INSTALLED) + предложение установить
 *  - несколько похожих кандидатов        → FAILURE (AMBIGUOUS) с уточняющим вопросом
 *  - совпадений нет                      → FAILURE (APP_UNKNOWN)
 *
 * Единый контракт: публичного API «приложение в фокусе» без специального
 * доступа НЕТ, поэтому verify() — pass-through; формулировка результата —
 * сделанное действие («Открываю…»), а не результат («Открыл»).
 */
@Singleton
class OpenAppTool @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appResolver: AppResolver
) : CapabilityAwareTool {

    private val executor = BackgroundLaunchExecutor(context)

    private companion object {
        const val LAUNCH_CHANNEL_ID = "omnix_app_launch"
        const val LAUNCH_NOTIFICATION_ID = 7411
    }

    override val toolId: String = "device.open_app"
    override val description: String = "Открывает установленное приложение на телефоне по названию (Telegram, YouTube, WhatsApp, Камера, Chrome, Карты, Настройки)"
    override val category: ToolCategory = ToolCategory.DEVICE
    override val riskLevel: ToolRisk = ToolRisk.LOW
    override val isOffline: Boolean = true
    override val mayDiscloseUserContentExternally: Boolean = true
    override val requiresForeground: Boolean = true

    override val capabilityContract = ToolCapabilityContract(
        capabilities = setOf(DeviceCapability.OPEN_APP),
        dangerLevel = DangerLevel.LOW
    )
    override val capability: OmniCapability = OmniCapability.Apps

    override val parametersSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("app_name") {
                put("type", "string")
                put("description", "Название приложения так, как его назвал пользователь: telegram, ютуб, whatsapp, камера, карты, настройки")
            }
        }
        put("required", buildJsonArray { add("app_name") })
    }

    override suspend fun execute(arguments: JsonObject): ToolExecutionResult {
        val rawName = arguments["app_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (rawName.isEmpty()) {
            return ToolExecutionResult.failure("Не указано название приложения", "MISSING_PARAM")
        }

        return when (val resolution = appResolver.resolve(rawName)) {
            is AppResolution.Resolved -> launch(resolution, rawName)

            is AppResolution.NotInstalled -> ToolExecutionResult.failure(
                summary = "Приложение «$rawName» не установлено на устройстве",
                error = "APP_NOT_INSTALLED"
            ).copy(
                data = buildJsonObject {
                    put("query", rawName)
                    resolution.knownPackage?.let { put("expected_package", it) }
                }
            )

            is AppResolution.Ambiguous -> ToolExecutionResult.failure(
                summary = "Нашёл несколько подходящих приложений: " +
                    resolution.candidates.joinToString(", ") { it.label } +
                    ". Какое именно открыть?",
                error = "AMBIGUOUS_APP"
            ).copy(
                data = buildJsonObject {
                    put("query", rawName)
                    put("candidates", buildJsonArray {
                        resolution.candidates.forEach { candidate ->
                            add(buildJsonObject {
                                put("label", candidate.label)
                                put("package", candidate.packageName)
                            })
                        }
                    })
                }
            )

            is AppResolution.Unknown -> ToolExecutionResult.failure(
                summary = "Не нашёл приложение «$rawName» среди установленных",
                error = "APP_UNKNOWN"
            )
        }
    }

    private suspend fun launch(resolution: AppResolution.Resolved, rawName: String): ToolExecutionResult {
        // Системные экраны (настройки, камера) открываются через action, а не launch intent.
        appResolver.systemActions[resolution.packageName]?.let { action ->
            val intent = Intent(action).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            if (intent.resolveActivity(context.packageManager) == null) {
                return ToolExecutionResult.failure(
                    summary = "Системный экран «$rawName» недоступен на этом устройстве",
                    error = "SYSTEM_SCREEN_UNAVAILABLE"
                )
            }
            return when (executor.launch(intent, rawName)) {
                BackgroundLaunchExecutor.Result.Started -> ToolExecutionResult.success(
                    summary = "Открываю $rawName",
                    data = buildJsonObject { put("action", action) }
                )
                BackgroundLaunchExecutor.Result.Blocked -> backgroundBlocked(rawName, intent)
                BackgroundLaunchExecutor.Result.NotFound ->
                    ToolExecutionResult.failure("Не удалось открыть $rawName", "ACTIVITY_NOT_FOUND")
                BackgroundLaunchExecutor.Result.Denied ->
                    ToolExecutionResult.failure("Система запретила открыть $rawName", "LAUNCH_DENIED")
            }
        }

        val launchIntent = context.packageManager.getLaunchIntentForPackage(resolution.packageName)
            ?: return ToolExecutionResult.failure(
                summary = "У приложения «${resolution.label}» нет экрана запуска",
                error = "NO_LAUNCH_INTENT"
            )

        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return when (executor.launch(launchIntent, resolution.label)) {
            BackgroundLaunchExecutor.Result.Started -> ToolExecutionResult.success(
                summary = "Открываю ${resolution.label}",
                data = buildJsonObject {
                    put("package", resolution.packageName)
                    put("label", resolution.label)
                    put("matched_by", resolution.matchedBy.name)
                }
            )
            BackgroundLaunchExecutor.Result.Blocked -> backgroundBlocked(resolution.label, launchIntent)
            BackgroundLaunchExecutor.Result.NotFound -> ToolExecutionResult.failure(
                summary = "Не удалось запустить ${resolution.label}",
                error = "ACTIVITY_NOT_FOUND"
            )
            BackgroundLaunchExecutor.Result.Denied -> ToolExecutionResult.failure(
                summary = "Система запретила запуск ${resolution.label}",
                error = "LAUNCH_DENIED"
            )
        }
    }

    /**
     * Экран OMNIX закрыт, и Android молча отбросил бы запуск: раньше инструмент
     * отвечал «Открываю…» и ничего не открывалось. Теперь честно говорим причину
     * и даём законный путь: уведомление (тап по нему запускает приложение) и
     * кнопку выдачи разрешения «Поверх других приложений».
     */
    private fun backgroundBlocked(label: String, intent: Intent): ToolExecutionResult {
        val overlaySettings = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:${context.packageName}")
        )
        // Служба специальных возможностей необязательна: подсказываем её только пока
        // она не включена, без навязывания, и сразу говорим про запасной путь.
        val a11yStatus = runCatching { AccessibilityStatusReader.read(context) }.getOrNull()
        val suggestA11y = a11yStatus != null &&
            AccessibilityEnablement.shouldSuggestInBlockedLaunch(a11yStatus)
        // Слой agent не знает про presentation: берём стартовое намерение своего пакета.
        val wizardIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.putExtra(AccessibilityWizardRequest.EXTRA_OPEN_WIZARD, true)
        UserActionNotifier.show(
            context = context,
            channelId = LAUNCH_CHANNEL_ID,
            channelName = context.getString(R.string.omnix_perm_launch_channel),
            notificationId = LAUNCH_NOTIFICATION_ID,
            title = context.getString(R.string.omnix_perm_launch_title, label),
            text = context.getString(R.string.omnix_perm_launch_text),
            open = intent,
            extra = UserActionNotifier.ExtraAction(
                label = context.getString(R.string.omnix_perm_launch_overlay),
                intent = overlaySettings
            ),
            secondExtra = if (suggestA11y && wizardIntent != null) {
                UserActionNotifier.ExtraAction(
                    label = context.getString(R.string.omnix_a11y_notification_action),
                    intent = wizardIntent
                )
            } else {
                null
            }
        )
        val blockedText = context.getString(R.string.omnix_perm_launch_blocked, label)
        return ToolExecutionResult.userActionRequired(
            summary = if (suggestA11y) {
                blockedText + " " + context.getString(R.string.omnix_a11y_hint_blocked_launch)
            } else {
                blockedText
            },
            reason = "BACKGROUND_LAUNCH_BLOCKED",
            data = buildJsonObject { put("label", label) }
        )
    }

    /** Предлагает установить приложение — вызывается явно, а не молча вместо запуска. */
    fun openStoreFor(query: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=$query")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        }
    }
}
