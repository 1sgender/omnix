package com.omnix.assistant.agent.planner

/**
 * Сценарии планировщика. Приоритет = порядок проверки в исходном planForGoal
 * (меньший номер проверялся раньше и выигрывает при множественном совпадении).
 */
enum class ScenarioId(val priority: Int) {
    LEAVING_HOME(1),
    COMING_HOME(2),
    SLEEP(3),
    MORNING(4),
    MEETING(5),
    DRIVING(6),
    POWER_SAVING(7),
    DIAGNOSTICS(8),
    PRESENTATION(9),
    RESET(10)
}

/**
 * Матчер сценариев по ключевым словам (пункт аудита #6 — HIGH).
 *
 * Сценарий — это короткая команда переключения режима телефона («я ухожу», «спокойной ночи»,
 * «подготовь презентацию»), а не любой запрос, где встретилось слово. Раньше ключ искался как
 * кусок строки где угодно, и «отправь мне несколько запоминающихся презентаций…» включало
 * «Режим презентации» с «Не беспокоить» (отчёт владельца 2026-11-26), «персона»/«Сони» давали «сон».
 * Поэтому:
 *  - фраза из одного слова-ключа совпадает с НАЧАЛОМ слова запроса, фраза из нескольких слов —
 *    с началом слова и дальше подряд ([keywordIndex]); короткие корни — только целым словом
 *    ([exactWords]);
 *  - длинный запрос (больше [MAX_SCENARIO_WORDS] слов) и запрос, начинающийся с глагола-просьбы
 *    к ИИ («найди», «отправь», «расскажи»…), сценарием не считаются (кроме диагностики:
 *    «покажи статус телефона»);
 *  - неоднозначные слова — только вместе с командным словом ([compositeConditions]).
 * Побеждает сценарий с наименьшим приоритетом (порядок исходных if).
 */
object ScenarioMatcher {

    /** Ключ (начало слова или фраза с начала слова) → сценарий. */
    private val keywordIndex: Map<String, ScenarioId> = buildMap {
        // 1. «Я ухожу» / «Выхожу из дома» / «На выход»
        put("я ухожу", ScenarioId.LEAVING_HOME)
        put("выхожу", ScenarioId.LEAVING_HOME)
        put("вышел из дома", ScenarioId.LEAVING_HOME)
        put("на выход", ScenarioId.LEAVING_HOME)
        put("ухожу на работу", ScenarioId.LEAVING_HOME)
        put("ухожу из дома", ScenarioId.LEAVING_HOME)
        put("выхожу из офиса", ScenarioId.LEAVING_HOME)

        // 2. «Я пришёл домой» / «Я дома» / «Вернулся»
        put("я пришел", ScenarioId.COMING_HOME)
        put("я пришёл", ScenarioId.COMING_HOME)
        put("я дома", ScenarioId.COMING_HOME)
        put("вернулся домой", ScenarioId.COMING_HOME)
        put("пришёл домой", ScenarioId.COMING_HOME)
        put("пришел домой", ScenarioId.COMING_HOME)
        put("дома уже", ScenarioId.COMING_HOME)

        // 3. «Подготовь ко сну» / «Режим сна» / «Спокойной ночи»
        put("ко сну", ScenarioId.SLEEP)
        put("режим сна", ScenarioId.SLEEP)
        put("спать", ScenarioId.SLEEP)
        put("спокойной ночи", ScenarioId.SLEEP)
        put("ночной режим", ScenarioId.SLEEP)
        put("ложусь", ScenarioId.SLEEP)
        put("засыпа", ScenarioId.SLEEP)

        // 4. «Доброе утро» / «Просыпаюсь» / «Утренний режим»
        put("доброе утро", ScenarioId.MORNING)
        put("просыпа", ScenarioId.MORNING)
        put("утренний режим", ScenarioId.MORNING)
        put("проснулся", ScenarioId.MORNING)
        put("проснулась", ScenarioId.MORNING)

        // 5. «Режим совещания» / «Я на встрече» / «Митинг»
        put("на совещани", ScenarioId.MEETING)
        put("режим совещани", ScenarioId.MEETING)
        put("на встрече", ScenarioId.MEETING)
        put("иду на встречу", ScenarioId.MEETING)
        put("митинг", ScenarioId.MEETING)
        put("на собрании", ScenarioId.MEETING)
        put("переговорн", ScenarioId.MEETING)

        // 6. «Подготовь к поездке» / «Еду на машине» / «Навигация»
        put("в поездк", ScenarioId.DRIVING)
        put("к поездк", ScenarioId.DRIVING)
        put("за рулём", ScenarioId.DRIVING)
        put("за рулем", ScenarioId.DRIVING)
        put("в машин", ScenarioId.DRIVING)
        put("на машин", ScenarioId.DRIVING)
        put("автомобил", ScenarioId.DRIVING)

        // 7. «Режим экономии» / «Батарея садится» / «Экономь заряд»
        put("режим эконом", ScenarioId.POWER_SAVING)
        put("батарея садится", ScenarioId.POWER_SAVING)
        put("мало заряда", ScenarioId.POWER_SAVING)
        put("экономь", ScenarioId.POWER_SAVING)
        put("сэкономь заряд", ScenarioId.POWER_SAVING)
        put("сохрани заряд", ScenarioId.POWER_SAVING)

        // 8. «Статус системы» / «Что с телефоном» / «Диагностика».
        // Общие слова «отчёт», «состояние», «статус» намеренно не используем:
        // они давали ложный сценарий устройства для финансовых/технических текстов.
        put("статус системы", ScenarioId.DIAGNOSTICS)
        put("статус телефона", ScenarioId.DIAGNOSTICS)
        put("состояние системы", ScenarioId.DIAGNOSTICS)
        put("состояние телефона", ScenarioId.DIAGNOSTICS)
        put("системный отчёт", ScenarioId.DIAGNOSTICS)
        put("диагностик", ScenarioId.DIAGNOSTICS)
        put("что с телефоном", ScenarioId.DIAGNOSTICS)
        put("проверь всё", ScenarioId.DIAGNOSTICS)
        put("проверь телефон", ScenarioId.DIAGNOSTICS)

        // 9. «Режим презентации» / «Демо режим» — само слово «презентация» только вместе с
        // командным словом (см. compositeConditions): «найди презентации» — просьба к ИИ.
        put("режим презентац", ScenarioId.PRESENTATION)
        put("демо режим", ScenarioId.PRESENTATION)
        put("демо-режим", ScenarioId.PRESENTATION)

        // 10. «Отмени всё» / «Верни как было» / «Обычный режим» — «отмени будильник» сюда не относится.
        put("отмени всё", ScenarioId.RESET)
        put("отмени все", ScenarioId.RESET)
        put("верни как было", ScenarioId.RESET)
        put("обычный режим", ScenarioId.RESET)
        put("стандартный режим", ScenarioId.RESET)
        put("сброс настроек", ScenarioId.RESET)
        put("сбрось настройки", ScenarioId.RESET)
    }

