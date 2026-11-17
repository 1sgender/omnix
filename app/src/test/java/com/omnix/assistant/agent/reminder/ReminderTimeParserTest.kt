package com.omnix.assistant.agent.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeParserTest {

    private val zone: ZoneId = ZoneId.of("UTC")

    // Среда, 18 ноября 2026, 12:00.
    private val now: Long = ZonedDateTime.of(2026, 11, 18, 12, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    private fun ok(phrase: String): ReminderTimeParser.Result.Ok {
        val result = ReminderTimeParser.parse(phrase, now, zone)
        assertTrue("expected Ok for \"$phrase\" but was $result", result is ReminderTimeParser.Result.Ok)
        return result as ReminderTimeParser.Result.Ok
    }

    @Test
    fun `at a clock time later today`() {
        val r = ok("напомни в 18:00 позвонить маме")
        assertEquals(at(18, 18, 0), r.triggerAt)
        assertEquals("Позвонить маме", r.text)
    }

    @Test
    fun `a clock time that already passed moves to tomorrow`() {
        val r = ok("напомни в 9:30 принять таблетки")
        assertEquals(at(19, 9, 30), r.triggerAt)
        assertEquals("Принять таблетки", r.text)
    }

    @Test
    fun `in N minutes`() {
        val r = ok("напомни через 20 минут позвонить маме")
        assertEquals(now + 20 * 60_000L, r.triggerAt)
        assertEquals("Позвонить маме", r.text)
    }

    @Test
    fun `in an hour and in half an hour`() {
        assertEquals(now + 3_600_000L, ok("напомни через час выключить плиту").triggerAt)
        assertEquals(now + 1_800_000L, ok("напомни через полчаса выключить плиту").triggerAt)
        assertEquals(now + 2 * 3_600_000L, ok("через 2 часа проверить почту").triggerAt)
    }

    @Test
    fun `tomorrow at ten`() {
        val r = ok("напомни завтра в 10 купить хлеб")
        assertEquals(at(19, 10, 0), r.triggerAt)
        assertEquals("Купить хлеб", r.text)
    }

    @Test
    fun `the day after tomorrow with minutes`() {
        val r = ok("напомни послезавтра в 8:15 встреча с врачом")
        assertEquals(at(20, 8, 15), r.triggerAt)
        assertEquals("Встреча с врачом", r.text)
    }

    @Test
    fun `evening hour is read as pm`() {
        assertEquals(at(18, 18, 0), ok("напомни в 6 вечера выпить воды").triggerAt)
    }

    @Test
    fun `the text may come before the time`() {
        val r = ok("напомни позвонить маме в 18:00")
        assertEquals(at(18, 18, 0), r.triggerAt)
        assertEquals("Позвонить маме", r.text)
    }

    @Test
    fun `english`() {
        val pm = ok("remind me to call mom at 6 pm")
        assertEquals(at(18, 18, 0), pm.triggerAt)
        assertEquals("Call mom", pm.text)
        val relative = ok("remind me in 2 hours to take a break")
        assertEquals(now + 2 * 3_600_000L, relative.triggerAt)
        assertEquals("Take a break", relative.text)
        assertEquals(at(19, 7, 0), ok("remind me tomorrow at 7:00 to run").triggerAt)
    }

    @Test
    fun `turkish`() {
        val r = ok("yarın saat 10'da anneyi aramayı hatırlat")
        assertEquals(at(19, 10, 0), r.triggerAt)
        assertEquals("Anneyi aramayı", r.text)
        assertEquals(now + 20 * 60_000L, ok("20 dakika sonra çamaşırı çıkar hatırlat").triggerAt)
    }

    @Test
    fun `uzbek`() {
        val r = ok("ertaga soat 18:00 da onamga qo'ng'iroq qilishni eslat")
        assertEquals(at(19, 18, 0), r.triggerAt)
        assertEquals("Onamga qo'ng'iroq qilishni", r.text)
        assertEquals(now + 30 * 60_000L, ok("30 daqiqadan keyin choy qo'yishni eslat").triggerAt)
    }

    @Test
    fun `kazakh`() {
        val r = ok("ертең сағат 18:00-де анама қоңырау шалуды еске сал")
        assertEquals(at(19, 18, 0), r.triggerAt)
        assertEquals("Анама қоңырау шалуды", r.text)
        assertEquals(now + 20 * 60_000L, ok("20 минуттан кейін шәйді өшіруді еске сал").triggerAt)
    }

    @Test
    fun `turkmen`() {
        val r = ok("ertir sagat 18:00-da ejeme jaň etmegi ýatla")
        assertEquals(at(19, 18, 0), r.triggerAt)
        assertEquals("Ejeme jaň etmegi", r.text)
        assertEquals(now + 20 * 60_000L, ok("20 minutdan çaý goýmagy ýatla").triggerAt)
    }

    @Test
    fun `no time asks when`() {
        assertEquals(ReminderTimeParser.Result.NoTime, ReminderTimeParser.parse("напомни позвонить маме", now, zone))
        assertEquals(ReminderTimeParser.Result.NoTime, ReminderTimeParser.parse("напомни завтра купить хлеб", now, zone))
    }

    @Test
    fun `no text asks what`() {
        assertEquals(ReminderTimeParser.Result.NoText, ReminderTimeParser.parse("напомни в 18:00", now, zone))
        assertEquals(ReminderTimeParser.Result.NoText, ReminderTimeParser.parse("напомни через 20 минут", now, zone))
    }

    @Test
    fun `an impossible clock time is not a time`() {
        assertEquals(ReminderTimeParser.Result.NoTime, ReminderTimeParser.parse("напомни в 25:00 уснуть", now, zone))
    }

    @Test
    fun `a leading preposition of the text is kept`() {
        assertEquals("В магазин зайти", ok("напомни в 18:00 в магазин зайти").text)
    }

    @Test
    fun `reminder requests are told from other talk`() {
        assertTrue(ReminderTimeParser.isReminderRequest("Напомни в 18:00 позвонить маме"))
        assertTrue(ReminderTimeParser.isReminderRequest("поставь напоминание на 18:00"))
        assertTrue(ReminderTimeParser.isReminderRequest("remind me at 6 pm"))
        assertTrue(ReminderTimeParser.isReminderRequest("saat 6'da anneyi hatırlat"))
        assertFalse(ReminderTimeParser.isReminderRequest("какие у меня напоминания"))
        assertFalse(ReminderTimeParser.isReminderRequest("позвони маме"))
        assertFalse(ReminderTimeParser.isReminderRequest("поставь будильник на семь"))
    }

    @Test
    fun `next occurrence rolls to tomorrow when the time passed`() {
        assertEquals(at(18, 13, 0), ReminderTimeParser.nextOccurrence(13, 0, now, zone))
        assertEquals(at(19, 11, 0), ReminderTimeParser.nextOccurrence(11, 0, now, zone))
        assertEquals(at(19, 13, 0), ReminderTimeParser.nextOccurrence(13, 0, now, zone, dayOffset = 1))
    }
}
