package com.omnix.assistant.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** QUOTA_EXCEEDED → понятное сообщение, и честный остаток «Сегодня осталось». */
class AiQuotaTest {

    private val now = Instant.parse("2026-11-13T12:00:00Z").toEpochMilli()
    private val hour = 3_600_000L
    private val pro = PlanEntitlements(dailyVoiceAi = 300, dailyBaseAi = 300, dailyProAi = 20, monthlyProAi = 250)

    // ------------------------------------------------ разбор отказа сервера

    @Test
    fun `only quota exceeded is mapped`() {
        assertNull(QuotaExceeded.parse("RATE_LIMITED", "pro", "60", requestedPro = true, voice = false, nowMs = now))
        assertNull(QuotaExceeded.parse("PROVIDER_ERROR", null, null, requestedPro = false, voice = false, nowMs = now))
    }

    @Test
    fun `scope header names the exhausted bucket`() {
        val proOut = QuotaExceeded.parse("QUOTA_EXCEEDED", "pro", "7200", requestedPro = true, voice = false, nowMs = now)!!
        assertEquals(AiQuotaBucket.PRO, proOut.bucket)
        assertEquals(7_200L, proOut.retryAfterSeconds)

        // Просили Pro, но кончился сам Flash (базовая квота) — переключение на Flash не поможет.
        val flashOut = QuotaExceeded.parse("QUOTA_EXCEEDED", "flash", "60", requestedPro = true, voice = true, nowMs = now)!!
        assertEquals(AiQuotaBucket.FLASH_VOICE, flashOut.bucket)

        val textOut = QuotaExceeded.parse("QUOTA_EXCEEDED", "FLASH", null, requestedPro = false, voice = false, nowMs = now)!!
        assertEquals(AiQuotaBucket.FLASH_TEXT, textOut.bucket)
        assertNull(textOut.retryAfterSeconds)
    }

    @Test
    fun `old server without scope header falls back to what was requested`() {
        val asPro = QuotaExceeded.parse("QUOTA_EXCEEDED", null, "abc", requestedPro = true, voice = false, nowMs = now)!!
        assertEquals(AiQuotaBucket.PRO, asPro.bucket)
        assertNull("мусорный Retry-After игнорируется", asPro.retryAfterSeconds)
        val asFlash = QuotaExceeded.parse("QUOTA_EXCEEDED", null, null, requestedPro = false, voice = true, nowMs = now)!!
        assertEquals(AiQuotaBucket.FLASH_VOICE, asFlash.bucket)
    }

    // ------------------------------------------------ сообщение пользователю

    @Test
    fun `pro limit offers flash and says when it resets`() {
        val notice = QuotaNotice.of(QuotaExceeded(AiQuotaBucket.PRO, 5 * 3_600L - 30, now), proInPlan = true)
        assertEquals(QuotaResetUnit.HOURS, notice.unit)
        assertEquals(5, notice.amount)
        assertTrue(notice.offerFlash)
        assertFalse(notice.offerUpgrade)
    }

    @Test
    fun `free plan flash limit suggests pro and max`() {
        val notice = QuotaNotice.of(QuotaExceeded(AiQuotaBucket.FLASH_TEXT, 600L, now), proInPlan = false)
        assertEquals(QuotaResetUnit.HOURS, notice.unit)
        assertEquals("меньше часа округляем до 1 ч", 1, notice.amount)
        assertFalse(notice.offerFlash)
        assertTrue(notice.offerUpgrade)
    }

    @Test
    fun `paid plan flash limit does not advertise an upgrade`() {
        val notice = QuotaNotice.of(QuotaExceeded(AiQuotaBucket.FLASH_VOICE, 3_600L, now), proInPlan = true)
        assertFalse(notice.offerFlash)
        assertFalse(notice.offerUpgrade)
    }

    @Test
    fun `monthly pro limit is told in days`() {
        val notice = QuotaNotice.of(QuotaExceeded(AiQuotaBucket.PRO, 17L * 24 * 3_600, now), proInPlan = true)
        assertEquals(QuotaResetUnit.DAYS, notice.unit)
        assertEquals(17, notice.amount)
    }

    @Test
    fun `missing retry after counts to utc midnight`() {
        val notice = QuotaNotice.of(QuotaExceeded(AiQuotaBucket.FLASH_TEXT, null, now), proInPlan = false)
        assertEquals(QuotaResetUnit.HOURS, notice.unit)
        assertEquals(12, notice.amount)
    }

    // ------------------------------------------------ «Сегодня осталось»

