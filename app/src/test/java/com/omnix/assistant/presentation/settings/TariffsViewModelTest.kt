package com.omnix.assistant.presentation.settings

import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.payments.ManualOrder
import com.omnix.assistant.core.payments.ManualOrderStatus
import com.omnix.assistant.core.payments.ManualPaymentApi
import com.omnix.assistant.core.payments.OrderTicket
import com.omnix.assistant.core.payments.PaymentsResult
import com.omnix.assistant.core.payments.TariffPlan
import com.omnix.assistant.core.payments.TariffsCatalog
import com.omnix.assistant.testing.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TariffsViewModelTest {

    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val licenseFlow = MutableStateFlow(LicenseInfo(isActivated = true, planId = "free"))
    private val license = mockk<LicenseManager>(relaxed = true).also {
        every { it.licenseFlow } returns licenseFlow
        every { it.getLicenseInfo() } answers { licenseFlow.value }
    }

    private fun order(status: ManualOrderStatus, plan: String = "pro") = ManualOrder(
        orderId = "o1", number = "OMX-4821", planId = plan, status = status,
        amountMinor = 14_900, currency = "TMT", expiresAtIso = "2026-11-17T10:00:00Z"
    )

    private fun catalog(vararg orders: ManualOrder) = TariffsCatalog(
        plans = listOf(TariffPlan("free", "OMNIX Free", 0, "TMT", 0), TariffPlan("pro", "OMNIX Pro", 14_900, "TMT", 30)),
        contacts = emptyList(),
        orders = orders.toList()
    )

    @Test
    fun `catalog loads on start`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog())
        val vm = TariffsViewModel(api, license)
        advanceUntilIdle()
        assertFalse(vm.state.value.loading)
        assertEquals(2, vm.state.value.catalog?.plans?.size)
        assertNull(vm.state.value.failure)
        assertEquals("free", vm.state.value.currentPlanId)
    }

    @Test
    fun `load failures are mapped for the screen`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.NoConnection
        val vm = TariffsViewModel(api, license)
        advanceUntilIdle()
        assertEquals(TariffsFailure.NoConnection, vm.state.value.failure)
        assertNull(vm.state.value.catalog)

        coEvery { api.tariffs() } returns PaymentsResult.RateLimited
        vm.refresh()
        advanceUntilIdle()
        assertEquals(TariffsFailure.Busy, vm.state.value.failure)

        coEvery { api.tariffs() } returns PaymentsResult.Unavailable
        vm.refresh()
        advanceUntilIdle()
        assertEquals(TariffsFailure.Server, vm.state.value.failure)
    }

    @Test
    fun `a paid request makes the license refresh by itself`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog(order(ManualOrderStatus.PAID)))
        TariffsViewModel(api, license)
        advanceUntilIdle()
        coVerify(exactly = 1) { license.refreshFromServer() }
    }

    @Test
    fun `a pending request does not touch the license`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog(order(ManualOrderStatus.PENDING)))
        TariffsViewModel(api, license)
        advanceUntilIdle()
        coVerify(exactly = 0) { license.refreshFromServer() }
    }

    @Test
    fun `requesting a plan creates the order and reloads the catalog`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog())
        coEvery { api.createOrder("pro") } returns PaymentsResult.Ok(OrderTicket(order(ManualOrderStatus.PENDING), emptyList()))
        val vm = TariffsViewModel(api, license)
        advanceUntilIdle()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog(order(ManualOrderStatus.PENDING)))
        vm.request("pro")
        advanceUntilIdle()
        coVerify(exactly = 1) { api.createOrder("pro") }
        assertNull(vm.state.value.creatingPlanId)
        assertEquals(1, vm.state.value.catalog?.orders?.size)
    }

    @Test
    fun `a failed request keeps the screen and shows the reason`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog())
        coEvery { api.createOrder("pro") } returns PaymentsResult.NoLicense
        val vm = TariffsViewModel(api, license)
        advanceUntilIdle()
        vm.request("pro")
        advanceUntilIdle()
        assertEquals(TariffsFailure.NoLicense, vm.state.value.failure)
        assertNull(vm.state.value.creatingPlanId)
        assertNotNull(vm.state.value.catalog)
    }

    @Test
    fun `the current plan follows the license flow`() = runTest(mainDispatcher.dispatcher) {
        val api = mockk<ManualPaymentApi>()
        coEvery { api.tariffs() } returns PaymentsResult.Ok(catalog())
        val vm = TariffsViewModel(api, license)
        advanceUntilIdle()
        licenseFlow.value = LicenseInfo(isActivated = true, planId = "pro")
        advanceUntilIdle()
        assertEquals("pro", vm.state.value.currentPlanId)
    }

    @Test
    fun `payment results map to failures`() {
        assertNull(PaymentsResult.Ok(Unit).toFailure())
        assertEquals(TariffsFailure.Server, PaymentsResult.Unauthorized.toFailure())
        assertEquals(TariffsFailure.NoLicense, PaymentsResult.NoLicense.toFailure())
    }
}
