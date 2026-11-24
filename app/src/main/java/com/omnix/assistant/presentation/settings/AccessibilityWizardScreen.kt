package com.omnix.assistant.presentation.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.omnix.assistant.R
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement.Oem
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement.Status
import com.omnix.assistant.agent.tools.accessibility.AccessibilityEnablement.Step
import com.omnix.assistant.agent.tools.accessibility.AccessibilityStatusReader
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.design.OmnixTheme

/** Маршрут мастера: «Я → Специальные возможности». */
const val SECTION_ACCESSIBILITY_WIZARD = "accessibility"

/**
 * Мастер включения службы специальных возможностей в три шага.
 *
 * Служба необязательная: она позволяет открывать приложения голосом, когда
 * Android не даёт запускать их из фона. Без неё работают запасные пути.
 * На APK не из магазина Android 13+ сначала блокирует включение службы
 * («Ограниченные настройки»), поэтому первый шаг снимает это ограничение.
 *
 * Состояние читается заново при каждом возвращении на экран (ON_RESUME):
 * пользователь уходит в системные настройки и возвращается сюда.
 */
@Composable
internal fun AccessibilityWizardScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(AccessibilityStatusReader.read(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = AccessibilityStatusReader.read(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val current = AccessibilityEnablement.currentStep(status)
    val spacing = OmnixTheme.spacing

    SectionScaffold(stringResource(R.string.omnix_a11y_title), modifier, onBack) {
        Text(
            text = stringResource(R.string.omnix_a11y_why),
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textPrimary
        )
        Spacer(Modifier.height(spacing.sm))
        Text(
            text = stringResource(R.string.omnix_a11y_optional),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(spacing.md))
        Text(
            text = stringResource(R.string.omnix_a11y_block_warning),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(spacing.lg))

        StepBlock(
            title = stringResource(R.string.omnix_a11y_step1_title),
            body = stringResource(R.string.omnix_a11y_step1_body),
            extra = stringResource(oemHint(AccessibilityStatusReader.oem())),
            note = stringResource(R.string.omnix_a11y_step1_note),
            buttonText = stringResource(R.string.omnix_a11y_step1_button),
            highlighted = current == Step.UNLOCK_RESTRICTED,
            onClick = { openAppDetails(context) }
        )
        Spacer(Modifier.height(spacing.lg))
        StepBlock(
            title = stringResource(R.string.omnix_a11y_step2_title),
            body = stringResource(R.string.omnix_a11y_step2_body),
            buttonText = stringResource(R.string.omnix_a11y_step2_button),
            highlighted = current == Step.ENABLE_SERVICE,
            onClick = { openAccessibilitySettings(context) }
        )
        Spacer(Modifier.height(spacing.lg))
        Text(
            text = stringResource(R.string.omnix_a11y_step3_title),
            style = OmnixTheme.typography.headline,
            color = OmnixTheme.colors.textPrimary
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = stringResource(
                when (status) {
                    Status.ENABLED -> R.string.omnix_a11y_state_enabled
                    Status.DISABLED_RESTRICTED_LIKELY -> R.string.omnix_a11y_state_restricted
                    Status.DISABLED -> R.string.omnix_a11y_state_disabled
                }
            ),
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textPrimary
        )
        Spacer(Modifier.height(spacing.sm))
        OmnixSecondaryButton(
            text = stringResource(R.string.omnix_a11y_step3_button),
            onClick = { status = AccessibilityStatusReader.read(context) },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

private fun oemHint(oem: Oem): Int = when (oem) {
    Oem.SAMSUNG -> R.string.omnix_a11y_hint_samsung
    Oem.PIXEL -> R.string.omnix_a11y_hint_pixel
    Oem.XIAOMI -> R.string.omnix_a11y_hint_xiaomi
    Oem.OTHER -> R.string.omnix_a11y_hint_other
}

@Composable
private fun StepBlock(
    title: String,
    body: String,
    buttonText: String,
    highlighted: Boolean,
    onClick: () -> Unit,
    extra: String? = null,
    note: String? = null
) {
    val spacing = OmnixTheme.spacing
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = OmnixTheme.typography.headline,
            color = OmnixTheme.colors.textPrimary
        )
        Spacer(Modifier.height(spacing.xs))
        Text(
            text = body,
            style = OmnixTheme.typography.body,
            color = OmnixTheme.colors.textPrimary
        )
        extra?.let {
            Spacer(Modifier.height(spacing.xs))
            Text(text = it, style = OmnixTheme.typography.caption, color = OmnixTheme.colors.textSecondary)
        }
        note?.let {
            Spacer(Modifier.height(spacing.xs))
            Text(text = it, style = OmnixTheme.typography.caption, color = OmnixTheme.colors.textSecondary)
        }
        Spacer(Modifier.height(spacing.sm))
        // Актуальный шаг подсвечен основной кнопкой, остальные остаются доступны.
        if (highlighted) {
            OmnixPrimaryButton(text = buttonText, onClick = onClick, modifier = Modifier.fillMaxWidth())
        } else {
            OmnixSecondaryButton(text = buttonText, onClick = onClick, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun openAppDetails(context: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        .setData(Uri.fromParts("package", context.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openAccessibilitySettings(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
