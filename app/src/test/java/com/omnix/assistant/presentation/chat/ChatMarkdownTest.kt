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

    @Test
    fun `pipe table becomes a table block`() {
        val blocks = parseMarkdownBlocks(
            """
            Сравнение:
            | Тариф | Цена |
            |---|---|
            | Pro | 149 |
            | Max | 299 |
            """.trimIndent()
        )
        assertEquals(
            listOf(
                MdBlock.Paragraph("Сравнение:"),
                MdBlock.Table(listOf("Тариф", "Цена"), listOf(listOf("Pro", "149"), listOf("Max", "299")))
            ),
            blocks
        )
    }

    @Test
    fun `a line with a pipe but no separator is plain text`() {
        assertEquals(listOf(MdBlock.Paragraph("| просто | текст")), parseMarkdownBlocks("| просто | текст"))
    }

    @Test
    fun `chart block becomes bars`() {
        val blocks = parseMarkdownBlocks(
            "```chart\ntitle: Продажи\nЯнварь: 120\nФевраль: 1 500\nМарт: 45%\n```"
        )
        val chart = blocks.single() as MdBlock.Chart
        assertEquals("Продажи", chart.title)
        assertEquals(listOf("Январь", "Февраль", "Март"), chart.items.map { it.label })
        assertEquals(listOf(120.0, 1500.0, 45.0), chart.items.map { it.value })
        assertEquals("1 500", chart.items[1].valueText)
    }

    @Test
    fun `chart with fewer than two numbers falls back to code`() {
        val blocks = parseMarkdownBlocks("```chart\nтолько: 5\n```")
        assertEquals(listOf(MdBlock.Code("только: 5")), blocks)
    }

    @Test
    fun `other code blocks stay code`() {
        assertEquals(listOf(MdBlock.Code("a: 1\nb: 2")), parseMarkdownBlocks("```\na: 1\nb: 2\n```"))
    }

    @Test
    fun `chart numbers accept comma decimals and symbols`() {
        assertEquals(3.5, parseChartNumber("3,5 кг")!!, 0.0)
        assertEquals(null, parseChartNumber("много"))
    }
}
