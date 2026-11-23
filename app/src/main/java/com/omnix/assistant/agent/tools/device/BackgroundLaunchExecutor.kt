package com.omnix.assistant.agent.tools.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.omnix.assistant.R
import com.omnix.assistant.agent.tools.accessibility.OmnixAccessibilityService
import com.omnix.assistant.agent.tools.device.BackgroundLaunchPolicy.Route
import com.omnix.assistant.agent.tools.device.BackgroundLaunchPolicy.Step
import com.omnix.assistant.agent.tools.device.BackgroundLaunchPolicy.Verification
import com.omnix.assistant.core.permission.AppVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Запуск чужого приложения с учётом ограничений Android на фоновые запуски:
 * идёт по плану [BackgroundLaunchPolicy.plan] и проверяет результат, где это
 * возможно, а не рапортует «Открываю…» вслепую.
 */
internal class BackgroundLaunchExecutor(private val context: Context) {

    sealed interface Result {
        data object Started : Result
        /** Законных путей нет или ни один не сработал: нужен запасной вариант (уведомление). */
        data object Blocked : Result
        data object NotFound : Result
        data object Denied : Result
    }

    suspend fun launch(intent: Intent, label: String): Result {
        val accessibility = OmnixAccessibilityService.isServiceRunning()
        val plan = BackgroundLaunchPolicy.plan(
            sdkInt = Build.VERSION.SDK_INT,
            appVisible = AppVisibility.isForeground,
            accessibilityConnected = accessibility,
            canDrawOverlays = Settings.canDrawOverlays(context)
        )
        val targetPackage = intent.component?.packageName
            ?: intent.resolveActivity(context.packageManager)?.packageName
        for (route in plan) {
            val started = try {
                attempt(route, intent, label)
            } catch (_: ActivityNotFoundException) {
                return Result.NotFound
            } catch (_: SecurityException) {
                return Result.Denied
            }
            if (!started) continue
            val verification = if (route == Route.DIRECT) {
                Verification.UNKNOWN
            } else {
                verify(targetPackage, accessibility)
            }
            if (BackgroundLaunchPolicy.afterAttempt(route, verification) == Step.DONE) return Result.Started
        }
        return Result.Blocked
    }

    private suspend fun attempt(route: Route, intent: Intent, label: String): Boolean = when (route) {
        Route.DIRECT -> {
            context.startActivity(intent)
            true
        }
        Route.ACCESSIBILITY -> OmnixAccessibilityService.instance?.let {
            it.startActivity(intent)
            true
        } ?: false
        Route.OVERLAY -> withContext(Dispatchers.Main) {
            val overlay = LaunchOverlay(context)
            val visible = overlay.showAndAwaitVisible(context.getString(R.string.omnix_launch_overlay_opening, label))
            if (!visible) {
                overlay.hide()
                return@withContext false
            }
            try {
                context.startActivity(intent)
            } finally {
                overlay.hideLater()
            }
            true
        }
    }

    /**
     * Проверка по активному окну службы доступности: оно знает пакет на экране.
     * Без службы проверить нечем ([Verification.UNKNOWN]).
     */
    private suspend fun verify(targetPackage: String?, accessibilityConnected: Boolean): Verification {
        if (!accessibilityConnected || targetPackage == null) return Verification.UNKNOWN
        var waited = 0L
        while (waited < VERIFY_TIMEOUT_MS) {
            val active = OmnixAccessibilityService.instance?.rootInActiveWindow?.packageName?.toString()
            if (active == targetPackage) return Verification.CONFIRMED
            delay(VERIFY_POLL_MS)
            waited += VERIFY_POLL_MS
        }
        return Verification.NOT_CONFIRMED
    }

    private companion object {
        const val VERIFY_TIMEOUT_MS = 2_000L
        const val VERIFY_POLL_MS = 200L
    }
}
