package com.omnix.server

import com.omnix.server.config.ConcurrencyConfig
import com.omnix.server.http.HttpResponseContext
import com.omnix.server.http.OmnixApiHandler
import com.omnix.server.http.RequestBulkheads
import com.omnix.server.persistence.PostgresSingleInstanceGuard
import com.omnix.server.usage.AsyncUsageTracker
import com.zaxxer.hikari.HikariDataSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.sql.SQLTransientConnectionException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Под нагрузкой сервер быстро отказывает (503 SERVER_BUSY + Retry-After), а лёгкие запросы живут. */
class ServerOverloadTest {

    private class Reply(val code: Int, val body: String, val retryAfter: String?)

    private fun call(port: Int, path: String, method: String = "GET"): Reply {
        val conn = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 3000
        conn.readTimeout = 8000
        if (method == "POST") {
            conn.doOutput = true
            conn.outputStream.use { it.write("{}".toByteArray()) }
        }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val retry = conn.getHeaderField("Retry-After")
        conn.disconnect()
        return Reply(code, body, retry)
    }

    @Test
    fun `bulkhead admits up to the limit then refuses immediately and frees slots on close`() {
        val bulkheads = RequestBulkheads(ConcurrencyConfig(maxInFlight = 3, maxAiInFlight = 1, maxSttInFlight = 1))

        val ai = bulkheads.tryEnter(OmnixApiHandler.PATH_EXECUTE)
        assertNotNull(ai)
        assertNull("второй ИИ-запрос сверх лимита", bulkheads.tryEnter(OmnixApiHandler.PATH_EXECUTE))
        // Лёгкие запросы при занятом ИИ-лимите проходят.
        val light = bulkheads.tryEnter("/v1/license/validate")
        assertNotNull(light)

        ai!!.close()
        ai.close() // повторное закрытие не освобождает чужое место
        val again = bulkheads.tryEnter(OmnixApiHandler.PATH_EXECUTE)
        assertNotNull(again)
        assertNull(bulkheads.tryEnter(OmnixApiHandler.PATH_EXECUTE))
        light!!.close()
        again!!.close()
    }

    @Test
    fun `busy response uses the unified error format with retry_after`() {
        val response = RequestBulkheads.busyResponse(7)
        assertEquals(503, response.status)
        assertEquals("7", response.headers["Retry-After"])
        assertTrue(response.body.contains("\"code\":\"SERVER_BUSY\""))
        assertTrue(response.body.contains("\"retry_after\":7"))
    }

    @Test
    fun `database pool exhaustion is recognised through the cause chain`() {
        val wrapped = IllegalStateException("x", SQLTransientConnectionException("Connection is not available"))
        assertTrue(RequestBulkheads.isDatabaseOverload(wrapped))
        assertFalse(RequestBulkheads.isDatabaseOverload(IllegalStateException("other")))
    }

    @Test
    fun `server refuses extra ai requests with 503 while health and light requests still work`() {
        val dataSource = mockk<HikariDataSource>(relaxed = true)
        val usageTracker = mockk<AsyncUsageTracker>(relaxed = true)
        val instanceGuard = mockk<PostgresSingleInstanceGuard>(relaxed = true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val aiHandled = AtomicInteger(0)

        val handler = mockk<OmnixApiHandler>(relaxed = true)
        every { handler.healthSnapshot() } returns """{"status":"ok"}"""
        coEvery { handler.handle(any()) } coAnswers {
            val path = firstArg<com.omnix.server.http.HttpRequestContext>().path
            if (path == OmnixApiHandler.PATH_EXECUTE) {
                aiHandled.incrementAndGet()
                entered.countDown()
                while (!release.await(20, TimeUnit.MILLISECONDS)) delay(10)
            }
            HttpResponseContext(200, """{"ok":true}""")
        }

        val config = ServerBootstrapTestSupport.devConfig()
            .copy(concurrency = ConcurrencyConfig(maxInFlight = 16, maxAiInFlight = 1, maxSttInFlight = 1))
        val resources = ServerBootstrap.ServerResources(
            handler = handler,
            dataSource = dataSource,
            usageTracker = usageTracker,
            reconciliationWorker = null,
            instanceGuard = instanceGuard,
        )
        val running = ServerBootstrap.bindServer(config, resources, port = 0)
        try {
            val port = running.localPort
            val first = Thread { call(port, OmnixApiHandler.PATH_EXECUTE, "POST") }
            first.start()
            assertTrue("первый ИИ-запрос дошёл до обработчика", entered.await(5, TimeUnit.SECONDS))

            val refused = call(port, OmnixApiHandler.PATH_EXECUTE, "POST")
            assertEquals(503, refused.code)
            assertTrue(refused.body, refused.body.contains("SERVER_BUSY"))
            assertTrue(refused.body, refused.body.contains("\"retry_after\":3"))
            assertEquals("3", refused.retryAfter)
            assertEquals("отказанный запрос не дошёл до обработчика", 1, aiHandled.get())

            assertEquals(200, call(port, "/v1/health").code)
            assertEquals(200, call(port, "/v1/license/validate", "POST").code)

            release.countDown()
            first.join(5000)
            assertEquals("после освобождения места ИИ снова принимается", 200, call(port, OmnixApiHandler.PATH_EXECUTE, "POST").code)
        } finally {
            release.countDown()
            running.close()
        }
    }
}
