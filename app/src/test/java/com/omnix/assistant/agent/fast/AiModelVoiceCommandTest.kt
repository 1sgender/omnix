package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.decision.AiModelChoice
import com.omnix.assistant.agent.fast.AiModelVoiceCommand.Command
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Голосовая смена модели на шести языках: «переключись на Pro», «на облако», «какая сейчас модель?». */
class AiModelVoiceCommandTest {

    private fun assertSwitch(choice: AiModelChoice, phrases: List<String>) {
        phrases.forEach { assertEquals(it, Command.Switch(choice), AiModelVoiceCommand.parse(it)) }
    }

    @Test
    fun `model noun right after the preposition`() {
        // Не распознавалось до 2026-11-27: после «на» ожидалось сразу имя модели.
        assertSwitch(AiModelChoice.PRO, listOf("переключись на модель Pro", "перейди на про-модель"))
        assertSwitch(AiModelChoice.CLOUD, listOf("переключись на модель флеш", "переключи на flash-модель"))
        assertSwitch(AiModelChoice.ON_DEVICE, listOf("переключись на модель локальную"))
        assertNull(AiModelVoiceCommand.parse("переключись на модель"))
        assertNull(AiModelVoiceCommand.parse("переключи на режим авто"))
    }

    @Test
    fun `switch to pro in several languages`() {
        assertSwitch(
            AiModelChoice.PRO,
            listOf(
            "переключись на Pro",
            "переключись на про",
            "Переключи на Pro.",
            "перейди на про модель",
            "смени модель на pro",
            "омни, переключись на Pro",
            "switch to pro",
            "ответь умнее",
            "Ответь поумнее!",
            "switch the model to pro",
            "use the pro model",
            "включи про модель",
            "Pro-a geç",
            "Pro'ya geç",
            "pro modeline geç",
            "Pro ga o'tkaz",
            "Pro ga o'ting",
            "Pro-ға ауыстыр",
            "answer smarter"
            )
        )
    }

    @Test
    fun `switch to flash or cloud in several languages`() {
        assertSwitch(
            AiModelChoice.CLOUD,
            listOf(
            "переключись на Flash",
            "переключись на флеш",
            "перейди на флэш",
            "switch to flash",
            "Flaşa geç",
            "переключи на облако",
            "перейди в облако",
            "переключись на облачную модель",
            "включи облачную модель",
            "switch to cloud",
            "use the flash model",
            "switch to the cloud model",
            "buluta geç",
            "bulut modeline geç",
            "Flash'a geç",
            "flashga o'tkaz",
            "бұлтқа ауыстыр",
            "Flash-қа ауыстыр",
            "флешке ауыстыр",
            "bulut-a geç"
            )
        )
    }

    @Test
    fun `switch to local in several languages`() {
        assertSwitch(
            AiModelChoice.ON_DEVICE,
            listOf(
            "переключи на локальную",
            "переключись на локальную модель",
            "выбери локальную модель",
            "switch to local",
            "switch to on device",
            "use the offline model",
            "перейди на офлайн модель",
            "switch the model to offline",
            "yerel modele geç",
            "lokala geç",
            "lokal modelga o'ting",
            "жергілікті модельге ауыстыр"
            )
        )
    }

    @Test
    fun `switch to auto in several languages`() {
        assertSwitch(
            AiModelChoice.AUTO,
            listOf(
            "переключи на авто",
            "переключись на автоматическую",
            "включи автоматический выбор модели",
            "верни автовыбор",
            "switch to auto",
            "enable automatic model selection",
            "use the auto model",
            "смени модель на авто",
            "set the model to auto",
            "otomatiğe geç",
            "otomatik moda geç",
            "awtomatik režime geç",
            "avtomatik rejimga o'ting",
            "автоматты режимге ауыстыр"
            )
        )
    }

    @Test
    fun `current model questions`() {
        listOf(
            "какая сейчас модель",
            "какая модель сейчас",
            "какой ии отвечает",
            "Омни, какая сейчас модель?",
            "кто сейчас отвечает",
            "what model are you using",
            "which model is active",
            "what's the current model",
            "häzir haýsy model",
            "haýsy model işleýär",
            "şu an hangi model",
            "hangi model açık",
            "hozir qaysi model",
            "қазір қандай модель",
            "қандай модель қосулы"
        ).forEach { assertEquals(it, Command.Ask, AiModelVoiceCommand.parse(it)) }
    }

    @Test
    fun `ordinary phrases are not model commands`() {
        listOf(
            "расскажи про Flash память",
            "включи фонарик",
            "включи флеш",
            "включи вспышку",
            "что умнее Pro или Flash",
            "переключись на музыку",
            "переключи на следующий трек",
            "ответь на сообщение",
            "расскажи про погоду",
            "открой ютуб",
            "открой youtube",
            "включи музыку",
            "какая модель у моего телефона",
            "какая у меня модель телефона",
            "какая погода сейчас",
            "switch to the next song",
            "turn on flash",
            "turn on the flashlight",
            "open youtube",
            "what model is my phone",
            "what is the weather",
            "youtube geç",
            "включи автоматический режим",
            "смени режим на авто",
            "перейди в офлайн",
            "переключись в офлайн",
            "set to auto",
            "go to offline",
            "set to local",
            "go to local",
            "поставь режим авто",
            "позвони маме",
            "проверь баланс"
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
    fun `router sends cloud and local phrases to their choices`() {
        val flash = FastCommandRouter().route("перейди на Flash") as FastRouteResult.HandledLocally
        assertEquals("cloud", flash.toolCall!!.arguments["choice"]?.jsonPrimitive?.content)
        val cloud = FastCommandRouter().route("переключи на облако") as FastRouteResult.HandledLocally
        assertEquals("cloud", cloud.toolCall!!.arguments["choice"]?.jsonPrimitive?.content)
        val local = FastCommandRouter().route("переключись на локальную") as FastRouteResult.HandledLocally
        assertEquals("on_device", local.toolCall!!.arguments["choice"]?.jsonPrimitive?.content)
        val auto = FastCommandRouter().route("включи автоматический выбор модели") as FastRouteResult.HandledLocally
        assertEquals("auto", auto.toolCall!!.arguments["choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `router answers which model is on with the current marker`() {
        val ask = FastCommandRouter().route("какая сейчас модель?") as FastRouteResult.HandledLocally
        assertEquals(AiModelVoiceCommand.TOOL_ID, ask.toolCall!!.toolId)
        assertEquals(AiModelVoiceCommand.ARG_CURRENT, ask.toolCall!!.arguments["choice"]?.jsonPrimitive?.content)
    }

    @Test
    fun `router keeps flashlight and youtube commands`() {
        val light = FastCommandRouter().route("включи фонарик") as FastRouteResult.HandledLocally
        assertEquals("device.flashlight", light.toolCall?.toolId)
        val youtube = FastCommandRouter().route("открой ютуб")
        val toolId = (youtube as? FastRouteResult.HandledLocally)?.toolCall?.toolId
        assertTrue(toolId != AiModelVoiceCommand.TOOL_ID)
    }
}
