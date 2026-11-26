package com.omnix.assistant.agent.translator

import com.omnix.assistant.agent.tools.communication.NameTranslit

/**
 * Не даёт синхронному переводу «переводить» собственный голос OMNIX.
 *
 * Если перевод из наушника попал в микрофон, распознаватель языка собеседника (например,
 * английский) превращает русскую речь в похожие по звучанию английские слова:
 * «привет, как дела» → «pre vet cuck della». Сравниваем звучание: у услышанного куска и у
 * недавно сказанного перевода (в латинице) берём костяк согласных и ищем совпадение в окне.
 * Совпало на [threshold] и больше — кусок считается эхом и не переводится.
 */
class EchoGuard(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val threshold: Double = DEFAULT_THRESHOLD,
    private val minSkeleton: Int = DEFAULT_MIN_SKELETON
) {
    private val spoken = ArrayDeque<Pair<Long, String>>()

    @Synchronized
    fun onSpoken(text: String, nowMs: Long) {
        val key = skeleton(latin(text))
        if (key.isNotEmpty()) spoken.addLast(nowMs to key)
        prune(nowMs)
    }

    @Synchronized
    fun isEcho(heard: String, nowMs: Long): Boolean {
        prune(nowMs)
        if (spoken.isEmpty()) return false
        val h = skeleton(heard.lowercase())
        if (h.length < minSkeleton) return false
        return spoken.any { (_, s) -> bestWindowRatio(h, s) >= threshold }
    }

    private fun prune(nowMs: Long) {
        while (spoken.isNotEmpty() && nowMs - spoken.first().first > windowMs) spoken.removeFirst()
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 8_000L
        const val DEFAULT_THRESHOLD = 0.75
        const val DEFAULT_MIN_SKELETON = 5

        private val WORD = Regex("[\\p{L}]+")
        private const val VOWELS = "aeiouy"

        /** Кириллицу в латиницу по словам (латиница остаётся как есть). */
        fun latin(text: String): String =
            WORD.findAll(text.lowercase()).joinToString(" ") { NameTranslit.toLatinDisplay(it.value).lowercase() }

        /**
         * Костяк согласных: близкие по звучанию буквы сведены (c/q/k, w/v, ph/f, z/s, j/zh→j),
         * гласные и повторы выброшены.
         */
        fun skeleton(text: String): String {
            val s = text.lowercase()
                .replace("ph", "f")
                .replace("ck", "k")
                .replace("sh", "s")
                .replace("ch", "c")
                .replace("zh", "j")
                .replace("kh", "h")
            val out = StringBuilder()
            for (ch in s) {
                if (ch !in 'a'..'z' || ch in VOWELS) continue
                val c = when (ch) {
                    'c', 'q' -> 'k'
                    'w' -> 'v'
                    'z' -> 's'
                    'x' -> 'k'
                    else -> ch
                }
                if (out.isEmpty() || out.last() != c) out.append(c)
            }
            return out.toString()
        }

        /** Лучшая доля совпадения [h] с окном [s] длиной ~1,5×|h| (подпоследовательность). */
        fun bestWindowRatio(h: String, s: String): Double {
            if (h.isEmpty() || s.isEmpty()) return 0.0
            val len = minOf(s.length, (h.length * 3 + 1) / 2)
            var best = 0
            for (start in 0..(s.length - len)) {
                best = maxOf(best, lcs(h, s, start, start + len))
                if (best == h.length) break
            }
            return best.toDouble() / h.length
        }

        private fun lcs(a: String, b: String, from: Int, to: Int): Int {
            val m = to - from
            var prev = IntArray(m + 1)
            var cur = IntArray(m + 1)
            for (i in 1..a.length) {
                for (j in 1..m) {
                    cur[j] = if (a[i - 1] == b[from + j - 1]) prev[j - 1] + 1 else maxOf(prev[j], cur[j - 1])
                }
                val t = prev
                prev = cur
                cur = t
                cur.fill(0)
            }
            return prev[m]
        }
    }
}
