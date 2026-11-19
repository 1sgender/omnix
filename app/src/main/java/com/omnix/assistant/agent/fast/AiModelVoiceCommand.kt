package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.decision.AiModelChoice

/**
 * Голосовая смена модели ИИ: «переключись на Pro», «перейди на облако»,
 * «включи автоматический выбор модели», «какая сейчас модель?». Языки:
 * русский, английский, туркменский, турецкий, узбекский, казахский.
 *
 * Распознаём только целую фразу-команду: «расскажи про Flash-память» или
 * «включи флеш» (вспышка) сюда не попадают. Названия сравниваются по списку
 * написаний, потому что распознавание речи пишет их по-разному («про»,
 * «флеш», «флэш», «pro»).
 */
object AiModelVoiceCommand {

    /** Инструмент, который меняет модель (см. AiModelSwitchTool). */
    const val TOOL_ID = "assistant.ai_model"

    /** Значение аргумента `choice` для вопроса «какая сейчас модель?». */
    const val ARG_CURRENT = "current"

    /** Что услышали: сменить модель или спросить, какая включена. */
    sealed interface Command {
        data class Switch(val choice: AiModelChoice) : Command
        data object Ask : Command
    }

    /** Написание названия (в любом падеже и языке) → вариант выбора. */
    private val NAMES: Map<String, AiModelChoice> = listOf(
        AiModelChoice.PRO to listOf("pro", "про"),
        AiModelChoice.CLOUD to listOf(
            "flash", "flesh", "флеш", "флэш", "флеша", "флэша", "флаш", "fleş", "flaş", "fles", "flas",
            "облако", "облаке", "облачную", "облачная", "облачный", "облачной", "cloud",
            "bulut", "bulud", "бұлт", "бұлттық"
        ),
        AiModelChoice.ON_DEVICE to listOf(
            "локальную", "локальная", "локальный", "локальной", "локально", "офлайн", "оффлайн",
            "автономную", "автономная", "local", "device", "on device", "offline",
            "lokal", "yerel", "ýerli", "yerli", "жергілікті"
        ),
        AiModelChoice.AUTO to listOf(
            "авто", "автоматический", "автоматическую", "автоматическая", "автоматом", "автомат",
            "auto", "automatic", "awto", "awtomatik", "awtomat", "otomatik", "otomatiğ", "otomatig",
            "avtomatik", "avto", "автоматты"
        )
    ).flatMap { (choice, names) -> names.map { it to choice } }.toMap()

    private val RU_NOUN = "(?:модель|ии)"

    /** «переключи(сь)/перейди/поставь/смени [модель] на|в <имя> [модель]». */
    private val SWITCH_RU = Regex(
        "^(?:давай )?(?:переключи(?:сь)?|перейди|поставь|смени)(?: (?:модель|режим|ии))? (?:на|в) (\\p{L}+)" +
            "(?: (?:модель|режим|ии))?$"
    )

    /** «включи/выбери/используй <имя> модель»: без слова «модель» не берём («включи флеш» — вспышка). */
    private val USE_RU = Regex("^(?:давай )?(?:включи|выбери|используй|поставь|задействуй) (\\p{L}+) $RU_NOUN$")

    /** «включи автоматический выбор модели», «верни автовыбор». */
    private val AUTO_RU = Regex(
        "^(?:включи|верни|поставь|выбери|давай)(?: обратно)? " +
            "(?:автовыбор|(?:автоматический|автоматическую|автоматом|авто) (?:выбор|подбор))(?: модели| ии)?$"
    )

    /** «ответь/отвечай/думай/будь умнее» — просьба отвечать умной моделью. */
    private val SMARTER = Regex("^(?:(?:ответь|отвечай|думай|будь) (?:по)?умнее|(?:answer|think) smarter)$")

    /** Вопрос «какая сейчас модель?» по-русски. */
    private val ASK_RU = Regex(
        "^(?:(?:какая|какой|что за) сейчас (?:у тебя |у вас )?(?:модель|ии)(?: ии)?|" +
            "(?:какая|какой|что за) (?:у тебя |у вас )?(?:модель|ии)(?: ии)? " +
            "(?:сейчас|включена|выбрана|отвечает|стоит|работает|используется)(?: сейчас)?|" +
            "кто (?:сейчас )?отвечает)$"
    )

