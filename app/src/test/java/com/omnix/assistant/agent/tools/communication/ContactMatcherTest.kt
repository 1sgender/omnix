package com.omnix.assistant.agent.tools.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactMatcherTest {

    private var nextId = 0

    private fun contact(
        name: String,
        number: String = "+7999${100000 + nextId}",
        starred: Boolean = false,
        extra: List<String> = emptyList(),
        phones: List<PhoneEntry>? = null
    ) = ContactEntry(
        id = (++nextId).toString(),
        displayName = name,
        phones = phones ?: listOf(PhoneEntry(number, PhoneKind.MOBILE)),
        extraNames = extra,
        starred = starred
    )

    private fun resolved(outcome: MatchOutcome): String {
        assertTrue("ожидали однозначный выбор, а получили $outcome", outcome is MatchOutcome.Single)
        return (outcome as MatchOutcome.Single).displayName
    }

    private val book = listOf(
        contact("Osman"), contact("Oskar"), contact("Mama"), contact("Yusuf"),
        contact("Dr. Ahmedov"), contact("Алексей Петров"), contact("Hasan")
    )

    @Test
    fun `cyrillic query finds latin contact`() {
        assertEquals("Osman", resolved(ContactMatcher.decide("Осман", book)))
        assertEquals("Mama", resolved(ContactMatcher.decide("Мама", book)))
        assertEquals("Yusuf", resolved(ContactMatcher.decide("Юсуф", book)))
        assertEquals("Hasan", resolved(ContactMatcher.decide("Хасан", book)))
    }

    @Test
    fun `latin query finds cyrillic contact`() {
        val cyr = listOf(contact("Осман"), contact("Мама"), contact("Алексей"), contact("Хасан"))
        assertEquals("Осман", resolved(ContactMatcher.decide("Osman", cyr)))
        assertEquals("Мама", resolved(ContactMatcher.decide("Mama", cyr)))
        assertEquals("Алексей", resolved(ContactMatcher.decide("Alexey", cyr)))
        assertEquals("Хасан", resolved(ContactMatcher.decide("Hasan", cyr)))
    }

    @Test
    fun `russian case forms resolve to the base name`() {
        listOf("Осману", "Османа", "Османом", "осману").forEach {
            assertEquals(it, "Osman", resolved(ContactMatcher.decide(it, book)))
        }
        assertEquals("Mama", resolved(ContactMatcher.decide("маме", book)))
        assertEquals("Алексей Петров", resolved(ContactMatcher.decide("Алексею", book)))
    }

    @Test
    fun `osman does not find oskar`() {
        assertEquals(MatchOutcome.None, ContactMatcher.decide("Осман", listOf(contact("Oskar"))))
    }

    @Test
    fun `empty and garbage queries find nothing`() {
        listOf("", "   ", "!!!", "\u2764\uFE0F", "123").forEach {
            assertEquals(it, MatchOutcome.None, ContactMatcher.decide(it, book))
        }
    }

    @Test
    fun `turkmen names`() {
        val tm = listOf(contact("Mämmet"), contact("Gurbanguly"), contact("Şatlyk"))
        assertEquals("Mämmet", resolved(ContactMatcher.decide("Меммет", tm)))
        assertEquals("Mämmet", resolved(ContactMatcher.decide("Маммет", tm)))
        assertEquals("Gurbanguly", resolved(ContactMatcher.decide("Гурбангулы", tm)))
        assertEquals("Şatlyk", resolved(ContactMatcher.decide("Шатлык", tm)))
        val cyr = listOf(contact("Меммет"), contact("Шатлык"))
        assertEquals("Меммет", resolved(ContactMatcher.decide("Mämmet", cyr)))
        assertEquals("Шатлык", resolved(ContactMatcher.decide("Şatlyk", cyr)))
    }

    @Test
    fun `any part of a long name matches in any order`() {
        val people = listOf(contact("Osman Karimov"), contact("Осман Каримович Алиев"), contact("Мама ❤️"))
        assertEquals("Osman Karimov", resolved(ContactMatcher.decide("Каримову", people.take(1))))
        assertEquals("Osman Karimov", resolved(ContactMatcher.decide("Каримов Осман", people.take(1))))
        assertEquals("Osman Karimov", resolved(ContactMatcher.decide("Osman Karimov", people.take(1))))
        assertEquals("Осман Каримович Алиев", resolved(ContactMatcher.decide("Алиеву", people.drop(1))))
        assertEquals("Осман Каримович Алиев", resolved(ContactMatcher.decide("Алиев Осман", people.drop(1))))
        assertEquals("Мама ❤️", resolved(ContactMatcher.decide("маме", people)))
    }

    @Test
    fun `titles brackets and emoji do not interfere`() {
        val people = listOf(contact("Доктор Ахмедов"), contact("Юсуф (работа)"))
        assertEquals("Доктор Ахмедов", resolved(ContactMatcher.decide("Ахмедову", people)))
        assertEquals("Доктор Ахмедов", resolved(ContactMatcher.decide("Dr. Ahmedov", people)))
        assertEquals("Доктор Ахмедов", resolved(ContactMatcher.decide("доктору Ахмедову", people)))
        assertEquals("Юсуф (работа)", resolved(ContactMatcher.decide("Yusuf", people)))
    }

    @Test
    fun `nickname and organization are searchable`() {
        val people = listOf(contact("Иван Петров", extra = listOf("Сантехник", "Vodokanal")))
        assertEquals("Иван Петров", resolved(ContactMatcher.decide("Сантехник", people)))
        assertEquals("Иван Петров", resolved(ContactMatcher.decide("Vodokanal", people)))
    }

    @Test
    fun `exact beats transliterated beats prefix in ranking`() {
        val people = listOf(contact("Osmanov"), contact("Osman Ali"), contact("Osman"))
        val ranked = ContactMatcher.rank("Осман", people)
        assertEquals(listOf("Osman", "Osman Ali", "Osmanov"), ranked.map { it.displayName })
        assertTrue(ranked[0].score > ranked[1].score)
        assertTrue(ranked[1].score > ranked[2].score)
    }

    @Test
    fun `fuzzy matches are only for search not for dialing`() {
        val people = listOf(contact("Usman"))
        assertEquals(MatchOutcome.None, ContactMatcher.decide("Осман", people))
        val found = ContactMatcher.search("Осман", people, limit = 3)
        assertEquals(listOf("Usman"), found.map { it.first })
    }

    @Test
    fun `different people with the same first name are ambiguous`() {
        val people = listOf(contact("Осман Каримов"), contact("Osman Aliyev"))
        val outcome = ContactMatcher.decide("Осман", people)
        assertTrue(outcome.toString(), outcome is MatchOutcome.MultiplePeople)
        assertEquals(2, (outcome as MatchOutcome.MultiplePeople).people.size)
        val resolution = outcome.toResolution("Осман")
        assertTrue(resolution is ContactResolution.Ambiguous)
        // фамилия снимает неоднозначность
        assertEquals("Осман Каримов", resolved(ContactMatcher.decide("Каримову", people)))
        assertEquals("Osman Aliyev", resolved(ContactMatcher.decide("Osman Aliyev", people)))
    }

    @Test
    fun `starred contact wins at equal rank`() {
        val people = listOf(contact("Осман Каримов"), contact("Osman Aliyev", starred = true))
        assertEquals("Osman Aliyev", resolved(ContactMatcher.decide("Осман", people)))
    }

    @Test
    fun `two starred contacts at equal rank stay ambiguous`() {
        val people = listOf(contact("Осман Каримов", starred = true), contact("Osman Aliyev", starred = true))
        assertTrue(ContactMatcher.decide("Осман", people) is MatchOutcome.MultiplePeople)
    }

    @Test
    fun `one person written in two alphabets is not ambiguous`() {
        val people = listOf(contact("Осман", number = "+99365111111"), contact("Osman", number = "+99365111111"))
        val outcome = ContactMatcher.decide("Осман", people)
        assertEquals("+99365111111", (outcome as MatchOutcome.Single).phone.number)
        assertEquals(1, ContactMatcher.rank("Osman", people).size)
    }

    @Test
    fun `duplicate alphabets with different numbers ask which number`() {
        val people = listOf(contact("Осман", number = "+99365111111"), contact("Osman", number = "+99365222222"))
        val outcome = ContactMatcher.decide("Osman", people)
        assertTrue(outcome.toString(), outcome is MatchOutcome.MultipleNumbers)
        assertEquals(2, (outcome as MatchOutcome.MultipleNumbers).phones.size)
    }

    @Test
    fun `person with several numbers prefers mobile or the requested type`() {
        val phones = listOf(
            PhoneEntry("+10001", PhoneKind.WORK),
            PhoneEntry("+10002", PhoneKind.MOBILE),
            PhoneEntry("+10003", PhoneKind.HOME)
        )
        val people = listOf(contact("Юсуф", phones = phones))
        assertEquals("+10002", (ContactMatcher.decide("Юсуф", people) as MatchOutcome.Single).phone.number)
        assertEquals("+10001", (ContactMatcher.decide("Юсуф рабочий", people) as MatchOutcome.Single).phone.number)
        assertEquals("+10003", (ContactMatcher.decide("Yusuf home", people) as MatchOutcome.Single).phone.number)
    }

    @Test
    fun `several mobile numbers without hint ask which one`() {
        val phones = listOf(PhoneEntry("+10001", PhoneKind.MOBILE), PhoneEntry("+10002", PhoneKind.MOBILE))
        val outcome = ContactMatcher.decide("Юсуф", listOf(contact("Юсуф", phones = phones)))
        assertTrue(outcome is MatchOutcome.MultipleNumbers)
        val resolution = outcome.toResolution("Юсуф") as ContactResolution.Ambiguous
        assertEquals(2, resolution.candidates.size)
    }

    @Test
    fun `primary number wins among several`() {
        val phones = listOf(PhoneEntry("+10001", PhoneKind.MOBILE), PhoneEntry("+10002", PhoneKind.MOBILE, isPrimary = true))
        val outcome = ContactMatcher.decide("Юсуф", listOf(contact("Юсуф", phones = phones)))
        assertEquals("+10002", (outcome as MatchOutcome.Single).phone.number)
    }

    @Test
    fun `truncated name asks for confirmation instead of guessing`() {
        val outcome = ContactMatcher.decide("усма", listOf(contact("Usman")))
        assertTrue(outcome.toString(), outcome is MatchOutcome.MultiplePeople)
        assertEquals(1, (outcome as MatchOutcome.MultiplePeople).people.size)
        assertTrue(ambiguityMessage(listOf("Usman" to "+1")).contains("Вы имели в виду"))
        assertEquals(MatchOutcome.None, ContactMatcher.decide("усма", listOf(contact("Osman"))))
    }

    @Test
    fun `ambiguity message lists the people`() {
        val text = ambiguityMessage(listOf("Осман Каримов" to "+1", "Osman Aliyev" to "+2"))
        assertTrue(text.contains("Осман Каримов"))
        assertTrue(text.contains("Osman Aliyev"))
    }

    @Test
    fun `single outcome converts to resolved`() {
        val resolution = ContactMatcher.decide("Мама", book).toResolution("Мама")
        assertTrue(resolution is ContactResolution.Resolved)
        assertEquals("Mama", (resolution as ContactResolution.Resolved).displayName)
        assertTrue(ContactMatcher.decide("Кто-то", book).toResolution("Кто-то") is ContactResolution.NotFound)
    }
}
