package com.omnix.assistant.voice.wakeword

import java.security.MessageDigest

/**
 * Пининг wake-word моделей по sha256 (owner review 2026-09-26, п.7).
 *
 * `SHA256SUMS` в assets — это контроль БUILDA (его можно подменить вместе с
 * моделями). Настоящий пин — в КОДЕ: при подмене/повреждении ONNX-файла
 * движок падает с явной [WakeWordEngineError.ModelCorrupted], а не тихо
 * грузит «что попало». Дайджесты совпадают с `assets/wakeword/SHA256SUMS`
 * (тест ModelDigestsTest держит оба списка синхронными).
 *
 * Чистый Kotlin — покрыт JVM-тестами.
 */
object ModelDigests {

    /** Ожидаемые sha256 ассетов wake word (состояние main на 2026-09-26). */
    val EXPECTED: Map<String, String> = mapOf(
        "wakeword/omni_v0.1.onnx" to "aa8cf96faf3246b1790e16747ddf57da8807e7cade259ec33b95e2a84a9433fe",
        "wakeword/melspectrogram.onnx" to "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f",
        "wakeword/embedding_model.onnx" to "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f",
        "wakeword/hey_jarvis_v0.1.onnx" to "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb",
    )

    /** Ожидаемый дайджест для пути или null, если модель не зафиксирована (не пиновать). */
    fun expectedFor(assetPath: String): String? = EXPECTED[assetPath]

    /** sha256 в lowercase-гексе. Пустой массив -> дайджест пустой строки
     * (детерминированно), не исключение. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            for (b in digest) {
                append(HEX[(b shr 4) and 0x0F])
                append(HEX[b and 0x0F])
            }
        }
    }

    /**
     * Проверка ассета: true = совпадает, false = дайджест расстроен, null =
     * модель не зафиксирована (проверка не требуется).
     */
    fun verify(assetPath: String, bytes: ByteArray): Boolean? {
        val expected = expectedFor(assetPath) ?: return null
        return sha256Hex(bytes).equals(expected, ignoreCase = true)
    }

    private const val HEX = "0123456789abcdef"
}
