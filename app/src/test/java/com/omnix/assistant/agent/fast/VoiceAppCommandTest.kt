package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.model.ToolCall
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAppCommandTest {

    private val router = FastCommandRouter()

    private fun routedApp(phrase: String): String? {
        val result = router.route(phrase) as? FastRouteResult.HandledLocally ?: return null
        val call: ToolCall = result.toolCall ?: return null
        if (call.toolId != "device.open_app") return null
        return call.arguments["app_name"]?.jsonPrimitive?.content
    }

    @Test
    fun `all spoken spellings of youtube are recognised`() {
        listOf("ютуб", "ютюб", "ютьюб", "ю туб", "you tube", "YouTube", "ютубе", "ютуб приложение").forEach {
            assertTrue(it, VoiceAppCommand.mentionsYoutube(it))
        }
        assertFalse(VoiceAppCommand.mentionsYoutube("открой камеру"))
    }

    @Test
    fun `open youtube is routed for every recognition variant`() {
        listOf(
            "открой ютуб", "Открой ютуб.", "открой YouTube", "открой ютюб", "открой ютьюб",
            "открой you tube", "открой ю туб", "открой ютуб приложение", "запусти ютуб",
            "открыть ютуб", "ну открой ютуб", "пожалуйста, открой ютуб",
            "зайди в ютуб", "от крой ютуб"
        ).forEach { assertEquals(it, "youtube", routedApp(it)) }
    }

    @Test
    fun `search request is not a plain app launch`() {
        assertEquals(null, routedApp("открой ютуб и найди котиков"))
    }

    @Test
    fun `launch verb detection skips leading fillers`() {
        assertTrue(VoiceAppCommand.hasLaunchVerb("ну, открой телеграм"))
        assertTrue(VoiceAppCommand.hasLaunchVerb("зайди в ютуб"))
        assertFalse(VoiceAppCommand.hasLaunchVerb("позвони маме"))
        assertFalse(VoiceAppCommand.hasLaunchVerb(""))
    }
}