    /** Английский: «switch to pro», «switch the model to flash», «use the cloud model». */
    private val SWITCH_EN = Regex(
        "^(?:switch|change|set|go|swap)(?: the)?(?: (?:ai|model))* to (?:the )?((?:on )?\\p{L}+)(?: model)?$"
    )
    private val USE_EN = Regex("^(?:use|select|enable|choose|turn on)(?: the)? ((?:on )?\\p{L}+) model$")
    private val AUTO_EN = Regex(
        "^(?:(?:enable|turn on|use|set|switch to)(?: the)? (?:automatic|auto) model(?: selection| choice| mode)?|" +
            "(?:enable|turn on)(?: the)? automatic (?:model )?(?:selection|choice))$"
    )
    private val ASK_EN = Regex(
        "^(?:(?:what|which) (?:ai )?model (?:are you (?:using|running|on)|do you use|" +
            "is (?:this|it|active|selected|on|enabled|running|answering|being used))" +
            "(?: right now| now| currently)?|" +
            "(?:what|which) model (?:is it )?(?:right now|now|currently)|" +
            "what(?:s| is) the (?:current|active) (?:ai )?model)$"
    )

    /** Падежные окончания после названия: «flaşa», «pro-a», «Proga», «бұлтқа», «Pro-ға». */
    private val SUFFIX = "(?:-?(?:ya|ye|ga|ge|ka|ke|qa|na|ne|a|e|ға|ге|қа|ке|а|е)| (?:ga|ka|qa))?"
    private val NOUN = "(?:(?:model|mod|rejim|režim|seçim|saýlaw|tanlov|модель|режим|таңдау)\\p{L}*)"
    private val VERB = "(?:geç|gec|geçir|gecir|geçiň|geciň|geçiş yap|değiştir|otkaz|otkazing|oting|otish|" +
        "almashtir|almashtiring|ауыстыр|ауыстырыңыз|ауыстыршы|ауыс|көш)"

    /**
     * Туркменский, турецкий, узбекский, казахский: «pro-a geç», «bulut modeline geç»,
     * «Pro ga o'tkaz», «бұлтқа ауыстыр». Имя ленивое, чтобы отрезать падежное окончание.
     */
    private val SWITCH_TURKIC = Regex("^(\\p{L}+?)$SUFFIX(?: $NOUN)? $VERB$")

    /** «Какая сейчас модель?» по-туркменски, по-турецки, по-узбекски, по-казахски. */
    private val ASK_TURKIC = Regex(
        "^(?:(?:häzir|şu wagt) (?:haýsy|haysy) (?:model|ii)|" +
            "(?:haýsy|haysy) (?:model|ii) (?:häzir|şu wagt|işleýär|saýlanan|açyk|goşulan|ulanylýar)(?: häzir)?|" +
            "(?:şu an|şimdi|şuan) hangi (?:model|yapay zeka)|" +
            "hangi (?:model|yapay zeka) (?:şu an|şimdi|açık|seçili|aktif|kullanılıyor|yanıtlıyor|" +
            "cevap veriyor|çalışıyor)(?: şu an)?|" +
            "hozir qaysi (?:model|ai)|" +
            "qaysi (?:model|ai) (?:hozir|ishlayapti|tanlangan|yoqilgan|javob beryapti|ishlatilyapti)(?: hozir)?|" +
            "қазір (?:қандай|қай) модель|" +
            "(?:қандай|қай) модель (?:қазір|қосулы|таңдалған|жауап беріп тұр|жұмыс істеп тұр))$"
    )

    private val LEADING_WAKE = Regex("^(?:омни|omni|омникс|omnix)\\s+")
    private val APOSTROPHES = Regex("['’ʼ‘ʻ`´]")
    private val PUNCTUATION = Regex("[,.!?«»\":;…]")
    private val SPACES = Regex("\\s+")

    /** Фраза пользователя → команда смены модели или вопрос о ней; null — это не про модель. */
    fun parse(text: String): Command? {
        val q = text.lowercase()
            .replace(APOSTROPHES, "")
            .replace(PUNCTUATION, " ")
            .replace(SPACES, " ")
            .trim()
            .replace(LEADING_WAKE, "")
        if (q.isEmpty()) return null
        if (ASK_RU.matches(q) || ASK_EN.matches(q) || ASK_TURKIC.matches(q)) return Command.Ask
        if (SMARTER.matches(q)) return Command.Switch(AiModelChoice.PRO)
        if (AUTO_RU.matches(q) || AUTO_EN.matches(q)) return Command.Switch(AiModelChoice.AUTO)
        val name = listOf(SWITCH_RU, USE_RU, SWITCH_EN, USE_EN, SWITCH_TURKIC)
            .firstNotNullOfOrNull { it.matchEntire(q)?.groupValues?.get(1) }
            ?: return null
        return NAMES[name]?.let { Command.Switch(it) }
    }
}
