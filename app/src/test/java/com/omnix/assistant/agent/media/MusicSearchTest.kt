package com.omnix.assistant.agent.media

import com.omnix.assistant.agent.decision.PrivacyClassifier
import com.omnix.assistant.agent.decision.PrivacyContent
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.fast.FastCommandRouter
import com.omnix.assistant.agent.fast.FastRouteResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSearchTest {

    private val router = FastCommandRouter()

    private fun routed(phrase: String): Pair<String?, String?> {
        val result = router.route(phrase)
        val handled = result as? FastRouteResult.HandledLocally ?: return null to null
        return handled.toolCall?.toolId to handled.toolCall?.arguments?.get("query")?.jsonPrimitive?.content
    }

    // ------------------------------------------------------- маршрутизация

    @Test
    fun `russian song phrases go to media play_search and not to web search`() {
        assertEquals("media.play_search" to "глаза не врут", routed("Найди песню глаза не врут"))
        assertEquals("media.play_search" to "глаза не врут", routed("включи песню Глаза не врут"))
        assertEquals("media.play_search" to "глаза не врут", routed("поставь песню глаза не врут пожалуйста"))
        assertEquals("media.play_search" to "ляпис трубецкой", routed("включи музыку ляпис трубецкой"))
        assertEquals("media.play_search" to "кино группа крови", routed("Джарвис, найди трек кино группа крови"))
        assertEquals("media.play_search" to "земфира", routed("хочу послушать песню земфира"))
    }

    @Test
    fun `bare include and put phrases are treated as a song when nothing else matches`() {
        assertEquals("media.play_search" to "моргенштерн", routed("включи моргенштерн"))
        assertEquals("media.play_search" to "глаза не врут", routed("поставь глаза не врут"))
    }

    @Test
    fun `english and other languages reach the same tool`() {
        assertEquals("media.play_search" to "yesterday", routed("play song yesterday"))
        assertEquals("media.play_search" to "bohemian rhapsody", routed("find the song bohemian rhapsody"))
        assertEquals("media.play_search" to "tarkan", routed("play tarkan"))
        assertEquals("media.play_search" to "sezen aksu", routed("şarkı çal sezen aksu"))
        assertEquals("media.play_search" to "tarkan", routed("tarkan şarkısını aç"))
        assertEquals("media.play_search" to "ýaňy gelen", routed("aýdym tap ýaňy gelen"))
        assertEquals("media.play_search" to "ýaňy gelen", routed("ýaňy gelen aýdymyny goý"))
        assertEquals("media.play_search" to "димаш", routed("ән қос димаш"))
        assertEquals("media.play_search" to "shahzoda", routed("qo'shiq qo'y shahzoda"))
        assertEquals("media.play_search" to "shahzoda", routed("shahzoda qo'shig'ini qo'y"))
    }

    @Test
    fun `player and device commands are not stolen by song search`() {
        assertEquals("media.control", routed("включи музыку").first)
        assertEquals("media.control", routed("поставь на паузу").first)
        assertEquals("media.control", routed("следующий трек").first)
        assertEquals("media.control", routed("включи следующий трек").first)
        assertEquals("device.volume", routed("сделай музыку громче").first)
        assertEquals("device.flashlight", routed("включи фонарик").first)
        assertEquals("device.brightness", routed("поставь яркость 50 процентов").first)
        assertTrue(router.route("включи режим экономии заряда") is FastRouteResult.ForwardToLlm)
        assertTrue(router.route("включи ночной режим") is FastRouteResult.ForwardToLlm)
        assertTrue(router.route("поставь будильник на 7 утра") !is FastRouteResult.HandledLocally ||
            routed("поставь будильник на 7 утра").first != "media.play_search")
    }

    @Test
    fun `song search voice answer names Yandex Music`() {
        val handled = router.route("найди песню глаза не врут") as FastRouteResult.HandledLocally
        assertEquals("Ищу «глаза не врут» в Яндекс Музыке.", handled.immediateVoiceResponse)
    }

    @Test
    fun `parser ignores requests without a title`() {
        assertNull(MusicRequestParser.parseExplicit("найди песню"))
        assertNull(MusicRequestParser.parseExplicit("включи музыку громче"))
        assertNull(MusicRequestParser.parseExplicit("погода в ашхабаде"))
        assertNull(MusicRequestParser.parseBare("включи"))
    }

    // ------------------------------------------------------------ план

    @Test
    fun `plan has only Yandex Music app then its web page and never YouTube`() {
        val steps = MusicSearchPlan.steps("глаза не врут")

        assertEquals(
            listOf(
                MusicSearchTarget.YANDEX_MUSIC_APP, MusicSearchTarget.YANDEX_MUSIC_APP,
                MusicSearchTarget.YANDEX_MUSIC_WEB
            ),
            steps.map { it.target }
        )
        assertEquals("yandexmusic://search?text=%D0%B3%D0%BB%D0%B0%D0%B7%D0%B0+%D0%BD%D0%B5+%D0%B2%D1%80%D1%83%D1%82", steps[0].uri)
        assertTrue(steps[1].uri!!.startsWith("https://music.yandex.ru/search?text="))
        assertEquals(MusicSearchPlan.PKG_YANDEX_MUSIC, steps[0].packageName)
        assertEquals(MusicSearchPlan.PKG_YANDEX_MUSIC, steps[1].packageName)
        assertNull(steps[2].packageName)
        assertTrue(steps[2].uri!!.startsWith("https://music.yandex.ru/search?text="))
        assertTrue(steps.none { (it.uri.orEmpty() + it.packageName.orEmpty()).contains("youtube", ignoreCase = true) })
    }

    @Test
    fun `runner stops at the first step the phone can open`() {
        val tried = mutableListOf<MusicSearchTarget>()
        val outcome = MusicSearchRunner.run("x y") { step ->
            tried += step.target
            step.target == MusicSearchTarget.YANDEX_MUSIC_WEB
        }

        assertEquals(MusicSearchTarget.YANDEX_MUSIC_WEB, (outcome as MusicSearchOutcome.Opened).target)
        assertEquals(
            listOf(
                MusicSearchTarget.YANDEX_MUSIC_APP, MusicSearchTarget.YANDEX_MUSIC_APP,
                MusicSearchTarget.YANDEX_MUSIC_WEB
            ),
            tried
        )
    }

    @Test
    fun `runner prefers Yandex Music when installed and survives launcher exceptions`() {
        val first = MusicSearchRunner.run("q") { true } as MusicSearchOutcome.Opened
        assertEquals(MusicSearchTarget.YANDEX_MUSIC_APP, first.target)

        val afterCrash = MusicSearchRunner.run("q") { step ->
            if (step.target == MusicSearchTarget.YANDEX_MUSIC_APP) throw IllegalStateException("boom")
            true
        }
        assertEquals(MusicSearchTarget.YANDEX_MUSIC_WEB, (afterCrash as MusicSearchOutcome.Opened).target)
    }

    @Test
    fun `runner reports nothing installed honestly`() {
        assertEquals(MusicSearchOutcome.NothingInstalled, MusicSearchRunner.run("q") { false })
    }

    @Test
    fun `sanitize trims collapses and limits the query`() {
        assertEquals("a b", MusicSearchPlan.sanitize("  a   b "))
        assertNull(MusicSearchPlan.sanitize("   "))
        assertNull(MusicSearchPlan.sanitize(null))
        assertEquals(200, MusicSearchPlan.sanitize("z".repeat(500))!!.length)
    }

    // ------------------------------------------------------ приватность

    @Test
    fun `song title passes the privacy filter but secrets are still blocked`() {
        fun level(query: String) = PrivacyClassifier.classifySafely(
            PrivacyContent(buildJsonObject { put("query", query) }.toString())
        ).level

        assertEquals(PrivacyLevel.NORMAL, level("глаза не врут"))
        assertEquals(PrivacyLevel.NORMAL, level("bohemian rhapsody"))
        assertNotNull(level("мой пароль: hunter2hunter2").takeIf { it != PrivacyLevel.NORMAL })
    }
}
