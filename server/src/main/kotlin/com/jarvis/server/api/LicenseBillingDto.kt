package com.jarvis.server.api

import com.jarvis.server.license.PlanLimits
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class LicenseIssueRequest(
    @SerialName("plan_id") val planId: String,
    @SerialName("account_ref") val accountRef: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("one_time") val oneTime: Boolean = true,
    @SerialName("metadata") val metadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class LicenseIssueResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("license_id") val licenseId: String,
    /** Shown once and never persisted in plaintext. */
    @SerialName("code") val code: String,
    @SerialName("status") val status: String,
    @SerialName("plan_id") val planId: String,
    @SerialName("issued_at") val issuedAt: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class LicenseRedeemRequest(
    @SerialName("code") val code: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class LicenseRedeemResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String = "Bearer",
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class LicenseValidateRequest(
    @SerialName("device_id") val deviceId: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class LicenseValidateResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("valid") val valid: Boolean = true,
    @SerialName("plan_id") val planId: String,
    @SerialName("product_id") val productId: String,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("billing_status") val billingStatus: String,
    @SerialName("request_id") val requestId: String,
    /**
     * Квоты и гейты тарифа для КЛИЕНТСКОГО enforcement (часть 2).
     * Сервер считает только voice/base (AiRouter); остальное считает клиент.
     * Старые клиенты игнорируют поле (ignoreUnknownKeys).
     */
    @SerialName("entitlements") val entitlements: PlanEntitlementsDto,
    /**
     * Soft-привязка Clip: информативно, AI НЕ блокируется без клипа.
     * null = сервер не смог проверить (repo не подключён, только тесты).
     */
    @SerialName("clip") val clip: ClipBindingDto? = null
)

/** Зеркало PlanLimits для validate-ответа (см. PlanCatalog). */
@Serializable
data class PlanEntitlementsDto(
    @SerialName("daily_voice_ai") val dailyVoiceAi: Int,
    @SerialName("daily_base_ai") val dailyBaseAi: Int,
    @SerialName("daily_agent_actions") val dailyAgentActions: Int,
    @SerialName("daily_web_search") val dailyWebSearch: Int,
    @SerialName("daily_translation_units") val dailyTranslationUnits: Int,
    @SerialName("daily_ear_minutes") val dailyEarMinutes: Int,
    @SerialName("max_automations") val maxAutomations: Int,
    @SerialName("screen_reading") val screenReading: Boolean,
    @SerialName("ui_control") val uiControl: Boolean,
    @SerialName("priority_routing") val priorityRouting: Boolean,
    @SerialName("premium_models") val premiumModels: Boolean,
    @SerialName("max_clips") val maxClips: Int
)

/** Маппинг PlanLimits → DTO (api зависит от license, не наоборот). */
fun PlanLimits.toDto(): PlanEntitlementsDto = PlanEntitlementsDto(
    dailyVoiceAi = dailyVoiceAi,
    dailyBaseAi = dailyBaseAi,
    dailyWebSearch = dailyWebSearch,
    dailyAgentActions = dailyAgentActions,
    dailyTranslationUnits = dailyTranslationUnits,
    dailyEarMinutes = dailyEarMinutes,
    maxAutomations = maxAutomations,
    screenReading = screenReading,
    uiControl = uiControl,
    priorityRouting = priorityRouting,
    premiumModels = premiumModels,
    maxClips = maxClips
)

@Serializable
data class ClipBindingDto(
    @SerialName("has_bound_clip") val hasBoundClip: Boolean,
    @SerialName("bound_clip_count") val boundClipCount: Int
)

@Serializable
data class LicenseRevokeRequest(
    @SerialName("license_id") val licenseId: String,
    @SerialName("reason") val reason: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class BillingCheckoutRequest(
    @SerialName("plan_id") val planId: String,
    @SerialName("provider") val provider: String,
    @SerialName("idempotency_key") val idempotencyKey: String,
    @SerialName("request_id") val requestId: String? = null
)

@Serializable
data class BillingCheckoutResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("order_id") val orderId: String,
    @SerialName("status") val status: String,
    @SerialName("provider") val provider: String,
    @SerialName("checkout_url") val checkoutUrl: String? = null,
    @SerialName("request_id") val requestId: String
)

@Serializable
data class BillingWebhookResponse(
    @SerialName("success") val success: Boolean = true,
    @SerialName("result") val result: String
)
