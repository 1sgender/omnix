package com.omnix.assistant.presentation.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {

    @Test
    fun `headings lists and paragraphs become separate blocks`() {
        val blocks = parseMarkdownBlocks(
            """
            ## План
            Первая строка
            вторая строка

            - молоко
            * хлеб
            1. утро
            2) вечер
            """.trimIndent()
        )
        assertEquals(
            listOf(
                MdBlock.Heading(2, "План"),
                MdBlock.Paragraph("Первая строка\nвторая строка"),
                MdBlock.Bullet("молоко"),
                MdBlock.Bullet("хлеб"),
                MdBlock.Numbered("1", "утро"),
                MdBlock.Numbered("2", "вечер")
            ),
            blocks
        )
    }

    @Test
    fun `code fences keep their lines untouched`() {
        val blocks = parseMarkdownBlocks("До\n```kotlin\nval x = 1\n# не заголовок\n```\nПосле")
        assertEquals(
            listOf(
                MdBlock.Paragraph("До"),
                MdBlock.Code("val x = 1\n# не заголовок"),
                MdBlock.Paragraph("После")
            ),
            blocks
        )
    }

    @Test
    fun `an unclosed code fence is still shown`() {
        assertEquals(listOf(MdBlock.Code("a\nb")), parseMarkdownBlocks("```\na\nb"))
    }

    @Test
    fun `plain text answers stay one paragraph`() {
        assertEquals(listOf(MdBlock.Paragraph("Готово, будильник на 07:00.")), parseMarkdownBlocks("Готово, будильник на 07:00."))
    }
}
