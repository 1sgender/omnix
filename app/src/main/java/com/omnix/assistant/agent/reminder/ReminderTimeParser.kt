package com.omnix.assistant.agent.reminder

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * Разбор фразы «напомни в 18:00 позвонить маме» на время и текст.
 *
 * Понимает русский полностью, туркменский, английский, турецкий, узбекский
 * (латиница) и казахский в базовых формах: «через N минут/часов», «в HH:MM»,
 * «в N часов (вечера)», «сегодня/завтра/послезавтра». Время без дня — ближайшее
 * будущее. Чистая логика без Android: проверяется юнит-тестами.
 */
object ReminderTimeParser {

    sealed interface Result {
        data class Ok(val text: String, val triggerAt: Long) : Result

        /** В фразе нет времени: надо спросить, когда напомнить. */
        data object NoTime : Result

        /** Есть время, но не сказано, о чём напомнить. */
        data object NoText : Result
    }

    // Границы слов через lookaround: \b в разных движках по-разному понимает кириллицу.
    private const val L0 = """(?<![\p{L}\p{N}])"""
    private const val L1 = """(?![\p{L}\p{N}])"""

    private const val AMOUNT = """(\d{1,3}|an?|one|bir|бір|один|одну|одна)"""
    private const val MIN_UNIT = """(?:минут\p{L}*|мин|minutes?|mins?|dakika\p{L}*|dk|minut\p{L}*|daqiqa\p{L}*|дақиқа\p{L}*)"""
    private const val HOUR_UNIT = """(?:час\p{L}*|hours?|hrs?|saat\p{L}*|sagat\p{L}*|soat\p{L}*|сағат\p{L}*)"""

    private val startVerb = Regex(
        """^(?:(?:пожалуйста|please)[,\s]+)?(?:напомни(?:ть)?(?:\s+мне)?|""" +
            """(?:поставь|создай|добавь|сделай|установи)\s+(?:мне\s+)?напоминание|""" +
            """remind\s+me|(?:set|add|create)\s+(?:a\s+)?reminder)${L1}"""
    )
    private val anyVerb = Regex("""(?:hatırlat|ýatla|ýadyma\s+sal|eslat|еске\s+сал|ескерт)\p{L}*""")

    private val halfHour = Regex(
        """${L0}(?:через\s+полчаса|in\s+half\s+an?\s+hour|yarım\s+saat\s+sonra|""" +
            """yarım\s+sagat(?:dan)?|yarim\s+soat(?:dan)?\s+keyin|жарты\s+сағат(?:тан)?)${L1}"""
    )
    private val oneWord = Regex("""${L0}через\s+(час|минуту|минутку)${L1}""")

    private val relativeMinutes = listOf(
        Regex("""${L0}(?:через|in)\s+${AMOUNT}\s*${MIN_UNIT}${L1}"""),
        Regex("""${L0}${AMOUNT}\s*${MIN_UNIT}\s+(?:sonra|keyin|кейін|соң|soň)${L1}"""),
        Regex("""${L0}(\d{1,3}|bir|бір)\s*(?:minut|минут)(?:dan|den|тан|тен)${L1}""")
    )
    private val relativeHours = listOf(
        Regex("""${L0}(?:через|in)\s+${AMOUNT}\s*${HOUR_UNIT}${L1}"""),
        Regex("""${L0}${AMOUNT}\s*${HOUR_UNIT}\s+(?:sonra|keyin|кейін|соң|soň)${L1}"""),
        Regex("""${L0}(\d{1,3}|bir|бір)\s*(?:sagat|сағат)(?:dan|den|тан|тен)${L1}""")
    )

    private val dayAfter = Regex("""${L0}(?:послезавтра|day\s+after\s+tomorrow|öbür\s+gün|obur\s+gun)${L1}""")
    private val dayTomorrow = Regex("""${L0}(?:завтра|tomorrow|yarın|yarin|ertir|ertaga|ертең)${L1}""")
    private val dayToday = Regex("""${L0}(?:сегодня|today|bugün|bugun|bu\s+gün|şu\s+gün|бүгін)${L1}""")

    private const val PREP = """(?:в|at|saat|sagat|soat|сағат|соат)"""
    private const val MERIDIEM = """(?:\s*(am|pm|вечера|утра|дня|ночи))?"""
    // Падежное окончание татарского/тюркского типа: «18-да», «6'da», «18:00 da».
    private const val CASE_SUFFIX = """(?:['’-]\p{L}{1,3}(?![\p{L}])|\s+(?:da|de)${L1})?"""

    private val clockColon = Regex("""${L0}(?:(?:${PREP}|на)\s+)?(\d{1,2}):(\d{2})${MERIDIEM}${CASE_SUFFIX}${L1}""")
    private val clockDot = Regex("""${L0}(?:${PREP}|на)\s+(\d{1,2})\.(\d{2})${MERIDIEM}${CASE_SUFFIX}${L1}""")
    private val clockHour = Regex(
        """${L0}${PREP}\s+(\d{1,2})(?![\d:]|\.\d)(?:\s*(?:часов|часа|час))?${MERIDIEM}${CASE_SUFFIX}${L1}"""
    )

