package com.omnix.assistant.agent.tools.accessibility

import android.content.Context
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Android-обвязка над [AccessibilityEnablement]: читает настройки системы. */
object AccessibilityStatusReader {

    fun read(context: Context): AccessibilityEnablement.Status =
        AccessibilityEnablement.status(
            enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ),
            packageName = context.packageName,
            serviceClass = OmnixAccessibilityService::class.java.name,
            sdkInt = Build.VERSION.SDK_INT,
            installerPackage = installerOf(context)
        )

    fun oem(): AccessibilityEnablement.Oem = AccessibilityEnablement.oemFor(Build.MANUFACTURER)

    @Suppress("DEPRECATION")
    private fun installerOf(context: Context): String? = runCatching {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pm.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            pm.getInstallerPackageName(context.packageName)
        }
    }.getOrNull()
}

/**
 * Просьба открыть мастер включения службы (из уведомления при блокировке
 * запуска приложений). Как [com.omnix.assistant.agent.media.ymusic.YandexMusicSettingsRequest]:
 * MainActivity ставит просьбу, навигация подхватывает и очищает.
 */
object AccessibilityWizardRequest {
    /** Extra намерения, которым уведомление просит открыть мастер. */
    const val EXTRA_OPEN_WIZARD = "omnix.open_accessibility_wizard"

    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() { _pending.value = true }

    fun consume() { _pending.value = false }
}
