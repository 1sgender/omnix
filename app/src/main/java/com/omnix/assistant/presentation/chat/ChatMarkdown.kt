package com.omnix.assistant.presentation.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Ответы OMNIX в чате приходят с простой разметкой (сервер просит модель
 * писать списками и выделять главное). Полный Markdown не нужен: заголовки,
 * списки, жирный/курсив, `код` и блоки кода покрывают то, что пишет модель.
 * Всё остальное показывается как есть.
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val text: String) : MdBlock
    data class Numbered(val number: String, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
}

private val HEADING = Regex("""^(#{1,6})\s+(.*)$""")
private val BULLET = Regex("""^\s*[-*•]\s+(.*)$""")
private val NUMBERED = Regex("""^\s*(\d{1,3})[.)]\s+(.*)$""")

/** Строки ответа → блоки. Соседние строки обычного текста склеиваются в абзац. */
internal fun parseMarkdownBlocks(source: String): List<MdBlock> {
    val blocks = ArrayList<MdBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var inCode = false

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MdBlock.Paragraph(paragraph.toString())
            paragraph.setLength(0)
        }
    }

    for (raw in source.replace("\r\n", "\n").split('\n')) {
        if (raw.trimStart().startsWith("```")) {
            if (inCode) {
                blocks += MdBlock.Code(code.toString().trimEnd('\n'))
                code.setLength(0)
                inCode = false
            } else {
                flushParagraph()
                inCode = true
            }
            continue
        }
        if (inCode) {
            code.append(raw).append('\n')
            continue
        }
        val line = raw.trimEnd()
        if (line.isBlank()) {
            flushParagraph()
            continue
        }
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        val numbered = NUMBERED.matchEntire(line)
        when {
            heading != null -> {
                flushParagraph()
                blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            }
            bullet != null -> {
                flushParagraph()
                blocks += MdBlock.Bullet(bullet.groupValues[1].trim())
            }
            numbered != null -> {
                flushParagraph()
                blocks += MdBlock.Numbered(numbered.groupValues[1], numbered.groupValues[2].trim())
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line.trim())
            }
        }
    }
    // Незакрытый блок кода (ответ оборвался) всё равно показываем.
    if (inCode && code.isNotEmpty()) blocks += MdBlock.Code(code.toString().trimEnd('\n'))
    flushParagraph()
    return blocks
}

/** `**жирный**`, `__жирный__`, `*курсив*`, `_курсив_` и `` `код` `` внутри строки. */
internal fun markdownInline(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val rest = text.substring(i)
        val bold = when {
            rest.startsWith("**") -> "**"
            rest.startsWith("__") -> "__"
            else -> null
        }
        if (bold != null) {
            val end = text.indexOf(bold, i + 2)
            if (end > i + 2) {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(i + 2, end)) }
                i = end + 2
                continue
            }
        }
        val c = text[i]
        if (c == '`') {
            val end = text.indexOf('`', i + 1)
            if (end > i + 1) {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) {
                    append(text.substring(i + 1, end))
                }
                i = end + 1
                continue
            }
        }
        if ((c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace()) {
            val end = text.indexOf(c, i + 1)
            // `_` внутри слов (snake_case, имена файлов) — не курсив.
            val wordInside = c == '_' && i > 0 && text[i - 1].isLetterOrDigit()
            if (end > i + 1 && !text[end - 1].isWhitespace() && !wordInside) {
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text.substring(i + 1, end)) }
                i = end + 1
                continue
            }
        }
        append(c)
        i++
    }
}

@Composable
internal fun ChatMarkdown(
    text: String,
    style: TextStyle,
    color: Color,
    codeBackground: Color,
    modifier: Modifier = Modifier
) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(
                    text = markdownInline(block.text, codeBackground),
                    style = style.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontSize = if (block.level <= 2) 18.sp else 16.sp,
                        lineHeight = if (block.level <= 2) 25.sp else 23.sp
                    ),
                    color = color,
                    modifier = Modifier.padding(top = 4.dp)
                )
                is MdBlock.Bullet -> ListItem(marker = "•", text = block.text, style = style, color = color, codeBackground = codeBackground)
                is MdBlock.Numbered -> ListItem(marker = "${block.number}.", text = block.text, style = style, color = color, codeBackground = codeBackground)
                is MdBlock.Paragraph -> Text(text = markdownInline(block.text, codeBackground), style = style, color = color)
                is MdBlock.Code -> Text(
                    text = block.text,
                    style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                    color = color,
                    softWrap = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(codeBackground)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 14.dp, vertical = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun ListItem(marker: String, text: String, style: TextStyle, color: Color, codeBackground: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text = marker, style = style, color = color, modifier = Modifier.widthIn(min = 14.dp))
        Text(text = markdownInline(text, codeBackground), style = style, color = color)
    }
}
