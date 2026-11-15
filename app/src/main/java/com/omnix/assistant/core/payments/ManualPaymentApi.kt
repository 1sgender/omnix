package com.omnix.assistant.core.payments

import androidx.annotation.VisibleForTesting
import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.core.network.readUtf8Bounded
import com.omnix.assistant.core.security.AccessTokenPolicy
import com.omnix.assistant.core.security.SecurityManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Экран «Тарифы»: каталог с сервера и заявки на ручную оплату. */
interface ManualPaymentApi {
    suspend fun tariffs(): PaymentsResult<TariffsCatalog>
    suspend fun createOrder(planId: String): PaymentsResult<OrderTicket>
}

@Serializable
private data class TariffsRequestBody(@SerialName("request_id") val requestId: String)

@Serializable
private data class OrderRequestBody(
    @SerialName("plan_id") val planId: String,
    @SerialName("request_id") val requestId: String
)

@Singleton
class HttpManualPaymentApi @Inject constructor(
    private val securityManager: SecurityManager
) : ManualPaymentApi {
    companion object {
        private const val MAX_RESPONSE_BYTES = 128L * 1024
        private val BASE_URL: String = AppConstants.OMNIX_LICENSE_BASE_URL
    }

    private var baseUrl: String = BASE_URL

    @VisibleForTesting
    constructor(securityManager: SecurityManager, baseUrl: String) : this(securityManager) {
        this.baseUrl = baseUrl
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = false; encodeDefaults = true }
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override suspend fun tariffs(): PaymentsResult<TariffsCatalog> {
        val body = json.encodeToString(TariffsRequestBody.serializer(), TariffsRequestBody(UUID.randomUUID().toString()))
        return post("/v1/billing/tariffs", body) { raw ->
            val parsed = json.decodeFromString(TariffsResponse.serializer(), raw)
            TariffsCatalog(parsed.plans, parsed.contacts, parsed.orders.map { it.toOrder() })
        }
    }

    override suspend fun createOrder(planId: String): PaymentsResult<OrderTicket> {
        val body = json.encodeToString(
            OrderRequestBody.serializer(), OrderRequestBody(planId, UUID.randomUUID().toString())
        )
        return post("/v1/billing/manual/order", body) { raw ->
            val parsed = json.decodeFromString(ManualOrderResponse.serializer(), raw)
            OrderTicket(parsed.order.toOrder(), parsed.contacts)
        }
    }

    private suspend fun <T> post(path: String, body: String, parse: (String) -> T): PaymentsResult<T> =
        withContext(Dispatchers.IO) {
            val token = securityManager.getAccessToken().trim()
            if (!AccessTokenPolicy.isValid(token)) return@withContext PaymentsResult.Unauthorized
            val request = Request.Builder()
                .url("$baseUrl$path")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(mediaType))
                .build()
            try {
                client.newCall(request).execute().use { response ->
                    val raw = response.body?.readUtf8Bounded(MAX_RESPONSE_BYTES).orEmpty()
                    when (response.code) {
                        200, 201 -> runCatching { PaymentsResult.Ok(parse(raw)) }
                            .getOrElse { PaymentsResult.Unavailable }
                        401, 403 -> PaymentsResult.Unauthorized
                        404, 409, 410 -> PaymentsResult.NoLicense
                        429 -> PaymentsResult.RateLimited
                        else -> PaymentsResult.Unavailable
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                PaymentsResult.NoConnection
            } catch (_: Exception) {
                PaymentsResult.Unavailable
            }
        }
}
