package com.omnix.assistant.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateModelsTest {

    private fun info(
        success: Boolean = true,
        versionCode: Long = 100L,
        url: String = "https://github.com/acme/app/releases/download/staging/OMNIX-staging.apk"
    ) = AppLatestInfo(
        success = success,
        channel = "staging",
        versionCode = versionCode,
        url = url
    )

    @Test
    fun `newer version with https url is an update`() {
        assertTrue(isUpdateAvailable(info(versionCode = 101L), 100L))
    }

    @Test
    fun `same version is not an update`() {
        assertFalse(isUpdateAvailable(info(versionCode = 100L), 100L))
    }

    @Test
    fun `older version is not an update`() {
        assertFalse(isUpdateAvailable(info(versionCode = 99L), 100L))
    }

    @Test
    fun `failed response is not an update`() {
        assertFalse(isUpdateAvailable(info(success = false), 100L))
    }

    @Test
    fun `payload without success flag is an update when newer`() {
        // Регрессия: живой сервер опускает success:true (дефолт сериализации),
        // и старый дефолт false вечно запрещал обновления.
        val parsed = parseAppLatest(
            """{"channel":"staging","versionCode":101,"url":"https://x/y.apk","sizeBytes":1}"""
        )!!
        assertTrue(isUpdateAvailable(parsed, 100L))
    }

    @Test
    fun `null response is not an update`() {
        assertFalse(isUpdateAvailable(null, 100L))
    }

    @Test
    fun `non-https url is not an update`() {
        assertFalse(isUpdateAvailable(info(url = "http://evil.local/app.apk"), 100L))
    }

    @Test
    fun `parser reads server response`() {
        val parsed = parseAppLatest(
            """{"success":true,"channel":"staging","versionCode":123,"url":"https://x/y.apk",
               |"sizeBytes":42000000,"sha":"abc1234","publishedAt":"2026-09-16","stale":false,
               |"unknownFutureField":1}""".trimMargin()
        )!!

        assertEquals(123L, parsed.versionCode)
        assertEquals("https://x/y.apk", parsed.url)
        assertEquals("abc1234", parsed.sha)
        assertFalse(parsed.stale)
    }

    @Test
    fun `parser turns garbage into null`() {
        assertNull(parseAppLatest("not-json{{{"))
        assertNull(parseAppLatest(""))
    }

    @Test
    fun `only signed direct release variants get an OTA channel`() {
        assertEquals("staging", directUpdateChannel(flavor = "staging", buildType = "release"))
        assertEquals("prod", directUpdateChannel(flavor = "prod", buildType = "release"))
        assertNull(directUpdateChannel(flavor = "dev", buildType = "release"))
        assertNull(directUpdateChannel(flavor = "prod", buildType = "debug"))
    }

    @Test
    fun `no pending download discards`() {
        assertEquals(
            ResumeAction.DISCARD,
            decideResume(pending = null, fileExists = true, currentVersionCode = 100L)
        )
    }

    @Test
    fun `obsolete pending version discards`() {
        val pending = PendingUpdate(downloadId = 7L, versionCode = 100L, status = 8)

        assertEquals(
            ResumeAction.DISCARD,
            decideResume(pending, fileExists = true, currentVersionCode = 100L)
        )
    }

    @Test
    fun `successful download with file installs`() {
        // DownloadManager.STATUS_SUCCESSFUL == 8 (константа инлайнится).
        val pending = PendingUpdate(downloadId = 7L, versionCode = 101L, status = 8)

        assertEquals(
            ResumeAction.INSTALL,
            decideResume(pending, fileExists = true, currentVersionCode = 100L)
        )
    }

    @Test
    fun `successful download without file discards`() {
        val pending = PendingUpdate(downloadId = 7L, versionCode = 101L, status = 8)

        assertEquals(
            ResumeAction.DISCARD,
            decideResume(pending, fileExists = false, currentVersionCode = 100L)
        )
    }

    @Test
    fun `running download waits`() {
        // DownloadManager.STATUS_RUNNING == 2.
        val pending = PendingUpdate(downloadId = 7L, versionCode = 101L, status = 2)

        assertEquals(
            ResumeAction.WAIT_DOWNLOAD,
            decideResume(pending, fileExists = false, currentVersionCode = 100L)
        )
    }

    @Test
    fun `failed download discards`() {
        // DownloadManager.STATUS_FAILED == 16.
        val pending = PendingUpdate(downloadId = 7L, versionCode = 101L, status = 16)

        assertEquals(
            ResumeAction.DISCARD,
            decideResume(pending, fileExists = false, currentVersionCode = 100L)
        )
    }
}
