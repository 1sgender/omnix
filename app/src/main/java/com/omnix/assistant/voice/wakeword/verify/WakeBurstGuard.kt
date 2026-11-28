package com.omnix.assistant.voice.wakeword.verify

/**
 * Умная защита от серии ложных срабатываний: если за [WINDOW_MS] было [MAX_UNCONFIRMED] и больше
 * срабатываний, после которых человек ничего не сказал, то на [STRICT_FOR_MS] вторая ступень
 * становится строгой (принимает только «омни»-подобные слова). Настоящая команда сбрасывает серию.
 *
 * Время передаётся снаружи (elapsedRealtime), чтобы класс проверялся JVM-тестами.
 */
class WakeBurstGuard {

    private val unconfirmed = ArrayDeque<Long>()
    private var strictUntilMs = 0L

    @Synchronized
    fun onUnconfirmed(nowMs: Long) {
        unconfirmed.addLast(nowMs)
        while (unconfirmed.isNotEmpty() && nowMs - unconfirmed.first() > WINDOW_MS) unconfirmed.removeFirst()
        if (unconfirmed.size >= MAX_UNCONFIRMED) strictUntilMs = nowMs + STRICT_FOR_MS
    }

    @Synchronized
    fun onConfirmed() {
        unconfirmed.clear()
        strictUntilMs = 0L
    }

    @Synchronized
    fun strict(nowMs: Long): Boolean = nowMs < strictUntilMs

    companion object {
        const val WINDOW_MS = 5 * 60_000L
        const val MAX_UNCONFIRMED = 3
        const val STRICT_FOR_MS = 10 * 60_000L
    }
}
