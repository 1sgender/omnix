package com.jarvis.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.jarvis.assistant.core.constants.AppConstants
import com.jarvis.assistant.voice.wakeword.WakeWordConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "jarvis_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object PreferencesKeys {
        val USER_NAME = stringPreferencesKey("user_name")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val SELECTED_MODEL = stringPreferencesKey("selected_model")
        val HEADSET_ONLY_MODE = booleanPreferencesKey("headset_only_mode")
        val WAKE_WORD_SENSITIVITY = floatPreferencesKey("wake_word_sensitivity")
        val WAKEWORD_ENABLED = booleanPreferencesKey("wakeword.enabled")
        val WAKEWORD_THRESHOLD = floatPreferencesKey("wakeword.threshold")
        val WAKEWORD_PATIENCE = intPreferencesKey("wakeword.patience_frames")
        val WAKEWORD_COOLDOWN_MS = longPreferencesKey("wakeword.cooldown_ms")
        val WAKEWORD_DEBUG = booleanPreferencesKey("wakeword.debug_logging")
        val LOCAL_MODEL_CONSENT = stringPreferencesKey("local_model_consent")
        val LOCAL_MODEL_DOWNLOAD_ID = longPreferencesKey("local_model_download_id")
    }

    val userNameFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.USER_NAME] ?: "Сэр"
        }

    val systemPromptFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SYSTEM_PROMPT] ?: AppConstants.DEFAULT_SYSTEM_PROMPT
        }

    val speechRateFlow: Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SPEECH_RATE] ?: AppConstants.DEFAULT_SPEECH_RATE
        }

    val speechPitchFlow: Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SPEECH_PITCH] ?: AppConstants.DEFAULT_SPEECH_PITCH
        }

    val selectedModelFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.SELECTED_MODEL] ?: AppConstants.DEFAULT_MODEL
        }

    val isHeadsetOnlyModeFlow: Flow<Boolean> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.HEADSET_ONLY_MODE] ?: false
        }

    val wakeWordSensitivityFlow: Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.WAKE_WORD_SENSITIVITY] ?: 0.65f
        }

    suspend fun setUserName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USER_NAME] = name
        }
    }

    suspend fun setSystemPrompt(prompt: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SYSTEM_PROMPT] = prompt
        }
    }

    suspend fun setSpeechRate(rate: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_RATE] = rate
        }
    }

    suspend fun setSpeechPitch(pitch: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SPEECH_PITCH] = pitch
        }
    }

    suspend fun setSelectedModel(model: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.SELECTED_MODEL] = model
        }
    }

    suspend fun setHeadsetOnlyMode(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.HEADSET_ONLY_MODE] = enabled
        }
    }

    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKE_WORD_SENSITIVITY] = sensitivity
        }
    }

    /**
     * Neural wake-word конфиг (§11 ТЗ). Дефолты совпадают с [WakeWordConfig].
     * Legacy-ключ wake_word_sensitivity оставлен нетронутым для совместимости.
     */
    val wakeWordConfig: Flow<WakeWordConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            WakeWordConfig(
                enabled = preferences[PreferencesKeys.WAKEWORD_ENABLED] ?: true,
                threshold = preferences[PreferencesKeys.WAKEWORD_THRESHOLD] ?: 0.5f,
                patienceFrames = preferences[PreferencesKeys.WAKEWORD_PATIENCE] ?: 2,
                cooldownMs = preferences[PreferencesKeys.WAKEWORD_COOLDOWN_MS] ?: 2000L,
                debugLogging = preferences[PreferencesKeys.WAKEWORD_DEBUG] ?: false,
            )
        }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKEWORD_ENABLED] = enabled
        }
    }

    suspend fun setWakeWordThreshold(threshold: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKEWORD_THRESHOLD] = threshold
        }
    }

    suspend fun setWakeWordPatience(frames: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKEWORD_PATIENCE] = frames
        }
    }

    suspend fun setWakeWordCooldownMs(cooldownMs: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKEWORD_COOLDOWN_MS] = cooldownMs
        }
    }

    suspend fun setWakeWordDebugLogging(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKEWORD_DEBUG] = enabled
        }
    }

    /**
     * Одноразовое согласие на автозагрузку локальной модели (~521 МБ).
     * Значения — [com.jarvis.assistant.agent.localai.downloader.ModelDownloadPolicy]:
     * unasked / any / wifi / later.
     */
    val localModelConsentFlow: Flow<String> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_MODEL_CONSENT] ?: "unasked"
        }

    suspend fun setLocalModelConsent(consent: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_MODEL_CONSENT] = consent
        }
    }

    /**
     * Системный downloadId активной загрузки модели (-1 — нет).
     * Переживает перезапуск: DownloadManager помнит очередь.
     */
    val localModelDownloadIdFlow: Flow<Long> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            preferences[PreferencesKeys.LOCAL_MODEL_DOWNLOAD_ID] ?: -1L
        }

    suspend fun setLocalModelDownloadId(downloadId: Long) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.LOCAL_MODEL_DOWNLOAD_ID] = downloadId
        }
    }

    suspend fun resetDefaults() {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.USER_NAME] = "Сэр"
            preferences[PreferencesKeys.SYSTEM_PROMPT] = AppConstants.DEFAULT_SYSTEM_PROMPT
            preferences[PreferencesKeys.SPEECH_RATE] = AppConstants.DEFAULT_SPEECH_RATE
            preferences[PreferencesKeys.SPEECH_PITCH] = AppConstants.DEFAULT_SPEECH_PITCH
            preferences[PreferencesKeys.SELECTED_MODEL] = AppConstants.DEFAULT_MODEL
            preferences[PreferencesKeys.HEADSET_ONLY_MODE] = false
            preferences[PreferencesKeys.WAKE_WORD_SENSITIVITY] = 0.65f
            preferences[PreferencesKeys.WAKEWORD_ENABLED] = true
            preferences[PreferencesKeys.WAKEWORD_THRESHOLD] = 0.5f
            preferences[PreferencesKeys.WAKEWORD_PATIENCE] = 2
            preferences[PreferencesKeys.WAKEWORD_COOLDOWN_MS] = 2000L
            preferences[PreferencesKeys.WAKEWORD_DEBUG] = false
        }
    }
}
