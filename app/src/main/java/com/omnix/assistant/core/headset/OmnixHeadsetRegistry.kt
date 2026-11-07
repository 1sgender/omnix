package com.omnix.assistant.core.headset

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class RegisteredHeadset(val address: String, val name: String)

/**
 * Наушники OMNIX, зарегистрированные на этом телефоне (уровень 1 привязки).
 * Обычные SharedPreferences: адрес наушников не секрет, а регистрирует их
 * только админ.
 */
@Singleton
class OmnixHeadsetRegistry @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _headsets = MutableStateFlow(readHeadsets())
    val headsets: StateFlow<List<RegisteredHeadset>> = _headsets.asStateFlow()

    private val _enforceForAdmin = MutableStateFlow(prefs.getBoolean(KEY_ENFORCE_ADMIN, false))

    /** Админ включил проверку и для себя — чтобы увидеть то, что видят все. */
    val enforceForAdmin: StateFlow<Boolean> = _enforceForAdmin.asStateFlow()

    fun add(address: String, name: String) {
        val key = OmnixHeadsetPolicy.normalize(address)
        if (key.isEmpty()) return
        val next = _headsets.value.filterNot { it.address == key } + RegisteredHeadset(key, name)
        save(next)
    }

    fun remove(address: String) {
        val key = OmnixHeadsetPolicy.normalize(address)
        save(_headsets.value.filterNot { it.address == key })
    }

    fun setEnforceForAdmin(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENFORCE_ADMIN, enabled).apply()
        _enforceForAdmin.value = enabled
    }

    private fun save(list: List<RegisteredHeadset>) {
        prefs.edit().putString(KEY_HEADSETS, json.encodeToString(LIST, list)).apply()
        _headsets.value = list
    }

    private fun readHeadsets(): List<RegisteredHeadset> = runCatching {
        json.decodeFromString(LIST, prefs.getString(KEY_HEADSETS, null) ?: "[]")
    }.getOrDefault(emptyList())

    private companion object {
        const val PREFS_NAME = "omnix_headsets"
        const val KEY_HEADSETS = "headsets"
        const val KEY_ENFORCE_ADMIN = "enforce_for_admin"
        val LIST = ListSerializer(RegisteredHeadset.serializer())
        val json = Json { ignoreUnknownKeys = true }
    }
}
