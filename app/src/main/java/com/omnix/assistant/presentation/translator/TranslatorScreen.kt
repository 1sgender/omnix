package com.omnix.assistant.presentation.translator

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.translator.LiveTranslatorEngine
import com.omnix.assistant.agent.translator.SupportedLanguage
import com.omnix.assistant.presentation.components.OmnixBackLineIcon
import com.omnix.assistant.presentation.components.OmnixCloseLineIcon
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.components.OmnixPauseLineIcon
import com.omnix.assistant.presentation.components.OmnixSwapLineIcon
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.voice.conversation.ConversationLine
import com.omnix.assistant.voice.conversation.ConversationProblem
import com.omnix.assistant.voice.conversation.ConversationState
import com.omnix.assistant.voice.conversation.Ear
import com.omnix.assistant.voice.conversation.Speaker

/**
 * Переводчик «по наушнику на каждого» (отзыв владельца на v344).
 *
 * Сверху — язык владельца и язык собеседника («Определю сам» — по его первой
 * фразе), под ними — чей наушник левый, чей правый. Ниже разговор: мои
 * реплики справа, собеседника слева; у каждой — оригинал и перевод. Внизу —
 * куда звучит перевод, «Начать/Пауза» и «Завершить».
 *
 * Плашка ONLINE/OFFLINE честная: распознавание и перевод идут через сервер.
 */
@Composable
fun TranslatorScreen(
    state: ConversationState,
    isOnline: Boolean,
    outputToPhone: Boolean,
    languages: List<SupportedLanguage>,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    onMyLanguage: (String) -> Unit = {},
    onTheirLanguage: (String?) -> Unit = {},
    onSwapEars: () -> Unit = {},
    onOutputToPhone: (Boolean) -> Unit = {},
    onToggle: () -> Unit = {},
    onEnd: () -> Unit = {}
) {
    Column(modifier = modifier.fillMaxSize()) {
        Header(isOnline = isOnline, onBack = onBack)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, top = 20.dp, end = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            LanguageButton(
                label = stringResource(R.string.omnix_translator_i_speak),
                selected = state.myLanguage,
                languages = languages,
                allowAuto = false,
                onPick = { it?.let(onMyLanguage) },
                modifier = Modifier.weight(1f)
            )
            LanguageButton(
                label = stringResource(R.string.omnix_translator_partner),
                selected = state.theirLanguage,
                languages = languages.filter { it.code != state.myLanguage },
                allowAuto = true,
                onPick = onTheirLanguage,
                modifier = Modifier.weight(1f)
            )
        }

        if (!outputToPhone) {
            EarsRow(theirEar = state.theirEar, onSwap = onSwapEars)
        }

        Conversation(
            state = state,
            languages = languages,
            modifier = Modifier
                .weight(1f)
                .padding(start = 20.dp, top = 16.dp, end = 20.dp)
        )

        OutputRow(
            toPhone = outputToPhone,
            onOutputToPhone = onOutputToPhone,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp)
        )

        Controls(running = state.running, onToggle = onToggle, onEnd = onEnd)
    }
}

@Composable
private fun Header(isOnline: Boolean, onBack: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val backLabel = stringResource(R.string.omnix_nav_back)
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button, onClick = onBack)
                .clearAndSetSemantics {
                    contentDescription = backLabel
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(OmnixBackLineIcon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(22.dp))
        }
        Text(
            text = stringResource(R.string.omnix_translator_title),
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 20.sp),
            color = colors.textPrimary,
            modifier = Modifier.weight(1f)
        )
        Row(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(if (isOnline) colors.accentBrand else colors.textDisabled, CircleShape)
            )
            Text(
                text = stringResource(
                    if (isOnline) R.string.omnix_translator_status_online else R.string.omnix_translator_status_offline
                ),
                style = MonoMeta,
                color = colors.textSecondary
            )
        }
    }
}

/** Язык одной стороны; у собеседника есть «Определю сам». */
@Composable
private fun LanguageButton(
    label: String,
    selected: String?,
    languages: List<SupportedLanguage>,
    allowAuto: Boolean,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val autoLabel = stringResource(R.string.omnix_translator_auto_detect)
    val name = languages.firstOrNull { it.code == selected }?.displayName
        ?: LiveTranslatorEngine.SUPPORTED_LANGUAGES.firstOrNull { it.code == selected }?.displayName
        ?: autoLabel
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(LanguageShape)
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), LanguageShape)
                .clickable(role = Role.Button) { expanded = true }
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        ) {
            Text(text = label, style = TextStyle(fontFamily = OmnixSans, fontSize = 11.sp), color = colors.textSecondary)
            Text(
                text = name,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
                color = colors.textPrimary,
                maxLines = 1
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(colors.surfaceElevated)
        ) {
            if (allowAuto) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = autoLabel,
                            style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp),
                            color = if (selected == null) colors.accentBrand else colors.textPrimary
                        )
                    },
                    onClick = {
                        expanded = false
                        onPick(null)
                    }
                )
            }
            languages.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.displayName,
                            style = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp),
                            color = if (option.code == selected) colors.accentBrand else colors.textPrimary
                        )
                    },
                    onClick = {
                        expanded = false
                        onPick(option.code)
                    }
                )
            }
        }
    }
}

