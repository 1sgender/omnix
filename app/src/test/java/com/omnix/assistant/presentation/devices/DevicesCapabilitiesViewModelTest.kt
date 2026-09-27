package com.omnix.assistant.presentation.devices

import com.omnix.assistant.agent.capability.CapabilityStatus
import com.omnix.assistant.agent.capability.DeviceCapabilityRegistry
import com.omnix.assistant.agent.capability.OmniCapability
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Capability-снимок для Devices (блок 6 плана пересборки фронта, 2026-09-26):
 * порядок строк = порядок документации OmniCapability, недостающая группа
 * не роняет список.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesCapabilitiesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `snapshot follows the documented group order`() = runTest {
        val registry = mockk<DeviceCapabilityRegistry>()
        every { registry.snapshotByGroup() } returns mapOf(
            OmniCapability.Bluetooth to CapabilityStatus.Available,
            OmniCapability.Sms to CapabilityStatus.PermissionRequired(
                listOf("android.permission.SEND_SMS")
            )
        )
        val vm = DevicesCapabilitiesViewModel(registry)
        advanceUntilIdle()

        val snapshot = vm.snapshot.value
        assertEquals(OmniCapability.all.size, snapshot.size)
        assertEquals(OmniCapability.all, snapshot.map { it.capability })
        assertEquals(CapabilityStatus.Available, snapshot.first { it.capability == OmniCapability.Bluetooth }.status)
    }

    @Test
    fun `missing group is reported as unsupported, not dropped`() = runTest {
        val registry = mockk<DeviceCapabilityRegistry>()
        every { registry.snapshotByGroup() } returns emptyMap()
        val vm = DevicesCapabilitiesViewModel(registry)
        advanceUntilIdle()

        val snapshot = vm.snapshot.value
        assertEquals(OmniCapability.all.size, snapshot.size)
        assertTrue(snapshot.all { it.status is CapabilityStatus.Unsupported })
    }

    @Test
    fun `refresh re-reads the registry`() = runTest {
        val registry = mockk<DeviceCapabilityRegistry>()
        every { registry.snapshotByGroup() } returns mapOf(
            OmniCapability.Media to CapabilityStatus.Available
        ) andThen mapOf(
            OmniCapability.Media to CapabilityStatus.Unsupported("нет")
        )
        val vm = DevicesCapabilitiesViewModel(registry)
        advanceUntilIdle()
        assertEquals(
            CapabilityStatus.Available,
            vm.snapshot.value.first { it.capability == OmniCapability.Media }.status
        )

        vm.refresh()
        advanceUntilIdle()
        assertEquals(
            CapabilityStatus.Unsupported("нет"),
            vm.snapshot.value.first { it.capability == OmniCapability.Media }.status
        )
    }
}
