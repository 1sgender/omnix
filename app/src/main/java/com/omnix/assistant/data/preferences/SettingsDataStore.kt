package com.omnix.assistant.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.voice.wakeword.WakeWordConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "omnix_settings")

/**
 * Порог срабатывания wake-word по умолчанию; должен совпадать с
 * [WakeWordConfig.threshold] (дефолт движка, если чтение настроек упало).
 */
internal const val DEFAULT_WAKE_WORD_THRESHOLD = 0.35f

/** Клампы порога: даже «максимальная чувствительность» не вырождается в
 * порог 0 (= срабатывает на каждый кадр), «минимальная» — в 1 (никогда). */
internal const val MIN_WAKE_WORD_THRESHOLD = 0.05f
internal const val MAX_WAKE_WORD_THRESHOLD = 0.95f

/**
 * Порог wake-word из настроек с миграцией legacy-чувствительности.
 *
 * До фикса аудита слайдер «чувствительность» писал ключ wake_word_sensitivity,
 * который движок не читал: настройка не влияла на детекцию. Теперь слайдер
 * пишет wakeword.threshold, а старое значение (если порог ещё никогда не
 * записывался) мигрируется один раз: threshold = 1 − sensitivity.
 *
 * Чистая функция — тестируется в JVM-юнит-тесте без Context.
 */
internal fun resolveWakeWordThreshold(stored: Float?, legacySensitivity: Float?): Float {
    if (stored != null) return stored
    if (legacySensitivity == null) return DEFAULT_WAKE_WORD_THRESHOLD
    return (1f - legacySensitivity).coerceIn(MIN_WAKE_WORD_THRESHOLD, MAX_WAKE_WORD_THRESHOLD)
}

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

    /**
     * Чувствительность wake-word (0..1: выше = срабатывает легче).
     * Единый источник правды — ключ wakeword.threshold (его читает движок
     * при старте): sensitivity = 1 − threshold. Legacy-ключ
     * wake_word_sensitivity задействован только как источник миграции
     * в [resolveWakeWordThreshold].
     */
    val wakeWordSensitivityFlow: Flow<Float> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            1f - resolveWakeWordThreshold(
                preferences[PreferencesKeys.WAKEWORD_THRESHOLD],
                preferences[PreferencesKeys.WAKE_WORD_SENSITIVITY]
            )
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

    /** Пишет wakeword.threshold (инверсия чувствительности) — тот же ключ,
     * который читает движок. Кламп см. [MIN_WAKE_WORD_THRESHOLD]. */
    suspend fun setWakeWordSensitivity(sensitivity: Float) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.WAKEWORD_THRESHOLD] =
                (1f - sensitivity).coerceIn(MIN_WAKE_WORD_THRESHOLD, MAX_WAKE_WORD_THRESHOLD)
        }
    }

    /**
     * Neural wake-word конфиг (§11 ТЗ). Дефолты совпадают с [WakeWordConfig].
     * Порог — через [resolveWakeWordThreshold]: ключ wakeword.threshold,
     * при его отсутствии — однократная миграция с legacy-чувствительности.
     */
    val wakeWordConfig: Flow<WakeWordConfig> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) emit(emptyPreferences()) else throw exception
        }
        .map { preferences ->
            WakeWordConfig(
                enabled = preferences[PreferencesKeys.WAKEWORD_ENABLED] ?: true,
                threshold = resolveWakeWordThreshold(
                    preferences[PreferencesKeys.WAKEWORD_THRESHOLD],
                    preferences[PreferencesKeys.WAKE_WORD_SENSITIVITY]
                ),
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
     * Значения — [com.omnix.assistant.agent.localai.downloader.ModelDownloadPolicy]:
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
            preferences[PreferencesKeys.WAKEWORD_THRESHOLD] = DEFAULT_WAKE_WORD_THRESHOLD
            preferences[PreferencesKeys.WAKEWORD_PATIENCE] = 2
            preferences[PreferencesKeys.WAKEWORD_COOLDOWN_MS] = 2000L
            preferences[PreferencesKeys.WAKEWORD_DEBUG] = false
        }
    }
}
