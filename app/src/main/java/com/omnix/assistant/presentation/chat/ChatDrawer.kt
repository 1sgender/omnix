package com.omnix.assistant.presentation.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.domain.models.ConversationSummary
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixNewChatLineIcon
import com.omnix.assistant.presentation.components.OmnixSearchLineIcon
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Группы списка чатов, как в ChatGPT: по дате последнего сообщения. */
internal enum class ConversationGroup { PINNED, TODAY, YESTERDAY, WEEK, MONTH, OLDER }

internal fun conversationGroups(
    conversations: List<ConversationSummary>,
    query: String,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    pinned: Set<Long> = emptySet()
): List<Pair<ConversationGroup, List<ConversationSummary>>> {
    val needle = query.trim().lowercase(Locale.getDefault())
    return conversations
        .filter { needle.isEmpty() || it.title.orEmpty().lowercase(Locale.getDefault()).contains(needle) }
        .sortedByDescending { it.lastMessageAt }
        .groupBy { conversation ->
            if (conversation.id in pinned) return@groupBy ConversationGroup.PINNED
            val day = Instant.ofEpochMilli(conversation.lastMessageAt).atZone(zone).toLocalDate()
            when {
                !day.isBefore(today) -> ConversationGroup.TODAY
                day == today.minusDays(1) -> ConversationGroup.YESTERDAY
                day.isAfter(today.minusDays(8)) -> ConversationGroup.WEEK
                day.isAfter(today.minusDays(31)) -> ConversationGroup.MONTH
                else -> ConversationGroup.OLDER
            }
        }
        .toList()
        .sortedBy { it.first.ordinal }
}

/**
 * Боковое меню чата (отзыв владельца на v344: «как в ChatGPT»): поиск, новый
 * чат, прошлые разговоры по датам. Нажатие открывает разговор, удержание —
 * закрепляет, переименовывает или удаляет его.
 */
@Composable
internal fun ChatDrawerContent(
    conversations: List<ConversationSummary>,
    currentConversationId: Long,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    pinnedIds: Set<Long> = emptySet(),
    onTogglePin: (Long) -> Unit = {},
    customTitles: Map<Long, String> = emptyMap(),
    onRename: (Long, String) -> Unit = { _, _ -> }
) {
    val colors = OmnixTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteArmed by remember { mutableStateOf<ConversationSummary?>(null) }
    val untitled = stringResource(R.string.omnix_chat_untitled)
    val shown = remember(conversations, customTitles) {
        conversations.map { chat -> customTitles[chat.id]?.let { chat.copy(title = it) } ?: chat }
    }
    val groups = remember(shown, query, pinnedIds) { conversationGroups(shown, query, pinned = pinnedIds) }
    var renaming by remember { mutableStateOf<ConversationSummary?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp)) {
        SearchField(query = query, onQueryChange = { query = it })

        DrawerRow(
            text = stringResource(R.string.omnix_chat_new),
            selected = false,
            leading = {
                Icon(OmnixNewChatLineIcon, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.size(20.dp))
            },
            onClick = onNewChat,
            modifier = Modifier.padding(top = 8.dp)
        )

        if (groups.isEmpty()) {
            Text(
                text = stringResource(
                    if (conversations.isEmpty()) R.string.omnix_chat_history_empty_body else R.string.omnix_chat_search_empty
                ),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp),
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp)
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 12.dp)
            ) {
                groups.forEach { (group, chats) ->
                    item(key = "group-$group") { GroupLabel(group) }
                    items(chats, key = { it.id }) { conversation ->
                        DrawerRow(
                            text = conversation.title?.takeIf { it.isNotBlank() } ?: untitled,
                            selected = conversation.id == currentConversationId,
                            onClick = { onOpen(conversation.id) },
                            onLongClick = { actionsFor = conversation }
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.omnix_chat_delete_hint),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = colors.textTertiary,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
            )
        }
    }

    actionsFor?.let { conversation ->
        val pinned = conversation.id in pinnedIds
        ChatDialogCard(
            title = conversation.title?.takeIf { it.isNotBlank() } ?: untitled,
            onDismiss = { actionsFor = null }
        ) {
            ChatActionRow(stringResource(if (pinned) R.string.omnix_chat_unpin else R.string.omnix_chat_pin)) {
                actionsFor = null
                onTogglePin(conversation.id)
            }
            ChatActionRow(stringResource(R.string.omnix_chat_rename)) {
                actionsFor = null
                renameText = conversation.title.orEmpty()
                renaming = conversation
            }
            ChatActionRow(stringResource(R.string.omnix_chat_delete), danger = true) {
                actionsFor = null
                deleteArmed = conversation
            }
            ChatActionRow(stringResource(R.string.omnix_cancel), muted = true) { actionsFor = null }
        }
    }

    renaming?.let { conversation ->
        ChatDialogCard(
            title = stringResource(R.string.omnix_chat_rename),
            onDismiss = { renaming = null }
        ) {
            OutlinedTextField(
                value = renameText,
                onValueChange = { renameText = it.take(80) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text(stringResource(R.string.omnix_chat_rename_hint)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            )
            ChatActionRow(stringResource(R.string.omnix_chat_save)) {
                onRename(conversation.id, renameText)
                renaming = null
            }
            ChatActionRow(stringResource(R.string.omnix_cancel), muted = true) { renaming = null }
        }
    }

    deleteArmed?.let { conversation ->
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_chat_delete_confirm_title),
                detail = conversation.title?.takeIf { it.isNotBlank() } ?: untitled,
                confirmLabel = stringResource(R.string.omnix_chat_delete),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = {
                deleteArmed = null
                onDelete(conversation.id)
            },
            onCancel = { deleteArmed = null }
        )
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(colors.surfaceElevated)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(OmnixSearchLineIcon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(18.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = stringResource(R.string.omnix_chat_search_hint),
                    style = DrawerText,
                    color = colors.textTertiary,
                    maxLines = 1
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = DrawerText.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun GroupLabel(group: ConversationGroup) {
    val text = stringResource(
        when (group) {
            ConversationGroup.PINNED -> R.string.omnix_chat_group_pinned
            ConversationGroup.TODAY -> R.string.omnix_chat_today
            ConversationGroup.YESTERDAY -> R.string.omnix_chat_yesterday
            ConversationGroup.WEEK -> R.string.omnix_chat_group_week
            ConversationGroup.MONTH -> R.string.omnix_chat_group_month
            ConversationGroup.OLDER -> R.string.omnix_chat_group_older
        }
    )
    Text(
        text = text,
        style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
        color = OmnixTheme.colors.textSecondary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp, bottom = 6.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DrawerRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null
) {
    val colors = OmnixTheme.colors
    val shape = RoundedCornerShape(12.dp)
    val deleteLabel = stringResource(R.string.omnix_chat_delete)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(if (selected) colors.surfaceElevated else colors.background)
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = deleteLabel)
                } else {
                    Modifier.clickable(onClick = onClick)
                }
            )
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        leading?.invoke()
        Text(
            text = text,
            style = DrawerText.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private val DrawerText = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 20.sp)

/** Карточка диалога в стиле «Трафарет»: скругление 24dp, заголовок, строки действий. */
@Composable
private fun ChatDialogCard(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    val colors = OmnixTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(24.dp))
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = title,
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp)
            )
            content()
        }
    }
}

@Composable
private fun ChatActionRow(
    text: String,
    danger: Boolean = false,
    muted: Boolean = false,
    onClick: () -> Unit
) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 16.sp),
            color = when {
                danger -> colors.stateError
                muted -> colors.textSecondary
                else -> colors.textPrimary
            }
        )
    }
}
