package com.omnix.assistant.agent.tools.communication

import android.content.Context
import com.omnix.assistant.agent.capability.CapabilityStatus
import com.omnix.assistant.agent.capability.DeviceCapability
import com.omnix.assistant.agent.capability.DeviceCapabilityRegistry
import com.omnix.assistant.agent.model.ToolExecutionStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Честность SMS: «отправлено» только по отчёту системы; реальных SMS тест не шлёт. */
class SmsToolTest {

    private class FakeTransport(private val outcome: SmsSendOutcome) : SmsTransport {
        var calls = 0
        var number: String? = null
        override suspend fun send(phoneNumber: String, message: String): SmsSendOutcome {
            calls++
            number = phoneNumber
            return outcome
        }
    }

    private fun tool(
        transport: SmsTransport,
        smsStatus: CapabilityStatus = CapabilityStatus.Available,
        resolution: ContactResolution = ContactResolution.Resolved("+99365123456", "Усман")
    ): SmsTool {
        val capabilities = mockk<DeviceCapabilityRegistry>()
        every { capabilities.statusOf(DeviceCapability.SEND_SMS_DIRECTLY) } returns smsStatus
        val resolver = mockk<ContactResolver>()
        every { resolver.resolve(any()) } returns resolution
        return SmsTool(mockk<Context>(), capabilities, resolver, transport)
    }

    private fun args(recipient: String = "Усман", message: String = "привет"): JsonObject =
        buildJsonObject {
            put("recipient", recipient)
            put("message", message)
        }

    @Test
    fun `успех только когда система подтвердила отправку`() = runBlocking {
        val transport = FakeTransport(SmsSendOutcome.Sent(1))
        val result = tool(transport).execute(args())
        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.summary.contains("Усман"))
        assertEquals("+99365123456", transport.number)
    }

    @Test
    fun `длинное сообщение сообщает число частей`() = runBlocking {
        val result = tool(FakeTransport(SmsSendOutcome.Sent(3))).execute(args())
        assertEquals(ToolExecutionStatus.SUCCESS, result.status)
        assertTrue(result.summary.contains("3 SMS"))
    }

    @Test
    fun `ошибка модема - честный отказ без слова отправлено`() = runBlocking {
        val result = tool(FakeTransport(SmsSendOutcome.Failed(4, "нет сети оператора"))).execute(args())
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("SMS_SEND_FAILED", result.error)
        assertTrue(result.summary.contains("нет сети оператора"))
        assertFalse(result.summary.contains("SMS отправлено"))
    }

    @Test
    fun `нет отчёта системы - результат неизвестен, не успех`() = runBlocking {
        val result = tool(FakeTransport(SmsSendOutcome.Unknown(1))).execute(args())
        assertEquals(ToolExecutionStatus.FAILURE, result.status)
        assertEquals("SMS_RESULT_UNKNOWN", result.error)
    }

    @Test
    fun `без SEND_SMS отправка не вызывается`() = runBlocking {
        val transport = FakeTransport(SmsSendOutcome.Sent(1))
        val status = CapabilityStatus.PermissionRequired(listOf("android.permission.SEND_SMS"))
        val result = tool(transport, smsStatus = status).execute(args())
        assertEquals(ToolExecutionStatus.PERMISSION_REQUIRED, result.status)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `система отклонила SEND_SMS на лету - снова запрос разрешения`() = runBlocking {
        val result = tool(FakeTransport(SmsSendOutcome.PermissionDenied)).execute(args())
        assertEquals(ToolExecutionStatus.PERMISSION_REQUIRED, result.status)
        assertTrue(result.missingPermissions.contains("android.permission.SEND_SMS"))
    }

    @Test
    fun `контакт не найден или неоднозначен - SMS не уходит`() = runBlocking {
        val transport = FakeTransport(SmsSendOutcome.Sent(1))
        val notFound = tool(transport, resolution = ContactResolution.NotFound("Усман")).execute(args())
        assertEquals("CONTACT_NOT_FOUND", notFound.error)
        val ambiguous = tool(
            transport,
            resolution = ContactResolution.Ambiguous(listOf("Усман А" to "+1000", "Усман Б" to "+2000"))
        ).execute(args())
        assertEquals("AMBIGUOUS_CONTACT", ambiguous.error)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `пустой текст и слишком длинный текст отклоняются до отправки`() = runBlocking {
        val transport = FakeTransport(SmsSendOutcome.Sent(1))
        val sms = tool(transport)
        assertEquals("MISSING_MESSAGE", sms.execute(args(message = "  ")).error)
        assertEquals("MESSAGE_TOO_LONG", sms.execute(args(message = "а".repeat(1_601))).error)
        assertEquals("MISSING_RECIPIENT", sms.execute(args(recipient = "")).error)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `таймаут инструмента покрывает ожидание отчёта модема`() {
        assertTrue(tool(FakeTransport(SmsSendOutcome.Sent(1))).executionTimeoutMs >= 40_000L)
    }

    @Test
    fun `коды ошибок SmsManager переводятся в причину`() {
        assertTrue(SmsResultCodes.isOk(-1))
        assertFalse(SmsResultCodes.isOk(1))
        assertEquals("нет сети оператора", SmsResultCodes.describe(4))
        assertEquals("включён режим полёта или выключена связь", SmsResultCodes.describe(2))
        assertTrue(SmsResultCodes.describe(999).contains("999"))
    }
}
