package com.omnix.assistant.voice.live

import android.content.Context

/**
 * Какой микрофон слушает собеседника в синхронном переводе.
 *
 *  - [PHONE] — микрофон телефона: лучше всего слышит собеседника, перевод звучит в наушнике
 *    в обычном качестве.
 *  - [EARBUD] — микрофон наушника (режим гарнитуры): телефон может лежать в кармане, но
 *    собеседник слышен хуже, а перевод звучит как в звонке.
 *  - [BOTH] — оба сразу: звук сводится, голос владельца и перевод в ухе отсекаются по разнице
 *    громкости ([DualMicMixer]). Работает, если телефон умеет писать с двух микрофонов
 *    одновременно; иначе режим честно откатывается на [PHONE].
 */
enum class LiveMicMode(val storageKey: String) {
    PHONE("phone"),
    EARBUD("earbud"),
    BOTH("both");

    companion object {
        val DEFAULT = BOTH

        fun fromKey(key: String?): LiveMicMode = entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}

/**
 * Хранение выбора микрофона. SharedPreferences, а не DataStore: значение читается
 * синхронно в момент запуска перевода из голосового контура.
 */
object LiveMicSettings {
    private const val PREFS = "omnix_live_ear"
    private const val KEY_MIC = "mic_mode"

    fun get(context: Context): LiveMicMode =
        LiveMicMode.fromKey(prefs(context).getString(KEY_MIC, null))

    fun set(context: Context, mode: LiveMicMode) {
        prefs(context).edit().putString(KEY_MIC, mode.storageKey).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
