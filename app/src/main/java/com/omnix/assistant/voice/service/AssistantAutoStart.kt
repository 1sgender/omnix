package com.omnix.assistant.voice.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnix.assistant.R
import com.omnix.assistant.core.battery.BatteryOptimizationHelper
import com.omnix.assistant.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * «Ассистент включён» переживает перезапуск процесса, обновление и перезагрузку
 * (отзыв владельца на v344: «должен слышать, даже когда приложение не запущено»).
 * Выключает только сам пользователь — логотипом или кнопкой в уведомлении.
 */
object AssistantAutoStart {
    private const val PREFS = "omnix_assistant"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BATTERY_ASKED = "battery_exemption_asked"

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Просить исключение из оптимизации батареи один раз и только если его ещё нет. */
    fun shouldAskBatteryExemption(context: Context): Boolean =
        !context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_BATTERY_ASKED, false) &&
            !BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)

    fun markBatteryExemptionAsked(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_BATTERY_ASKED, true).apply()
    }
}

/**
 * После перезагрузки и обновления приложения поднимает прослушивание, если
 * пользователь его не выключал. Android 14+ может не дать микрофонной службе
 * стартовать из фона — тогда остаётся уведомление: одно нажатие открывает
 * OMNIX, и прослушивание включается само.
 */
class AssistantRearmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AssistantAutoStart.isEnabled(context)) return
        val app = context.applicationContext
        val started = OmnixVoiceService.start(app, rememberEnabled = false)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Служба отказывается от старта уже после startForegroundService
                // (нет доступа к микрофону из фона) — проверяем по факту.
                delay(CHECK_DELAY_MS)
                if (!started || !OmnixVoiceService.isRunning) {
                    Log.i(TAG, "listening not restored in background (${intent.action}); asking the user")
                    showRearmNotification(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showRearmNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.omnix_rearm_channel), NotificationManager.IMPORTANCE_DEFAULT)
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(context.getString(R.string.omnix_rearm_title))
            .setContentText(context.getString(R.string.omnix_rearm_body))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.omnix_rearm_body)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "rearm notification failed", it) }
    }

    private companion object {
        const val TAG = "AssistantRearm"
        const val CHANNEL_ID = "omnix_rearm"
        const val NOTIFICATION_ID = 1002
        const val CHECK_DELAY_MS = 5_000L
    }
}
