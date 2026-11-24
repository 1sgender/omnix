package com.omnix.assistant.voice.stt

import com.omnix.assistant.agent.tools.communication.NameTranslit

/**
 * Пост-коррекция по словарю подсказок для распознавания на телефоне (sherpa-onnx).
 *
 * Почему не «hotwords» в декодере: sherpa-onnx умеет контекстное смещение только у
 * трансдьюсеров с modified_beam_search. Наши модели — CTC (туркменская Omnilingual,
 * русская GigaAM v3), у них hotwords не поддерживаются вовсе; потоковый Vosk (запасной
 * русский) работает жадным поиском и без BPE-словаря подсказок. Поэтому подсказки
 * применяются ПОСЛЕ декодера: слово, которое стоит на месте имени и звучит как имя
 * из телефонной книги (или как название приложения), заменяется на это имя.
 *
 * Чистая функция без Android. Список имён живёт только в памяти устройства и никуда
 * не отправляется. Исправляется строго слот сразу после глагола («позвони …»,
 * «смс …», «открой …»), иначе обычные слова портились бы.
 */
object AsrHintCorrector {

    private const val MIN_WORD_LEN = 4
    private const val LONG_WORD_LEN = 7
    private const val MIN_SKELETON_LEN = 3
    private const val VOWELS = "aeiouyäöü"

    private val CALL_VERBS = setOf(
        "позвони", "позвонить", "набери", "вызови", "звони", "дозвонись", "call", "dial", "ara", "jaň", "zang"
    )
    private val SMS_NOUNS = setOf(
        "смс", "эсэмэс", "сообщение", "sms", "message", "mesaj", "habar", "xabar"
    )
    private val OPEN_VERBS = setOf(
        "открой", "запусти", "включи", "открыть", "запустить", "open", "launch", "start", "aç"
    )
    private val BRIDGE = setOf("для", "кому", "to", "на")

    private enum class Slot { CONTACT, APP }

    /** Слова-кандидаты из имён контактов: каждое слово имени отдельно, без титулов. */
    internal fun contactWords(names: List<String>): List<String> =
        names.asSequence()
            .flatMap { NameTranslit.tokenize(it).asSequence() }
            .filter { it.length >= MIN_WORD_LEN && !NameTranslit.isTitle(it) }
            .distinct()
            .toList()

    /**
     * [contactNames] — полные имена из телефонной книги; [appNames] — названия приложений
     * и команд. Возвращает исправленный текст; если менять нечего, ту же строку.
     */
    fun correct(text: String, contactNames: List<String>, appNames: List<String> = emptyList()): String {
        if (text.isBlank()) return text
        val contacts = contactWords(contactNames)
        val apps = appNames.flatMap { NameTranslit.tokenize(it) }.filter { it.length >= MIN_WORD_LEN }.distinct()
        if (contacts.isEmpty() && apps.isEmpty()) return text

        val parts = text.trim().split(Regex("\\s+"))
        var changed = false
        val out = parts.toMutableList()
        for (i in parts.indices) {
            val slot = slotAt(parts, i) ?: continue
            val (core, tail) = splitTail(parts[i])
            if (core.length < MIN_WORD_LEN) continue
            val pool = if (slot == Slot.CONTACT) contacts else apps
            val replacement = bestMatch(core, pool) ?: continue
            out[i] = restoreCase(core, replacement) + tail
            changed = true
        }
        return if (changed) out.joinToString(" ") else text
    }

    private fun slotAt(parts: List<String>, i: Int): Slot? {
        if (i == 0) return null
        val prev = plain(parts[i - 1])
        if (prev in CALL_VERBS || prev in SMS_NOUNS) return Slot.CONTACT
        if (prev in OPEN_VERBS) return Slot.APP
        if (prev in BRIDGE && i >= 2) {
            val before = plain(parts[i - 2])
            if (before in SMS_NOUNS) return Slot.CONTACT
        }
        return null
    }

    private fun plain(word: String): String = word.trim(',', '.', '!', '?', ':', ';', '«', '»', '"').lowercase()

    private fun splitTail(word: String): Pair<String, String> {
        val core = word.trimEnd(',', '.', '!', '?', ':', ';', '…')
        return core to word.substring(core.length)
    }

    /** Лучшее слово словаря или null: нет похожих, слово уже совпало, либо вариантов несколько. */
    private fun bestMatch(word: String, pool: List<String>): String? {
        val lower = word.lowercase()
        val own = NameTranslit.keys(lower) + NameTranslit.stemKeys(lower)
        // Слово уже есть в словаре (в любом падеже/алфавите): ничего не трогаем.
        if (pool.any { cand -> NameTranslit.keys(cand).any { it in own } }) return null

        var best: String? = null
        var bestDist = Int.MAX_VALUE
        var tie = false
        for (cand in pool) {
            val d = distance(own, NameTranslit.keys(cand)) ?: continue
            when {
                d < bestDist -> {
                    best = cand
                    bestDist = d
                    tie = false
                }
                d == bestDist && best != null && !NameTranslit.sameWord(best, cand) -> tie = true
            }
        }
        return if (tie) null else best
    }

    /** Наименьшее допустимое расстояние между ключами слова и кандидата или null, если не похожи. */
    private fun distance(own: Set<String>, candKeys: Set<String>): Int? {
        var best: Int? = null
        for (a in own) {
            for (b in candKeys) {
                if (a.length < MIN_WORD_LEN - 1 || b.length < MIN_WORD_LEN) continue
                val d = levenshtein(a, b)
                val limit = if (maxOf(a.length, b.length) >= LONG_WORD_LEN) 2 else 1
                val sameSkeleton = skeleton(a).length >= MIN_SKELETON_LEN && skeleton(a) == skeleton(b)
                val ok = d <= limit || (sameSkeleton && d <= 2)
                if (ok && (best == null || d < best)) best = d
            }
        }
        return best
    }

    private fun skeleton(s: String): String = s.filter { it !in VOWELS }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }

    private fun restoreCase(original: String, replacement: String): String =
        if (original.first().isUpperCase()) replacement.replaceFirstChar { it.uppercaseChar() } else replacement
}
