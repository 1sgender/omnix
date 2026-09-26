package com.omnix.server.license

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Cryptographic code/token generation and keyed-at-rest representation. */
class LicenseCrypto(
    pepper: String,
    private val random: SecureRandom = SecureRandom()
) {
    companion object {
        private const val CODE_PREFIX = "OMX"
        // Pre-rebrand codes stay valid: normalize/validate accept the legacy prefix.
        private const val LEGACY_CODE_PREFIX = "JRV"
        private const val API_TOKEN_PREFIX = "omx_"
        private const val BASE32 = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val CODE_REGEX = Regex("^(?:OMX|JRV)(?:-[A-Z2-9]{5}){4}$")

        // Короткий код на карточке из коробки Clip (мок активации 2026-09-25):
        // шесть символов того же неом ambiguity-алфавита (без 0/O/1/I — их нет
        // и на карточке). Единая точка смены длины: здесь и CODE_CELL_COUNT в
        // приложении. Легаси-кодек OMX-/JRV- остаётся принимаемым — как когда-то
        // остался JRV- после ребрендинга.
        const val BOX_CODE_LENGTH = 6
        private val BOX_CODE_REGEX = Regex("^[A-Z2-9]{$BOX_CODE_LENGTH}$")
    }

    private val key = pepper.toByteArray(StandardCharsets.UTF_8).also {
        require(it.size >= 32) { "LICENSE_CODE_PEPPER must contain at least 32 UTF-8 bytes" }
    }

    fun generateLicenseCode(): String {
        val bytes = ByteArray(20).also(random::nextBytes)
        val chars = CharArray(20) { index -> BASE32[bytes[index].toInt() and 31] }
        return CODE_PREFIX + chars.concatToString().chunked(5).joinToString(separator = "-", prefix = "-")
    }

    fun generateBoxCode(): String {
        val bytes = ByteArray(BOX_CODE_LENGTH).also(random::nextBytes)
        return CharArray(BOX_CODE_LENGTH) { index -> BASE32[bytes[index].toInt() and 31] }
            .concatToString()
    }

    fun normalizeLicenseCode(raw: String): String? {
        val compact = raw.trim().uppercase()
            .replace(Regex("\\s+"), "")
        // Короткий код с карточки: шесть символов, регистр и пробелы прощаются.
        // Дефис внутри короткого кода не предусмотрен карточкой, но строгать его
        // здесь дешевле, чем объяснять пользователю ошибку ввода.
        compact.replace("-", "").takeIf(BOX_CODE_REGEX::matches)?.let { return it }
        val canonical = if ((compact.startsWith("OMX-") || compact.startsWith("JRV-")) &&
            compact.count { it == '-' } == 4
        ) {
            compact
        } else {
            val noDash = compact.replace("-", "")
            val prefix = when {
                noDash.startsWith(CODE_PREFIX) -> CODE_PREFIX
                noDash.startsWith(LEGACY_CODE_PREFIX) -> LEGACY_CODE_PREFIX
                else -> return null
            }
            if (noDash.length != 23) return null
            prefix + noDash.removePrefix(prefix).chunked(5).joinToString("-", prefix = "-")
        }
        return canonical.takeIf(CODE_REGEX::matches)
    }

    fun licenseCodeHash(canonicalCode: String): ByteArray = hmac("license:$canonicalCode")

    fun codeHint(canonicalCode: String): String =
        // Для короткого кода хвост в 5 символов раскрывал бы почти весь код;
        // трёх достаточно для опознания в админ-списке.
        if (canonicalCode.length <= BOX_CODE_LENGTH) canonicalCode.takeLast(3)
        else canonicalCode.takeLast(5)

    fun generateAccessToken(): String = API_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))

    fun accessTokenHash(token: String): ByteArray = hmac("token:$token")

    fun deviceHash(deviceId: String): ByteArray {
        val normalized = deviceId.trim()
        require(normalized.length in 8..128 && normalized.none(Char::isISOControl)) {
            "device identifier must contain 8..128 non-control characters"
        }
        return hmac("device:$normalized")
    }

    fun payloadHash(rawBody: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(rawBody.toByteArray(StandardCharsets.UTF_8))

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    private fun hmac(value: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal(value.toByteArray(StandardCharsets.UTF_8))
    }
}
