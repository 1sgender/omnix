package com.omnix.assistant.agent.tools.files

import org.junit.Assert.assertEquals
import org.junit.Test

class CreateFileToolTest {

    @Test
    fun `path parts are dropped from the name`() {
        assertEquals("passwd.txt", CreateFileTool.safeFileName("../../etc/passwd"))
        assertEquals("plan.md", CreateFileTool.safeFileName("C:\\docs\\plan.md"))
    }

    @Test
    fun `unknown extension becomes txt`() {
        assertEquals("run.txt", CreateFileTool.safeFileName("run.exe"))
        assertEquals("notes.txt", CreateFileTool.safeFileName("notes"))
    }

    @Test
    fun `allowed extensions are kept`() {
        assertEquals("table.csv", CreateFileTool.safeFileName("table.CSV"))
        assertEquals("data.json", CreateFileTool.safeFileName("data.json"))
    }

    @Test
    fun `empty or missing name gets a default`() {
        assertEquals("omnix.txt", CreateFileTool.safeFileName(null))
        assertEquals("omnix.txt", CreateFileTool.safeFileName("  "))
    }
}