/** «Ваш наушник: левый · собеседника: правый» и кнопка «Поменять». */
@Composable
private fun EarsRow(theirEar: Ear, onSwap: () -> Unit) {
    val colors = OmnixTheme.colors
    val left = stringResource(R.string.omnix_translator_ear_left)
    val right = stringResource(R.string.omnix_translator_ear_right)
    val mine = if (theirEar == Ear.RIGHT) left else right
    val theirs = if (theirEar == Ear.RIGHT) right else left
    val swapLabel = stringResource(R.string.omnix_translator_swap_ears)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, top = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.omnix_translator_ears, mine, theirs),
            style = TextStyle(fontFamily = OmnixSans, fontSize = 13.sp),
            color = colors.textSecondary,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onSwap)
                .clearAndSetSemantics {
                    contentDescription = swapLabel
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(OmnixSwapLineIcon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(18.dp))
        }
    }
}

/** Разговор: свежие реплики внизу, мои — справа, собеседника — слева. */
@Composable
private fun Conversation(state: ConversationState, languages: List<SupportedLanguage>, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "status") { StatusLine(state) }
        items(state.lines.asReversed(), key = { it.id }) { line -> LineEntry(line, languages) }
        if (state.lines.isEmpty() && !state.running) {
            item(key = "hint") {
                Text(
                    text = stringResource(R.string.omnix_translator_hint),
                    style = OriginalStyle,
                    color = colors.textSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: ConversationState) {
    val colors = OmnixTheme.colors
    val text = when {
        state.problem == ConversationProblem.MICROPHONE -> stringResource(R.string.omnix_translator_problem_mic)
        state.problem == ConversationProblem.RECOGNITION ->
            stringResource(R.string.omnix_translator_problem_recognition, state.problemDetail.orEmpty())
        !state.running -> null
        state.working -> stringResource(R.string.omnix_translator_working)
        state.hearing -> stringResource(R.string.omnix_translator_hearing)
        state.theirLanguage == null -> stringResource(R.string.omnix_translator_waiting)
        else -> null
    } ?: return
    Text(
        text = text,
        style = MonoMeta,
        color = if (state.problem != null) colors.stateError else colors.accentBrand
    )
}

@Composable
private fun LineEntry(line: ConversationLine, languages: List<SupportedLanguage>) {
    val colors = OmnixTheme.colors
    val mine = line.speaker == Speaker.ME
    val who = stringResource(if (mine) R.string.omnix_translator_me else R.string.omnix_translator_them)
    val lang = languages.firstOrNull { it.code == line.sourceLang }?.displayName
        ?: LiveTranslatorEngine.SUPPORTED_LANGUAGES.firstOrNull { it.code == line.sourceLang }?.displayName
        ?: line.sourceLang
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(text = "$who · $lang", style = MonoMeta, color = colors.textTertiary)
        Text(text = line.original, style = OriginalStyle, color = colors.textSecondary)
        if (line.translated.isNotBlank()) {
            Text(
                text = line.translated,
                style = TranslationStyle,
                // Неудачный перевод несёт причину, а не перевод.
                color = if (line.translatedOk) colors.textPrimary else colors.textSecondary
            )
        }
        if (line.translatedOk && !line.spoken) {
            Text(
                text = stringResource(R.string.omnix_translator_no_voice),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = colors.textTertiary
            )
        }
    }
}

/** "To headphones" / "Aloud on the phone": where the translation sounds. */
@Composable
private fun OutputRow(toPhone: Boolean, onOutputToPhone: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutputButton(
            text = stringResource(R.string.omnix_translator_to_ears),
            selected = !toPhone,
            onClick = { onOutputToPhone(false) },
            modifier = Modifier.weight(1f)
        )
        OutputButton(
            text = stringResource(R.string.omnix_translator_to_phone),
            selected = toPhone,
            onClick = { onOutputToPhone(true) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OutputButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    val background = if (selected) colors.textPrimary else Color.Transparent
    val foreground = if (selected) colors.background else colors.textPrimary
    val border = if (selected) colors.textPrimary else colors.textPrimary.copy(alpha = 0.12f)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(OutputShape)
            .background(background)
            .border(BorderStroke(1.dp, border), OutputShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color = foreground,
            maxLines = 1
        )
    }
}

@Composable
private fun Controls(running: Boolean, onToggle: () -> Unit, onEnd: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp))
        RoundButton(
            icon = if (running) OmnixPauseLineIcon else OmnixMicLineIcon,
            label = stringResource(if (running) R.string.omnix_translator_pause else R.string.omnix_translator_speak),
            size = 84.dp,
            iconSize = 30.dp,
            background = if (running) colors.surface else colors.accentBrand,
            iconTint = if (running) colors.textPrimary else colors.background,
            border = if (running) colors.border else null,
            onClick = onToggle
        )
        RoundButton(
            icon = OmnixCloseLineIcon,
            label = stringResource(R.string.omnix_translator_end),
            size = 52.dp,
            iconSize = 18.dp,
            background = colors.surface,
            iconTint = colors.textPrimary,
            border = colors.border,
            onClick = onEnd
        )
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    iconSize: Dp,
    background: Color,
    iconTint: Color,
    border: Color?,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .size(size)
            .omnixPressScale(interaction)
            .clip(CircleShape)
            .background(background)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), CircleShape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize))
    }
}

private val MonoMeta = OmnixMonoLabelStyle.copy(fontSize = 11.sp, letterSpacing = 0.04.em)
private val OriginalStyle = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp)
private val TranslationStyle = TextStyle(
    fontFamily = OmnixSans,
    fontWeight = FontWeight.Medium,
    fontSize = 20.sp,
    lineHeight = 27.sp
)
private val LanguageShape = RoundedCornerShape(16.dp)
private val OutputShape = RoundedCornerShape(14.dp)
