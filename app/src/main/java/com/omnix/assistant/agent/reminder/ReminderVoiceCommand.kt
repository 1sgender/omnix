package com.omnix.assistant.agent.reminder

/**
 * Голосовой ответ на сработавшее напоминание: «готово» / «отложи на 10 минут».
 * Языки: русский, английский, туркменский, турецкий, узбекский (латиница), казахский.
 *
 * Берём только фразу целиком: «отложи звонок маме» или «готово, поехали» сюда не
 * попадают, лишние слова пропускаются только из списка безобидных. Чистая логика
 * без Android: проверяется юнит-тестами.
 */
object ReminderVoiceCommand {

    /** Инструмент, который исполняет команду (см. ReminderVoiceTool). */
    const val TOOL_ID = "productivity.reminder_reply"

    const val ARG_ACTION = "action"
    const val ARG_MINUTES = "minutes"
    const val ACTION_DONE = "done"
    const val ACTION_SNOOZE = "snooze"

    /** На сколько откладываем, если срок не назван. */
    const val DEFAULT_SNOOZE_MINUTES = ReminderManager.SNOOZE_MINUTES

    /** Больше суток откладывать голосом не даём: скорее всего это ошибка распознавания. */
    const val MAX_SNOOZE_MINUTES = 24 * 60

    sealed interface Command {
        data object Done : Command
        data class Snooze(val minutes: Int) : Command
    }

    private val APOSTROPHES = Regex("['’ʼ‘ʻ`´]")
    private val PUNCTUATION = Regex("[,.!?«»\":;…]")
    private val SPACES = Regex("\\s+")
    private val LEADING_WAKE = Regex("^(?:омни|omni|омникс|omnix)\\s+")

    // Границы слов через lookaround: \b в разных движках по-разному понимает кириллицу.
    private const val L0 = "(?<![\\p{L}\\p{N}])"
    private const val L1 = "(?![\\p{L}\\p{N}])"

    // ---------- «Готово» ----------

    /** Слова, которые можно выкинуть, не меняя смысла: «напоминание выполнено», «it is done». */
    private val DONE_FILLER = setOf(
        "напоминание", "напоминание", "пожалуйста", "все", "всё", "уже", "я", "это", "оно", "мое", "моё",
        "it", "its", "is", "i", "im", "ive", "all", "the", "reminder", "please", "thats", "that", "this",
        "hatırlatma", "hatirlatma", "artık", "artik", "bu", "ben",
        "ýatlatma", "indi", "men", "bu",
        "eslatma", "endi", "men", "bu",
        "еске", "салу", "ескерту", "енді", "мен", "бұл"
    )

    private val DONE_PHRASES = setOf(
        // ru
        "готово", "готов", "сделано", "сделал", "сделала", "выполнено", "выполнил", "выполнила",
        "закончил", "закончила", "завершено", "отметь выполненным", "отметь выполненной", "отметь выполненное",
        "отметь как выполненное", "отметь как выполненный", "отметь как сделанное", "отметь сделанным",
        "отметить выполненным", "закрой", "закрыто",
        // en
        "done", "finished", "completed", "complete", "mark done", "mark as done", "mark as complete",
        "mark as completed", "mark as finished", "mark complete", "mark completed",
        // tk
        "taýýar", "taýyn", "edildi", "ýerine ýetirildi", "ýerine ýetirdim", "gutardym", "etdim",
        "tamamlandy", "bitdi", "tamamlady", "bellik et ýerine ýetirildi",
        // tr
        "tamamlandı", "tamamlandi", "yapıldı", "yapildi", "bitti", "yaptım", "yaptim", "bitirdim",
        "tamamlandı olarak işaretle", "tamamlandı işaretle",
        // uz
        "bajarildi", "bajardim", "tayyor", "tugadi", "qildim", "bitdi", "tamom", "bajarildi deb belgila",
        // kk
        "дайын", "орындалды", "орындадым", "жасадым", "аяқталды", "бітті", "аяқтадым", "орындалды деп белгіле"
    )

    // ---------- «Отложи» ----------

    private val SNOOZE_VERB = Regex(
        L0 + "(?:" +
            // ru
            "отложи(?:ть)?|отлож|перенеси(?:ть)?|напомни\\s+(?:мне\\s+)?(?:еще\\s+раз|ещё\\s+раз|позже|попозже|потом|позднее)|" +
            // en
            "snooze|postpone|remind\\s+me\\s+(?:again\\s+)?(?:later|again)|later|" +
            // tk
            "yza\\s+goý|yza\\s+goy|soňa\\s+goý|sona\\s+goy|soňrak\\s+ýatla|soňrak\\s+ýatlat|ýene\\s+ýatla|" +
            // tr
            "ertele|sonra\\s+(?:tekrar\\s+)?hatırlat|sonra\\s+(?:tekrar\\s+)?hatirlat|tekrar\\s+hatırlat|" +
            // uz
            "keyinga\\s+qoldir|keyinga\\s+surib\\s+qo[yg]|surib\\s+qo[yg]|kechiktir|keyinroq\\s+eslat|" +
            // kk
            "кейінге\\s+қалдыр|кейінірек\\s+еске\\s+сал|кейін\\s+еске\\s+сал|ысыр" +
            ")" + L1
    )

