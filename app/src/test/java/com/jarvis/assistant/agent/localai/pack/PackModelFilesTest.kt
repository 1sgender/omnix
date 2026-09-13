package com.jarvis.assistant.agent.localai.pack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Установка модели из PAD-пака: копирование + проверка размера.
 * Чистый JVM-тест: PackModelFiles не зависит от Android.
 */
class PackModelFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `copy succeeds and removes temp file`() {
        val dest = File(tmp.root, "model.task")
        val data = ByteArray(1024) { it.toByte() }

        assertTrue(PackModelFiles.copyAndVerify(ByteArrayInputStream(data), dest, 1024))

        assertEquals(1024, dest.length())
        assertFalse(File(tmp.root, "model.task.part").exists())
    }

    @Test
    fun `size mismatch deletes temp and returns false`() {
        val dest = File(tmp.root, "model.task")

        assertFalse(PackModelFiles.copyAndVerify(ByteArrayInputStream(ByteArray(100)), dest, 1024))

        assertFalse(dest.exists())
        assertFalse(File(tmp.root, "model.task.part").exists())
    }

    @Test
    fun `broken stream returns false without throwing`() {
        val dest = File(tmp.root, "model.task")
        val bad = object : InputStream() {
            override fun read(): Int = throw IOException("broken")
        }

        assertFalse(PackModelFiles.copyAndVerify(bad, dest, 10))

        assertFalse(dest.exists())
    }

    @Test
    fun `stale dest is replaced on success`() {
        val dest = File(tmp.root, "model.task")
        dest.writeBytes(ByteArray(10))

        assertTrue(PackModelFiles.copyAndVerify(ByteArrayInputStream(ByteArray(100)), dest, 100))

        assertEquals(100, dest.length())
    }

    @Test
    fun `missing parent dirs are created`() {
        val dest = File(tmp.root, "a/b/model.task")

        assertTrue(PackModelFiles.copyAndVerify(ByteArrayInputStream(ByteArray(8)), dest, 8))

        assertEquals(8, dest.length())
    }
}
