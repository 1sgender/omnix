package com.omnix.assistant.presentation.headset

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.omnix.assistant.R
import com.omnix.assistant.core.headset.HeadsetGateState
import com.omnix.assistant.core.headset.LockReason
import com.omnix.assistant.core.headset.OmnixHeadsetPolicy
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.settings.OmnixGroupDivider
import com.omnix.assistant.presentation.settings.OmnixSettingRow
import com.omnix.assistant.presentation.settings.OmnixSettingsGroup
import com.omnix.assistant.presentation.settings.OmnixToggleRow
import com.omnix.assistant.presentation.settings.SectionScaffold

/**
 * Пускает дальше, только когда подключены наушники OMNIX (или это админ).
 * Пока состояние неизвестно ([HeadsetGateState.Checking]) — показывает
 * содержимое: блокировка не должна мигать на каждом холодном старте.
 */
@Composable
fun OmnixHeadsetGateHost(
    viewModel: OmnixHeadsetViewModel = hiltViewModel(),
    content: @Composable () -> Unit
) {
    val state by viewModel.gateState.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }
    when (val current = state) {
        is HeadsetGateState.Locked -> OmnixHeadsetRequiredScreen(
            reason = current.reason,
            onRefresh = viewModel::refresh
        )
        else -> content()
    }
}

@Composable
private fun OmnixHeadsetRequiredScreen(
    reason: LockReason,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val spacing = OmnixTheme.spacing
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.omnix_headset_required_title),
            style = OmnixTheme.typography.heading,
            color = OmnixTheme.colors.textPrimary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = stringResource(
                when (reason) {
                    LockReason.NO_PERMISSION -> R.string.omnix_headset_required_permission
                    LockReason.BLUETOOTH_OFF -> R.string.omnix_headset_required_bluetooth_off
                    LockReason.NO_OMNIX_HEADSET -> R.string.omnix_headset_required_body
                }
            ),
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textSecondary,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(spacing.lg))
        if (reason == LockReason.NO_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_headset_grant_permission),
                onClick = { permission.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_headset_open_bluetooth),
                onClick = {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(spacing.sm))
        OmnixSecondaryButton(
            text = stringResource(R.string.omnix_headset_check_again),
            onClick = onRefresh,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * Админ: регистрация наушников OMNIX на этом телефоне, их список и проверка
 * «и для меня», чтобы увидеть экран блокировки глазами пользователя.
 */
@Composable
fun OmnixHeadsetsAdminScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: OmnixHeadsetViewModel = hiltViewModel()
) {
    val registered by viewModel.registered.collectAsStateWithLifecycle()
    val connected by viewModel.connected.collectAsStateWithLifecycle()
    val enforce by viewModel.enforceForAdmin.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val pairedText = stringResource(R.string.omnix_headset_admin_paired)
    val failedText = stringResource(R.string.omnix_headset_admin_pair_failed)
    val startPairing = rememberOmnixHeadsetPairing(
        onPaired = { headset ->
            viewModel.register(headset)
            message = pairedText
        },
        onFailed = { error -> message = error ?: failedText }
    )
    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { startPairing() }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    SectionScaffold(stringResource(R.string.omnix_headset_admin_title), modifier, onBack) {
        val spacing = OmnixTheme.spacing
        Text(
            text = stringResource(R.string.omnix_headset_admin_body),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textTertiary
        )
        Spacer(Modifier.height(spacing.md))
        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_headset_admin_add),
            onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                } else {
                    startPairing()
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        message?.let {
            Spacer(Modifier.height(spacing.sm))
            Text(text = it, style = OmnixTheme.typography.caption, color = OmnixTheme.colors.textSecondary)
        }

        Spacer(Modifier.height(spacing.lg))
        OmnixSettingsGroup {
            if (registered.isEmpty()) {
                OmnixSettingRow(title = stringResource(R.string.omnix_headset_admin_none), inset = true)
            }
            val connectedAddresses = connected.mapTo(HashSet()) { OmnixHeadsetPolicy.normalize(it.address) }
            registered.forEachIndexed { index, headset ->
                if (index > 0) OmnixGroupDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OmnixSettingRow(
                        title = headset.name,
                        subtitle = headset.address,
                        value = if (headset.address in connectedAddresses) {
                            stringResource(R.string.omnix_headset_admin_connected)
                        } else {
                            null
                        },
                        inset = true,
                        modifier = Modifier.weight(1f)
                    )
                    OmnixTextButton(
                        text = stringResource(R.string.omnix_headset_admin_remove),
                        onClick = { viewModel.remove(headset.address) }
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.md))
        OmnixSettingsGroup {
            OmnixToggleRow(
                title = stringResource(R.string.omnix_headset_admin_enforce),
                subtitle = stringResource(R.string.omnix_headset_admin_enforce_body),
                checked = enforce,
                onCheckedChange = viewModel::setEnforceForAdmin,
                inset = true
            )
        }
    }
}
