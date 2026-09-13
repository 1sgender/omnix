package com.jarvis.server.provider

/**
 * Пул API-ключей одного провайдера с ротацией при 429.
 *
 * Мотивация: бесплатные ключи Groq быстро упираются в rate limit;
 * на VPS держим несколько ключей (через запятую в *_API_KEY) и
 * переключаемся на следующий при [ProviderFailureKind.RATE_LIMITED].
 *
 * - round-robin между запросами (нагрузка распределяется сразу);
 * - ключ с 429 уходит в cooldown [cooldownMs] (дефолт 60с);
 * - все в cooldown → всё равно пробуем (честный 429 лучше отказа);
 * - успех снимает cooldown досрочно (лимит могли поднять);
 * - сами ключи НИКОГДА не логируются — только индекс.
 *
 * Thread-safe: провайдеры — синглтоны, пул делят все корутины.
 */
class ApiKeyPool(
    keys: List<String>,
    private val cooldownMs: Long = 60_000,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val keys: List<String> = keys.filter { it.isNotBlank() }
    private val limitedUntil = LongArray(this.keys.size)
    private var cursor = 0

    val size: Int get() = keys.size
    val isEmpty: Boolean get() = keys.isEmpty()

    /**
     * Текущий ключ: первый не в cooldown начиная с курсора.
     * null = пул пуст. Вызов сдвигает курсор (round-robin).
     */
    @Synchronized
    fun current(): String? {
        if (keys.isEmpty()) return null
        val now = clock()
        for (step in keys.indices) {
            val i = (cursor + step) % keys.size
            if (now >= limitedUntil[i]) {
                cursor = (i + 1) % keys.size
                return keys[i]
            }
        }
        // Все в cooldown — пробуем по кругу (честный исход вместо отказа).
        val key = keys[cursor]
        cursor = (cursor + 1) % keys.size
        return key
    }

    /** Индекс ключа для логов (сам ключ не светим). -1 = ключ не из пула. */
    @Synchronized
    fun indexOf(key: String): Int = keys.indexOf(key)

    /** 429 по ключу: в cooldown + курсор на следующий. */
    @Synchronized
    fun reportRateLimited(key: String) {
        val i = keys.indexOf(key)
        if (i < 0) return
        limitedUntil[i] = clock() + cooldownMs
        cursor = (i + 1) % keys.size
    }

    /**
     * Успех: снимаем cooldown досрочно. Курсор НЕ двигаем — его уже
     * сдвинул current() (иначе round-robin пропускал бы ключи).
     */
    @Synchronized
    fun reportSuccess(key: String) {
        val i = keys.indexOf(key)
        if (i < 0) return
        limitedUntil[i] = 0
    }
}
