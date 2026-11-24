package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.tools.communication.NameTranslit

/**
 * Разбор голосовых/текстовых команд «отправь SMS» на шести языках (ru, en, tr, tk, uz, kk).
 * Чистая логика без Android. Текст сообщения сохраняет исходный регистр.
 *
 * Примеры: «отправь смс Осману привет», «отправь смс усма» (без текста),
 * «смс маме: буду через час», «напиши сообщение маме», «sms ber Osmana»,
 * «Osman'a SMS gönder», «Osmanga sms yubor», «Османға sms жібер», «text Osman hello».
 */
object SmsCommandParser {

    /** [recipient] == null — не названо, кому; [message] == null — нет текста. */
    data class SmsIntent(val recipient: String?, val message: String?)

    private val OPTIONS = setOf(RegexOption.IGNORE_CASE)

    private const val VERB_PRE =
        "отправь(?:те)?|отправить|пошли(?:те)?|послать|напиши(?:те)?|написать|кинь|скинь|отошли|шли|" +
            "черкани|набросай|send|write|shoot|compose|drop"
    private const val VERB_POST =
        "отправь(?:те)?|пошли(?:те)?|напиши(?:те)?|кинь|скинь|отошли|" +
            "gönder|yolla|yaz|at|iber|ugrat|ýaz|ber|yubor|jo['‘’ʻ]nat|yoz|жібер|жаз|салшы|send"
    private const val NOUN_PRE =
        "смс(?:-?ку)?|эсэмэс(?:ку)?|сообщение|сообщеньице|месседж|text\\s+message|sms|message|msg|mesaj|habar|xabar|хабарлама"
    private const val NOUN_FIRST =
        "смс(?:-?ку)?|эсэмэс(?:ку)?|сообщение|text\\s+message|sms|mesaj|habar|xabar|хабарлама"
    private const val SEP = "(?=\\s|$|[:,])"
    private const val GENERIC_NOUNS = "сообщение|сообщеньице|месседж|text\\s+message|message|msg|mesaj|habar|xabar|хабарлама"

    private val preRegex = Regex(
        "^(?:$VERB_PRE)\\s+(?:мне\\s+|me\\s+)?(?:(?:an?|the|новое|одно|одну)\\s+)?($NOUN_PRE)$SEP\\s*(.*)$",
        OPTIONS
    )
    private val textVerbRegex = Regex("^text\\s+(?!message\\b|msg\\b)(.+)$", OPTIONS)
    private val nounFirstRegex = Regex(
        "^($NOUN_FIRST)$SEP\\s*(?:(?:$VERB_POST)(?=\\s|$|[:,])\\s*)?(.*)$",
        OPTIONS
    )
    private val whoFirstRegex = Regex(
        "^(\\S+?)\\s+(?:$NOUN_FIRST)\\s+(?:$VERB_POST)(?:\\s*[:,]?\\s*(.+))?$",
        OPTIONS
    )

    private val polite = Regex("^(?:пожалуйста|please|lütfen|bolsa|илтимос|өтінемін)[,\\s]+", OPTIONS)
    private val otherMessenger = Regex(
        "^(?:в|во|через|via|in|on|на)\\s+(?:телеграм|telegram|whatsapp|ватсап|вотсап|вацап|вайбер|viber|инст|instagram|почт|email|e-mail|вк|vk|discord|слак|slack)",
        OPTIONS
    )
    private val notRecipient = Regex("^(?:от|from|про|about|за)(?:\\s|$)", OPTIONS)
    private val preposition = Regex("^(?:для|кому|to|на\\s+номер|на)\\s+", OPTIONS)
    private val textMarker = Regex(
        "^(?:с\\s+текстом|текст|что|saying|that\\s+says|that|with\\s+(?:the\\s+)?(?:text|message)|diye|деп)\\s*:?\\s+",
        OPTIONS
    )
    private val numberToken = Regex("^[+\\d][\\d()\\-]*$")

    fun parse(raw: String): SmsIntent? {
        var s = raw.trim().trim('"', '«', '»')
        s = s.replace(Regex("^(?:джарвис|omnix|жарвис)[,\\s]*", OPTIONS), "").trim()
        s = polite.replace(s, "").trim()
        if (s.isEmpty()) return null

        preRegex.find(s)?.let { m ->
            val noun = m.groupValues[1]
            val rest = m.groupValues[2]
            return build(rest, requireRecipient = Regex("^(?:$GENERIC_NOUNS)$", OPTIONS).matches(noun))
        }
        textVerbRegex.find(s)?.let { return build(it.groupValues[1], requireRecipient = true) }
        nounFirstRegex.find(s)?.let { return build(it.groupValues[2], requireRecipient = true, forbidFrom = true) }
        whoFirstRegex.find(s)?.let { m ->
            val who = m.groupValues[1].trim('\'', '‘', '’')
            val msg = m.groupValues[2].trim().let { textMarker.replace("$it ", "").trim() }
            return SmsIntent(who, msg.ifEmpty { null })
        }
        return null
    }

    private fun build(restRaw: String, requireRecipient: Boolean, forbidFrom: Boolean = false): SmsIntent? {
        var rest = restRaw.trim()
        if (otherMessenger.containsMatchIn(rest)) return null
        if (forbidFrom && notRecipient.containsMatchIn(rest)) return null
        rest = preposition.replace(rest, "").trim()
        if (rest.isEmpty()) return if (requireRecipient) null else SmsIntent(null, null)

        val delim = rest.indexOfFirst { it == ':' || it == ',' }
        if (delim >= 0) {
            val who = cleanWho(rest.substring(0, delim))
            val msg = cleanMessage(rest.substring(delim + 1), stripMarker = rest[delim] == ',')
            return if (who.isEmpty()) null else SmsIntent(who, msg)
        }
        val tokens = rest.split(Regex("\\s+")).filter { it.isNotEmpty() }
        var take = 1
        if (numberToken.matches(tokens[0])) {
            while (take < tokens.size && numberToken.matches(tokens[take])) take++
        } else if (tokens.size > 1 && NameTranslit.isTitle(tokens[0].lowercase())) {
            take = 2
        }
        val who = cleanWho(tokens.take(take).joinToString(" "))
        val msg = cleanMessage(tokens.drop(take).joinToString(" "))
        return if (who.isEmpty()) null else SmsIntent(who, msg)
    }

    private fun cleanWho(s: String): String = s.trim().trim('"', '«', '»', '\'', '.', '!', '?').trim()

    private fun cleanMessage(s: String, stripMarker: Boolean = true): String? {
        val t = if (stripMarker) textMarker.replace(s.trim() + " ", "").trim() else s.trim()
        val unquoted = t.trim('"', '«', '»').trim()
        return unquoted.ifEmpty { null }
    }
}
