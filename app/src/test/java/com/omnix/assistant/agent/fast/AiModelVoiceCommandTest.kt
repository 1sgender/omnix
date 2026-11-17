package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.decision.AiModelChoice
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Голосовая смена модели: «переключись на Pro / на Flash», «ответь умнее». */
class AiModelVoiceCommandTest {

    @Test
    fun `switch to pro in several spellings`() {
        listOf(
            "переключись на Pro", "переключись на про", "Переключи на Pro.", "перейди на про модель",
            "смени модель на pro", "омни, переключись на Pro", "switch to pro"
        ).forEach { assertEquals(it, AiModelChoice.PRO, AiModelVoiceCommand.parse(it)) }
    }

    @Test
    fun `switch to flash in several spellings`() {
        listOf(
            "переключись на Flash", "переключись на флеш", "перейди на флэш", "switch to flash",
            "Flaşa geç"
        ).forEach { assertEquals(it, AiModelChoice.CLOUD, AiModelVoiceCommand.parse(it)) }
    }

    @Test
    fun `answer smarter means pro`() {
        assertEquals(AiModelChoice.PRO, AiModelVoiceCommand.parse("ответь умнее"))
        assertEquals(AiModelChoice.PRO, AiModelVoiceCommand.parse("Ответь поумнее!"))
    }

    @Test
    fun `ordinary phrases are not model commands`() {
        listOf(
            "расскажи про Flash память", "включи фонарик", "что умнее Pro или Flash", "переключись на музыку",
            "ответь на сообщение", "", "расскажи про погоду"
        ).forEach { assertNull(it, AiModelVoiceCommand.parse(it)) }
    }

    @Test
    fun `router turns the phrase into the model tool call`() {
        val result = FastCommandRouter().route("переключись на Pro")
        assertTrue(result is FastRouteResult.HandledLocally)
        val call = (result as FastRouteResult.HandledLocally).toolCall!!
        assertEquals(AiModelVoiceCommand.TOOL_ID, call.toolId)
        assertEquals("pro", call.arguments["choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `router sends flash phrase to cloud choice and keeps flashlight`() {
        val flash = FastCommandRouter().route("перейди на Flash") as FastRouteResult.HandledLocally
        assertEquals("cloud", flash.toolCall!!.arguments["choice"]?.jsonPrimitive?.content)
        val light = FastCommandRouter().route("включи фонарик") as FastRouteResult.HandledLocally
        assertEquals("device.flashlight", light.toolCall?.toolId)
    }
}
