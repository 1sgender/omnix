package com.jarvis.assistant.agent.localai.downloader

import com.jarvis.assistant.agent.localai.LocalModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Чистые решения автозагрузки — без Android-фреймворка, выполняются на JVM.
 */
class ModelDownloadPolicyTest {

    @Test
    fun `download starts only with explicit consent`() {
        assertFalse(ModelDownloadPolicy.mayDownload(ModelDownloadPolicy.CONSENT_UNASKED))
        assertFalse(ModelDownloadPolicy.mayDownload(ModelDownloadPolicy.CONSENT_LATER))
        assertFalse(ModelDownloadPolicy.mayDownload("garbage"))
        assertTrue(ModelDownloadPolicy.mayDownload(ModelDownloadPolicy.CONSENT_ANY_NETWORK))
        assertTrue(ModelDownloadPolicy.mayDownload(ModelDownloadPolicy.CONSENT_WIFI_ONLY))
    }

    @Test
    fun `metered network allowed only for any-network consent`() {
        assertTrue(ModelDownloadPolicy.allowedOverMetered(ModelDownloadPolicy.CONSENT_ANY_NETWORK))
        assertFalse(ModelDownloadPolicy.allowedOverMetered(ModelDownloadPolicy.CONSENT_WIFI_ONLY))
        assertFalse(ModelDownloadPolicy.allowedOverMetered(ModelDownloadPolicy.CONSENT_UNASKED))
        assertFalse(ModelDownloadPolicy.allowedOverMetered(ModelDownloadPolicy.CONSENT_LATER))
    }

    @Test
    fun `progress is honest at boundaries`() {
        assertEquals(0, ModelDownloadPolicy.progressPercent(0L, 100L))
        assertEquals(50, ModelDownloadPolicy.progressPercent(50L, 100L))
        assertEquals(100, ModelDownloadPolicy.progressPercent(100L, 100L))
        // Перелёт не даёт >100.
        assertEquals(100, ModelDownloadPolicy.progressPercent(150L, 100L))
        // Неизвестный размер — честный 0, а не выдумка.
        assertEquals(0, ModelDownloadPolicy.progressPercent(10L, 0L))
        assertEquals(0, ModelDownloadPolicy.progressPercent(10L, -1L))
        assertEquals(0, ModelDownloadPolicy.progressPercent(-5L, 100L))
    }

    @Test
    fun `known failure reasons are human readable`() {
        // Значения — DownloadManager.ERROR_* (инлайнятся javac как int).
        assertTrue(ModelDownloadPolicy.reasonText(1006).contains("места"))
        assertTrue(ModelDownloadPolicy.reasonText(1007).contains("хранилище"))
    }

    @Test
    fun `unknown failure reason keeps its code for diagnostics`() {
        val text = ModelDownloadPolicy.reasonText(424242)
        assertTrue("код должен сохраниться в тексте: $text", text.contains("424242"))
    }

    @Test
    fun `bundled model spec is downloadable and verifiable`() {
        val spec = LocalModelSpec.QWEN2_5_0_5B_INSTRUCT_Q8
        assertTrue("URL обязан быть https", spec.downloadUrl.startsWith("https://"))
        assertTrue(spec.fileName.endsWith(".task"))
        assertTrue(spec.expectedSizeBytes > 500L * 1024 * 1024)
        assertEquals(1280, spec.contextTokens)
    }
}
