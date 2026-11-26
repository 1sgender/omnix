package com.omnix.assistant.agent.translator

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveEarSessionTest {

    private fun ok(text: String) = TranslationResult.Success(text, "en", "ru", "test")

    @Test
    fun `pieces are translated in order with context and spoken`() = runTest {
        val asked = mutableListOf<Pair<String, String>>()
        val spoken = mutableListOf<String>()
        val session = LiveEarSession(
            scope = backgroundScope,
            translate = { fragment, previous -> asked += fragment to previous; ok("[$fragment]") },
            speak = { spoken += it },
            describeFailure = { "fail" },
            chunkWords = 2
        )
        session.onPartial("good morning")
        session.onPartial("good morning everyone")
        runCurrent()
        session.onFinal("good morning everyone and welcome")
        runCurrent()

        assertEquals(listOf("good morning" to "", "everyone and welcome" to "good morning"), asked)
        assertEquals(listOf("[good morning]", "[everyone and welcome]"), spoken)
        assertEquals("[good morning] [everyone and welcome]", session.view.value.translation)
        session.stop()
    }

    @Test
    fun `pause sends the unfinished piece`() = runTest {
        val spoken = mutableListOf<String>()
        val session = LiveEarSession(
            scope = backgroundScope,
            translate = { fragment, _ -> ok(fragment.uppercase()) },
            speak = { spoken += it },
            describeFailure = { "fail" },
            pauseMs = 700
        )
        session.onPartial("hello")
        advanceTimeBy(701)
        runCurrent()
        assertEquals(listOf("HELLO"), spoken)
        session.stop()
    }

    @Test
    fun `backlog is merged into one request`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val asked = mutableListOf<String>()
        val session = LiveEarSession(
            scope = backgroundScope,
            translate = { fragment, _ ->
                asked += fragment
                if (asked.size == 1) gate.await()
                ok(fragment)
            },
            speak = {},
            describeFailure = { "fail" },
            chunkWords = 1
        )
        session.onPartial("one")
        session.onPartial("one two")
        runCurrent() // «one» ушло в перевод и ждёт
        session.onPartial("one two three")
        session.onFinal("one two three four")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("one", "two three four"), asked)
        session.stop()
    }

    @Test
    fun `failure is shown and not spoken`() = runTest {
        val spoken = mutableListOf<String>()
        val session = LiveEarSession(
            scope = backgroundScope,
            translate = { _, _ -> TranslationResult.NetworkRequired("нет сети") },
            speak = { spoken += it },
            describeFailure = { "Для перевода нужен интернет" }
        )
        session.onFinal("hello there")
        runCurrent()
        assertTrue(spoken.isEmpty())
        assertEquals("Для перевода нужен интернет", session.view.value.error)
        session.stop()
    }

    @Test
    fun `foreign piece waits and is dropped if language stays foreign`() = runTest {
        val asked = mutableListOf<String>()
        val session = LiveEarSession(
            scope = backgroundScope,
            translate = { fragment, _ -> asked += fragment; ok(fragment) },
            speak = {},
            describeFailure = { "fail" },
            isForeign = { true },
            recheckMs = 1_000
        )
        session.onFinal("privet kak dela")
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(asked.isEmpty())
        assertEquals(1, session.foreignDropped)
        session.stop()
    }

    @Test
    fun `foreign piece passes if partner language appears on recheck`() = runTest {
        var foreign = true
        val asked = mutableListOf<String>()
        val session = LiveEarSession(
            scope = backgroundScope,
            translate = { fragment, _ -> asked += fragment; ok(fragment) },
            speak = {},
            describeFailure = { "fail" },
            isForeign = { foreign },
            recheckMs = 1_000
        )
        session.onFinal("hello my friend")
        runCurrent()
        foreign = false
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(listOf("hello my friend"), asked)
        session.stop()
    }
}
