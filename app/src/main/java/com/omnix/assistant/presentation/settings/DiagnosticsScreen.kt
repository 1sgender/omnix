package com.omnix.assistant.presentation.settings

import android.content.Intent
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixDivider
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.diagnostics.DiagnosticCheckId
import com.omnix.assistant.presentation.diagnostics.DiagnosticResult
import com.omnix.assistant.presentation.diagnostics.DiagnosticStatus
import com.omnix.assistant.presentation.diagnostics.DiagnosticsViewModel

/**
 * OMNIX DIAGNOSTICS (§21 ТЗ): экран самопроверки.
 *
 * 12 строк — каждая с РЕАЛЬНЫМ статусом от [DiagnosticsViewModel]
 * (ok/fail/warning, N/A — когда проверить нельзя). RUN FULL TEST гоняет
 * все проверки последовательно с прогрессом, EXPORT REPORT отдаёт
 * текстовый отчёт через ACTION_SEND.
 */
@Composable
fun DiagnosticsScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsState()
    val running by viewModel.running.collectAsState()
    val completed by viewModel.completed.collectAsState()
    val context = LocalContext.current
    val spacing = OmnixTheme.spacing

    SectionScaffold(stringResource(R.string.omnix_diagnostics_title), modifier, onBack) {
        rows.forEach { result ->
            DiagnosticRow(result = result)
        }

        Spacer(Modifier.height(spacing.md))

        OmnixPrimaryButton(
            text = if (running) {
                stringResource(R.string.omnix_diagnostics_running, completed, viewModel.total)
            } else {
                stringResource(R.string.omnix_diagnostics_run)
            },
            onClick = viewModel::runFullTest,
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(spacing.sm))

        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_diagnostics_export),
            onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, viewModel.buildReportText())
                }
                context.startActivity(
                    Intent.createChooser(
                        send,
                        context.getString(R.string.omnix_diagnostics_share_title)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            enabled = !running,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DiagnosticRow(result: DiagnosticResult) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val (label, color) = when (result.status) {
        DiagnosticStatus.OK -> "OK" to colors.stateSuccess
        DiagnosticStatus.WARNING -> "WARN" to colors.stateThinking
        DiagnosticStatus.FAIL -> "FAIL" to colors.stateError
        DiagnosticStatus.NA -> "N/A" to colors.textTertiary
        DiagnosticStatus.RUNNING -> "…" to colors.stateListening
        DiagnosticStatus.PENDING -> "•" to colors.textDisabled
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(titleFor(result.id)),
                style = OmnixTheme.typography.body,
                color = colors.textPrimary
            )
            if (result.detail.isNotEmpty()) {
                Text(
                    text = result.detail,
                    style = OmnixTheme.typography.caption,
                    color = colors.textSecondary
                )
            }
        }
        Text(
            text = label,
            style = OmnixTheme.typography.status,
            color = color,
            modifier = Modifier.border(
                width = 1.dp,
                color = color,
                shape = RoundedCornerShape(4.dp)
            ).padding(horizontal = spacing.sm, vertical = 2.dp)
        )
    }
    OmnixDivider()
}

private fun titleFor(id: DiagnosticCheckId): Int =
    when (id) {
        DiagnosticCheckId.MIC -> R.string.omnix_diag_mic
        DiagnosticCheckId.STT -> R.string.omnix_diag_stt
        DiagnosticCheckId.LOCAL_AI -> R.string.omnix_diag_local_ai
        DiagnosticCheckId.CLOUD_AI -> R.string.omnix_diag_cloud_ai
        DiagnosticCheckId.BLUETOOTH -> R.string.omnix_diag_bluetooth
        DiagnosticCheckId.CLIP -> R.string.omnix_diag_clip
        DiagnosticCheckId.ACCESSIBILITY -> R.string.omnix_diag_accessibility
        DiagnosticCheckId.PERMISSIONS -> R.string.omnix_diag_permissions
        DiagnosticCheckId.TTS -> R.string.omnix_diag_tts
        DiagnosticCheckId.LICENSE -> R.string.omnix_diag_license
        DiagnosticCheckId.NETWORK -> R.string.omnix_diag_network
        DiagnosticCheckId.BATTERY -> R.string.omnix_diag_battery
    }
