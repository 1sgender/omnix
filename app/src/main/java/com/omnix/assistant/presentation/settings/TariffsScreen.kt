package com.omnix.assistant.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.omnix.assistant.R
import com.omnix.assistant.core.license.PlanEntitlements
import com.omnix.assistant.core.payments.ContactKind
import com.omnix.assistant.core.payments.ManualOrder
import com.omnix.assistant.core.payments.ManualOrderStatus
import com.omnix.assistant.core.payments.PaymentContact
import com.omnix.assistant.core.payments.TariffPlan
import com.omnix.assistant.core.payments.formatPrice
import com.omnix.assistant.core.payments.orderForPlan
import com.omnix.assistant.core.payments.planRank
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * «Тарифы»: Free, Pro и Max с ценой и лимитами из каталога сервера.
 * «Получить» создаёт заявку на ручную оплату: номер, сумма и контакты владельца.
 */
@Composable
fun TariffsScreen(
    state: TariffsUiState,
    onRequest: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    SectionScaffold(stringResource(R.string.pay_title), modifier, onBack) {
        val catalog = state.catalog
        state.failure?.let { failure ->
            Text(
                text = stringResource(failureText(failure)),
                style = OmnixTheme.typography.body,
                color = OmnixTheme.colors.textSecondary,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            OmnixSecondaryButton(
                text = stringResource(R.string.pay_retry),
                onClick = onRefresh,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        if (catalog == null) {
            if (state.loading) {
                Text(
                    text = stringResource(R.string.pay_loading),
                    style = OmnixTheme.typography.body,
                    color = OmnixTheme.colors.textSecondary
                )
            }
            return@SectionScaffold
        }
        val currentRank = planRank(state.currentPlanId)
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            catalog.plans.forEach { plan ->
                PlanCard(
                    plan = plan,
                    isCurrent = planRank(plan.id) == currentRank,
                    order = orderForPlan(catalog.orders, plan.id),
                    contacts = catalog.contacts,
                    creating = state.creatingPlanId == plan.id,
                    canRequest = !plan.isFree && state.creatingPlanId == null,
                    onRequest = { onRequest(plan.id) }
                )
            }
            OmnixSecondaryButton(text = stringResource(R.string.pay_refresh), onClick = onRefresh)
        }
    }
}

@Composable
private fun PlanCard(
    plan: TariffPlan,
    isCurrent: Boolean,
    order: ManualOrder?,
    contacts: List<PaymentContact>,
    creating: Boolean,
    canRequest: Boolean,
    onRequest: () -> Unit
) {
    val colors = OmnixTheme.colors
    val typography = OmnixTheme.typography
    OmnixSettingsGroup {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = plan.name, style = typography.headline, color = colors.textPrimary)
            Text(
                text = if (plan.isFree) {
                    stringResource(R.string.pay_price_free)
                } else {
                    stringResource(R.string.pay_price_period, formatPrice(plan.priceMinor, plan.currency), plan.durationDays.toString())
                },
                style = typography.body,
                color = colors.textSecondary
            )
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.pay_current),
                    style = typography.body,
                    color = colors.textPrimary
                )
            }
            if (plan.isFree) {
                Text(text = stringResource(R.string.pay_free_note), style = typography.body, color = colors.textSecondary)
            }
            entitlementLines(plan.entitlements).forEach { line ->
                Text(text = line, style = typography.body, color = colors.textSecondary)
            }
            if (order != null) {
                OrderBlock(order, contacts)
            }
            val canOrderAgain = order == null || order.status.isFinal
            if (!plan.isFree && !isCurrent && canOrderAgain) {
                OmnixPrimaryButton(
                    text = if (creating) stringResource(R.string.pay_creating) else stringResource(R.string.pay_get, plan.name),
                    onClick = onRequest,
                    enabled = canRequest,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun OrderBlock(order: ManualOrder, contacts: List<PaymentContact>) {
    val colors = OmnixTheme.colors
    val typography = OmnixTheme.typography
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.pay_order_number, order.number),
            style = typography.headline,
            color = colors.textPrimary
        )
        Text(
            text = stringResource(statusText(order.status)),
            style = typography.body,
            color = colors.textPrimary
        )
        if (order.status == ManualOrderStatus.PENDING) {
            Text(
                text = stringResource(R.string.pay_order_amount, formatPrice(order.amountMinor, order.currency)),
                style = typography.body,
                color = colors.textSecondary
            )
            Text(
                text = stringResource(R.string.pay_instruction, order.number),
                style = typography.body,
                color = colors.textSecondary
            )
            Text(text = stringResource(R.string.pay_contacts_title), style = typography.headline, color = colors.textPrimary)
            if (contacts.isEmpty()) {
                Text(text = stringResource(R.string.pay_contacts_later), style = typography.body, color = colors.textSecondary)
            } else {
                contacts.forEach { contact ->
                    Text(text = contactLine(contact), style = typography.body, color = colors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun contactLine(contact: PaymentContact): String {
    val kind = stringResource(contactKindText(contact.kind))
    return if (contact.label.isBlank()) "$kind: ${contact.value}" else "$kind: ${contact.value} (${contact.label})"
}

@Composable
private fun entitlementLines(e: PlanEntitlements): List<String> = buildList {
    add(stringResource(R.string.pay_line_voice, e.dailyVoiceAi))
    add(stringResource(R.string.pay_line_base, e.dailyBaseAi))
    add(
        if (e.dailyProAi > 0) {
            stringResource(R.string.pay_line_pro, e.dailyProAi, e.monthlyProAi)
        } else {
            stringResource(R.string.pay_line_pro_none)
        }
    )
    add(stringResource(R.string.pay_line_agent, e.dailyAgentActions))
    add(
        stringResource(
            R.string.pay_line_automations,
            if (e.maxAutomations >= UNLIMITED_AUTOMATIONS) {
                stringResource(R.string.pay_unlimited)
            } else {
                e.maxAutomations.toString()
            }
        )
    )
}

private const val UNLIMITED_AUTOMATIONS = 1_000

internal fun statusText(status: ManualOrderStatus): Int = when (status) {
    ManualOrderStatus.PENDING -> R.string.pay_status_pending
    ManualOrderStatus.PAID -> R.string.pay_status_paid
    ManualOrderStatus.CANCELED -> R.string.pay_status_canceled
    ManualOrderStatus.EXPIRED -> R.string.pay_status_expired
    ManualOrderStatus.UNKNOWN -> R.string.pay_status_unknown
}

internal fun failureText(failure: TariffsFailure): Int = when (failure) {
    TariffsFailure.NoConnection -> R.string.pay_error_connection
    TariffsFailure.Server -> R.string.pay_error_server
    TariffsFailure.NoLicense -> R.string.pay_error_no_license
    TariffsFailure.Busy -> R.string.pay_error_busy
}

internal fun contactKindText(kind: ContactKind): Int = when (kind) {
    ContactKind.PHONE -> R.string.pay_contact_phone
    ContactKind.TELEGRAM -> R.string.pay_contact_telegram
    ContactKind.IMO -> R.string.pay_contact_imo
    ContactKind.WHATSAPP -> R.string.pay_contact_whatsapp
    ContactKind.OTHER -> R.string.pay_contact_other
}
