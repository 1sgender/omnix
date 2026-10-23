package com.omnix.assistant.presentation.settings

import android.app.LocaleManager
import android.content.Context
import android.os.Build
import android.os.LocaleList
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.omnix.assistant.R
import com.omnix.assistant.core.locale.AppLanguage
import com.omnix.assistant.presentation.components.OmnixCheckIcon
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Язык (решение владельца 2026-10-22: шесть языков). Выбор — тот же per-app
 * язык Android 13+, что в системных настройках приложения: экран
 * пересоздаётся на новом языке, OMNIX начинает слушать и говорить на нём.
 * На Android 10–12 выбора в приложении нет — язык как в системе.
 */
@Composable
internal fun LanguageSettingsScreen(modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    val context = LocalContext.current
    val perAppSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var selected by remember { mutableStateOf(currentAppLanguage(context)) }

    SectionScaffold(stringResource(R.string.omnix_language_title), modifier, onBack) {
        val spacing = OmnixTheme.spacing
        Text(
            text = stringResource(R.string.omnix_language_hint),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textSecondary
        )
        Spacer(Modifier.height(spacing.sm))

        if (!perAppSupported) {
            OmnixSettingsGroup {
                OmnixSettingRow(
                    title = stringResource(R.string.omnix_language_system),
                    value = context.resources.configuration.locales[0].displayLanguage,
                    subtitle = stringResource(R.string.omnix_language_old_android),
                    inset = true
                )
            }
            return@SectionScaffold
        }

        OmnixSettingsGroup {
            LanguageRow(
                title = stringResource(R.string.omnix_language_system),
                selected = selected == null,
                onSelect = {
                    selected = null
                    applyAppLanguage(context, null)
                }
            )
            AppLanguage.entries.forEach { language ->
                OmnixGroupDivider()
                LanguageRow(
                    title = language.nativeName,
                    selected = selected == language,
                    onSelect = {
                        selected = language
                        applyAppLanguage(context, language)
                    }
                )
            }
        }
    }
}

@Composable
private fun LanguageRow(title: String, selected: Boolean, onSelect: () -> Unit) {
    OmnixSettingRow(
        title = title,
        inset = true,
        onClick = onSelect,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { this.selected = selected }
    ) {
        if (selected) OmnixCheckIcon(color = OmnixTheme.colors.textPrimary)
    }
}

/** null — язык как в системе (per-app язык не задан). */
private fun currentAppLanguage(context: Context): AppLanguage? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val locales = context.getSystemService(LocaleManager::class.java)?.applicationLocales ?: return null
    if (locales.isEmpty) return null
    return AppLanguage.fromLocale(locales[0])
}

private fun applyAppLanguage(context: Context, language: AppLanguage?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val manager = context.getSystemService(LocaleManager::class.java) ?: return
    manager.applicationLocales = if (language == null) {
        LocaleList.getEmptyLocaleList()
    } else {
        LocaleList.forLanguageTags(language.code)
    }
}
