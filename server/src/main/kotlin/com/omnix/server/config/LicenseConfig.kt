package com.omnix.server.config

import com.omnix.server.billing.HeleketBillingConfig
import com.omnix.server.billing.PaddleBillingConfig
import com.omnix.server.license.BillingPlan
import com.omnix.server.persistence.DatabaseConfig

data class LicenseSubsystemConfig(
    val database: DatabaseConfig,
    val codePepper: String,
    val plans: List<BillingPlan>,
    val redeemRateLimit: RateLimitConfig,
    val authenticatedRateLimit: RateLimitConfig,
    val webhookRateLimit: RateLimitConfig,
    val paddle: PaddleBillingConfig,
    val heleket: HeleketBillingConfig
) {
    init {
        require(codePepper.toByteArray().size >= 32) { "LICENSE_CODE_PEPPER must be at least 32 bytes" }
        require(plans.isNotEmpty()) { "At least one BILLING_PLANS entry is required" }
        require(plans.map { it.id }.distinct().size == plans.size) { "Duplicate billing plan ID" }
    }
}
