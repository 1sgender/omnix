package com.omnix.assistant.presentation.activation

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Чистая логика ячеек кода (мок «OMNIX — активация», 2026-09-25):
 * санитайзка и переходы фокуса покрываются JVM-тестами без эмулятора.
 */
class ActivationCodeCellsTest {

    @Test
    fun `sanitizer keeps letters and digits, uppercases, caps at six cells`() {
        assertEquals("ABCDEF", sanitizeActivationInput(" ab_cd!ef "))
        assertEquals("123456", sanitizeActivationInput("123456"))
        assertEquals("123456", sanitizeActivationInput("1234567890"))
        assertEquals("4X2", sanitizeActivationInput("4x2"))
        assertEquals("A7B2C3", sanitizeActivationInput("a7b2c3"))
    }

    @Test
    fun `typing a digit into the next empty cell appends and advances`() {
        val (code, focus) = cellInputResult(code = "472", index = 3, raw = "9")
        assertEquals("4729", code)
        assertEquals(4, focus)
    }

    @Test
    fun `typing over a filled cell replaces the digit`() {
        val (code, focus) = cellInputResult(code = "472915", index = 2, raw = "12")
        // raw = «2» (старый символ) + «1» (новый): заменяем вторую позицию.
        assertEquals("471915", code)
        assertEquals(3, focus)
    }

    @Test
    fun `pasting the whole code fills every cell`() {
        val (code, focus) = cellInputResult(code = "", index = 0, raw = "472915")
        assertEquals("472915", code)
        assertEquals(5, focus)
    }

    @Test
    fun `deleting in a filled cell removes the digit and keeps focus`() {
        val (code, focus) = cellInputResult(code = "472915", index = 4, raw = "")
        assertEquals("47295", code)
        assertEquals(4, focus)
    }

    @Test
    fun `backspace in an empty cell moves focus back without changing the code`() {
        val (code, focus) = cellInputResult(code = "472", index = 5, raw = "")
        assertEquals("472", code)
        assertEquals(4, focus)
    }

    @Test
    fun `non-digit input is ignored`() {
        val (code, focus) = cellInputResult(code = "47", index = 2, raw = "x")
        assertEquals("47", code)
        assertEquals(2, focus)
    }
}
