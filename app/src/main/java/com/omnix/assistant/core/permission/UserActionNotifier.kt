package com.omnix.assistant.core.permission

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Уведомление «нужно ваше действие»: тап по нему запускает Activity законно
 * (запуск по нажатию пользователя Android не блокирует, в отличие от запуска
 * из фонового сервиса). Используется, когда голосовая команда упёрлась в
 * разрешение или в запрет фонового запуска, а экран OMNIX закрыт.
 */
object UserActionNotifier {

    /** Дополнительная кнопка уведомления: подпись и намерение. */
    data class ExtraAction(val label: String, val intent: Intent)

    @Suppress("LongParameterList")
    fun show(
        context: Context,
        channelId: String,
        channelName: String,
        notificationId: Int,
        title: String,
        text: String,
        open: Intent,
        extra: ExtraAction? = null,
        secondExtra: ExtraAction? = null
    ): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_HIGH)
        )
        if (!manager.areNotificationsEnabled()) return false
        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setAutoCancel(true)
            .setContentIntent(pending(context, notificationId, open))
        extra?.let {
            builder.addAction(0, it.label, pending(context, notificationId + 1, it.intent))
        }
        secondExtra?.let {
            builder.addAction(0, it.label, pending(context, notificationId + 2, it.intent))
        }
        manager.notify(notificationId, builder.build())
        return true
    }

    private fun pending(context: Context, requestCode: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
}
