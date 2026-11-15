package com.omnix.assistant.core.license

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.ceil

/**
 * Сколько ответов ИИ сервер уже засчитал (поле usage validate-ответа).
 * Зеркало server PlanUsageDto: сутки UTC по корзинам и календарный месяц UTC
 * для Pro, тем же счётом, что у серверного PlanQuotaGate.
 */
@Serializable
data class PlanAiUsage(
    @SerialName("used_today") val usedToday: UsedToday = UsedToday(),
    @SerialName("used_month") val usedMonth: UsedMonth = UsedMonth()
) {
    @Serializable
    data class UsedToday(
        @SerialName("voice_ai") val voiceAi: Long = 0,
        @SerialName("base_ai") val baseAi: Long = 0,
        @SerialName("pro_ai") val proAi: Long = 0
    )

    @Serializable
    data class UsedMonth(
        @SerialName("pro_ai") val proAi: Long = 0
    )
}

/** Корзина квоты — как сервер засчитывает успешный ответ (AiRouter.recordUsage). */
enum class AiQuotaBucket {
    FLASH_VOICE,
    FLASH_TEXT,
    PRO;

    companion object {
        fun of(servedPro: Boolean, voice: Boolean): AiQuotaBucket = when {
            servedPro -> PRO
            voice -> FLASH_VOICE
            else -> FLASH_TEXT
        }
    }
}

/** Ответ облака, засчитанный после последнего validate (сервер о нём ещё не рассказал). */
data class LocalAnswer(val bucket: AiQuotaBucket, val atMs: Long)

/** Сервер отказал QUOTA_EXCEEDED: какая корзина кончилась и когда обновится. */
data class QuotaExceeded(
    val bucket: AiQuotaBucket,
    /** Retry-After сервера; null — старый сервер, считаем до конца суток UTC. */
    val retryAfterSeconds: Long?,
    val atMs: Long
) {
    /** Когда корзина снова откроется. */
    fun untilMs(): Long = atMs + 1_000L * (retryAfterSeconds?.takeIf { it > 0 } ?: AiQuotaClock.secondsUntilNextUtcDay(atMs))

    companion object {
        const val CODE = "QUOTA_EXCEEDED"
        private const val SCOPE_PRO = "pro"
        private const val SCOPE_FLASH = "flash"

        /**
         * Разбор отказа сервера. Корзину называет заголовок X-Quota-Scope; без
         * него (старый сервер) — Pro, если просили Pro, иначе Flash по источнику.
         */
        fun parse(
            code: String,
            scopeHeader: String?,
            retryAfterHeader: String?,
            requestedPro: Boolean,
            voice: Boolean,
            nowMs: Long
        ): QuotaExceeded? {
            if (code != CODE) return null
            val scope = scopeHeader?.trim()?.lowercase()
            val bucket = when {
                scope == SCOPE_PRO -> AiQuotaBucket.PRO
                scope == SCOPE_FLASH -> AiQuotaBucket.of(servedPro = false, voice = voice)
                requestedPro -> AiQuotaBucket.PRO
                else -> AiQuotaBucket.of(servedPro = false, voice = voice)
            }
            val retryAfter = retryAfterHeader?.trim()?.toLongOrNull()?.takeIf { it > 0 }
            return QuotaExceeded(bucket, retryAfter, nowMs)
        }
    }
}

/** Одна строка «Сегодня осталось»: лимит тарифа и остаток (null — сервер не сообщил использованное). */
data class QuotaLine(val limit: Int, val remaining: Int?)

/** Экран «Сегодня осталось»: Flash голос/текст и Pro день/месяц. */
data class AiQuotaRemaining(
    val flashVoice: QuotaLine,
    val flashText: QuotaLine,
    val proToday: QuotaLine,
    val proMonth: QuotaLine
) {
    /** Pro есть в тарифе: строки Pro показываем только тогда. */
    val proInPlan: Boolean get() = proToday.limit > 0
}

