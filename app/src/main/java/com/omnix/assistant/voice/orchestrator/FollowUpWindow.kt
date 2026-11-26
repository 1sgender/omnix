package com.omnix.assistant.voice.orchestrator

import android.speech.SpeechRecognizer

/**
 * Окно продолжения разговора: после ответа OMNIX ещё слушает без «Омни».
 *
 * Распознаватель Android закрывает сессию сам, если несколько секунд тихо
 * ([SpeechRecognizer.ERROR_SPEECH_TIMEOUT]) или услышан только шум
 * ([SpeechRecognizer.ERROR_NO_MATCH]). Внутри окна это не повод замолкать — слушаем снова.
 * Остальные ошибки (сеть, микрофон, разрешения) окно закрывают.
 */
object FollowUpWindow {
    fun isQuietError(errorCode: Int): Boolean =
        errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || errorCode == SpeechRecognizer.ERROR_NO_MATCH
}

/**
 * «Это мне?» — фраза в окне продолжения (без «Омни») принимается, только если похожа на
 * обращение к помощнику: вопрос, команда из того, что OMNIX умеет, или продолжение («а…»,
 * «ещё…»). Фраза другому человеку («я сейчас приду», «передай соль») пропускается, и OMNIX
 * снова ждёт «Омни» (владелец 2026-11-26). 6 языков: ru, en, tk, tr, uz, kk.
 *
 * Словесный фильтр, а не смысловой: фразу в форме команды, сказанную другому человеку
 * («позвони ему завтра»), он может принять на себя.
 */
object FollowUpAddressee {

    private val SPLIT = Regex("[\\s,.!;:«»\"()]+")

    /** Вопросительные слова — в начале фразы (первые два слова). */
    private val QUESTION = setOf(
        // ru
        "что", "чё", "где", "когда", "сколько", "почему", "зачем", "кто", "какой", "какая", "какое",
        "какие", "как", "куда", "откуда", "чей", "чья", "можно", "можешь", "сможешь",
        // en
        "what", "where", "when", "how", "why", "who", "which", "whose", "can", "could", "will",
        "would", "is", "are", "do", "does", "should", "did",
        // tk
        "näme", "nirede", "haçan", "näçe", "kim", "nähili", "nädip", "nirä", "haýsy", "näme üçin",
        // tr
        "ne", "nerede", "kaç", "neden", "niye", "hangi", "nasıl", "nereye",
        // uz
        "nima", "qayerda", "qachon", "qancha", "nega", "qanday", "qaysi", "qayerga",
        // kk
        "не", "қайда", "қашан", "қанша", "неге", "кім", "қандай", "қай", "қалай"
    )

    /** Начала глаголов-команд (что умеет OMNIX) — в первых трёх словах. */
    private val COMMAND_STEMS = listOf(
        // ru
        "позвон", "набер", "напиш", "отправ", "открой", "открыв", "закрой", "включ", "выключ", "отключ",
        "постав", "напомн", "найд", "поищ", "покаж", "расскаж", "скаж", "перевед", "переведи", "сделай",
        "запиш", "засек", "удали", "добав", "громче", "тише", "следующ", "предыдущ", "пауз", "продолж",
        "повтор", "отмени", "верни", "прочит", "прочти", "посчит", "запусти", "останов", "вызов", "заведи",
        "разбуди", "проложи", "маршрут", "погода", "время", "будильник", "таймер",
        // en
        "call", "text", "send", "open", "close", "turn", "switch", "set", "remind", "find", "search",
        "show", "tell", "read", "translate", "play", "pause", "resume", "next", "previous", "louder",
        "quieter", "volume", "cancel", "repeat", "start", "navigate", "add", "delete", "write", "message",
        "wake", "weather", "timer", "alarm",
        // tk
        "jaň", "ýaz", "iber", "aç", "ýap", "öçür", "ýatlat", "tap", "görkez", "aýt", "terjime", "oýna",
        "sakla", "dowam", "gaýtala", "indiki", "goýber", "howa", "sagat",
        // tr
        "ara", "yaz", "gönder", "kapat", "kur", "hatırlat", "bul", "göster", "söyle", "çevir", "oynat",
        "durdur", "devam", "tekrar", "sonraki", "hava", "alarm",
        // uz
        "qo'ng'iroq", "qoʻngʻiroq", "yoz", "yubor", "och", "yop", "o'chir", "oʻchir", "eslat", "top",
        "ko'rsat", "koʻrsat", "ayt", "tarjima", "ijro", "to'xtat", "toʻxtat", "davom", "takrorla", "ob-havo",
        // kk
        "қоңырау", "жаз", "жібер", "аш", "жап", "қос", "өшір", "еске", "тап", "көрсет", "айт", "аудар",
        "ойнат", "тоқтат", "жалғастыр", "қайтала", "ауа"
    )

    /** Продолжение предыдущего разговора: «а завтра?», «ещё одну», «and tomorrow». */
    private val CONTINUATION = setOf(
        "а", "и", "ещё", "еще", "также", "тогда", "and", "also", "then", "plus",
        "we", "ýene", "hem", "onda", "ve", "peki", "yana", "va", "ал", "және", "тағы"
    )

    fun isForAssistant(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith("?")) return true
        val words = trimmed.lowercase().split(SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val head2 = words.take(2)
        if (head2.any { it in QUESTION }) return true
        if (words.size >= 2 && words.first() in CONTINUATION) return true
        return words.take(3).any { w -> COMMAND_STEMS.any { w.startsWith(it) } }
    }
}
