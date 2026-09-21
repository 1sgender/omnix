package com.omnix.assistant.presentation

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.localai.downloader.ModelDownloadPolicy
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.data.preferences.SettingsDataStore
import com.omnix.assistant.presentation.activation.ActivationScreen
import com.omnix.assistant.presentation.localmodel.LocalModelConsentDialog
import com.omnix.assistant.presentation.navigation.OmnixNavGraph
import com.omnix.assistant.update.AppUpdatePrompt
import androidx.lifecycle.lifecycleScope
import com.omnix.assistant.data.preferences.OmnixExperienceStore
import com.omnix.assistant.presentation.core.CoreState
import com.omnix.assistant.presentation.firstrun.FirstRunRoute
import com.omnix.assistant.presentation.core.OmnixCore
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.AppearanceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var licenseManager: LicenseManager

    @Inject
    lateinit var experienceStore: OmnixExperienceStore

    @Inject
    lateinit var localModelManager: LocalModelManager

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    /** Guards the first frame while stored preferences are read. */
    private var splashHeld: Boolean by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Cheaper and more reliable than drawing a placeholder: the window
        // simply does not draw until the theme is known.
        window.decorView.findViewById<android.view.View>(android.R.id.content)?.let { content ->
            content.viewTreeObserver.addOnPreDrawListener { !splashHeld }
        }

        setContent {
            // Appearance is resolved above the theme so that the choice
            // applies to every screen at once (§47).
            val appearanceViewModel: AppearanceViewModel = hiltViewModel()
            val appearanceState by appearanceViewModel.uiState.collectAsState()

            // Hold the very first frame until the stored preferences have
            // been read. Without this a dark-mode user sees a light flash on
            // every cold start, because the default is resolved first.
            SideEffect { splashHeld = !appearanceState.loaded }

            OmnixTheme(
                appearance = appearanceState.appearance,
                nightMode = appearanceState.nightDimming,
                reducedMotionOverride = appearanceState.reducedMotion
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = OmnixTheme.colors.background
                ) {
                    // First run is driven by a persisted flag, not by the
                    // permission state: a user who skipped the microphone
                    // should not be sent back through onboarding forever.
                    val onboardingCompleted by experienceStore.onboardingCompleted
                        .collectAsState(initial = true)
                    var firstRunDone by remember { mutableStateOf(false) }
                    val showFirstRun = !onboardingCompleted && !firstRunDone
                    val licenseInfo by licenseManager.licenseFlow.collectAsState(
                        initial = licenseManager.getLicenseInfo()
                    )
                    var serverCheckComplete by remember { mutableStateOf(false) }

                    LaunchedEffect(showFirstRun) {
                        if (!showFirstRun) {
                            // Кэш не открывает продукт сам: при любой связи решает
                            // сервер; без связи LicenseManager применяет офлайн-льготу
                            // по непросроченному кэшу последней успешной проверки.
                            licenseManager.refreshFromServer()
                            serverCheckComplete = true
                        }
                    }

                    when {
                        showFirstRun -> {
                            FirstRunRoute(
                                onFinished = {
                                    firstRunDone = true
                                    lifecycleScope.launch {
                                        experienceStore.setOnboardingCompleted(true)
                                    }
                                }
                            )
                        }
                        !serverCheckComplete -> {
                            // No spinner anywhere in OMNIX (§29): the Core
                            // itself is the only "working" indicator.
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                OmnixCore(state = CoreState.THINKING)
                            }
                        }
                        !licenseInfo.isActivated || licenseInfo.isExpired -> {
                            ActivationScreen(onActivationSuccess = { serverCheckComplete = true })
                        }
                        else -> {
                            OmnixNavGraph()
                            // Одноразовый вопрос про локальную модель: только
                            // после активации, только если файла нет и пользователя
                            // ещё не спрашивали. Ответ записывается сразу —
                            // диалог не всплывёт повторно ни при каких рекомпозициях.
                            val modelState by localModelManager.stateFlow.collectAsState(
                                initial = LocalModelState.NotInitialized
                            )
                            val modelConsent by settingsDataStore.localModelConsentFlow
                                .collectAsState(initial = ModelDownloadPolicy.CONSENT_UNASKED)
                            if (modelState is LocalModelState.NotInstalled &&
                                modelConsent == ModelDownloadPolicy.CONSENT_UNASKED
                            ) {
                                LocalModelConsentDialog(
                                    onDownloadAny = {
                                        lifecycleScope.launch {
                                            settingsDataStore.setLocalModelConsent(
                                                ModelDownloadPolicy.CONSENT_ANY_NETWORK
                                            )
                                            localModelManager.ensureModel()
                                        }
                                    },
                                    onDownloadWifi = {
                                        lifecycleScope.launch {
                                            settingsDataStore.setLocalModelConsent(
                                                ModelDownloadPolicy.CONSENT_WIFI_ONLY
                                            )
                                            localModelManager.ensureModel()
                                        }
                                    },
                                    onLater = {
                                        lifecycleScope.launch {
                                            settingsDataStore.setLocalModelConsent(
                                                ModelDownloadPolicy.CONSENT_LATER
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                    // Самообновление staging-сборок: проверка раз за холодный
                    // старт на ВСЕХ экранах (включая активацию и онбординг) —
                    // иначе баг в активации лечится только ручной переустановкой.
                    // На остальных флейворах молчит.
                    AppUpdatePrompt()
                }
            }
        }
    }

}
