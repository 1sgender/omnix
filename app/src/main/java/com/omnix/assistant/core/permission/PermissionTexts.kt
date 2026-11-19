package com.omnix.assistant.core.permission

import android.content.Context
import androidx.annotation.StringRes
import com.omnix.assistant.R

/** Названия разрешений для голоса, диалога и уведомления (6 языков через ресурсы). */
object PermissionTexts {

    @StringRes
    private fun labelRes(kind: PermissionKind): Int = when (kind) {
        PermissionKind.CONTACTS -> R.string.omnix_perm_kind_contacts
        PermissionKind.CALLS -> R.string.omnix_perm_kind_calls
        PermissionKind.SMS -> R.string.omnix_perm_kind_sms
        PermissionKind.NOTIFICATIONS -> R.string.omnix_perm_kind_notifications
        PermissionKind.LOCATION -> R.string.omnix_perm_kind_location
        PermissionKind.MICROPHONE -> R.string.omnix_perm_kind_microphone
        PermissionKind.CALENDAR -> R.string.omnix_perm_kind_calendar
        PermissionKind.OTHER -> R.string.omnix_perm_kind_other
    }

    fun describe(context: Context, permissions: Collection<String>): String =
        PermissionKind.distinctOf(permissions)
            .joinToString(", ") { context.getString(labelRes(it)) }
}
