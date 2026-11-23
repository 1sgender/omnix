package com.omnix.assistant.agent.tools.communication

enum class PhoneKind(val labelRu: String) {
    MOBILE("мобильный"),
    WORK("рабочий"),
    HOME("домашний"),
    OTHER("другой")
}

data class PhoneEntry(
    val number: String,
    val kind: PhoneKind = PhoneKind.OTHER,
    val isPrimary: Boolean = false
)

/** Одна запись телефонной книги (контакт Android) в виде, не зависящем от Android. */
data class ContactEntry(
    val id: String,
    val displayName: String,
    val phones: List<PhoneEntry>,
    /** Прозвище, организация и т.п. — сопоставляются с пониженным весом. */
    val extraNames: List<String> = emptyList(),
    val starred: Boolean = false
)

/** Человек: один или несколько дублирующихся контактов (например, «Осман» и «Osman»). */
data class PersonMatch(
    val displayName: String,
    val score: Int,
    val starred: Boolean,
    val phones: List<PhoneEntry>,
    /** Самое слабое совпадение слова запроса (до штрафов): < SCORE_STEM — неполное слово. */
    val weakest: Int = ContactMatcher.SCORE_EXACT
)

sealed interface MatchOutcome {
    data class Single(val displayName: String, val phone: PhoneEntry) : MatchOutcome
    data class MultiplePeople(val people: List<PersonMatch>) : MatchOutcome
    data class MultipleNumbers(val displayName: String, val phones: List<PhoneEntry>) : MatchOutcome
    object None : MatchOutcome
}

/**
 * Поиск человека в списке контактов по любой части имени, на любом алфавите.
 * Чистая логика без Android, покрыта юнит-тестами.
 */
object ContactMatcher {

    const val SCORE_EXACT = 100
    const val SCORE_STEM = 90
    const val SCORE_PREFIX = 60
    const val SCORE_FUZZY = 30

    private const val COVERAGE_BONUS = 20
    private const val EXTRA_NAME_PENALTY = 15
    private const val CLOSE_MARGIN = 5
    private const val MIN_PREFIX_LEN = 3
    private const val MIN_FUZZY_LEN = 5
    private const val MAX_AMBIGUOUS = 3
    private const val VOWELS = "aeiou"

    private val HINT_PREFIXES: Map<PhoneKind, List<String>> = mapOf(
        PhoneKind.MOBILE to listOf("mobil", "sotov", "cell"),
        PhoneKind.WORK to listOf("rabo", "work", "ofis", "office"),
        PhoneKind.HOME to listOf("domaS", "home")
    )

    private class QueryToken(val word: String) {
        val keys: Set<String> = NameTranslit.keys(word)
        val stemKeys: Set<String> = NameTranslit.stemKeys(word)
    }

    private class Parsed(val tokens: List<QueryToken>, val hint: PhoneKind?)

    private class Candidate(val keys: Set<String>, val isExtra: Boolean)

    private class Scored(val contact: ContactEntry, val score: Int, val nameKeys: List<Set<String>>, val weakest: Int)

    private fun hintOf(token: String): PhoneKind? {
        val keys = NameTranslit.keys(token)
        return HINT_PREFIXES.entries.firstOrNull { (_, prefixes) ->
            keys.any { k -> prefixes.any { k.startsWith(it) } }
        }?.key
    }

    private fun parse(query: String): Parsed {
        var words = NameTranslit.tokenize(query)
        var hint: PhoneKind? = null
        val withoutHints = words.filter { hintOf(it) == null }
        if (withoutHints.isNotEmpty() && withoutHints.size < words.size) {
            hint = words.firstNotNullOfOrNull { hintOf(it) }
            words = withoutHints
        }
        val withoutTitles = words.filter { !NameTranslit.isTitle(it) }
        if (withoutTitles.isNotEmpty()) words = withoutTitles
        return Parsed(words.map { QueryToken(it) }, hint)
    }

    /** Значимые слова имени: без титулов и пометок вида «(работа)», если что-то остаётся. */
    private fun significantTokens(name: String): List<String> {
        val all = NameTranslit.tokenize(name)
        val significant = all.filter { !NameTranslit.isTitle(it) && hintOf(it) == null }
        return significant.ifEmpty { all }
    }

