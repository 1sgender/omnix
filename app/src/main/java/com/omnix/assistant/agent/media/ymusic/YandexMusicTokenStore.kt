package com.omnix.assistant.agent.media.ymusic

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Токен Яндекс Музыки владельца. Хранится только на телефоне в
 * EncryptedSharedPreferences (ключ в Android Keystore), не логируется,
 * на сервер OMNIX не отправляется и в облачный бэкап не попадает.
 */
@Singleton
class YandexMusicTokenStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun token(): String? = runCatching { prefs.getString(KEY, null) }.getOrNull()?.takeIf { it.isNotBlank() }

    fun hasToken(): Boolean = token() != null

    /** false — значение не похоже на токен, ничего не сохранено. */
    fun save(raw: String): Boolean {
        val token = YandexMusicDecision.normalizeToken(raw) ?: return false
        return runCatching { prefs.edit().putString(KEY, token).commit() }.getOrDefault(false)
    }

    fun clear() {
        runCatching { prefs.edit().remove(KEY).commit() }
    }

    private companion object {
        const val FILE = "omnix_ymusic_token"
        const val KEY = "oauth_token"
    }
}
