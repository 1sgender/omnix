package com.omnix.assistant.agent.tools.communication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NameTranslitTest {

    private fun assertSame(a: String, b: String) =
        assertTrue("«$a» должно совпасть с «$b»: ${NameTranslit.keys(a)} / ${NameTranslit.keys(b)}", NameTranslit.sameWord(a, b))

    private fun assertDifferent(a: String, b: String) =
        assertFalse("«$a» не должно совпасть с «$b»", NameTranslit.sameWord(a, b))

    @Test
    fun `basic cyrillic and latin pairs match both ways`() {
        listOf(
            "Осман" to "Osman", "Мама" to "Mama", "Юсуф" to "Yusuf", "Алексей" to "Alexey",
            "Хасан" to "Hasan", "Джон" to "John", "Мария" to "Maria", "Дмитрий" to "Dmitry",
            "Фёдор" to "Fyodor", "Аслан" to "Aslan"
        ).forEach { (cyr, lat) ->
            assertSame(cyr, lat)
            assertSame(lat, cyr)
        }
    }

    @Test
    fun `several spellings of one name share a key`() {
        listOf("Yusuf", "Iusuf", "Jusuf", "Yusuph").forEach { assertSame("Юсуф", it) }
        listOf("Aleksey", "Alexey", "Aleksei").forEach { assertSame("Алексей", it) }
        listOf("Sergey", "Sergei").forEach { assertSame("Сергей", it) }
        listOf("Hasan", "Khasan", "Xasan").forEach { assertSame("Хасан", it) }
        listOf("John", "Dzhon", "Jon").forEach { assertSame("Джон", it) }
    }

    @Test
    fun `turkmen turkish uzbek and kazakh letters`() {
        assertSame("Mämmet", "Меммет")
        assertSame("Mämmet", "Маммет")
        assertSame("Gurbanguly", "Гурбангулы")
        assertSame("Şatlyk", "Шатлык")
        assertSame("Çary", "Чары")
        assertSame("Cem", "Джем")
        assertSame("Ahmet", "Ахмед")
        assertSame("Әлихан", "Alikhan")
        assertSame("Aslan", "Аслан")
    }

    @Test
    fun `different names do not match`() {
        assertDifferent("Осман", "Оскар")
        assertDifferent("Осман", "Усман")
        assertDifferent("Мама", "Папа")
    }

    @Test
    fun `case is ignored`() {
        assertSame("ОСМАН", "osman")
        assertSame("osman", "ОсМаН")
    }

    @Test
    fun `tokenize drops emoji brackets and punctuation`() {
        assertEquals(listOf("мама"), NameTranslit.tokenize("Мама ❤️"))
        assertEquals(listOf("юсуф", "работа"), NameTranslit.tokenize("Юсуф (работа)"))
        assertEquals(listOf("osmana"), NameTranslit.tokenize("Osman'a"))
        assertEquals(emptyList<String>(), NameTranslit.tokenize("!!! 123 ???"))
        assertEquals(emptyList<String>(), NameTranslit.tokenize(""))
    }

    @Test
    fun `russian case endings reduce to the stem`() {
        listOf("Осману", "Османа", "Османом", "Османе").forEach {
            assertTrue(it, NameTranslit.stemKeys(it).any { k -> k in NameTranslit.keys("Osman") })
        }
        assertTrue(NameTranslit.stemKeys("маме").any { it == "mam" })
        assertTrue(NameTranslit.stemKeys("Алексею").any { it in NameTranslit.keys("Alexey") })
        assertTrue(NameTranslit.stemKeys("Osmanga").any { it in NameTranslit.keys("Osman") })
    }

    @Test
    fun `titles are recognised including case forms`() {
        listOf("доктор", "Dr", "Ака", "Hoca", "доктору").forEach { assertTrue(it, NameTranslit.isTitle(it.lowercase())) }
        assertFalse(NameTranslit.isTitle("осман"))
    }

    @Test
    fun `latin variants for a cyrillic name`() {
        val v = NameTranslit.latinVariants("Юсуф")
        assertTrue(v.toString(), "Yusuf" in v)
        assertTrue(v.toString(), "Iusuf" in v)
        assertTrue(v.toString(), "Jusuf" in v)
        assertEquals("Osman", NameTranslit.toLatinDisplay("Осман"))
    }

    @Test
    fun `cyrillic variants for a latin name`() {
        assertTrue("Осман" in NameTranslit.cyrillicVariants("Osman"))
        assertTrue("Юсуф" in NameTranslit.cyrillicVariants("Yusuf"))
        assertTrue("Алексей" in NameTranslit.cyrillicVariants("Alexey"))
        assertTrue("Хасан" in NameTranslit.cyrillicVariants("Hasan"))
        assertTrue("Мама" in NameTranslit.cyrillicVariants("Mama"))
        assertTrue("Джон" in NameTranslit.cyrillicVariants("John"))
    }
}
