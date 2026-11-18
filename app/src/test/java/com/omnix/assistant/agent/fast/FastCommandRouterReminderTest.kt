package com.omnix.assistant.agent.fast

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
}
