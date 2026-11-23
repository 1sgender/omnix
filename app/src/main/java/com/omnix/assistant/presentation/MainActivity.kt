package com.omnix.assistant.presentation

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.omnix.assistant.R
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.core.license.LicenseManager
import com.omnix.assistant.core.permission.PermissionOutcome
import com.omnix.assistant.core.permission.PermissionPrompts
import com.omnix.assistant.core.permission.PermissionTexts
import com.omnix.assistant.presentation.permission.PermissionSettingsDialog
import com.omnix.assistant.domain.chat.ChatConversationStore
import com.omnix.assistant.presentation.activation.ActivationScreen
import com.omnix.assistant.presentation.localmodel.OfflinePackDialog
import com.omnix.assistant.voice.offline.OfflinePack
import com.omnix.assistant.voice.offline.OfflineSpeechModelStore
import com.omnix.assistant.presentation.launch.LaunchStatus
import com.omnix.assistant.presentation.launch.OmnixLaunchScreen
import com.omnix.assistant.core.license.LicenseRefreshResult
import com.omnix.assistant.presentation.navigation.OmnixNavGraph
import com.omnix.assistant.update.AppUpdatePrompt
import com.omnix.assistant.update.OtaDownloadMonitor
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.omnix.assistant.data.preferences.OmnixExperienceStore
import com.omnix.assistant.presentation.firstrun.FirstRunRoute
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.AppearanceViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TOAST_MAX_CHARS = 80

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var licenseManager: LicenseManager

    @Inject
    lateinit var experienceStore: OmnixExperienceStore

    @Inject
    lateinit var localModelManager: LocalModelManager

    /** Офлайн-режим: локальный ИИ + голос + распознавание одним пакетом. */
    @Inject
    lateinit var offlinePack: OfflinePack

    @Inject
    lateinit var speechModelStore: OfflineSpeechModelStore

    /** Ход OTA-загрузки для дуги ядра (см. AppUpdatePrompt). */
    @Inject
    lateinit var otaDownloadMonitor: OtaDownloadMonitor

    @Inject
    lateinit var chatConversations: ChatConversationStore

    private val permissionPromptLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> onPermissionPromptResult(grants) }

    /** Разрешения, отказанные насовсем: непустой список показывает диалог «Открыть настройки». */
    private var settingsPromptPermissions: List<String> by mutableStateOf(emptyList())

    /**
     * Ответ на системный запрос от инструмента. Выдали: команда повторяется
     * сама и её итог объявляется (чат показывает, голос озвучивает). Отказали
     * насовсем: системный диалог больше не покажется, ведём в настройки.
     */
    private fun onPermissionPromptResult(grants: Map<String, Boolean>) {
        if (grants.isEmpty()) {
            // Диалог закрыт без ответа: отложенный повтор больше не актуален.
            PermissionPrompts.cancelRetries()
            return
        }
        val denied = grants.filterValues { granted -> !granted }.keys.toList()
        val retry = PermissionPrompts.takeRetry()
        if (denied.isEmpty()) {
            if (retry == null) return
            lifecycleScope.launch {
                val outcome = runCatching { retry() }.getOrNull()
                announcePermissionOutcome(outcome ?: getString(R.string.omnix_perm_granted_repeat))
            }
            return
        }
        val permanent = PermissionOutcome.permanentlyDenied(denied) { shouldShowRequestPermissionRationale(it) }
        if (permanent.isNotEmpty()) settingsPromptPermissions = permanent
        announcePermissionOutcome(getString(R.string.omnix_perm_denied))
    }

    private fun announcePermissionOutcome(message: String) {
        // Тост только для коротких статусов: длинный итог инструмента (текст, список)
        // в тосте обрезается и пропадает, он доезжает через announce в чат и голос.
        if (message.length <= TOAST_MAX_CHARS) Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        PermissionPrompts.announce(message)
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
        runCatching { startActivity(intent) }
    }

    /** Guards the first frame while stored preferences are read. */
    private var splashHeld: Boolean by mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Инструменту не хватило разрешения: спрашиваем системным диалогом сразу.
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                com.omnix.assistant.core.permission.PermissionPrompts.pending.collect { wanted ->
                    if (wanted.isNotEmpty()) {
                        com.omnix.assistant.core.permission.PermissionPrompts.clear()
                        val missing = wanted.filter {
                            androidx.core.content.ContextCompat.checkSelfPermission(this@MainActivity, it) !=
                                android.content.pm.PackageManager.PERMISSION_GRANTED
                        }
                        if (missing.isNotEmpty()) permissionPromptLauncher.launch(missing.toTypedArray())
                    }
                }
            }
        }
        // Each fresh open of the app starts a new chat; the process may have
        // stayed alive for the voice service. A recreated activity (rotation,
        // theme) keeps the open conversation.
        if (savedInstanceState == null) chatConversations.startNew()

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
                    // Сервер не ответил, а свежей успешной проверки на телефоне нет:
                    // показываем это прямо (повторить / ввести код), а не молча
                    // отправляем человека с действующей лицензией на активацию.
                    var serverUnreachable by remember { mutableStateOf(false) }
                    var licenceRetry by remember { mutableIntStateOf(0) }

                    LaunchedEffect(showFirstRun, licenceRetry) {
                        if (!showFirstRun) {
                            serverUnreachable = false
                            // Кэш не открывает продукт сам: при любой связи решает
                            // сервер; без связи LicenseManager применяет офлайн-льготу
                            // по непросроченному кэшу последней успешной проверки.
                            val result = licenseManager.refreshFromServer()
                            serverUnreachable = result is LicenseRefreshResult.ServiceUnavailable ||
                                result is LicenseRefreshResult.RateLimited
                            serverCheckComplete = !serverUnreachable
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
                        serverUnreachable -> {
                            OmnixLaunchScreen(
                                status = LaunchStatus.ServerUnreachable,
                                onRetry = { licenceRetry += 1 },
                                onEnterCode = {
                                    serverUnreachable = false
                                    serverCheckComplete = true
                                }
                            )
                        }
                        !serverCheckComplete -> {
                            OmnixLaunchScreen(status = LaunchStatus.CheckingLicence)
                        }
                        !licenseInfo.isActivated || licenseInfo.isExpired -> {
                            ActivationScreen(onActivationSuccess = { serverCheckComplete = true })
                        }
                        else -> {
                            // Вопрос про офлайн-режим (локальный ИИ + голос +
                            // распознавание): только после активации, при каждом
                            // запуске, пока пакет не на телефоне и не качается
                            // (решение владельца 2026-11-12). «Позже» — до
                            // следующего запуска.
                            val modelState by localModelManager.stateFlow.collectAsState(
                                initial = LocalModelState.NotInitialized
                            )
                            val speechStates by speechModelStore.states.collectAsState()
                            val packInstalled by produceState<Boolean?>(null, modelState, speechStates) {
                                value = offlinePack.isInstalled()
                            }
                            var packPromptDismissed by rememberSaveable { mutableStateOf(false) }
                            if (modelState is LocalModelState.Loading) {
                                // Первый запрос: модель грузится в память. Спокойный
                                // экран загрузки на весь период Loading — навграф
                                // перестраивается, когда состояние уйдёт (Ready,
                                // Failed, InsufficientMemory — фолбэк в облако
                                // делает приложение пригодным в любом исходе).
                                // Downloading намеренно НЕ блокирует: у
                                // пользователя должен остаться путь в настройки,
                                // где загрузку можно отменить.
                                OmnixLaunchScreen(status = LaunchStatus.LoadingModel)
                            } else {
                                OmnixNavGraph()
                                if (packInstalled == false && !packPromptDismissed &&
                                    !offlinePack.isDownloading(modelState)
                                ) {
                                    OfflinePackDialog(
                                        totalSizeMb = offlinePack.totalSizeMb,
                                        onDownloadAny = {
                                            packPromptDismissed = true
                                            lifecycleScope.launch { offlinePack.downloadAll(overMetered = true) }
                                        },
                                        onDownloadWifi = {
                                            packPromptDismissed = true
                                            lifecycleScope.launch { offlinePack.downloadAll(overMetered = false) }
                                        },
                                        onLater = { packPromptDismissed = true }
                                    )
                                }
                            }
                        }
                    }
                    // Самообновление staging-сборок: проверка раз за холодный
                    // старт на ВСЕХ экранах (включая активацию и онбординг) —
                    // иначе баг в активации лечится только ручной переустановкой.
                    // На остальных флейворах молчит.
                    AppUpdatePrompt(otaDownloadMonitor)
                    if (settingsPromptPermissions.isNotEmpty()) {
                        PermissionSettingsDialog(
                            accessLabel = PermissionTexts.describe(this@MainActivity, settingsPromptPermissions),
                            onOpenSettings = {
                                settingsPromptPermissions = emptyList()
                                openAppSettings()
                            },
                            onDismiss = { settingsPromptPermissions = emptyList() }
                        )
                    }
                }
            }
        }
    }

}
