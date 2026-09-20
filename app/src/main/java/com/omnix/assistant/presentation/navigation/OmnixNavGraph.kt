package com.omnix.assistant.presentation.navigation

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.omnix.assistant.presentation.chat.ChatViewModel
import com.omnix.assistant.presentation.chat.OmnixChatScreen
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.devices.DevicesScreen
import com.omnix.assistant.presentation.history.HistoryScreen
import com.omnix.assistant.presentation.home.HomeScreen
import com.omnix.assistant.presentation.settings.MeScreen
import com.omnix.assistant.presentation.settings.PrivacyScreen
import com.omnix.assistant.presentation.settings.SECTION_ABOUT
import com.omnix.assistant.presentation.settings.SECTION_ADVANCED
import com.omnix.assistant.presentation.settings.SECTION_AI
import com.omnix.assistant.presentation.settings.SECTION_APPEARANCE
import com.omnix.assistant.presentation.settings.SECTION_LANGUAGE
import com.omnix.assistant.presentation.settings.SECTION_NOTIFICATIONS
import com.omnix.assistant.presentation.settings.SECTION_VOICE
import com.omnix.assistant.presentation.settings.SettingsSectionRoute
import com.omnix.assistant.presentation.state.OmnixViewModel
import com.omnix.assistant.presentation.state.SystemStateType
import com.omnix.assistant.presentation.translator.TranslatorRoute

/**
 * The OMNIX navigation graph (§20, §44).
 *
 * One `NavHost`, one persistent navigation bar, one shared [OmnixViewModel].
 * State remains hoisted above the host so returning Home restores the current
 * Core state without a separate, competing status indicator in navigation.
 *
 * Screen transitions are plain cross-fades: sliding panes would fight the
 * stillness the product depends on (§29).
 */
@Composable
fun OmnixNavGraph(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
    omnixViewModel: OmnixViewModel = hiltViewModel()
) {
    val uiState by omnixViewModel.uiState.collectAsState()
    val enterMs = OmnixTheme.motion.screenEnterMs
    val exitMs = OmnixTheme.motion.screenExitMs
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(OmnixTheme.colors.background)
    ) {
        NavHost(
            navController = navController,
            startDestination = OmnixDestination.Home.route,
            modifier = Modifier
                .weight(1f)
                .statusBarsPadding(),
            // Durations come from the motion tokens, which collapse when
            // reduced motion is on — a transitionSpec lambda cannot read
            // OmnixTheme, so they are hoisted here (§29).
            enterTransition = { fadeIn(tween(enterMs)) },
            exitTransition = { fadeOut(tween(exitMs)) },
            popEnterTransition = { fadeIn(tween(enterMs)) },
            popExitTransition = { fadeOut(tween(exitMs)) }
        ) {
            composable(OmnixDestination.Home.route) {
                HomeScreen(
                    state = uiState,
                    onClipTap = { navController.navigateSingleTop(OmnixDestination.Devices) },
                    onSystemStateAction = { omnixViewModel.refreshPermissions() }
                )
            }

            composable(OmnixDestination.History.route) {
                val chatViewModel: ChatViewModel = hiltViewModel()
                val chatState by chatViewModel.uiState.collectAsState()
                HistoryScreen(
                    messages = chatState.messages,
                    onClear = chatViewModel::clearAllHistory
                )
            }

            composable(OmnixDestination.Me.route) {
                MeScreen(
                    clip = uiState.clip,
                    isOnline = uiState.isOnline,
                    onOpenSection = { navController.navigateSingleTop(it) }
                )
            }

            composable(OmnixDestination.Chat.route) {
                val chatViewModel: ChatViewModel = hiltViewModel()
                val chatState by chatViewModel.uiState.collectAsState()
                OmnixChatScreen(
                    state = chatState,
                    onBack = navController::popBackStack,
                    onInputChange = chatViewModel::onInputTextChanged,
                    onSend = { chatViewModel.sendTextMessage() },
                    onConfirm = chatViewModel::confirmPendingAction,
                    onCancel = chatViewModel::cancelPendingAction,
                    onClear = chatViewModel::clearAllHistory,
                    onAllowCloud = chatViewModel::confirmCloudConsent,
                    onKeepLocal = chatViewModel::denyCloudConsent,
                    onToggleDictation = chatViewModel::toggleVoiceDictation
                )
            }

            composable(OmnixDestination.Translator.route) {
                TranslatorRoute(
                    audioLevel = uiState.audioLevel,
                    onBack = navController::popBackStack
                )
            }

            composable(OmnixDestination.Devices.route) {
                DevicesScreen(
                    clip = uiState.clip,
                    isOnline = uiState.isOnline,
                    onBack = navController::popBackStack,
                    onConnect = { omnixViewModel.setSearching(true) }
                )
            }

            composable(OmnixDestination.Privacy.route) {
                // Privacy states facts, but its two actions must be real:
                // "manage permissions" opens this app's system page, and
                // "delete history" clears the one shared log (§3).
                val context = LocalContext.current
                val privacyChatViewModel: ChatViewModel = hiltViewModel()
                PrivacyScreen(
                    microphoneAllowed =
                        uiState.systemState != SystemStateType.MICROPHONE_DENIED,
                    historyStored = true,
                    onBack = navController::popBackStack,
                    onManagePermissions = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(
                                    Uri.fromParts("package", context.packageName, null)
                                )
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onDeleteHistory = privacyChatViewModel::clearAllHistory
                )
            }

            composable(
                route = OmnixDestination.SettingsSection.ROUTE_PATTERN,
                arguments = listOf(
                    navArgument(OmnixDestination.SettingsSection.ARG_SECTION) {
                        type = NavType.StringType
                    }
                )
            ) { entry ->
                val section = entry.arguments
                    ?.getString(OmnixDestination.SettingsSection.ARG_SECTION)
                    .orEmpty()
                SettingsSectionRoute(
                    section = section,
                    onBack = navController::popBackStack
                )
            }
        }

        // Navigation stays outside the NavHost so it remains a stable way to
        // reach Home, History and Profile while the live Core itself keeps a
        // single, unambiguous home on the Home screen.
        OmnixNavigationBar(
            currentRoute = currentRoute,
            onNavigate = { navController.navigateSingleTop(it) }
        )
    }

    // The confirmation sheet lives above every screen: a spoken command can
    // require confirmation while the user is looking at Settings (§17).
    uiState.confirmation?.let { request ->
        ConfirmationSheet(
            request = request,
            onConfirm = omnixViewModel::confirmPendingAction,
            onCancel = omnixViewModel::cancelPendingAction
        )
    }
}

/**
 * Navigates without stacking duplicates of the primary destinations, so the
 * back button always leads out of the app rather than through a history of
 * tab switches.
 */
private fun NavHostController.navigateSingleTop(destination: OmnixDestination) {
    navigate(destination.route) {
        if (destination in OmnixDestination.primary) {
            popUpTo(OmnixDestination.Home.route) { saveState = true }
            restoreState = true
        }
        launchSingleTop = true
    }
}

/** Section keys, re-exported so callers do not import the settings package. */
internal val settingsSections = listOf(
    SECTION_VOICE,
    SECTION_AI,
    SECTION_LANGUAGE,
    SECTION_NOTIFICATIONS,
    SECTION_APPEARANCE,
    SECTION_ABOUT,
    SECTION_ADVANCED
)
