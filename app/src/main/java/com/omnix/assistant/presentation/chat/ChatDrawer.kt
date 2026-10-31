package com.omnix.assistant.presentation.chat

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
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
 * закрепляет или удаляет его.
 */
@Composable
internal fun ChatDrawerContent(
    conversations: List<ConversationSummary>,
    currentConversationId: Long,
    onNewChat: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit,
    pinnedIds: Set<Long> = emptySet(),
    onTogglePin: (Long) -> Unit = {}
) {
    val colors = OmnixTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    var actionsFor by remember { mutableStateOf<ConversationSummary?>(null) }
    var deleteArmed by remember { mutableStateOf<ConversationSummary?>(null) }
    val untitled = stringResource(R.string.omnix_chat_untitled)
    val groups = remember(conversations, query, pinnedIds) { conversationGroups(conversations, query, pinned = pinnedIds) }

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
        AlertDialog(
            onDismissRequest = { actionsFor = null },
            title = {
                Text(
                    text = conversation.title?.takeIf { it.isNotBlank() } ?: untitled,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = {
                Column {
                    TextButton(onClick = {
                        actionsFor = null
                        onTogglePin(conversation.id)
                    }) {
                        Text(stringResource(if (pinned) R.string.omnix_chat_unpin else R.string.omnix_chat_pin))
                    }
                    TextButton(onClick = {
                        actionsFor = null
                        deleteArmed = conversation
                    }) {
                        Text(stringResource(R.string.omnix_chat_delete))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { actionsFor = null }) { Text(stringResource(R.string.omnix_cancel)) }
            }
        )
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
