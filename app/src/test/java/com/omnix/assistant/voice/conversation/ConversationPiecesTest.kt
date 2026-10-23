package com.omnix.assistant.voice.conversation

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class ConversationPiecesTest {

    // ---------------------------------------------------------------- WAV

    @Test
    fun `wav round trip keeps samples and rate`() {
        val samples = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 1234)
        val decoded = PcmWav.decode(PcmWav.encode(samples, 16_000))!!
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(1, decoded.channels)
        assertArrayEquals(samples, decoded.samples)
    }

    @Test
    fun `decode skips unknown chunks before data`() {
        val plain = PcmWav.encode(shortArrayOf(5, 6, 7), 22_050)
        // fmt (12..36) + LIST-чанк + data
        val list = "LIST".toByteArray() + byteArrayOf(4, 0, 0, 0) + "abcd".toByteArray()
        val withList = plain.copyOfRange(0, 36) + list + plain.copyOfRange(36, plain.size)
        val decoded = PcmWav.decode(withList)!!
        assertEquals(22_050, decoded.sampleRate)
        assertArrayEquals(shortArrayOf(5, 6, 7), decoded.samples)
    }

    @Test
    fun `not a wav decodes to null`() {
        assertNull(PcmWav.decode(ByteArray(100)))
    }

    @Test
    fun `single ear puts sound only in that channel`() {
        val left = PcmWav.toSingleEar(shortArrayOf(10, 20), Ear.LEFT)
        assertArrayEquals(shortArrayOf(10, 0, 20, 0), left)
        val right = PcmWav.toSingleEar(shortArrayOf(10, 20), Ear.RIGHT)
        assertArrayEquals(shortArrayOf(0, 10, 0, 20), right)
        assertEquals(Ear.RIGHT, Ear.LEFT.other)
        assertArrayEquals(shortArrayOf(10, 10, 20, 20), PcmWav.toBothEars(shortArrayOf(10, 20)))
    }

    @Test
    fun `stereo synthesis is mixed down to mono`() {
        assertArrayEquals(shortArrayOf(15, -5), PcmWav.toMono(shortArrayOf(10, 20, 0, -10), 2))
    }

    // ---------------------------------------------------------- segmenter

    private fun frames(ms: Int, amplitude: Int): List<ShortArray> = List(ms / 20) {
        ShortArray(320) { i -> (amplitude * sin(2 * PI * 440 * i / 16_000.0)).toInt().toShort() }
    }

    private fun feed(segmenter: UtteranceSegmenter, frames: List<ShortArray>): List<ShortArray> =
        frames.mapNotNull { segmenter.accept(it) }

    @Test
    fun `speech between pauses becomes one utterance`() {
        val s = UtteranceSegmenter()
        val out = feed(s, frames(1000, 20) + frames(800, 6000) + frames(900, 20))
        assertEquals(1, out.size)
        // Речь 0.8 с + preroll до 0.3 с + 0.2 с хвоста.
        val seconds = out.single().size / 16_000.0
        assertTrue("utterance length $seconds", seconds in 0.9..1.4)
    }

    @Test
    fun `a click shorter than min speech is dropped`() {
        val s = UtteranceSegmenter()
        val out = feed(s, frames(1000, 20) + frames(100, 6000) + frames(900, 20))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `endless talk is cut at the max length`() {
        val s = UtteranceSegmenter(maxUtteranceMs = 2_000)
        val out = feed(s, frames(500, 20) + frames(5_000, 6000))
        assertTrue(out.size >= 2)
        assertTrue(out.all { it.size <= 16_000 * 2 + 320 * 20 })
    }

    @Test
    fun `steady hum is not speech but a voice over it is`() {
        val s = UtteranceSegmenter()
        assertTrue("hum alone", feed(s, frames(3_000, 1500)).isEmpty())
        val out = feed(s, frames(800, 6000) + frames(900, 1500))
        assertEquals(1, out.size)
    }

    // ------------------------------------------------------------ speakers

    @Test
    fun `my language goes to them, theirs comes to me`() {
        val r = SpeakerResolver(mine = "ru", theirs = "en")
        assertEquals(SpeakerResolver.Resolution(Speaker.ME, "ru", "en"), r.resolve("ru"))
        assertEquals(SpeakerResolver.Resolution(Speaker.THEM, "en", "ru"), r.resolve("en"))
        assertNull("a third language is ignored", r.resolve("de"))
        assertNull(r.resolve(null))
    }

    @Test
    fun `their language is learned from their first phrase`() {
        val r = SpeakerResolver(mine = "tk")
        assertTrue(r.waitingForThem)
        assertNull("my phrase waits until their language is known", r.resolve("tk"))
        assertEquals(SpeakerResolver.Resolution(Speaker.THEM, "en", "tk"), r.resolve("en"))
        assertEquals("en", r.theirs)
        assertNotNull(r.resolve("tk"))
    }

    @Test
    fun `turkmen heard as turkish still counts as turkmen`() {
        val r = SpeakerResolver(mine = "tk", theirs = "en")
        assertEquals(SpeakerResolver.Resolution(Speaker.ME, "tk", "en"), r.resolve("tr"))
        assertEquals(SpeakerResolver.Resolution(Speaker.ME, "tk", "en"), r.resolve("az"))
        // Пока язык собеседника неизвестен, близкий к моему язык не становится его языком.
        val fresh = SpeakerResolver(mine = "tk")
        assertNull(fresh.resolve("tr"))
        assertNull(fresh.theirs)
    }

    @Test
    fun `exact match wins inside one family`() {
        val r = SpeakerResolver(mine = "tk", theirs = "tr")
        assertEquals(Speaker.ME, r.resolve("tk")!!.speaker)
        assertEquals(Speaker.THEM, r.resolve("tr")!!.speaker)
        assertNull("ambiguous family member", r.resolve("az"))
    }
}
