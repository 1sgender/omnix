package com.omnix.assistant.agent.localai.downloader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты целостности файла модели: размер + SHA-256 + sidecar-маркер.
 *
 * Регрессия v97: повреждённый .task прошёл проверку по размеру и убил
 * нативный рантайм («Unable to open zip archive»). Хеш обязан ловить
 * подмену содержимого при совпавшем размере — и не хешировать 521 МБ
 * повторно без причины.
 */
class ModelFileIntegrityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val io = Dispatchers.IO

    /** Эталонная схема: маленький файл, реальный SHA-256, ожидаемая длина. */
    private data class Fixture(val file: java.io.File, val sha256: String, val size: Long)

    private fun fixture(content: ByteArray): Fixture {
        val file = tmp.newFile("model.task")
        file.writeBytes(content)
        val sha = ModelFileIntegrity.sha256HexOrNull(file)
            ?: throw IllegalStateException("fixture hash failed")
        return Fixture(file, sha, content.size.toLong())
    }

    @Test
    fun `missing file reports MISSING`() = runBlocking {
        val f = fixture("hello".toByteArray())

        val verdict = ModelFileIntegrity.verify(
            file = tmp.root.resolve("absent.task"),
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.MISSING, verdict)
    }

    @Test
    fun `wrong size reports SIZE_MISMATCH`() = runBlocking {
        val f = fixture("hello".toByteArray())

        val verdict = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size + 1,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.SIZE_MISMATCH, verdict)
    }

    /**
     * Главный сценарий регрессии v97: длина совпала байт в байт, а
     * содержимое другое — SIZE-проверка таких не ловит, хеш обязан.
     */
    @Test
    fun `same size but corrupted content reports HASH_MISMATCH`() = runBlocking {
        val f = fixture("aaaaaa".toByteArray())

        val verdict = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = "0".repeat(64),
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.HASH_MISMATCH, verdict)
    }

    @Test
    fun `intact file passes and marker is written`() = runBlocking {
        val f = fixture("model-bytes".toByteArray())

        val verdict = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.VALID, verdict)
        val marker = ModelFileIntegrity.markerFileFor(f.file)
        assertTrue("Маркер верификации должен быть записан", marker.exists())
        assertTrue(
            "Маркер обязан содержать эталонный хеш и параметры файла",
            marker.readText().startsWith(f.sha256)
        )
    }

    /**
     * Маркер привязан к файлу (длина + mtime): после замены содержимого
     * при той же длине старый маркер НЕ должен прикрывать битый файл.
     */
    @Test
    fun `replaced file with same length is re-verified and fails`() = runBlocking {
        val f = fixture("model-bytes".toByteArray())
        val first = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )
        assertEquals(ModelIntegrity.VALID, first)

        // «Замена»: та же длина, другое содержимое. mtime принудительно
        // отличается от записанного в маркер — независимо от гранулярности ФС.
        f.file.writeBytes("m0del-bytes".toByteArray())
        assertEquals("Тест обязан подменять содержимое при той же длине", f.size, f.file.length())
        assertTrue(f.file.setLastModified(f.file.lastModified() + 60_000))

        val second = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.HASH_MISMATCH, second)
    }

    /** Эталон в верхнем регистре — тот же хеш (hex нечувствителен к регистру). */
    @Test
    fun `expected hash is case-insensitive`() = runBlocking {
        val f = fixture("model-bytes".toByteArray())

        val verdict = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256.uppercase(),
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.VALID, verdict)
    }

    /** Битый/чужой маркер не даёт ложного VALID — хеш пересчитывается. */
    @Test
    fun `garbage marker is ignored`() = runBlocking {
        val f = fixture("model-bytes".toByteArray())
        val marker = ModelFileIntegrity.markerFileFor(f.file)
        marker.writeText("garbage")

        val verdict = ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )

        assertEquals(ModelIntegrity.VALID, verdict)
    }

    @Test
    fun `deleteWithMarker removes both file and marker`() = runBlocking {
        val f = fixture("model-bytes".toByteArray())
        ModelFileIntegrity.verify(
            file = f.file,
            expectedSizeBytes = f.size,
            expectedSha256 = f.sha256,
            ioDispatcher = io
        )
        val marker = ModelFileIntegrity.markerFileFor(f.file)
        assertTrue(marker.exists())

        ModelFileIntegrity.deleteWithMarker(f.file)

        assertFalse(f.file.exists())
        assertFalse(marker.exists())
    }
}
