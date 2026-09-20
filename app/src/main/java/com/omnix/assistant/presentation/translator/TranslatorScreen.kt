package com.omnix.assistant.presentation.translator

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.omnix.assistant.R
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixScreenHeader
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.core.CoreState
import com.omnix.assistant.presentation.core.OmnixAudioBars
import com.omnix.assistant.presentation.core.OmnixCore
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Translation — a **mode of OMNIX**, not a separate app (§24, §43).
 *
 * The proof that it is a mode: the same Core, in the same eight states, is the
 * centre of this screen too. There is no second visual identity, no
 * translator-specific chrome, and no separate "app" framing.
 *
 * Stage 4: the screen gains the back affordance every sub-screen carries
 * (§20), and the result is typeset at title2 — the translation is what the
 * user is waiting for, so it gets the same presence the state line has on
 * Home. The live transcript settles into subheadline: it is context, not
 * content.
 */
@Composable
fun TranslatorScreen(
    active: Boolean,
    coreState: CoreState,
    audioLevel: Float,
    sourceLanguage: String,
    targetLanguage: String,
    transcript: String,
    translation: String,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    onSwap: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OmnixScreenHeader(
            title = stringResource(R.string.omnix_translator_title),
            onBack = onBack
        )

        Text(
            text = stringResource(
                R.string.omnix_translator_direction,
                sourceLanguage,
                targetLanguage
            ),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textTertiary,
            modifier = Modifier.padding(top = spacing.sm)
        )

        Spacer(Modifier.weight(1f))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            if (active) {
                OmnixAudioBars(
                    level = audioLevel,
                    color = OmnixTheme.colors.stateListening,
                    mirrored = true
                )
            }
            OmnixCore(
                state = coreState,
                size = OmnixTheme.coreSizes.home,
                audioLevel = audioLevel
            )
            if (active) {
                OmnixAudioBars(
                    level = audioLevel,
                    color = OmnixTheme.colors.stateListening
                )
            }
        }

        Spacer(Modifier.height(spacing.xl))

        // Live transcript above, translation below. The translation is the
        // primary text because it is the thing the user is waiting for.
        if (transcript.isNotBlank() || translation.isNotBlank()) {
            Text(
                text = transcript,
                style = OmnixTheme.typography.subheadline,
                color = OmnixTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(spacing.xs))
            Text(
                text = translation,
                style = OmnixTheme.typography.title2,
                color = OmnixTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Text(
                text = stringResource(R.string.omnix_translator_empty_body),
                style = OmnixTheme.typography.subheadline,
                color = OmnixTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }

        Spacer(Modifier.weight(1f))

        if (active) {
            OmnixSecondaryButton(
                text = stringResource(R.string.omnix_translator_stop),
                onClick = onStop
            )
        } else {
            OmnixPrimaryButton(
                text = stringResource(R.string.omnix_translator_start),
                onClick = onStart
            )
        }

        OmnixTextButton(
            text = stringResource(R.string.omnix_translator_swap),
            onClick = onSwap
        )

        Spacer(Modifier.height(spacing.xxl))
    }
}
