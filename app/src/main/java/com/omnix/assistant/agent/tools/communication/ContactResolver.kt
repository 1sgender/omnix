package com.omnix.assistant.agent.tools.communication

import android.Manifest
import android.content.Context
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import com.omnix.assistant.agent.capability.DeviceCapability
import com.omnix.assistant.agent.capability.DeviceCapabilityRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Результат разрешения получателя в конкретный номер телефона.
 *
 * Раньше CallTool и SmsTool дублировали логику поиска контакта и в случае
 * неудачи молча подставляли введённую строку как «номер». Это приводило к
 * попыткам звонить на текст вроде "маме". Теперь неудача — явное состояние.
 */
sealed interface ContactResolution {
    data class Resolved(val phoneNumber: String, val displayName: String?) : ContactResolution
    data class Ambiguous(val candidates: List<Pair<String, String>>) : ContactResolution
    data class PermissionRequired(val permissions: List<String>) : ContactResolution
    data class NotFound(val query: String) : ContactResolution
}

/**
 * Единая точка разрешения «имя контакта или номер» → номер телефона.
 */
@Singleton
class ContactResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilities: DeviceCapabilityRegistry
) {

    fun resolve(recipient: String): ContactResolution {
        val raw = recipient.trim()
        if (raw.isEmpty()) return ContactResolution.NotFound(recipient)

        // 1. Уже похоже на телефонный номер.
        if (looksLikePhoneNumber(raw)) {
            val normalized = normalizeNumber(raw)
            return if (isValidNormalizedNumber(normalized)) {
                ContactResolution.Resolved(normalized, null)
            } else {
                ContactResolution.NotFound(raw)
            }
        }

        // 2. Иначе нужен доступ к телефонной книге.
        if (!capabilities.statusOf(DeviceCapability.READ_CONTACTS).isAvailable) {
            return ContactResolution.PermissionRequired(listOf(Manifest.permission.READ_CONTACTS))
        }

        return queryContact(raw)
    }

    /** Возвращает до [limit] совпадений — используется ContactsTool. */
    fun search(query: String, limit: Int = 3): List<Pair<String, String>> {
        if (!capabilities.statusOf(DeviceCapability.READ_CONTACTS).isAvailable) return emptyList()
        return ContactMatcher.search(query, loadContacts(), limit)
    }

    private fun queryContact(name: String): ContactResolution =
        ContactMatcher.decide(name, loadContacts()).toResolution(name)

    /**
     * Читает телефонную книгу целиком и сопоставляет имена в Kotlin (а не SQL LIKE):
     * только так работают транслитерация («Осман» ↔ «Osman»), падежи и поиск по части имени.
     */
    private fun loadContacts(): List<ContactEntry> {
        val names = LinkedHashMap<String, String>()
        val starred = HashSet<String>()
        val phones = LinkedHashMap<String, MutableList<PhoneEntry>>()
        context.contentResolver.query(
            Phone.CONTENT_URI,
            arrayOf(
                Phone.CONTACT_ID,
                Phone.DISPLAY_NAME,
                Phone.NUMBER,
                Phone.TYPE,
                Phone.IS_PRIMARY,
                Phone.STARRED
            ),
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val number = cursor.getString(2)?.let(::normalizeNumber).orEmpty()
                if (number.isBlank()) continue
                names.putIfAbsent(id, cursor.getString(1).orEmpty())
                if (cursor.getInt(5) == 1) starred += id
                phones.getOrPut(id) { mutableListOf() } +=
                    PhoneEntry(number, phoneKind(cursor.getInt(3)), cursor.getInt(4) != 0)
            }
        }
        val extras = loadExtraNames()
        return names.map { (id, displayName) ->
            ContactEntry(
                id = id,
                displayName = displayName,
                phones = phones[id].orEmpty(),
                extraNames = extras[id].orEmpty(),
                starred = id in starred
            )
        }
    }

    /** Прозвища и организации контактов (если есть) — дополнительные слова для поиска. */
    private fun loadExtraNames(): Map<String, List<String>> {
        val result = HashMap<String, MutableList<String>>()
        val data = ContactsContract.Data.CONTENT_URI
        context.contentResolver.query(
            data,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.Data.DATA1),
            "${ContactsContract.Data.MIMETYPE} IN (?, ?)",
            arrayOf(
                ContactsContract.CommonDataKinds.Nickname.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE
            ),
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val value = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                result.getOrPut(id) { mutableListOf() } += value
            }
        }
        return result
    }

    private fun phoneKind(type: Int): PhoneKind = when (type) {
        Phone.TYPE_MOBILE -> PhoneKind.MOBILE
        Phone.TYPE_WORK,
        Phone.TYPE_WORK_MOBILE -> PhoneKind.WORK
        Phone.TYPE_HOME -> PhoneKind.HOME
        else -> PhoneKind.OTHER
    }

    companion object {
        /**
         * Номер должен состоять преимущественно из цифр. Строка "маме" или
         * "Иван" номером не является — раньше такие значения попадали в tel:.
         */
        fun looksLikePhoneNumber(value: String): Boolean {
            val digits = value.count { it.isDigit() }
            val allowed = value.all { it.isDigit() || it in " +-()\u00A0" }
            return digits >= 4 && allowed
        }

        fun normalizeNumber(value: String): String = value.replace(Regex("[^0-9+]"), "")

        fun isValidNormalizedNumber(value: String): Boolean =
            Regex("^\\+?[0-9]{4,15}$").matches(value)
    }
}
