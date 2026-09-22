package com.omnix.assistant.core.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты хранилища краш-отчётов: запись, ротация, порядок чтения.
 */
class CrashFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = CrashFileStore(tmp.newFolder())

    @Test
    fun `write creates crash file with device thread and stack`() {
        val s = store()

        val file = s.write(
            deviceInfo = "app: 0.3.0 (107)\ndevice: Test Phone",
            threadName = "main",
            stackTrace = "java.lang.IllegalStateException: boom\n\tat Foo.bar(Foo.kt:1)",
            nowMs = 1_700_000_000_000L
        )

        assertNotNull(file)
        val text = file!!.readText()
        assertTrue("Должна быть информация о сборке", text.contains("app: 0.3.0 (107)"))
        assertTrue("Должен быть поток", text.contains("thread: main"))
        assertTrue("Должен быть стек", text.contains("IllegalStateException: boom"))
    }

    @Test
    fun `latest returns newest files first`() {
        val s = store()
        s.write("d", "t", "stack-1", nowMs = 1_700_000_000_000L)
        s.write("d", "t", "stack-2", nowMs = 1_700_000_060_000L)

        val latest = s.latest()

        assertEquals(2, latest.size)
        assertTrue("Новый краш — первым", latest.first().readText().contains("stack-2"))
    }

    @Test
    fun `rotation keeps only last MAX_FILES crashes`() {
        val s = store()
        // +60 секунд между крашами — имена файлов гарантированно различаются.
        repeat(CrashFileStore.MAX_FILES + 3) { i ->
            s.write("d", "t", "stack-$i", nowMs = 1_700_000_000_000L + i * 60_000L)
        }

        assertEquals(CrashFileStore.MAX_FILES, s.latest(Int.MAX_VALUE).size)
        // Самые старые удалены, самые новые остались.
        val all = s.latest(Int.MAX_VALUE)
        assertFalse("Старые краши удалены ротацией", all.any { it.readText().contains("stack-0") })
        assertTrue("Последний краш сохранён", all.first().readText().contains("stack-7"))
    }

    @Test
    fun `latestReports formats blocks with file name`() {
        val s = store()
        s.write("d", "t", "java.lang.RuntimeException: x", nowMs = 1_700_000_000_000L)

        val reports = s.latestReports()

        assertEquals(1, reports.size)
        assertTrue(reports[0].startsWith("--- crash_"))
        assertTrue(reports[0].contains("RuntimeException: x"))
    }

    @Test
    fun `empty store reports nothing`() {
        val s = store()

        assertTrue(s.latest().isEmpty())
        assertTrue(s.latestReports().isEmpty())
    }
}
