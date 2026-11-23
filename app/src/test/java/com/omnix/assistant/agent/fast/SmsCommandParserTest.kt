package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.fast.SmsCommandParser.SmsIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCommandParserTest {

    private fun parse(s: String) = SmsCommandParser.parse(s)

    @Test
    fun `russian with recipient and text`() {
        assertEquals(SmsIntent("Осману", "привет"), parse("отправь смс Осману привет"))
        assertEquals(SmsIntent("Осману", "Привет Мир"), parse("Отправь СМС Осману Привет Мир"))
        assertEquals(SmsIntent("Юсуфу", "приеду"), parse("пошли эсэмэс Юсуфу что приеду"))
        assertEquals(SmsIntent("маме", "буду через час"), parse("смс маме: буду через час"))
        assertEquals(SmsIntent("маме", "опоздаю"), parse("отправь смс маме, что опоздаю"))
        assertEquals(SmsIntent("маме", "что нового"), parse("смс маме: что нового"))
    }

    @Test
    fun `russian without text`() {
        assertEquals(SmsIntent("усма", null), parse("отправь смс усма"))
        assertEquals(SmsIntent("маме", null), parse("напиши сообщение маме"))
        assertEquals(SmsIntent("Осману", null), parse("Джарвис, отправь смс Осману"))
    }

    @Test
    fun `missing recipient`() {
        assertEquals(SmsIntent(null, null), parse("отправь смс"))
    }

    @Test
    fun `phone number recipient`() {
        assertEquals(SmsIntent("+7 999 123-45-67", "привет"), parse("отправь смс +7 999 123-45-67 привет"))
    }

    @Test
    fun `title is part of the recipient`() {
        assertEquals(SmsIntent("доктору Ахмедову", "приезжай"), parse("кинь смс доктору Ахмедову приезжай"))
    }

    @Test
    fun `english turkish turkmen uzbek kazakh`() {
        assertEquals(SmsIntent("Osman", "hello"), parse("text Osman hello"))
        assertEquals(SmsIntent("Osman", "hello"), parse("send sms to Osman hello"))
        assertEquals(SmsIntent("mom", "I'll be late"), parse("send a message to mom saying I'll be late"))
        assertEquals(SmsIntent("Osman", "hi"), parse("text message to Osman hi"))
        assertEquals(SmsIntent("Osmana", null), parse("sms ber Osmana"))
        assertEquals(SmsIntent("Osman'a", null), parse("Osman'a SMS gönder"))
        assertEquals(SmsIntent("Osman'a", null), parse("Osman'a mesaj at"))
        assertEquals(SmsIntent("Osmanga", null), parse("Osmanga sms yubor"))
        assertEquals(SmsIntent("Османға", null), parse("Османға sms жібер"))
    }

    @Test
    fun `other requests are not sms commands`() {
        listOf(
            "отправь сообщение",
            "отправь сообщение в телеграм Осману привет",
            "смс от мамы",
            "открой смс",
            "покажи сообщения",
            "позвони Осману",
            "что такое смс",
            ""
        ).forEach { assertNull(it, parse(it)) }
    }
}
