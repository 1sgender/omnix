package com.omnix.assistant.core.clip

import android.content.Context
import android.provider.Settings
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Стабильный отпечаток телефона для правила «пробный Pro — один раз на
 * телефон» (V013).
 *
 * ANDROID_ID на Android 8+ уникален для пары «ключ подписи приложения +
 * пользователь» и переживает очистку данных и переустановку. Наружу уходит
 * только SHA-256 от него с солью приложения — сам идентификатор не
 * передаётся и не хранится; сервер дополнительно хеширует его своим ключом.
 */
@Singleton
class PhoneIdentity @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /** 64 hex-символа или null (идентификатора нет/заглушка эмулятора). */
    @Suppress("HardwareIds")
    fun phoneHash(): String? {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() && it != BROKEN_ANDROID_ID } ?: return null
        return hash(androidId)
    }

    companion object {
        private const val SALT = "omnix-clip-phone-v1:"

        /** Известный ANDROID_ID ранних прошивок, общий для многих телефонов. */
        private const val BROKEN_ANDROID_ID = "9774d56d682e549c"

        fun hash(androidId: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest((SALT + androidId).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