    /** Слова вокруг срока, которые не несут смысла: «отложи ЭТО напоминание НА 10 минут». */
    private val SNOOZE_FILLER = setOf(
        "напоминание", "это", "его", "её", "ее", "мне", "пожалуйста", "на", "через", "ещё", "еще", "его",
        "it", "the", "reminder", "please", "for", "by", "in", "this", "that", "me", "my",
        "hatırlatmayı", "hatırlatma", "hatirlatma", "bunu", "onu", "lütfen", "lutfen", "beni", "bana",
        "ýatlatmany", "ýatlatma", "muny", "bu", "haýyş", "haysy",
        "eslatmani", "eslatma", "buni", "iltimos", "meni",
        "еске", "салуды", "ескертуді", "мұны", "оны", "өтінемін", "маған"
    )

    private const val NUMBER_WORDS =
        "(?:один|одну|одна|пять|десять|пятнадцать|двадцать|тридцать|an?|one|five|ten|fifteen|twenty|thirty|bir|бір)"
    private const val AMOUNT = "(\\d{1,4}|$NUMBER_WORDS)"
    private const val MIN_UNIT =
        "(?:минут\\p{L}*|мин|minutes?|mins?|dakika\\p{L}*|dk|minut\\p{L}*|daqiqa\\p{L}*|дақиқа\\p{L}*)"
    private const val HOUR_UNIT = "(?:час\\p{L}*|hours?|hrs?|saat\\p{L}*|sagat\\p{L}*|soat\\p{L}*|сағат\\p{L}*)"

    private val HALF_HOUR = Regex(
        L0 + "(?:полчаса|half\\s+an?\\s+hour|half\\s+hour|yarım\\s+saat\\p{L}*|yarim\\s+saat\\p{L}*|" +
            "ýarym\\s+sagat\\p{L}*|yarim\\s+soat\\p{L}*|жарты\\s+сағат\\p{L}*)" + L1
    )
    private val ONE_WORD = Regex(L0 + "(час|минуту|минутку)" + L1)
    private val MINUTES = Regex(L0 + AMOUNT + "\\s*" + MIN_UNIT + L1)
    private val HOURS = Regex(L0 + AMOUNT + "\\s*" + HOUR_UNIT + L1)

    private val WORD_VALUES = mapOf(
        "один" to 1, "одну" to 1, "одна" to 1, "a" to 1, "an" to 1, "one" to 1, "bir" to 1, "бір" to 1,
        "пять" to 5, "five" to 5, "десять" to 10, "ten" to 10, "пятнадцать" to 15, "fifteen" to 15,
        "двадцать" to 20, "twenty" to 20, "тридцать" to 30, "thirty" to 30
    )

    /** Срок и кусок фразы, который его назвал (его потом вычёркиваем). */
    private class Duration(val minutes: Int, val range: IntRange)

    private fun findDuration(q: String): Duration? {
        HALF_HOUR.find(q)?.let { return Duration(30, it.range) }
        for ((regex, unit) in listOf(MINUTES to 1, HOURS to 60)) {
            val match = regex.find(q) ?: continue
            val raw = match.groupValues[1]
            val amount = raw.toIntOrNull() ?: WORD_VALUES[raw] ?: continue
            return Duration(amount * unit, match.range)
        }
        // «на час», «на минуту»: без числа, поэтому проверяем после «1 час».
        ONE_WORD.find(q)?.let { return Duration(if (it.groupValues[1] == "час") 60 else 1, it.range) }
        return null
    }

    private fun normalize(text: String): String = text.lowercase()
        .replace(APOSTROPHES, "")
        .replace(PUNCTUATION, " ")
        .replace(SPACES, " ")
        .trim()
        .replace(LEADING_WAKE, "")
        .trim()

    /** Фраза пользователя → «готово» или «отложи»; null — это не ответ на напоминание. */
    fun parse(text: String): Command? {
        val q = normalize(text)
        if (q.isEmpty()) return null
        return parseSnooze(q) ?: parseDone(q)
    }

    private fun parseDone(q: String): Command? {
        val rest = q.split(' ').filter { it !in DONE_FILLER }.joinToString(" ")
        return if (rest in DONE_PHRASES) Command.Done else null
    }

    private fun parseSnooze(q: String): Command? {
        val verb = SNOOZE_VERB.find(q) ?: return null
        // Срок ищем вне глагола: «напомни через 10 минут» не должно превращаться в отложить.
        val outside = q.removeRange(verb.range.first, verb.range.last + 1)
        val duration = findDuration(outside)
        val rest = (if (duration == null) outside else outside.removeRange(duration.range.first, duration.range.last + 1))
            .split(' ')
            .filter { it.isNotEmpty() }
        if (rest.any { it !in SNOOZE_FILLER }) return null
        val minutes = duration?.minutes ?: DEFAULT_SNOOZE_MINUTES
        return if (minutes in 1..MAX_SNOOZE_MINUTES) Command.Snooze(minutes) else null
    }
}
