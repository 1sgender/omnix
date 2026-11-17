package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.decision.AiModelChoice

/**
 * Голосовая смена модели ИИ: «переключись на Pro», «перейди на Flash»,
 * «ответь умнее». Распознаём только целую фразу-команду: «расскажи про
 * Flash-память» или «включи флеш» (вспышка) сюда не попадают.
 *
 * Распознавание речи пишет названия по-разному («про», «флеш», «флэш»,
 * «pro»), поэтому названия сравниваются по списку написаний.
 */
object AiModelVoiceCommand {

    /** Инструмент, который меняет модель (см. AiModelSwitchTool). */
    const val TOOL_ID = "assistant.ai_model"

    private val PRO_NAMES = setOf("pro", "про")
    private val FLASH_NAMES = setOf("flash", "флеш", "флэш", "флеша", "флэша", "флаш", "fleş", "flaş", "fles", "flas")

    /** «переключи(сь)/перейди/поставь/смени [модель|режим] на <имя> [модель|режим]». */
    private val SWITCH = Regex(
        "^(?:давай\\s+)?(?:переключи(?:сь)?|перейди|поставь|смени)" +
            "(?:\\s+(?:модель|режим|ии))?\\s+на\\s+(\\p{L}+)(?:\\s+(?:модель|режим))?$"
    )

    /** «ответь/отвечай/думай/будь умнее» — просьба отвечать умной моделью. */
    private val SMARTER = Regex("^(?:ответь|отвечай|думай|будь)\\s+(?:по)?умнее$")

    /** Английский: «switch to pro», «switch the model to flash». */
    private val SWITCH_EN = Regex("^(?:switch|change)\\s+(?:the\\s+model\\s+)?to\\s+([a-z]+)$")

    /** Туркменский: «pro-a geç», «flaşa geç». Имя ленивое, чтобы отрезать падежный суффикс. */
    private val SWITCH_TK = Regex("^(\\p{L}+?)(?:-?(?:a|e|ga|ge|ka|ke))?\\s+(?:geç|gec|geçir|gecir)$")

    private val LEADING_WAKE = Regex("^(?:омни|omni|омникс|omnix)\\s+")

    /** Фраза пользователя → выбор модели или null, если это не команда смены модели. */
    fun parse(text: String): AiModelChoice? {
        val q = text.lowercase()
            .replace(Regex("[,.!?«»\"]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .replace(LEADING_WAKE, "")
        if (q.isEmpty()) return null
        if (SMARTER.matches(q)) return AiModelChoice.PRO
        val name = SWITCH.matchEntire(q)?.groupValues?.get(1)
            ?: SWITCH_EN.matchEntire(q)?.groupValues?.get(1)
            ?: SWITCH_TK.matchEntire(q)?.groupValues?.get(1)
            ?: return null
        return when (name) {
            in PRO_NAMES -> AiModelChoice.PRO
            in FLASH_NAMES -> AiModelChoice.CLOUD
            else -> null
        }
    }
}
