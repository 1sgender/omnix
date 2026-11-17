package com.omnix.assistant.agent.reminder

import android.content.Context
import com.omnix.assistant.R
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Какой это день относительно «сейчас»: от этого зависит подпись «Сегодня / Завтра / дата». */
enum class ReminderDay { TODAY, TOMORROW, OTHER }

/** Подписи времени без Android: «18:00», «05.12». */
object ReminderTimeFormat {

    fun dayOf(triggerAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): ReminderDay {
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val day = Instant.ofEpochMilli(triggerAt).atZone(zone).toLocalDate()
        return when (day) {
            today -> ReminderDay.TODAY
            today.plusDays(1) -> ReminderDay.TOMORROW
            else -> ReminderDay.OTHER
        }
    }

    fun clock(triggerAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochMilli(triggerAt).atZone(zone)
        return String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)
    }

    fun date(triggerAt: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(triggerAt).atZone(zone)
        return String.format(Locale.ROOT, "%02d.%02d", day.dayOfMonth, day.monthValue)
    }
}

/** Подписи напоминания на языке приложения: «сегодня 18:00», «завтра 10:00», «05.12 09:30». */
object ReminderLabels {

    fun whenText(context: Context, triggerAt: Long, now: Long = System.currentTimeMillis()): String {
        val clock = ReminderTimeFormat.clock(triggerAt)
        return when (ReminderTimeFormat.dayOf(triggerAt, now)) {
            ReminderDay.TODAY -> context.getString(R.string.omnix_reminder_today) + " " + clock
            ReminderDay.TOMORROW -> context.getString(R.string.omnix_reminder_tomorrow) + " " + clock
            ReminderDay.OTHER -> ReminderTimeFormat.date(triggerAt) + " " + clock
        }
    }
}

/**
 * Карточка напоминания в ленте чата хранится обычным сообщением с этим
 * текстом: сама запись живёт в таблице напоминаний, лента лишь ссылается на неё.
 */
object ReminderMessage {
    private const val PREFIX = "reminder:"

    fun encode(reminderId: Long): String = PREFIX + reminderId

    fun decode(text: String): Long? =
        if (text.startsWith(PREFIX)) text.removePrefix(PREFIX).toLongOrNull() else null
}
