package com.omnix.assistant.agent.localai

/**
 * Хватит ли памяти, чтобы загрузить локальную модель.
 *
 * `availMem` — память, свободная в эту секунду. Android держит в ней фоновые приложения и
 * освобождает её, когда память нужна, поэтому на телефоне с 12 ГБ «свободно» часто 2–3 ГБ.
 * Прежняя проверка `availMem >= нужно` отказывала Qwen 1.5B (нужно 3 ГБ) на S24 Ultra
 * (журнал 2026-11-27: available 2943 MB < 3072 MB) — «Локально» не включалось.
 *
 * Теперь: хватает свободной — да; памяти всего с большим запасом ([TOTAL_HEADROOM] × нужного) и
 * система не сообщает о нехватке — тоже да, Android освободит фоновые приложения. Нативный сбой
 * при загрузке и так перехватывается отдельно (повтор для того же файла запрещён).
 */
object LocalMemoryCheck {

    const val TOTAL_HEADROOM = 3

    fun enough(totalMb: Long, availableMb: Long, lowMemory: Boolean, requiredMb: Long): Boolean {
        if (availableMb >= requiredMb) return true
        if (lowMemory) return false
        return totalMb >= requiredMb * TOTAL_HEADROOM
    }
}