    private val usage = PlanAiUsage(
        usedToday = PlanAiUsage.UsedToday(voiceAi = 10, baseAi = 100, proAi = 5),
        usedMonth = PlanAiUsage.UsedMonth(proAi = 40)
    )

    @Test
    fun `remaining is server snapshot minus answers after it`() {
        val left = AiQuotaCalculator.remaining(
            entitlements = pro,
            usage = usage,
            usageAtMs = now - 2 * hour,
            localAnswers = listOf(
                LocalAnswer(AiQuotaBucket.PRO, now - hour),
                LocalAnswer(AiQuotaBucket.FLASH_VOICE, now - hour),
                // До снимка — уже в счёте сервера, второй раз не считаем.
                LocalAnswer(AiQuotaBucket.FLASH_TEXT, now - 3 * hour)
            ),
            exhausted = emptyList(),
            nowMs = now
        )
        assertEquals(QuotaLine(300, 289), left.flashVoice)
        assertEquals(QuotaLine(300, 200), left.flashText)
        assertEquals(QuotaLine(20, 14), left.proToday)
        assertEquals(QuotaLine(250, 209), left.proMonth)
        assertTrue(left.proInPlan)
    }

    @Test
    fun `yesterday snapshot no longer counts for today but still counts for the month`() {
        val left = AiQuotaCalculator.remaining(
            entitlements = pro,
            usage = usage,
            usageAtMs = now - 13 * hour,
            localAnswers = listOf(LocalAnswer(AiQuotaBucket.PRO, now - hour)),
            exhausted = emptyList(),
            nowMs = now
        )
        assertEquals(QuotaLine(300, 300), left.flashVoice)
        assertEquals(QuotaLine(20, 19), left.proToday)
        assertEquals(QuotaLine(250, 209), left.proMonth)
    }

    @Test
    fun `quota refusal closes the bucket whatever the count says`() {
        val left = AiQuotaCalculator.remaining(
            entitlements = pro,
            usage = usage,
            usageAtMs = now - hour,
            localAnswers = emptyList(),
            exhausted = listOf(QuotaExceeded(AiQuotaBucket.PRO, 3 * 3_600L, now - hour)),
            nowMs = now
        )
        assertEquals(QuotaLine(20, 0), left.proToday)
        assertEquals("дневной отказ не закрывает месяц", QuotaLine(250, 210), left.proMonth)

        val monthOut = AiQuotaCalculator.remaining(
            entitlements = pro,
            usage = usage,
            usageAtMs = now - hour,
            localAnswers = emptyList(),
            exhausted = listOf(QuotaExceeded(AiQuotaBucket.PRO, 10L * 24 * 3_600, now - hour)),
            nowMs = now
        )
        assertEquals(QuotaLine(250, 0), monthOut.proMonth)
    }

    @Test
    fun `expired refusal no longer closes the bucket`() {
        val left = AiQuotaCalculator.remaining(
            entitlements = pro,
            usage = usage,
            usageAtMs = now - hour,
            localAnswers = emptyList(),
            exhausted = listOf(QuotaExceeded(AiQuotaBucket.FLASH_TEXT, 60L, now - hour)),
            nowMs = now
        )
        assertEquals(QuotaLine(300, 200), left.flashText)
    }

    @Test
    fun `unknown usage shows only the limit and free has no pro rows`() {
        val left = AiQuotaCalculator.remaining(
            entitlements = PlanEntitlements.FREE,
            usage = null,
            usageAtMs = 0L,
            localAnswers = emptyList(),
            exhausted = emptyList(),
            nowMs = now
        )
        assertEquals(QuotaLine(10, null), left.flashVoice)
        assertEquals(QuotaLine(10, null), left.flashText)
        assertFalse(left.proInPlan)
    }

    @Test
    fun `remaining never goes below zero`() {
        val left = AiQuotaCalculator.remaining(
            entitlements = PlanEntitlements.FREE,
            usage = PlanAiUsage(usedToday = PlanAiUsage.UsedToday(baseAi = 25)),
            usageAtMs = now - hour,
            localAnswers = emptyList(),
            exhausted = emptyList(),
            nowMs = now
        )
        assertEquals(QuotaLine(10, 0), left.flashText)
    }

    @Test
    fun `bucket follows the served tier and the source`() {
        assertEquals(AiQuotaBucket.PRO, AiQuotaBucket.of(servedPro = true, voice = true))
        assertEquals(AiQuotaBucket.FLASH_VOICE, AiQuotaBucket.of(servedPro = false, voice = true))
        assertEquals(AiQuotaBucket.FLASH_TEXT, AiQuotaBucket.of(servedPro = false, voice = false))
    }
}