    private fun tokenScore(q: QueryToken, keys: Set<String>, includeFuzzy: Boolean): Int {
        if (q.keys.any { it in keys }) return SCORE_EXACT
        for (stem in q.stemKeys) {
            for (k in keys) {
                if (stem.length >= 2 && k.startsWith(stem) && k.length - stem.length <= 1 &&
                    (k.length == stem.length || k.last() in VOWELS)
                ) {
                    return SCORE_STEM
                }
            }
        }
        for (stem in q.stemKeys + q.keys) {
            if (stem.length >= MIN_PREFIX_LEN && keys.any { it.startsWith(stem) }) return SCORE_PREFIX
        }
        if (includeFuzzy) {
            for (qk in q.keys) {
                if (qk.length >= MIN_FUZZY_LEN && keys.any { it.length >= MIN_FUZZY_LEN && withinOneEdit(qk, it) }) {
                    return SCORE_FUZZY
                }
            }
        }
        return 0
    }

    private fun withinOneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        if (i == a.length || i == b.length) return true
        return when {
            a.length == b.length -> a.substring(i + 1) == b.substring(i + 1)
            a.length > b.length -> a.substring(i + 1) == b.substring(i)
            else -> a.substring(i) == b.substring(i + 1)
        }
    }

    private fun scoreContact(q: List<QueryToken>, c: ContactEntry, includeFuzzy: Boolean): Scored? {
        val nameKeys = significantTokens(c.displayName).map { NameTranslit.keys(it) }
        val candidates = nameKeys.map { Candidate(it, false) } +
            c.extraNames.flatMap { NameTranslit.tokenize(it) }.map { Candidate(NameTranslit.keys(it), true) }
        if (candidates.isEmpty() || q.isEmpty()) return null
        val used = BooleanArray(candidates.size)
        var total = 0
        var weakest = SCORE_EXACT
        for (qt in q) {
            var best = -1
            var bestScore = 0
            for (i in candidates.indices) {
                if (used[i]) continue
                val s = tokenScore(qt, candidates[i].keys, includeFuzzy) -
                    (if (candidates[i].isExtra) EXTRA_NAME_PENALTY else 0)
                if (s > bestScore) {
                    bestScore = s
                    best = i
                }
            }
            if (best < 0) return null
            used[best] = true
            total += bestScore
            weakest = minOf(weakest, bestScore + (if (candidates[best].isExtra) EXTRA_NAME_PENALTY else 0))
        }
        var score = total / q.size
        if (nameKeys.indices.all { used[it] }) score += COVERAGE_BONUS
        return Scored(c, score, nameKeys, weakest)
    }

    private fun samePerson(a: Scored, b: Scored): Boolean {
        if (a.contact.id == b.contact.id) return true
        if (a.nameKeys.isEmpty() || a.nameKeys.size != b.nameKeys.size) return false
        val used = BooleanArray(b.nameKeys.size)
        for (ka in a.nameKeys) {
            val j = b.nameKeys.indices.firstOrNull { !used[it] && b.nameKeys[it].any { k -> k in ka } } ?: return false
            used[j] = true
        }
        return true
    }

    private fun digits(number: String) = number.filter { it.isDigit() }

    private fun toPerson(group: List<Scored>): PersonMatch {
        val top = group.maxByOrNull { it.score }!!
        val phones = LinkedHashMap<String, PhoneEntry>()
        for (s in group.sortedByDescending { it.score }) {
            for (p in s.contact.phones) phones.putIfAbsent(digits(p.number), p)
        }
        return PersonMatch(
            displayName = top.contact.displayName,
            score = top.score,
            starred = group.any { it.contact.starred },
            phones = phones.values.toList(),
            weakest = top.weakest
        )
    }

    /** Ранжированный список людей, подходящих под запрос (дубли в двух алфавитах склеены). */
    fun rank(query: String, contacts: List<ContactEntry>, includeFuzzy: Boolean = false): List<PersonMatch> =
        rankParsed(parse(query), contacts, includeFuzzy)

    private fun rankParsed(parsed: Parsed, contacts: List<ContactEntry>, includeFuzzy: Boolean): List<PersonMatch> {
        if (parsed.tokens.isEmpty()) return emptyList()
        val scored = contacts.mapNotNull { scoreContact(parsed.tokens, it, includeFuzzy) }
        val groups = mutableListOf<MutableList<Scored>>()
        for (s in scored) {
            val group = groups.firstOrNull { samePerson(it.first(), s) }
            if (group != null) group += s else groups += mutableListOf(s)
        }
        return groups.map { toPerson(it) }
            .filter { it.phones.isNotEmpty() }
            .sortedWith(compareByDescending<PersonMatch> { it.score }.thenByDescending { it.starred }.thenBy { it.displayName })
    }

    /** Номер по умолчанию: основной, затем мобильный, затем первый. */
    fun defaultPhone(person: PersonMatch): PhoneEntry =
        person.phones.firstOrNull { it.isPrimary } ?: person.phones.firstOrNull { it.kind == PhoneKind.MOBILE }
            ?: person.phones.first()

    /** Решение для звонка/SMS: однозначный номер, выбор человека или выбор номера. */
    fun decide(query: String, contacts: List<ContactEntry>): MatchOutcome {
        val parsed = parse(query)
        val people = rankParsed(parsed, contacts, includeFuzzy = false)
        if (people.isEmpty()) return MatchOutcome.None
        val top = people.first()
        val close = people.filter { it.score >= top.score - CLOSE_MARGIN }
        val winner = when {
            close.size == 1 -> top
            else -> close.singleOrNull { it.starred }
        } ?: return MatchOutcome.MultiplePeople(close.take(MAX_AMBIGUOUS))
        // Слово запроса совпало лишь началом («усма» → «Usman»): не угадываем, а переспрашиваем.
        if (winner.weakest < SCORE_STEM) return MatchOutcome.MultiplePeople(listOf(winner))
        return pickNumber(winner, parsed.hint)
    }

    private fun pickNumber(person: PersonMatch, hint: PhoneKind?): MatchOutcome {
        val byHint = hint?.let { h -> person.phones.filter { it.kind == h } }.orEmpty()
        val pool = byHint.ifEmpty { person.phones }
        if (pool.size == 1) return MatchOutcome.Single(person.displayName, pool.single())
        val chosen = pool.singleOrNull { it.isPrimary }
            ?: if (hint == null) pool.singleOrNull { it.kind == PhoneKind.MOBILE } else null
        return if (chosen != null) {
            MatchOutcome.Single(person.displayName, chosen)
        } else {
            MatchOutcome.MultipleNumbers(person.displayName, pool)
        }
    }

    /** Результат для ContactsTool: пары «имя — номер», по людям в порядке ранга. */
    fun search(query: String, contacts: List<ContactEntry>, limit: Int): List<Pair<String, String>> =
        rank(query, contacts, includeFuzzy = true)
            .flatMap { p -> p.phones.map { p.displayName to it.number } }
            .take(limit)
}

/** Преобразование результата в ContactResolution с понятными для пользователя вариантами. */
fun MatchOutcome.toResolution(query: String): ContactResolution = when (this) {
    is MatchOutcome.None -> ContactResolution.NotFound(query)
    is MatchOutcome.Single -> ContactResolution.Resolved(phone.number, displayName.takeIf { it.isNotBlank() })
    is MatchOutcome.MultiplePeople -> ContactResolution.Ambiguous(
        people.map { it.displayName to ContactMatcher.defaultPhone(it).number }
    )
    is MatchOutcome.MultipleNumbers -> ContactResolution.Ambiguous(
        phones.take(3).map { "$displayName (${it.kind.labelRu})" to it.number }
    )
}

/** Текст для пользователя, когда получателя нельзя выбрать молча. */
fun ambiguityMessage(candidates: List<Pair<String, String>>): String =
    if (candidates.size == 1) {
        val (name, number) = candidates.single()
        "Вы имели в виду «$name» ($number)? Назовите полное имя контакта или номер, чтобы я не ошибся."
    } else {
        "Нашёл несколько вариантов: " + candidates.joinToString("; ") { (name, number) -> "$name ($number)" } +
            ". Кому именно? Назовите полное имя, фамилию или номер."
    }
