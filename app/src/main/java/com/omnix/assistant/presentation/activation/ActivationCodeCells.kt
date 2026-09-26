package com.omnix.assistant.presentation.activation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.presentation.design.OmnixTheme

/**
 * Ячейки кода активации (мок «OMNIX — активация», 2026-09-25): шесть коротких
 * полей с подчёркиванием вместо рамки — длина кода видна сразу, курсор сам
 * прыгает к следующему символу. Код — буквы и цифры неом ambiguity-алфавита
 * карточки (0/O/1/I на ней не печатаются), поэтому клавиатура текстовая с
 * автоверхним регистром (отклонение от inputMode=numeric демо-мока; письмо
 * дизайнера: «цифр или букв»).
 */
internal const val CODE_CELL_COUNT = 6

/**
 * Чистая санитайзка ввода: буквы и цифры, верхний регистр, не длиннее кода.
 */
internal fun sanitizeActivationInput(raw: String, cellCount: Int = CODE_CELL_COUNT): String =
    raw.filter { it.isLetterOrDigit() }.uppercase().take(cellCount)

/**
 * Чистая логика ячейки: что сделать с кодом и куда перевести фокус.
 *
 * Ветви:
 *  - ввод/вставка цифр в ячейку [index] — цифры ложатся с этой позиции,
 *    хвост кода сохраняется (одиночная цифра заменяет символ в ячейке,
 *    вставка шести цифр заполняет всё); фокус — на следующую заполненную;
 *  - удаление в заполненной ячейке — символ убирается, строка сдвигается,
 *    фокус остаётся;
 *  - бэкспейс в пустой ячейке — код не меняется, фокус уходит назад;
 *  - недопустимый символ (не цифра) — игнор.
 */
internal fun cellInputResult(
    code: String,
    index: Int,
    raw: String,
    cellCount: Int = CODE_CELL_COUNT
): Pair<String, Int> {
    val current = if (index < code.length) code[index].toString() else ""
    // Поле может отдать «старый символ + новый» (допечатка в конец) или
    // «новый + старый» (курсор в начале) — отделяем напечатанное.
    val typed = when {
        raw.length > 1 && raw.startsWith(current) -> raw.drop(current.length)
        raw.length > 1 && raw.endsWith(current) -> raw.dropLast(current.length)
        else -> raw
    }
    val chars = typed.filter { it.isLetterOrDigit() }
    return when {
        raw.isEmpty() -> {
            val filled = index < code.length
            val newCode = if (filled) code.removeRange(index, index + 1) else code
            val focus = if (filled) index else (index - 1).coerceAtLeast(0)
            newCode to focus
        }
        chars.isEmpty() -> code to index
        else -> {
            val merged = sanitizeActivationInput(
                code.take(index) + chars + code.drop(index + chars.length),
                cellCount
            )
            merged to (index + chars.length).coerceAtMost(cellCount - 1)
        }
    }
}

/**
 * Ряд ячеек: 38×48dp (мок), подчёркивание 2dp — спокойное (surfaceFilled),
 * под фокусом и в заполненном коде — ink, при ошибке — err и цифры красные.
 * Переход цвета линии 0.2s (transition мока). Каретка — системный синий.
 */
@Composable
internal fun ActivationCodeCells(
    code: String,
    onCodeChange: (String) -> Unit,
    isError: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val focusRequesters = remember { List(CODE_CELL_COUNT) { FocusRequester() } }
    // Индекс ячейки под фокусом — для цвета подчёркивания.
    var focusedIndex by remember { mutableStateOf(-1) }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CELL_GAP)
    ) {
        for (index in 0 until CODE_CELL_COUNT) {
            val char = if (index < code.length) code[index].toString() else ""
            val isFocused = index == focusedIndex
            val isComplete = code.length == CODE_CELL_COUNT
            val lineColorTarget = when {
                isError -> colors.stateError
                isFocused -> colors.textPrimary
                isComplete -> colors.textPrimary
                else -> colors.surfaceFilled
            }
            val lineColor by animateColorAsState(
                targetValue = lineColorTarget,
                animationSpec = tween(durationMillis = 200),
                label = "cell_underline"
            )
            val textColor = if (isError) colors.stateError else colors.textPrimary
            val underline = UNDERLINE_WIDTH

            BasicTextField(
                value = char,
                onValueChange = { raw ->
                    if (!enabled) return@BasicTextField
                    val (newCode, focus) = cellInputResult(code, index, raw)
                    if (newCode != code) onCodeChange(newCode)
                    focusRequesters[focus].requestFocus()
                },
                modifier = Modifier
                    .width(CELL_WIDTH)
                    .height(CELL_HEIGHT)
                    .focusRequester(focusRequesters[index])
                    .onFocusChanged { state ->
                        if (state.isFocused) focusedIndex = index
                        else if (focusedIndex == index) focusedIndex = -1
                    }
                    .drawBehind {
                        val y = size.height - underline.toPx() / 2f
                        drawLine(
                            color = lineColor,
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = underline.toPx()
                        )
                    },
                enabled = enabled,
                singleLine = true,
                textStyle = TextStyle(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    color = textColor
                ),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    capitalization = KeyboardCapitalization.Characters
                ),
                cursorBrush = SolidColor(colors.actionLink),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.Center) { innerTextField() }
                }
            )
        }
    }
}

// Мок: .otp input — 38×48, gap 8, подчёркивание 2px.
private val CELL_WIDTH = 38.dp
private val CELL_HEIGHT = 48.dp
private val CELL_GAP = 8.dp
private val UNDERLINE_WIDTH = 2.dp
