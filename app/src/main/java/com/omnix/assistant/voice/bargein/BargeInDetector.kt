package com.omnix.assistant.voice.bargein

/**
 * Решает, перебивает ли человек OMNIX, пока тот говорит. На вход — громкость кадров
 * микрофона по 20 мс (дБ относительно полной шкалы).
 *
 * Микрофон слышит и собственный голос OMNIX (эхо, даже после эхоподавления). Поэтому:
 *  - первые [calibrationFrames] кадров только запоминается, как громко звучит эхо;
 *  - «уровень эха» — 90-й процентиль громкости за последние [floorWindow] кадров
 *    (пики голоса OMNIX, а не паузы между словами);
 *  - кадр «похож на человека», если он громче уровня эха на [marginDb] и громче [absoluteMinDb];
 *    такие кадры в уровень эха не попадают, иначе голос человека поднял бы порог сам себе;
 *  - перебивание — когда в последних [decisionFrames] кадрах похожих на человека не меньше
 *    [requiredFrames] (около 0,3 с речи подряд), а не один щелчок.
 */
class BargeInDetector(
    private val calibrationFrames: Int = 20,
    private val floorWindow: Int = 75,
    private val marginDb: Float = 9f,
    private val absoluteMinDb: Float = -42f,
    private val decisionFrames: Int = 15,
    private val requiredFrames: Int = 12
) {
    private val floorLevels = ArrayDeque<Float>()
    private val recent = ArrayDeque<Boolean>()
    private var seen = 0

    /** @return true — человек перебивает (сработать один раз и сбросить [reset]). */
    fun accept(levelDb: Float): Boolean {
        seen++
        if (seen <= calibrationFrames) {
            remember(levelDb)
            return false
        }
        val threshold = maxOf(echoLevel() + marginDb, absoluteMinDb)
        val human = levelDb > threshold
        if (!human) remember(levelDb)
        recent.addLast(human)
        while (recent.size > decisionFrames) recent.removeFirst()
        return recent.size == decisionFrames && recent.count { it } >= requiredFrames
    }

    fun reset() {
        floorLevels.clear()
        recent.clear()
        seen = 0
    }

    private fun remember(levelDb: Float) {
        floorLevels.addLast(levelDb)
        while (floorLevels.size > floorWindow) floorLevels.removeFirst()
    }

    private fun echoLevel(): Float {
        if (floorLevels.isEmpty()) return SILENCE_DB
        val sorted = floorLevels.sorted()
        val index = ((sorted.size - 1) * PERCENTILE).toInt()
        return sorted[index]
    }

    private companion object {
        const val PERCENTILE = 0.9
        const val SILENCE_DB = -120f
    }
}

/**
 * Откуда продолжить ответ, если перебивание оказалось ложным (эхо, шум, тишина):
 * с начала предложения, на котором OMNIX остановился, — как человек, который
 * повторяет начатую фразу.
 *
 * Позиция оценивается по времени речи: синтезатор не сообщает, какое слово сейчас звучит.
 */
object ResumePoint {
    /** Примерно столько символов в секунду произносит синтезатор при скорости 1.0. */
    const val CHARS_PER_SECOND = 14f

    private val SENTENCE_END = Regex("[.!?…]+\\s+")

    /**
     * @param spokenMs сколько миллисекунд ответ уже звучал.
     * @return остаток ответа с начала текущего предложения; пусто — ответ уже почти договорён.
     */
    fun remainder(text: String, spokenMs: Long, speechRate: Float = 1f): String {
        if (text.isBlank()) return ""
        val position = (spokenMs / 1000f * CHARS_PER_SECOND * speechRate.coerceAtLeast(0.1f)).toInt()
        if (position >= text.length) return ""
        var start = 0
        for (m in SENTENCE_END.findAll(text)) {
            val next = m.range.last + 1
            if (next > position) break
            start = next
        }
        return text.substring(start).trim()
    }
}

/** «Стоп», сказанное, чтобы перебить OMNIX: он замолкает и ждёт продолжения, без нового запроса. */
object BargeInPhrases {
    private val STOP = setOf(
        "стоп", "хватит", "помолчи", "замолчи", "тихо", "погоди", "подожди", "достаточно", "понял", "ладно",
        "stop", "enough", "wait", "hold on", "quiet", "ok stop", "okay stop",
        "bes", "boldy", "sakla", "garaş",
        "dur", "yeter", "bekle", "sus",
        "bas", "to'xta", "toʻxta", "yetarli",
        "тоқта", "жетер", "күте тұр"
    )

    fun isStop(text: String): Boolean {
        val q = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        if (q in STOP) return true
        val words = q.split(Regex("[\\s,]+")).filter { it.isNotEmpty() }
        return words.size <= 2 && words.isNotEmpty() && words.all { it in STOP || it == "омни" || it == "omni" }
    }
}
