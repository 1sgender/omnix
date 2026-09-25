package com.omnix.assistant.presentation.activation

import android.content.Context
import com.omnix.assistant.R
import com.omnix.assistant.core.license.ActivationResult
import com.omnix.assistant.core.license.LicenseInfo
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.license.LicenseRefreshResult
import com.omnix.assistant.testing.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivationViewModelTest {
    @get:Rule
    val mainDispatcher = MainDispatcherRule()

    private val context = mockk<Context> {
        every { getString(R.string.pozhaluysta_vvedite_kod) } returns "Введите код"
    }

    @Test
    fun `input keeps digits only and is bounded by the six cells`() =
        runTest(mainDispatcher.dispatcher) {
            val viewModel = ActivationViewModel(context, FakeLicenseManager())

            viewModel.onCodeChanged(" a1b_2c!3 4567890 ")

            assertEquals("123456", viewModel.uiState.value.inputCode)
            assertEquals(6, viewModel.uiState.value.inputCode.length)
        }

    @Test
    fun `blank activation is rejected without calling license manager`() =
        runTest(mainDispatcher.dispatcher) {
            val manager = FakeLicenseManager()
            val viewModel = ActivationViewModel(context, manager)

            viewModel.activate()

            assertEquals("Введите код", viewModel.uiState.value.errorMessage)
            assertEquals(0, manager.activationCalls)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `successful activation exposes server license and clears loading`() =
        runTest(mainDispatcher.dispatcher) {
            val info = LicenseInfo(
                isActivated = true,
                planId = "pro",
                expiryDate = System.currentTimeMillis() + 86_400_000
            )
            val manager = FakeLicenseManager(
                activationResult = ActivationResult.Success(info, "Активировано")
            )
            val viewModel = ActivationViewModel(context, manager)
            viewModel.onCodeChanged("472915")

            viewModel.activate()
            advanceUntilIdle()

            assertEquals("472915", manager.lastCode)
            assertTrue(viewModel.uiState.value.isActivated)
            assertEquals(info, viewModel.uiState.value.licenseInfo)
            assertEquals("Активировано", viewModel.uiState.value.successMessage)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    @Test
    fun `server failure is shown and does not activate UI`() =
        runTest(mainDispatcher.dispatcher) {
            val manager = FakeLicenseManager(
                activationResult = ActivationResult.ServiceUnavailable("Сервер недоступен")
            )
            val viewModel = ActivationViewModel(context, manager)
            viewModel.onCodeChanged("654321")

            viewModel.activate()
            advanceUntilIdle()

            assertEquals("Сервер недоступен", viewModel.uiState.value.errorMessage)
            assertFalse(viewModel.uiState.value.isActivated)
            assertFalse(viewModel.uiState.value.isLoading)
        }

    private class FakeLicenseManager(
        initial: LicenseInfo = LicenseInfo(isActivated = false),
        var activationResult: ActivationResult = ActivationResult.InvalidCode("invalid")
    ) : LicenseManager {
        private val state = MutableStateFlow(initial)
        var activationCalls = 0
        var lastCode: String? = null

        override val licenseFlow: Flow<LicenseInfo> = state
        override fun getLicenseInfo(): LicenseInfo = state.value
        override fun isActivatedAndValid(): Boolean = state.value.isActivated && !state.value.isExpired
        override suspend fun refreshFromServer(): LicenseRefreshResult = LicenseRefreshResult.Invalid

        override suspend fun activateWithCode(code: String): ActivationResult {
            activationCalls++
            lastCode = code
            if (activationResult is ActivationResult.Success) {
                state.value = (activationResult as ActivationResult.Success).licenseInfo
            }
            return activationResult
        }
    }
}
