package com.omnix.assistant.core.payments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualPaymentModelsTest {

    private fun order(plan: String, status: ManualOrderStatus, number: String = "OMX-1000") = ManualOrder(
        orderId = "id-$number", number = number, planId = plan, status = status,
        amountMinor = 14_900, currency = "TMT", expiresAtIso = "2026-11-17T00:00:00Z"
    )

    @Test
    fun `server statuses map to the four screen statuses`() {
        assertEquals(ManualOrderStatus.PENDING, ManualOrderStatus.fromServer("PENDING"))
        assertEquals(ManualOrderStatus.PAID, ManualOrderStatus.fromServer("paid"))
        assertEquals(ManualOrderStatus.CANCELED, ManualOrderStatus.fromServer("CANCELED"))
        assertEquals(ManualOrderStatus.EXPIRED, ManualOrderStatus.fromServer("EXPIRED"))
        assertEquals(ManualOrderStatus.UNKNOWN, ManualOrderStatus.fromServer("REFUNDED"))
    }

    @Test
    fun `only a pending request is still open`() {
        assertFalse(ManualOrderStatus.PENDING.isFinal)
        assertTrue(ManualOrderStatus.PAID.isFinal)
        assertTrue(ManualOrderStatus.CANCELED.isFinal)
        assertTrue(ManualOrderStatus.EXPIRED.isFinal)
        assertTrue(ManualOrderStatus.UNKNOWN.isFinal)
    }

    @Test
    fun `contact types map and unknown types fall back to other`() {
        assertEquals(ContactKind.PHONE, PaymentContact("phone", "+993").kind)
        assertEquals(ContactKind.TELEGRAM, PaymentContact("Telegram", "@omnix").kind)
        assertEquals(ContactKind.IMO, PaymentContact("imo", "1").kind)
        assertEquals(ContactKind.WHATSAPP, PaymentContact("whatsapp", "1").kind)
        assertEquals(ContactKind.OTHER, PaymentContact("fax", "1").kind)
    }

    @Test
    fun `price shows whole amounts without cents and keeps real cents`() {
        assertEquals("149 TMT", formatPrice(14_900, "TMT"))
        assertEquals("299 TMT", formatPrice(29_900, "TMT"))
        assertEquals("149.50 TMT", formatPrice(14_950, "TMT"))
        assertEquals("0 TMT", formatPrice(0, "TMT"))
    }

    @Test
    fun `plans rank free below pro below max`() {
        assertTrue(planRank("omnix") > planRank("pro"))
        assertTrue(planRank("pro") > planRank("free"))
        assertEquals(planRank("free"), planRank(""))
        assertEquals(planRank("free"), planRank("something-else"))
    }

    @Test
    fun `a paid request for a higher plan asks for a license refresh`() {
        assertTrue(needsLicenseRefresh(listOf(order("pro", ManualOrderStatus.PAID)), "free"))
        assertTrue(needsLicenseRefresh(listOf(order("omnix", ManualOrderStatus.PAID)), "pro"))
        assertTrue(needsLicenseRefresh(listOf(order("pro", ManualOrderStatus.PAID)), ""))
    }

    @Test
    fun `no refresh when the plan already matches or the request is not paid`() {
        assertFalse(needsLicenseRefresh(listOf(order("pro", ManualOrderStatus.PAID)), "pro"))
        assertFalse(needsLicenseRefresh(listOf(order("pro", ManualOrderStatus.PAID)), "omnix"))
        assertFalse(needsLicenseRefresh(listOf(order("pro", ManualOrderStatus.PENDING)), "free"))
        assertFalse(needsLicenseRefresh(listOf(order("pro", ManualOrderStatus.CANCELED)), "free"))
        assertFalse(needsLicenseRefresh(emptyList(), "free"))
    }

    @Test
    fun `the open request wins over closed ones for a plan`() {
        val orders = listOf(
            order("pro", ManualOrderStatus.EXPIRED, "OMX-1"),
            order("pro", ManualOrderStatus.PENDING, "OMX-2"),
            order("omnix", ManualOrderStatus.PAID, "OMX-3")
        )
        assertEquals("OMX-2", orderForPlan(orders, "pro")?.number)
        assertEquals("OMX-3", orderForPlan(orders, "omnix")?.number)
        assertNull(orderForPlan(orders, "free"))
    }

    @Test
    fun `the first listed request is shown when none is open`() {
        val orders = listOf(
            order("pro", ManualOrderStatus.PAID, "OMX-9"),
            order("pro", ManualOrderStatus.EXPIRED, "OMX-8")
        )
        assertEquals("OMX-9", orderForPlan(orders, "pro")?.number)
    }

    @Test
    fun `free plan is recognised by zero price`() {
        assertTrue(TariffPlan("free", "OMNIX Free", 0, "TMT", 30).isFree)
        assertFalse(TariffPlan("pro", "OMNIX Pro", 14_900, "TMT", 30).isFree)
    }
}