/** Сутки и месяц UTC — те же границы, что у серверного PlanQuotaGate. */
object AiQuotaClock {
    fun dayStartMs(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate()
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun monthStartMs(nowMs: Long): Long =
        Instant.ofEpochMilli(nowMs).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun secondsUntilNextUtcDay(nowMs: Long): Long =
        ((dayStartMs(nowMs) + DAY_MS - nowMs) / 1_000L).coerceAtLeast(1L)

    const val DAY_MS = 24L * 60 * 60 * 1_000
}

/**
 * Честный остаток: использованное по снимку сервера (validate) плюс ответы,
 * полученные после снимка. Снимок вчерашний (или прошлого месяца) — его
 * использованное уже обнулилось. Отказ QUOTA_EXCEEDED закрывает корзину до
 * обновления, что бы ни говорил счёт.
 */
object AiQuotaCalculator {

    fun remaining(
        entitlements: PlanEntitlements,
        usage: PlanAiUsage?,
        usageAtMs: Long,
        localAnswers: List<LocalAnswer>,
        exhausted: List<QuotaExceeded>,
        nowMs: Long
    ): AiQuotaRemaining {
        val dayStart = AiQuotaClock.dayStartMs(nowMs)
        val monthStart = AiQuotaClock.monthStartMs(nowMs)
        val nextDay = dayStart + AiQuotaClock.DAY_MS

        fun line(limit: Int, serverUsed: Long?, bucket: AiQuotaBucket, since: Long, closedUntilAfter: Long): QuotaLine {
            val closed = exhausted.any { it.bucket == bucket && it.untilMs() > maxOf(nowMs, closedUntilAfter) }
            if (closed) return QuotaLine(limit, 0)
            if (usage == null || serverUsed == null) return QuotaLine(limit, null)
            val fromServer = if (usageAtMs >= since) serverUsed else 0L
            val local = localAnswers.count { it.bucket == bucket && it.atMs > usageAtMs && it.atMs >= since }
            val left = (limit - fromServer - local).coerceAtLeast(0L)
            return QuotaLine(limit, left.toInt())
        }

        return AiQuotaRemaining(
            flashVoice = line(entitlements.dailyVoiceAi, usage?.usedToday?.voiceAi, AiQuotaBucket.FLASH_VOICE, dayStart, nowMs),
            flashText = line(entitlements.dailyBaseAi, usage?.usedToday?.baseAi, AiQuotaBucket.FLASH_TEXT, dayStart, nowMs),
            proToday = line(entitlements.dailyProAi, usage?.usedToday?.proAi, AiQuotaBucket.PRO, dayStart, nowMs),
            // Месяц закрыт, только если отказ держит Pro дольше текущих суток.
            proMonth = line(entitlements.monthlyProAi, usage?.usedMonth?.proAi, AiQuotaBucket.PRO, monthStart, nextDay)
        )
    }
}

/** Через сколько обновится лимит: часы, а для месячного лимита Pro — дни. */
enum class QuotaResetUnit { HOURS, DAYS }

/** Что сказать пользователю на QUOTA_EXCEEDED. */
data class QuotaNotice(
    val unit: QuotaResetUnit,
    val amount: Int,
    /** Кончился Pro: предложить переключиться на Flash одним нажатием. */
    val offerFlash: Boolean,
    /** Тариф без Pro (Free): «Больше ответов в Pro и Max». */
    val offerUpgrade: Boolean
) {
    companion object {
        private const val SECONDS_PER_HOUR = 3_600.0
        private const val HOURS_PER_DAY = 24.0
        private const val MAX_HOURS_SHOWN = 48

        fun of(exceeded: QuotaExceeded, proInPlan: Boolean): QuotaNotice {
            val seconds = (exceeded.untilMs() - exceeded.atMs) / 1_000L
            val hours = ceil(seconds / SECONDS_PER_HOUR).toInt().coerceAtLeast(1)
            val (unit, amount) = if (hours > MAX_HOURS_SHOWN) {
                QuotaResetUnit.DAYS to ceil(hours / HOURS_PER_DAY).toInt()
            } else {
                QuotaResetUnit.HOURS to hours
            }
            val pro = exceeded.bucket == AiQuotaBucket.PRO
            return QuotaNotice(unit, amount, offerFlash = pro, offerUpgrade = !pro && !proInPlan)
        }
    }
}
