package com.omnix.assistant.presentation.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.core.constants.AppConstants
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Адрес публичной политики конфиденциальности: `<адрес сервера>/privacy?lang=…`.
 * Адрес сервера берётся из сборки (flavor), а не зашит в код. Тексты политики
 * есть на русском и английском: русский — для ru/tk/kk/uz (русскоязычная
 * аудитория региона), английский — для остальных.
 */
internal fun privacyPolicyUrl(baseUrl: String, languageTag: String): String {
    val lang = if (languageTag.lowercase().substringBefore('-') in RUSSIAN_READING) "ru" else "en"
    return baseUrl.trimEnd('/') + "/privacy?lang=" + lang
}

private val RUSSIAN_READING = setOf("ru", "tk", "kk", "uz")

/** Открывает политику в браузере; false — подходящего приложения нет. */
internal fun openPrivacyPolicy(context: Context): Boolean {
    val languageTag = context.resources.configuration.locales[0].toLanguageTag()
    val url = privacyPolicyUrl(AppConstants.OMNIX_API_BASE_URL, languageTag)
    return try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

/** Обработчик нажатия «Политика конфиденциальности»: открыть или сказать, что браузера нет. */
@Composable
internal fun rememberOpenPrivacyPolicy(): () -> Unit {
    val context = LocalContext.current
    return remember(context) {
        {
            if (!openPrivacyPolicy(context)) {
                Toast.makeText(context, R.string.omnix_privacy_policy_open_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}

/**
 * «Карточка приватности» на экране выбора модели: что уходит в облако при
 * Flash/Pro и что остаётся при «Локально». Формулировки совпадают с
 * docs/PRIVACY_POLICY.md; ссылка ведёт на полную политику.
 */
@Composable
internal fun PrivacyCloudCard(modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    val openPolicy = rememberOpenPrivacyPolicy()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.omnix_privacy_card_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 15.sp),
            color = colors.textPrimary
        )
        for (line in listOf(
            R.string.omnix_privacy_card_local,
            R.string.omnix_privacy_card_flash,
            R.string.omnix_privacy_card_pro,
            R.string.omnix_privacy_card_speech
        )) {
            Text(
                text = stringResource(line),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp, lineHeight = 18.sp),
                color = colors.textSecondary
            )
        }
        OmnixTextButton(
            text = stringResource(R.string.omnix_privacy_policy_link),
            onClick = openPolicy
        )
    }
}
