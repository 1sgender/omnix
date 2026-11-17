package com.omnix.assistant.agent.media.ymusic

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YandexMusicTest {

    private val searchBody = """
        {"invocationInfo":{"hostname":"x"},"result":{"text":"глаза не врут","tracks":{"total":3,"results":[
          {"id":"111","title":"Глаза не врут","artists":[{"id":1,"name":"Группа"},{"id":2,"name":"Гость"}],
           "albums":[{"id":9}],"available":true,"durationMs":201000},
          {"id":222,"title":"Live","version":"remix","artists":[],"available":true},
          {"id":"333","title":"Закрытый","artists":[{"name":"Кто-то"}],"available":false}
        ]}}}
    """.trimIndent()

    private val fileInfoBody = """
        {"result":{"downloadInfo":{"trackId":"111","quality":"nq","codec":"mp3","bitrate":192,
          "transport":"raw","url":"https://s.example.net/get-mp3/abc/def","urls":["https://s.example.net/get-mp3/abc/def"]}}}
    """.trimIndent()

    // ------------------------------------------------------------ разбор ответов

    @Test
    fun `search parser keeps playable tracks with artists, version and numeric ids`() {
        val tracks = YandexMusicProtocol.parseSearch(searchBody)

        assertEquals(listOf("111", "222"), tracks.map { it.id })
        assertEquals("Глаза не врут", tracks[0].title)
        assertEquals("Группа, Гость", tracks[0].artistLine)
        assertEquals(201000L, tracks[0].durationMs)
        assertEquals("Live (remix)", tracks[1].title)
        assertEquals("", tracks[1].artistLine)
    }

    @Test
    fun `search parser survives garbage and empty answers`() {
        assertTrue(YandexMusicProtocol.parseSearch("").isEmpty())
        assertTrue(YandexMusicProtocol.parseSearch("not json").isEmpty())
        assertTrue(YandexMusicProtocol.parseSearch("""{"result":{"text":"x"}}""").isEmpty())
        assertTrue(YandexMusicProtocol.parseSearch("""{"result":{"tracks":{"results":[{"title":"без id"}]}}}""").isEmpty())
    }

    @Test
    fun `file info parser takes a plain https link and rejects encrypted transport`() {
        val stream = YandexMusicProtocol.parseFileInfo(fileInfoBody)
        assertNotNull(stream)
        assertEquals("https://s.example.net/get-mp3/abc/def", stream!!.url)
        assertEquals("mp3", stream.codec)
        assertEquals(192, stream.bitrateKbps)

        val encrypted = """{"result":{"downloadInfo":{"transport":"encraw","key":"00ff","url":"https://s.example.net/x"}}}"""
        assertNull(YandexMusicProtocol.parseFileInfo(encrypted))
        assertNull(YandexMusicProtocol.parseFileInfo("""{"result":{"downloadInfo":{"transport":"raw","url":"http://insecure/x"}}}"""))
        assertNull(YandexMusicProtocol.parseFileInfo("""{"result":{}}"""))
        assertNull(YandexMusicProtocol.parseFileInfo("oops"))
    }

    @Test
    fun `queue is deduplicated and limited`() {
        val many = (1..30).map { YmTrack("${it % 15}", "t$it", emptyList(), null) }
        val queue = YandexMusicProtocol.playableQueue(many, limit = 10)
        assertEquals(10, queue.size)
        assertEquals(queue.map { it.id }.distinct(), queue.map { it.id })
    }

    // ------------------------------------------------------------------ подпись

    @Test
    fun `file info sign is deterministic base64 without padding`() {
        val sign = YandexMusicProtocol.fileInfoSign(1_700_000_000L, "111")
        assertEquals(43, sign.length)
        assertFalse(sign.endsWith("="))
        assertEquals(sign, YandexMusicProtocol.fileInfoSign(1_700_000_000L, "111"))
        assertTrue(sign != YandexMusicProtocol.fileInfoSign(1_700_000_001L, "111"))
        assertTrue(sign != YandexMusicProtocol.fileInfoSign(1_700_000_000L, "112"))
    }

    @Test
    fun `authorization header uses OAuth scheme`() {
        assertEquals("OAuth y0_abc", YandexMusicProtocol.authorizationHeader("  y0_abc "))
    }

    // ------------------------------------------------------------ выбор действия

    @Test
    fun `no token asks for it and token plays inside OMNIX`() {
        assertEquals(MusicStart.ASK_FOR_TOKEN, YandexMusicDecision.start(hasToken = false))
        assertEquals(MusicStart.PLAY_INSIDE_OMNIX, YandexMusicDecision.start(hasToken = true))
    }

    @Test
    fun `rejected token goes to settings and other failures fall back to Yandex apps`() {
        assertEquals(MusicFallback.OPEN_TOKEN_SETTINGS, YandexMusicDecision.fallback(MusicFailure.UNAUTHORIZED))
        assertEquals(MusicFallback.OPEN_EXTERNAL_SEARCH, YandexMusicDecision.fallback(MusicFailure.NO_RESULTS))
        assertEquals(MusicFallback.OPEN_EXTERNAL_SEARCH, YandexMusicDecision.fallback(MusicFailure.UNAVAILABLE))
    }

    @Test
    fun `token input is cleaned and junk is rejected`() {
        val token = "y0_AgAAAAAbcdefghijklmnopqrstuvwxyz0123456789"
        assertEquals(token, YandexMusicDecision.normalizeToken("  \"$token\"  "))
        assertNull(YandexMusicDecision.normalizeToken(""))
        assertNull(YandexMusicDecision.normalizeToken("короткий"))
        assertNull(YandexMusicDecision.normalizeToken("two words two words two words"))
        assertNull(YandexMusicDecision.normalizeToken(null))
    }

    // ------------------------------------------------------------ клиент (сеть)

    private lateinit var server: MockWebServer
    private lateinit var client: YandexMusicClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = YandexMusicClient(OkHttpClient(), server.url("/").toString().trimEnd('/'), { 1_700_000_000_000L })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search sends token only in header and returns tracks`() = runBlocking {
        server.enqueue(MockResponse().setBody(searchBody))

        val result = client.search("глаза не врут", "secret-token-value")

        assertEquals(listOf("111", "222"), (result as YmResult.Ok).value.map { it.id })
        val request = server.takeRequest()
        assertEquals("OAuth secret-token-value", request.getHeader("Authorization"))
        assertFalse(request.path!!.contains("secret-token-value"))
        assertTrue(request.path!!.startsWith("/search?"))
        assertTrue(request.path!!.contains("type=track"))
    }

    @Test
    fun `resolve signs the request and returns the link`() = runBlocking {
        server.enqueue(MockResponse().setBody(fileInfoBody))

        val result = client.resolve("111", "tok")

        assertEquals("https://s.example.net/get-mp3/abc/def", (result as YmResult.Ok).value.url)
        val request = server.takeRequest()
        assertEquals(YandexMusicProtocol.CLIENT_HEADER_VALUE, request.getHeader(YandexMusicProtocol.CLIENT_HEADER_NAME))
        val url = request.requestUrl!!
        assertEquals("1700000000", url.queryParameter("ts"))
        assertEquals("111", url.queryParameter("trackId"))
        assertEquals("raw", url.queryParameter("transports"))
        assertEquals(YandexMusicProtocol.fileInfoSign(1_700_000_000L, "111"), url.queryParameter("sign"))
    }

    @Test
    fun `http errors map to honest results`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(YmResult.Unauthorized, client.search("x", "tok"))

        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(YmResult.Unavailable, client.search("x", "tok"))

        server.enqueue(MockResponse().setBody("""{"result":{"tracks":{"results":[]}}}"""))
        assertEquals(YmResult.NoResults, client.search("x", "tok"))

        server.enqueue(MockResponse().setBody("""{"result":{"downloadInfo":{"transport":"encraw","key":"aa","url":"https://x/y"}}}"""))
        assertEquals(YmResult.Unavailable, client.resolve("111", "tok"))
    }
}
