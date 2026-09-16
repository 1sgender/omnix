package com.omnix.assistant.core.security

import com.omnix.assistant.BuildConfig
import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.data.remote.OmnixApiClient
import com.omnix.assistant.data.remote.interceptor.BackendRequestPolicy
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ApiTransportSecurityTest {
    @Test
    fun `flavor API origins are credential free and scheme constrained`() {
        for (configured in listOf(
            AppConstants.OMNIX_API_BASE_URL,
            AppConstants.OMNIX_LICENSE_BASE_URL
        )) {
            val uri = URI(configured)
            if (BuildConfig.ALLOW_CLEARTEXT_BACKEND) {
                assertTrue(uri.scheme in setOf("http", "https"))
            } else {
                assertEquals("https", uri.scheme)
            }
            assertTrue(uri.host.isNotBlank())
            assertNull(uri.userInfo)
            assertNull(uri.query)
            assertNull(uri.fragment)
        }
        assertEquals(AppConstants.OMNIX_API_BASE_URL, OmnixApiClient.BASE_URL)
    }

    @Test
    fun `bearer policy accepts only exact configured origin`() {
        val backend = AppConstants.OMNIX_API_BASE_URL.toHttpUrl()
        assertTrue(BackendRequestPolicy.isTrusted(backend.newBuilder().addPathSegment("v1").build()))
        val oppositeScheme = if (backend.scheme == "https") "http" else "https"
        assertFalse(BackendRequestPolicy.isTrusted(backend.newBuilder().scheme(oppositeScheme).build()))
        val alternatePort = if (backend.port == 8443) 8444 else 8443
        assertFalse(BackendRequestPolicy.isTrusted(backend.newBuilder().port(alternatePort).build()))
        assertFalse(BackendRequestPolicy.isTrusted("https://evil.example/v1/license/validate".toHttpUrl()))
    }
}
