package com.omnix.assistant.domain.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextBudgetTest {

    @Test
    fun `short text is kept whole`() {
        val fitted = TextBudget.fit("коротко", 100)
        assertEquals("коротко", fitted.text)
        assertFalse(fitted.truncated)
    }

    @Test
    fun `long text is cut at a line break and marked`() {
        val text = (1..1000).joinToString("\n") { "line number $it" }
        val fitted = TextBudget.fit(text, 100)
        assertTrue(fitted.truncated)
        assertTrue(fitted.text.contains("[truncated: showing about"))
        assertTrue(fitted.text.length < 100 * TextBudget.CHARS_PER_TOKEN + 100)
        // Обрезано по границе строки: последняя строка целая.
        val body = fitted.text.substringBefore("\n\n[truncated")
        assertTrue(body.lines().last().matches(Regex("line number \\d+")))
    }

    @Test
    fun `token estimate rounds up`() {
        assertEquals(1, TextBudget.estimateTokens("ab"))
        assertEquals(2, TextBudget.estimateTokens("abcd"))
    }
}
