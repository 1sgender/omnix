package com.omnix.assistant.core.permission

import android.content.Context
import com.omnix.assistant.R

/** Голосовая команда ждёт разрешения, а экран закрыт: зовём пользователя уведомлением. */
object PermissionRequestNotifier {
    private const val CHANNEL_ID = "omnix_permission_requests"
    private const val NOTIFICATION_ID = 7410

    fun show(context: Context, accessLabel: String): Boolean {
        // Пустой Intent() при тапе ничего не открывает: без launch-intent уведомление не показываем.
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return false
        return UserActionNotifier.show(
            context = context,
            channelId = CHANNEL_ID,
            channelName = context.getString(R.string.omnix_perm_notif_channel),
            notificationId = NOTIFICATION_ID,
            title = context.getString(R.string.omnix_perm_notif_title),
            text = context.getString(R.string.omnix_perm_notif_text, accessLabel),
            open = open
        )
    }
}
