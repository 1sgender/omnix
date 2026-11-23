package com.omnix.assistant.agent.reminder

import com.omnix.assistant.agent.reminder.ReminderVoiceCommand.Command
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReminderVoiceCommandTest {

    private fun parse(text: String) = ReminderVoiceCommand.parse(text)

    private fun assertDone(vararg phrases: String) {
        for (p in phrases) assertEquals("«$p»", Command.Done, parse(p))
    }

    private fun assertSnooze(minutes: Int, vararg phrases: String) {
        for (p in phrases) assertEquals("«$p»", Command.Snooze(minutes), parse(p))
    }

    private fun assertNone(vararg phrases: String) {
        for (p in phrases) assertNull("«$p»", parse(p))
    }

    @Test
    fun `done in russian`() = assertDone(
        "Готово", "готово!", "Сделано", "я сделал", "Напоминание выполнено", "отметь как выполненное",
        "Омни, готово", "всё сделано"
    )

    @Test
    fun `done in english`() = assertDone("Done", "it's done", "I'm done", "mark as done", "mark it as done", "all done")

    @Test
    fun `done in turkmen turkish uzbek kazakh`() = assertDone(
        "Taýýar", "edildi", "gutardym",
        "tamamlandı", "yapıldı", "bitti",
        "bajarildi", "Tayyor", "tugadi",
        "дайын", "орындалды", "жасадым"
    )

    @Test
    fun `snooze defaults to ten minutes`() = assertSnooze(
        10, "отложи", "Отложи напоминание", "snooze", "snooze it", "remind me later", "напомни позже",
        "ertele", "keyinga qoldir", "кейінге қалдыр", "yza goý"
    )

    @Test
    fun `snooze with minutes in every language`() {
        assertSnooze(10, "отложи на 10 минут", "snooze for 10 minutes", "10 dakika ertele", "10 minut yza goý",
            "10 daqiqaga keyinga qoldir", "10 минутқа кейінге қалдыр")
        assertSnooze(5, "отложи на 5 минут", "отложи на пять минут", "snooze 5 min", "5 dakika ertele")
        assertSnooze(1, "отложи на минуту")
        assertSnooze(15, "отложи напоминание на 15 минут пожалуйста", "postpone by 15 minutes")
    }

    @Test
    fun `snooze with hours`() {
        assertSnooze(60, "отложи на час", "snooze for an hour", "bir saat ertele", "bir sagat yza goý",
            "bir soatga keyinga qoldir", "бір сағатқа кейінге қалдыр", "отложи на 1 час")
        assertSnooze(120, "отложи на 2 часа", "snooze for 2 hours")
        assertSnooze(30, "отложи на полчаса", "snooze for half an hour", "yarım saat ertele")
    }

    @Test
    fun `absurd durations are refused`() = assertNone("отложи на 5000 минут", "snooze for 100 hours")

    @Test
    fun `other phrases are not replies`() = assertNone(
        "", "привет",
        "отложи звонок маме",
        "готово, поехали",
        "когда будет готово",
        "позвони маме",
        "snooze the alarm of my neighbour",
        "see you later",
        "напомни через 10 минут позвонить маме",
        "напомни в 18:00 позвонить маме",
        "remind me in 20 minutes to call mom",
        "что такое готово",
        "done with the report"
    )
}
