package com.omnix.assistant.agent.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.omnix.assistant.R
import com.omnix.assistant.presentation.reminders.ReminderAlertActivity
import com.omnix.assistant.voice.audio.SoftChime
import com.omnix.assistant.voice.tts.TextToSpeechManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Что Android разрешил напоминаниям: точные будильники, уведомления, полный экран. */
@Singleton
class ReminderAccess @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    fun notificationsEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true

    /** На Android 14 полноэкранное уведомление можно запретить в настройках. */
    fun canUseFullScreen(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
    }

    /**
     * Открывает системный экран «Будильники и напоминания» для OMNIX. Один раз:
     * человек уже видел запрос, повторять его при каждом напоминании не нужно.
     */
    fun openExactAlarmSettingsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_EXACT_PROMPTED, false)) return
        prefs.edit().putBoolean(KEY_EXACT_PROMPTED, true).apply()
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "exact alarm settings not opened: ${it.javaClass.simpleName}") }
    }

    private companion object {
        const val TAG = "ReminderAccess"
        const val PREFS = "reminder_access"
        const val KEY_EXACT_PROMPTED = "exact_alarm_prompted"
    }
}

/** Будильники напоминаний на AlarmManager: по одному на напоминание, ключ — его id. */
@Singleton
class AndroidReminderAlarms @Inject constructor(
    @ApplicationContext private val context: Context,
    private val access: ReminderAccess
) : ReminderAlarms {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun schedule(id: Long, triggerAt: Long): Boolean {
        val manager = alarmManager ?: return false
        val operation = fireIntent(id, PendingIntent.FLAG_UPDATE_CURRENT) ?: return false
        if (access.canScheduleExact()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
                return true
            } catch (_: SecurityException) {
                // Доступ могли отозвать между проверкой и вызовом.
                Log.w(TAG, "exact alarm access changed; scheduled inexact fallback")
            }
        }
        // Без точных будильников напоминание всё равно сработает, но может опоздать.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, operation)
        return false
    }

    override fun cancel(id: Long) {
        fireIntent(id, PendingIntent.FLAG_NO_CREATE)?.let { alarmManager?.cancel(it) }
    }

    private fun fireIntent(id: Long, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(context, id.toInt(), intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val TAG = "ReminderAlarms"
    }
}

/**
 * Уведомление высокой важности с полноэкранным показом: на заблокированном
 * экране оно открывает [ReminderAlertActivity], при включённом экране
 * всплывает сверху. Кнопки «Готово» и «Отложить» работают прямо из него.
 */
@Singleton
class ReminderNotifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun show(reminder: ReminderEntity) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.omnix_reminders_title),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                // Звук даёт SoftChime вместе с голосом: два сигнала сразу не нужны.
                setSound(null, null)
                enableVibration(true)
            }
        )
        if (!manager.areNotificationsEnabled()) return
        val alert = alertIntent(reminder.id)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.omnix_reminder_label))
            .setContentText(reminder.text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(alert)
            .setFullScreenIntent(alert, true)
            .addAction(0, context.getString(R.string.omnix_reminder_done), actionIntent(ReminderReceiver.ACTION_DONE, reminder.id))
            .addAction(0, context.getString(R.string.omnix_reminder_snooze), actionIntent(ReminderReceiver.ACTION_SNOOZE, reminder.id))
            .build()
        manager.notify(reminder.id.toInt(), notification)
    }

    fun cancel(id: Long) {
        context.getSystemService(NotificationManager::class.java)?.cancel(id.toInt())
    }

    private fun alertIntent(id: Long): PendingIntent {
        val intent = Intent(context, ReminderAlertActivity::class.java)
            .putExtra(ReminderAlertActivity.EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun actionIntent(action: String, id: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(action)
            .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private companion object {
        const val CHANNEL_ID = "omnix_reminders"
    }
}

/** Голос и мягкий сигнал в момент напоминания: «Напоминаю: позвонить маме». */
@Singleton
class ReminderAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tts: TextToSpeechManager
) {
    fun announce(reminder: ReminderEntity) {
        SoftChime.play(SoftChime.reminder())
        tts.speak(context.getString(R.string.omnix_reminder_voice, reminder.text))
    }

    fun stop() = tts.stop()
}
