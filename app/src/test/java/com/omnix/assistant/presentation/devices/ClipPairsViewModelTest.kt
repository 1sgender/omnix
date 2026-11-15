package com.omnix.assistant.presentation.devices

import android.content.Context
import com.omnix.assistant.core.clip.ClipPairBindResult
import com.omnix.assistant.core.clip.ClipPairOutcome
import com.omnix.assistant.core.clip.ClipPairService
import com.omnix.assistant.core.clip.ConnectedPair
import com.omnix.assistant.core.license.ClipBindingState
import com.omnix.assistant.core.license.ClipPairInfo
import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.testing.MainDispatcherRule
import com.omnix.assistant.voice.audio.BluetoothAudioRouter
import com.omnix.assistant.voice.audio.BluetoothAudioState
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

/** «Мои наушники»: список из лицензии, предложение новой пары, отвязка, оператор. */
@OptIn(ExperimentalCoroutinesApi::class)
class ClipPairsViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = mockk<Context>(relaxed = true)
    private val service = mockk<ClipPairService>(relaxed = true)
    private val audio = MutableStateFlow<BluetoothAudioState>(BluetoothAudioState.Disconnected)
    private val router = mockk<BluetoothAudioRouter> { every { audioState } returns audio }
    private val pair = ClipPairInfo("OMX-1", "AA:BB:CC:DD:EE:01")
    private val licenseFlow = MutableStateFlow(
        LicenseInfo(isActivated = true, clipBinding = ClipBindingState(pairRequired = true, pairs = listOf(pair)))
    )
    private val license = mockk<LicenseManager>(relaxed = true) {
        every { licenseFlow } returns this@ClipPairsViewModelTest.licenseFlow
        every { isActivatedAndValid() } returns true
    }

    private fun viewModel() = ClipPairsViewModel(context, service, license, router)

    @Test
    fun `pairs come from license and connected own pair is marked`() = runTest(mainDispatcher.dispatcher) {
        every { service.connectedAddress() } returns "AA:BB:CC:DD:EE:01"
        coEvery { service.connectedPair() } returns ConnectedPair.Own("OMX-1", null)
        val vm = viewModel()
        advanceUntilIdle()
        assertEquals(listOf(pair), vm.uiState.value.pairs)
        assertFalse(vm.isConnected(pair))
        audio.value = BluetoothAudioState.Connected("OMNIX Clip")
        advanceUntilIdle()
        assertTrue(vm.isConnected(pair))
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun `new available pair is offered and added with trial`() = runTest(mainDispatcher.dispatcher) {
        every { service.connectedAddress() } returns "AA:BB:CC:DD:EE:02"
        coEvery { service.connectedPair() } returns ConnectedPair.Available("OMX-2", null)
        val until = Instant.parse("2026-11-20T00:00:00Z")
        coEvery { service.bindConnected(true) } returns ClipPairBindResult(ClipPairOutcome.ADDED, until)
        val vm = viewModel()
        audio.value = BluetoothAudioState.Connected("OMNIX Clip")
        advanceUntilIdle()
        assertEquals(ConnectedPair.Available("OMX-2", null), vm.uiState.value.offer)
        vm.addOffered()
        advanceUntilIdle()
        assertNull(vm.uiState.value.offer)
        assertEquals(ClipPairOutcome.ADDED, vm.uiState.value.message)
        assertEquals(until, vm.uiState.value.trialUntil)
        vm.clearMessage()
        assertNull(vm.uiState.value.message)
        // Отказ от предложения.
        audio.value = BluetoothAudioState.Disconnected
        advanceUntilIdle()
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun `release needs confirmation and operator registers by serial`() = runTest(mainDispatcher.dispatcher) {
        coEvery { service.release("OMX-1") } returns ClipPairOutcome.RELEASED
        coEvery { service.operatorRegister("OMX-7", null) } returns ClipPairOutcome.REGISTERED
        val vm = viewModel()
        advanceUntilIdle()
        vm.confirmRelease() // без вопроса — ничего
        coVerify(exactly = 0) { service.release(any()) }
        vm.askRelease(pair)
        assertEquals(pair, vm.uiState.value.confirmRelease)
        vm.cancelRelease()
        assertNull(vm.uiState.value.confirmRelease)
        vm.askRelease(pair)
        vm.confirmRelease()
        advanceUntilIdle()
        assertEquals(ClipPairOutcome.RELEASED, vm.uiState.value.message)

        vm.registerConnected() // пустой серийник — ничего
        coVerify(exactly = 0) { service.operatorRegister(any(), any()) }
        vm.onOperatorSerialChanged(" OMX-7!? ")
        assertEquals("OMX-7", vm.uiState.value.operatorSerial)
        vm.registerConnected()
        advanceUntilIdle()
        assertEquals(ClipPairOutcome.REGISTERED, vm.uiState.value.message)
        assertEquals("", vm.uiState.value.operatorSerial)
        vm.dismissOffer()
        assertNull(vm.uiState.value.offer)
    }
}
