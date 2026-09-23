package com.omnix.assistant.data.remote

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

/**
 * Контракт [NetworkErrorMessages]: каждый класс сетевого сбоя даёт
 * СВОЮ понятную причину — пользователь по сообщению отличает «нет
 * интернета» от «DNS не резолвит домен» от «SSL» и т.д.
 */
class NetworkErrorMessagesTest {

    @Test
    fun `timeout maps to timeout reason`() {
        val msg = NetworkErrorMessages.messageFor(SocketTimeoutException("read timed out"))
        assertTrue("expected timeout reason in: '$msg'", msg.contains("таймаут"))
    }

    @Test
    fun `unknown host maps to dns reason`() {
        val msg = NetworkErrorMessages.messageFor(
            UnknownHostException("omnix.144.31.14.236.sslip.io")
        )
        assertTrue("expected DNS reason in: '$msg'", msg.contains("сервер не найден"))
        assertTrue("expected DNS wording in: '$msg'", msg.contains("DNS"))
    }

    @Test
    fun `connection refused maps to no-connection reason`() {
        val msg = NetworkErrorMessages.messageFor(ConnectException("Connection refused"))
        assertTrue("expected no-connection reason in: '$msg'", msg.contains("нет соединения"))
    }

    @Test
    fun `ssl failure maps to ssl reason`() {
        val msg = NetworkErrorMessages.messageFor(
            SSLHandshakeException("certificate mismatch")
        )
        assertTrue("expected SSL reason in: '$msg'", msg.contains("SSL"))
    }

    @Test
    fun `unknown IOException keeps exception class name for diagnostics`() {
        val msg = NetworkErrorMessages.messageFor(IOException("boom"))
        assertTrue("expected class name in: '$msg'", msg.contains("IOException"))
    }

    @Test
    fun `every message carries the server prefix for consistency`() {
        val msg = NetworkErrorMessages.messageFor(IOException("x"))
        assertTrue(
            "expected stable prefix in: '$msg'",
            msg.startsWith("Ошибка сети при обращении к серверу OMNIX:")
        )
    }
}
