package com.omnix.assistant.voice.stt

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Контекстные подсказки движкам распознавания: слово вызова, названия функций
 * приложения и (только для распознавателя Android на телефоне) имена контактов.
 *
 * Чистая часть без Android: проверяется юнит-тестами.
 */
object AsrContextHints {

    const val WAKE_NAME = "Омни"

    /** Сколько строк отдаём в EXTRA_BIASING_STRINGS. */
    const val MAX_BIASING_STRINGS = 80

    /** Контакты с длинными «именами» (организации, подписи) только мешают. */
    const val MAX_NAME_LENGTH = 40

    /** Whisper учитывает лишь последние ~224 токена подсказки; держим её короткой. */
    const val MAX_PROMPT_CHARS = 240

    private val APP_PHRASES = listOf(
        "OMNIX", "напомни", "будильник", "таймер", "переводчик", "навигатор", "позвони",
        "включи музыку", "Spotify", "YouTube", "Telegram", "WhatsApp", "Bluetooth", "Wi-Fi",
        "фонарик", "громкость", "яркость", "jaň et", "ýatla"
    )

    /**
     * Строки для EXTRA_BIASING_STRINGS: «Омни», имена контактов (полное имя и первое слово),
     * затем названия функций. Без повторов (без учёта регистра), не больше [MAX_BIASING_STRINGS].
     */
    fun biasingStrings(contactNames: List<String>): List<String> {
        val names = contactNames.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.length <= MAX_NAME_LENGTH }
            .flatMap { full -> sequenceOf(full, full.substringBefore(' ')) }
        val all = sequenceOf(WAKE_NAME) + names + APP_PHRASES.asSequence()
        return all.distinctBy { it.lowercase() }.take(MAX_BIASING_STRINGS).toList()
    }

    /**
     * Подсказка (prompt) для Whisper на сервере. Имена контактов сюда НЕ попадают:
     * это личные данные, а сервер сторонний облачный сервис.
     */
    fun serverPrompt(): String {
        val joined = (listOf(WAKE_NAME) + APP_PHRASES).joinToString(", ")
        return if (joined.length <= MAX_PROMPT_CHARS) joined else joined.take(MAX_PROMPT_CHARS)
    }
}

/**
 * Имена контактов для подсказок распознавателю. Читаются, только если выдано
 * READ_CONTACTS; кэшируются, чтобы не ходить в телефонную книгу на каждую фразу.
 */
@Singleton
class AsrHintsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers
) {
    @Volatile
    private var cachedNames: List<String> = emptyList()

    @Volatile
    private var loadedAtMs = NEVER_LOADED

    /** Подсказки из кэша: мгновенно, можно звать с главного потока. */
    fun cachedBiasing(): List<String> = AsrContextHints.biasingStrings(cachedNames)

    /** Обновляет кэш имён контактов, если он устарел; работает вне главного потока. */
    suspend fun refreshIfStale() {
        val now = SystemClock.elapsedRealtime()
        if (loadedAtMs != NEVER_LOADED && now - loadedAtMs < TTL_MS) return
        cachedNames = withContext(dispatchers.io) { readContactNames() }
        loadedAtMs = now
    }

    private fun readContactNames(): List<String> {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return emptyList()
        val names = ArrayList<String>()
        try {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
                null,
                "${ContactsContract.Contacts.STARRED} DESC"
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
                while (nameIndex != -1 && cursor.moveToNext() && names.size < MAX_CONTACTS) {
                    cursor.getString(nameIndex)?.let { names += it }
                }
            }
        } catch (e: SecurityException) {
            return emptyList()
        }
        return names
    }

    private companion object {
        const val NEVER_LOADED = -1L
        const val TTL_MS = 5 * 60 * 1000L
        const val MAX_CONTACTS = 100
    }
}
