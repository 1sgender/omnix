package com.jarvis.assistant.data.remote.interceptor

import com.jarvis.assistant.core.license.ActivationResult
import com.jarvis.assistant.core.license.LicenseInfo
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.core.license.LicenseRefreshResult
import com.jarvis.assistant.core.security.SecurityManager
import com.jarvis.assistant.data.remote.JarvisApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * V007 device binding: the server rejects jrv_ license tokens on the AI path
 * fail-closed when X-Jarvis-Device is missing. [JarvisApiClient] sets
 * Authorization itself, so the interceptor MUST attach the device header
 * independently of whether Authorization was already present — otherwise
 * every real license-token AI request gets 401.
 */
class AuthInterceptorTest {

    private val security = FakeSecurityManager("test-access-token-abcdefghijklmnopqrstuvwxyz")
    private val licenses = FakeLicenseManager("JRV-TEST-DEVICE-01")

    private fun intercept(request: Request): Request {
        val interceptor = AuthInterceptor(security, licenses)
        val chain = RecordingChain(request)
        interceptor.intercept(chain)
        return chain.proceeded ?: error("interceptor did not proceed")
    }

    @Test
    fun `device header is added when caller already set Authorization`() {
        val request = Request.Builder()
            .url(JarvisApiClient.BASE_URL + JarvisApiClient.EXECUTE_PATH)
            .header("Authorization", "Bearer caller-token")
            .post("".toRequestBody())
            .build()

        val sent = intercept(request)

        assertEquals("Bearer caller-token", sent.header("Authorization"))
        assertEquals("JRV-TEST-DEVICE-01", sent.header("X-Jarvis-Device"))
    }

    @Test
    fun `token and device header are added when both are missing`() {
        val request = Request.Builder()
            .url(JarvisApiClient.BASE_URL + "/v1/license/validate")
            .post("".toRequestBody())
            .build()

        val sent = intercept(request)

        assertEquals(
            "Bearer test-access-token-abcdefghijklmnopqrstuvwxyz",
            sent.header("Authorization")
        )
        assertEquals("JRV-TEST-DEVICE-01", sent.header("X-Jarvis-Device"))
    }

    @Test
    fun `explicit device header is never overwritten`() {
        val request = Request.Builder()
            .url(JarvisApiClient.BASE_URL + JarvisApiClient.EXECUTE_PATH)
            .header("Authorization", "Bearer caller-token")
            .header("X-Jarvis-Device", "CALLER-DEVICE")
            .post("".toRequestBody())
            .build()

        val sent = intercept(request)

        assertEquals("CALLER-DEVICE", sent.header("X-Jarvis-Device"))
    }

    @Test
    fun `blank device id sends no device header but keeps Authorization`() {
        licenses.fakeId = "  "
        val request = Request.Builder()
            .url(JarvisApiClient.BASE_URL + JarvisApiClient.EXECUTE_PATH)
            .header("Authorization", "Bearer caller-token")
            .post("".toRequestBody())
            .build()

        val sent = intercept(request)

        assertNull(sent.header("X-Jarvis-Device"))
        assertEquals("Bearer caller-token", sent.header("Authorization"))
    }

    @Test
    fun `Authorization to a foreign host is refused`() {
        val request = Request.Builder()
            .url("https://example.invalid/steal")
            .header("Authorization", "Bearer caller-token")
            .get()
            .build()

        try {
            intercept(request)
            fail("expected IOException when sending Authorization off-origin")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("outside the configured JARVIS origin"))
        }
    }

    @Test
    fun `no token and no device id leak to foreign hosts`() {
        val request = Request.Builder()
            .url("https://example.invalid/public")
            .get()
            .build()

        val sent = intercept(request)

        assertNull(sent.header("Authorization"))
        assertNull(sent.header("X-Jarvis-Device"))
    }

    private class FakeSecurityManager(initial: String) : SecurityManager {
        private val token = MutableStateFlow(initial)
        override fun getAccessToken(): String = token.value
        override fun saveAccessToken(token: String) {
            this.token.value = token
        }
        override fun clearAccessToken() {
            token.value = ""
        }
        override fun hasValidAccessToken(): Boolean = token.value.length >= 32
        override val accessTokenFlow: Flow<String> = token
    }

    private class FakeLicenseManager(var fakeId: String) : LicenseManager {
        override val licenseFlow: Flow<LicenseInfo> =
            MutableStateFlow(LicenseInfo(isActivated = false))
        override fun getLicenseInfo(): LicenseInfo = LicenseInfo(isActivated = false)
        override fun isActivatedAndValid(): Boolean = false
        override suspend fun refreshFromServer(): LicenseRefreshResult =
            LicenseRefreshResult.ServiceUnavailable
        override suspend fun activateWithCode(code: String): ActivationResult =
            ActivationResult.ServiceUnavailable("fake")
        override fun getDeviceId(): String = fakeId
    }

    private class RecordingChain(private val original: Request) : Interceptor.Chain {
        var proceeded: Request? = null

        override fun request(): Request = original

        override fun proceed(request: Request): Response {
            proceeded = request
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .build()
        }

        override fun connection(): Connection? = null
        override fun call(): okhttp3.Call = error("not needed")
        override fun connectTimeoutMillis(): Int = 0
        override fun withConnectTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun readTimeoutMillis(): Int = 0
        override fun withReadTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
        override fun writeTimeoutMillis(): Int = 0
        override fun withWriteTimeout(timeout: Int, unit: TimeUnit): Interceptor.Chain = this
    }
}