    /** Короткие слова — только целиком («сон», а не «персона»/«Сони»). */
    private val exactWords: Map<String, ScenarioId> = mapOf(
        "сон" to ScenarioId.SLEEP,
        "еду" to ScenarioId.DRIVING,
        "демо" to ScenarioId.PRESENTATION
    )

    /** Комбинированные условия: оба слова (начала слов) обязаны присутствовать. */
    private val compositeConditions: List<Pair<ScenarioId, Pair<String, String>>> = listOf(
        ScenarioId.MORNING to ("утро" to "режим"),
        ScenarioId.PRESENTATION to ("показ" to "режим"),
        ScenarioId.PRESENTATION to ("подготов" to "презентац"),
        ScenarioId.PRESENTATION to ("включи" to "презентац")
    )

    /** Начало просьбы к ИИ: это вопрос или задание, а не переключение режима. */
    private val CONTENT_VERBS = setOf(
        "найди", "поищи", "отправь", "пришли", "скинь", "расскажи", "напиши", "покажи", "объясни",
        "создай", "придумай", "подбери", "сравни", "проанализируй", "перескажи", "дай", "составь",
        "переведи", "нарисуй", "опиши", "приведи", "посоветуй", "какие", "какой", "какая", "что",
        "почему", "зачем", "как", "где", "кто", "сколько", "send", "find", "show", "tell", "write",
        "explain", "create", "give", "list", "what", "why", "how", "who", "where"
    )

    private const val MAX_SCENARIO_WORDS = 7

    private val SPLIT = Regex("[\\s,.!?;:«»\"()]+")

    /**
     * Находит сценарий по запросу.
     *
     * @return сценарий с наименьшим приоритетом (порядок исходных if) или null.
     */
    fun match(query: String): ScenarioId? {
        val words = query.lowercase().split(SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > MAX_SCENARIO_WORDS) return null
        val joined = words.joinToString(" ")
        val contentRequest = words.first() in CONTENT_VERBS

        var best: ScenarioId? = null
        fun consider(scenario: ScenarioId) {
            if (contentRequest && scenario != ScenarioId.DIAGNOSTICS) return
            val current = best
            if (current == null || scenario.priority < current.priority) best = scenario
        }

        for ((keyword, scenario) in keywordIndex) {
            if (startsAtWord(joined, keyword)) consider(scenario)
        }
        for (word in words) {
            exactWords[word]?.let(::consider)
        }
        for ((scenario, pair) in compositeConditions) {
            if (words.any { it.startsWith(pair.first) } && words.any { it.startsWith(pair.second) }) {
                consider(scenario)
            }
        }
        return best
    }

    /** [keyword] встречается в [text], начиная с начала слова. */
    private fun startsAtWord(text: String, keyword: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(keyword, from)
            if (i < 0) return false
            if (i == 0 || text[i - 1] == ' ') return true
            from = i + 1
        }
    }

    /** Количество ключевых слов (для диагностики/тестов). */
    fun keywordCount(): Int = keywordIndex.size + exactWords.size
}
