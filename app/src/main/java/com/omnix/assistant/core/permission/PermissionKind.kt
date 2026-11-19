package com.omnix.assistant.core.permission

/**
 * Человеческая группа runtime-разрешения: для фразы «нужен доступ к контактам».
 * Чистая логика без Android, проверяется юнит-тестами.
 */
enum class PermissionKind {
    CONTACTS,
    CALLS,
    SMS,
    NOTIFICATIONS,
    LOCATION,
    MICROPHONE,
    CALENDAR,
    OTHER;

    companion object {
        private const val PREFIX = "android.permission."

        fun of(permission: String): PermissionKind = when (permission.removePrefix(PREFIX)) {
            "READ_CONTACTS", "WRITE_CONTACTS", "GET_ACCOUNTS" -> CONTACTS
            "CALL_PHONE", "READ_PHONE_STATE", "READ_CALL_LOG", "ANSWER_PHONE_CALLS" -> CALLS
            "SEND_SMS", "READ_SMS", "RECEIVE_SMS" -> SMS
            "POST_NOTIFICATIONS" -> NOTIFICATIONS
            "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "ACCESS_BACKGROUND_LOCATION" -> LOCATION
            "RECORD_AUDIO" -> MICROPHONE
            "READ_CALENDAR", "WRITE_CALENDAR" -> CALENDAR
            else -> OTHER
        }

        /** Группы без повторов в порядке появления. */
        fun distinctOf(permissions: Collection<String>): List<PermissionKind> =
            permissions.map(::of).distinct()
    }
}

/** Разбор ответа системного диалога. */
object PermissionOutcome {
    /**
     * Отказанные разрешения, о которых система больше не спросит
     * («больше не спрашивать»): для них остаётся только экран настроек.
     * [canAskAgain] — `shouldShowRequestPermissionRationale`.
     */
    fun permanentlyDenied(denied: Collection<String>, canAskAgain: (String) -> Boolean): List<String> =
        denied.filterNot(canAskAgain)
}
