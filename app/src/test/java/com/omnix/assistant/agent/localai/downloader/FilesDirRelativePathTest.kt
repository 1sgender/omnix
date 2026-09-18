package com.omnix.assistant.agent.localai.downloader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Относительный путь внутри filesDir: задаёт подсхему внешнего зеркала
 * (externalFallbackFile) для fallback-загрузки, когда прошивка отвергает
 * file:// во внутреннее хранилище («не удалось начать загрузку»). Путь не
 * должен выводить за пределы filesDir.
 */
class FilesDirRelativePathTest {

    private val filesDir = File("/data/user/0/com.omnix.assistant.staging/files")

    @Test
    fun `вложенный путь внутри filesDir`() {
        assertEquals(
            "llm/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            filesDirRelativePath(File(filesDir, "llm/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"), filesDir)
        )
    }

    @Test
    fun `сам filesDir и родительские каталоги — не валидны`() {
        assertNull(filesDirRelativePath(filesDir, filesDir))
        assertNull(filesDirRelativePath(File("/data/user/0"), filesDir))
        assertNull(filesDirRelativePath(File("/data/user/0/other/files/llm/x.task"), filesDir))
    }

    @Test
    fun `путь с выходом за пределы filesDir отклоняется`() {
        assertNull(filesDirRelativePath(File(filesDir, "../secret/x.task"), filesDir))
    }
}
