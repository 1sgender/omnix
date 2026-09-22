package com.omnix.assistant.agent.localai.downloader

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Результат проверки целостности файла модели. */
enum class ModelIntegrity {
    /** Файл прошёл проверку: размер и SHA-256 совпали с эталоном. */
    VALID,

    /** Файла нет. */
    MISSING,

    /** Размер не совпал — к хешированию даже не приступаем. */
    SIZE_MISMATCH,

    /** Размер совпал, но SHA-256 нет: содержимое повреждено или подменено. */
    HASH_MISMATCH
}

/**
 * Целостность файла модели: точный размер + SHA-256 против эталона из
 * [com.omnix.assistant.agent.localai.LocalModelSpec].
 *
 * Регрессия v97: скачанный .task прошёл проверку по размеру, но содержимое
 * было повреждено — нативный движок упал с «Unable to open zip archive».
 * Размер ловит обрывы загрузки, но не битые байты: их ловит только хеш.
 *
 * Чтобы не хешировать 521 МБ при каждой проверке (инициализация модели,
 * старт приложения, экран настроек), успешная верификация запоминается
 * sidecar-маркером `<file>.sha256`: «именно этот файл (длина + mtime)
 * проверен, хеш совпал». Маркер привязан к длине И времени изменения,
 * поэтому любая замена файла (новая загрузка, переустановка из пакa,
 * докачка) автоматически инвалидирует его, и хеш считается заново.
 *
 * Чистый Kotlin без Android-зависимостей — покрывается JVM-тестами.
 */
object ModelFileIntegrity {

    /** Читаем файл блоками по 64 КБ — константная память при любом размере. */
    private const val BUFFER_BYTES = 64 * 1024

    private val HEX_DIGITS = "0123456789abcdef".toCharArray()

    /** Sidecar-маркер успешной верификации — рядом с файлом модели. */
    fun markerFileFor(modelFile: File): File =
        File(modelFile.parentFile, modelFile.name + ".sha256")

    /**
     * Полная проверка файла. Существование и размер — мгновенно; блокирующее
     * хеширование (нужно только без валидного маркера) уезжает на
     * [ioDispatcher], поэтому вызов — suspend.
     *
     * Политика удаления — НЕ здесь: метод только сообщает вердикт, что делать
     * с битым файлом, решает менеджер модели.
     */
    suspend fun verify(
        file: File,
        expectedSizeBytes: Long,
        expectedSha256: String,
        ioDispatcher: CoroutineDispatcher
    ): ModelIntegrity {
        if (!file.exists()) return ModelIntegrity.MISSING
        if (file.length() != expectedSizeBytes) return ModelIntegrity.SIZE_MISMATCH
        if (markerMatches(markerFileFor(file), file, expectedSha256)) {
            return ModelIntegrity.VALID
        }
        val actual = withContext(ioDispatcher) { sha256HexOrNull(file) }
            ?: return ModelIntegrity.HASH_MISMATCH
        if (!actual.equals(expectedSha256, ignoreCase = true)) return ModelIntegrity.HASH_MISMATCH
        withContext(ioDispatcher) { writeMarker(markerFileFor(file), file, actual) }
        return ModelIntegrity.VALID
    }

    /**
     * Удаляет файл модели вместе с маркером верификации: оставить маркер —
     * значит оставить мусор, который всё равно инвалидируется при следующей
     * загрузке (новый mtime).
     */
    fun deleteWithMarker(file: File) {
        runCatching { markerFileFor(file).delete() }
        runCatching { file.delete() }
    }

    /**
     * Маркер доверяем, только если он записан для ЭТОГО файла: эталонный хеш,
     * длина и mtime совпадают с текущими. Любое несовпадение (включая битый
     * или чужой маркер) — хеш считаем заново.
     */
    private fun markerMatches(marker: File, file: File, expectedSha256: String): Boolean {
        val line = runCatching { marker.readText().trim() }.getOrNull() ?: return false
        val parts = line.split(' ')
        if (parts.size != 3) return false
        return parts[0].equals(expectedSha256, ignoreCase = true) &&
            parts[1] == file.length().toString() &&
            parts[2] == file.lastModified().toString()
    }

    private fun writeMarker(marker: File, file: File, sha256Hex: String) {
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText("$sha256Hex ${file.length()} ${file.lastModified()}")
        }
    }

    /** SHA-256 файла в нижнем hex; null при ошибке чтения. */
    internal fun sha256HexOrNull(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val sb = StringBuilder(digest.getDigestLength() * 2)
        for (byte in digest.digest()) {
            val v = byte.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0x0F])
        }
        sb.toString()
    } catch (e: Exception) {
        null
    }
}
