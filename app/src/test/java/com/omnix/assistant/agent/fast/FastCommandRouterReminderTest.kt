package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.reminder.ReminderVoiceCommand
import com.omnix.assistant.agent.tools.productivity.ReminderTool
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastCommandRouterReminderTest {

    private val router = FastCommandRouter()

    private fun toolIdOf(phrase: String): String? =
        (router.route(phrase) as? FastRouteResult.HandledLocally)?.toolCall?.toolId

    @Test
    fun `remind me goes to the reminder tool with the whole phrase`() {
        val routed = router.route("Напомни в 18:00 позвонить маме")

        assertTrue(routed is FastRouteResult.HandledLocally)
        val call = (routed as FastRouteResult.HandledLocally).toolCall
        assertEquals(ReminderTool.TOOL_ID, call?.toolId)
        assertEquals("напомни в 18:00 позвонить маме", call?.arguments?.get("phrase")?.jsonPrimitive?.content)
    }

    @Test
    fun `other languages reach the reminder tool too`() {
        assertEquals(ReminderTool.TOOL_ID, toolIdOf("remind me in 20 minutes to call mom"))
        assertEquals(ReminderTool.TOOL_ID, toolIdOf("yarın saat 10'da anneyi aramayı hatırlat"))
        assertEquals(ReminderTool.TOOL_ID, toolIdOf("ertaga soat 18:00 da onamga qo'ng'iroq qilishni eslat"))
    }

    @Test
    fun `a question about reminders is not a request`() {
        assertNotEquals(ReminderTool.TOOL_ID, toolIdOf("какие у меня напоминания"))
        assertNotEquals(ReminderTool.TOOL_ID, toolIdOf("позвони маме"))
    }

    private fun argsOf(phrase: String) =
        (router.route(phrase) as FastRouteResult.HandledLocally).toolCall!!.arguments

    @Test
    fun `done and snooze go to the reply tool`() {
        assertEquals(ReminderVoiceCommand.TOOL_ID, toolIdOf("Готово"))
        assertEquals(ReminderVoiceCommand.TOOL_ID, toolIdOf("done"))
        assertEquals(ReminderVoiceCommand.TOOL_ID, toolIdOf("отложи на час"))
        assertEquals(ReminderVoiceCommand.TOOL_ID, toolIdOf("омни, snooze"))

        assertEquals("done", argsOf("готово")[ReminderVoiceCommand.ARG_ACTION]?.jsonPrimitive?.content)
        val snooze = argsOf("отложи на 10 минут")
        assertEquals("snooze", snooze[ReminderVoiceCommand.ARG_ACTION]?.jsonPrimitive?.content)
        assertEquals(10, snooze[ReminderVoiceCommand.ARG_MINUTES]?.jsonPrimitive?.content?.toInt())
        assertEquals(60, argsOf("отложи на час")[ReminderVoiceCommand.ARG_MINUTES]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `a new reminder is not taken for a reply`() {
        assertEquals(ReminderTool.TOOL_ID, toolIdOf("напомни через 10 минут позвонить маме"))
        assertNotEquals(ReminderVoiceCommand.TOOL_ID, toolIdOf("отложи звонок маме"))
    }
}