    private val leadingFiller = Regex("""^(?:мне|me|to|that|about|про|что|чтобы|надо|нужно|ve|and)${L1}[\s,:;.-]*""")
    private val trailingFiller = Regex("""[\s,]+(?:на|в|at|on|for)$""")
    private val spaces = Regex("""\s+""")

    private const val MINUTE_MS = 60_000L
    private const val HOUR_MS = 3_600_000L
    private const val HALF_HOUR_MS = 1_800_000L

    /** Похоже ли на просьбу поставить напоминание. */
    fun isReminderRequest(query: String): Boolean {
        val q = query.lowercase().trim()
        return startVerb.containsMatchIn(q) || anyVerb.containsMatchIn(q)
    }

    fun parse(input: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Result {
        val q = input.lowercase().trim()
        val consumed = mutableListOf<IntRange>()
        startVerb.find(q)?.let { consumed += it.range }
        anyVerb.findAll(q).forEach { consumed += it.range }

        val triggerAt = relativeTime(q, now, consumed) ?: absoluteTime(q, now, zone, consumed)
            ?: return Result.NoTime
        val text = cleanText(q, consumed)
        return if (text.isEmpty()) Result.NoText else Result.Ok(text, triggerAt)
    }

    /**
     * Ближайший момент с часами [hour]:[minute], считая от дня «сегодня + [dayOffset]».
     * Уже прошедшее время переносится на следующие сутки.
     */
    fun nextOccurrence(hour: Int, minute: Int, now: Long, zone: ZoneId, dayOffset: Int = 0): Long {
        val time = LocalTime.of(hour, minute)
        var date = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(dayOffset.toLong())
        var at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        if (at <= now) {
            date = date.plusDays(1)
            at = date.atTime(time).atZone(zone).toInstant().toEpochMilli()
        }
        return at
    }

    private fun relativeTime(q: String, now: Long, consumed: MutableList<IntRange>): Long? {
        halfHour.find(q)?.let {
            consumed += it.range
            return now + HALF_HOUR_MS
        }
        oneWord.find(q)?.let {
            consumed += it.range
            return now + if (it.groupValues[1] == "час") HOUR_MS else MINUTE_MS
        }
        for ((regexes, unitMs) in listOf(relativeMinutes to MINUTE_MS, relativeHours to HOUR_MS)) {
            for (regex in regexes) {
                val match = regex.find(q) ?: continue
                val amount = match.groupValues[1].toIntOrNull() ?: 1
                if (amount < 1) continue
                consumed += match.range
                return now + amount * unitMs
            }
        }
        return null
    }

    private fun absoluteTime(q: String, now: Long, zone: ZoneId, consumed: MutableList<IntRange>): Long? {
        val clock = findClock(q) ?: return null
        consumed += clock.range
        val dayMatch = dayAfter.find(q) to 2
        val tomorrowMatch = dayTomorrow.find(q) to 1
        val todayMatch = dayToday.find(q) to 0
        var offset = 0
        for ((match, days) in listOf(dayMatch, tomorrowMatch, todayMatch)) {
            if (match != null) {
                consumed += match.range
                offset = days
                break
            }
        }
        return nextOccurrence(clock.hour, clock.minute, now, zone, offset)
    }

    private class Clock(val hour: Int, val minute: Int, val range: IntRange)

    private fun findClock(q: String): Clock? {
        for (regex in listOf(clockColon, clockDot)) {
            val match = regex.find(q) ?: continue
            return clockOf(match.groupValues[1], match.groupValues[2], match.groupValues[3], match.range)
        }
        val match = clockHour.find(q) ?: return null
        return clockOf(match.groupValues[1], "0", match.groupValues[2], match.range)
    }

    private fun clockOf(hourText: String, minuteText: String, meridiem: String, range: IntRange): Clock? {
        val hour = hourText.toIntOrNull() ?: return null
        val minute = minuteText.toIntOrNull() ?: return null
        val adjusted = when (meridiem) {
            "pm", "вечера", "дня" -> if (hour in 1..11) hour + 12 else hour
            "am", "утра", "ночи" -> if (hour == 12) 0 else hour
            else -> hour
        }
        return if (adjusted in 0..23 && minute in 0..59) Clock(adjusted, minute, range) else null
    }

    private fun cleanText(q: String, consumed: List<IntRange>): String {
        val removed = BooleanArray(q.length)
        for (range in consumed) for (i in range) if (i in removed.indices) removed[i] = true
        val kept = StringBuilder()
        for (i in q.indices) kept.append(if (removed[i]) ' ' else q[i])
        var text = kept.toString().replace(spaces, " ").trim()
        repeat(FILLER_PASSES) {
            text = leadingFiller.replace(text.trim { it in PUNCTUATION }, "")
            text = trailingFiller.replace(text.trim { it in PUNCTUATION }, "")
        }
        text = text.trim { it in PUNCTUATION }
        return text.replaceFirstChar { it.titlecase() }
    }

    private const val FILLER_PASSES = 3
    private const val PUNCTUATION = " ,.;:!?-"
}
