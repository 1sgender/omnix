package com.omnix.assistant.agent.reminder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class ReminderTimeFormatTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2026, 11, 18, 23, 30, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(month: Int, day: Int, hour: Int, minute: Int) =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun `day label follows the calendar day and not the 24 hour distance`() {
        assertEquals(ReminderDay.TODAY, ReminderTimeFormat.dayOf(at(11, 18, 23, 59), now, zone))
        assertEquals(ReminderDay.TOMORROW, ReminderTimeFormat.dayOf(at(11, 19, 0, 5), now, zone))
        assertEquals(ReminderDay.OTHER, ReminderTimeFormat.dayOf(at(11, 20, 9, 0), now, zone))
    }

    @Test
    fun `clock and date are zero padded`() {
        assertEquals("09:05", ReminderTimeFormat.clock(at(12, 5, 9, 5), zone))
        assertEquals("05.12", ReminderTimeFormat.date(at(12, 5, 9, 5), zone))
    }

    @Test
    fun `chat card message round trips the reminder id`() {
        assertEquals(42L, ReminderMessage.decode(ReminderMessage.encode(42)))
        assertNull(ReminderMessage.decode("Напомню сегодня 18:00"))
        assertNull(ReminderMessage.decode("reminder:abc"))
    }
}
