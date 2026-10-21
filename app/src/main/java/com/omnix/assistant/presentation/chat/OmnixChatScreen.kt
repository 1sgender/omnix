package com.omnix.assistant.presentation.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.domain.models.ConversationSummary
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixAlertIcon
import com.omnix.assistant.presentation.components.OmnixCloudLineIcon
import com.omnix.assistant.presentation.components.OmnixEmptyState
import com.omnix.assistant.presentation.components.OmnixHairline
import com.omnix.assistant.presentation.components.OmnixLockIcon
import com.omnix.assistant.presentation.components.OmnixBackLineIcon
import com.omnix.assistant.presentation.components.OmnixMenuLineIcon
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.components.OmnixNewChatLineIcon
import com.omnix.assistant.presentation.components.OmnixTrashLineIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixSendLineIcon
import com.omnix.assistant.presentation.components.OmnixVoiceWaveLineIcon
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.LocalReducedMotion
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin

/**
 * Chat — design v2 canvas, artboard Chat.
 *
 * The header names OMNIX and says where it answers; "More" holds clearing.
 * The thread is grouped by day under a mono day label: the user's words in a
 * paper bubble on the right, OMNIX's answers in a card bubble on the left, an
 * answer that came from the cloud signed "Answer from the cloud" under it.
 * The composer is one field and one lime button: the microphone while the
 * field is empty, send once there is text.
 *
 * States the canvas does not draw keep working in the same language: the
 * thinking dots, a failed round trip (a red-tinted bubble), the cloud consent
 * question (C-02), the privacy badge while typing (CR-17) and the shared
 * confirmation sheet.
 */
@Composable
fun OmnixChatScreen(
    state: ChatUiState,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onCloseHistory: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onDeleteConversation: (Long) -> Unit,
    onAllowCloud: () -> Unit,
    onKeepLocal: () -> Unit,
    onToggleDictation: () -> Unit,
    onOpenVoiceMode: () -> Unit
) {
    if (state.showHistory) {
        BackHandler(onBack = onCloseHistory)
        ChatHistory(
            conversations = state.conversations,
            currentConversationId = state.currentConversationId,
            modifier = modifier,
            onBack = onCloseHistory,
            onOpen = onOpenConversation,
            onDelete = onDeleteConversation
        )
        return
    }

    val listState = rememberLazyListState()

    // The thinking row closes the list while a round trip is in flight; the
    // confirmation paths replace it with their own surfaces.
    val showThinking = state.isSending &&
        state.pendingConfirmation == null &&
        state.pendingCloudConsent == null
    val rows = remember(state.messages) { chatRows(state.messages) }
    val localOnly = state.localOnlyAnswer
    val rowCount = rows.size + (if (showThinking) 1 else 0) + (if (localOnly != null) 1 else 0)

    LaunchedEffect(rowCount) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        ChatHeader(
            isOnline = isOnline,
            onOpenHistory = onOpenHistory,
            onNewChat = onNewChat
        )
        Hairline()

        if (rowCount == 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                OmnixEmptyState(
                    title = stringResource(R.string.omnix_chat_empty_title),
                    description = stringResource(R.string.omnix_chat_empty_body)
                )
            }
        } else {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val threadWidth = maxWidth - 40.dp
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ChatRow.Day -> DayLabel(row.date)
                            is ChatRow.Bubble -> MessageRow(
                                message = row.message,
                                fromCloud = row.message.id in state.cloudMessageIds,
                                threadWidth = threadWidth
                            )
                        }
                    }
                    // Не хранится в истории (чтение экрана): только в ленте.
                    if (localOnly != null) {
                        item(key = "omnix-local-only") {
                            MessageRow(
                                message = Message(
                                    role = MessageRole.ASSISTANT,
                                    text = localOnly,
                                    timestamp = 0L
                                ),
                                fromCloud = false,
                                threadWidth = threadWidth
                            )
                        }
                    }
                    if (showThinking) {
                        item(key = "omnix-thinking") { ThinkingRow() }
                    }
                }
            }
        }

        ConsentAndBadge(state = state, onAllowCloud = onAllowCloud, onKeepLocal = onKeepLocal)

        Hairline()
        ChatComposer(
            text = state.inputText,
            isSending = state.isSending,
            isDictating = state.isVoiceDictating,
            onTextChange = onInputChange,
            onSend = onSend,
            onToggleDictation = onToggleDictation,
            onOpenVoiceMode = onOpenVoiceMode
        )
    }

    // Confirmation reuses the one shared sheet: the tap path and the voice
    // path resolve the same pending call in the executor (§17).
    state.pendingConfirmation?.let { pending ->
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = pending.promptMessage,
                detail = null,
                confirmLabel = stringResource(R.string.omnix_confirm_confirm),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = onConfirm,
            onCancel = onCancel
        )
    }
}

/** One row of the thread: a day label or a message. */
internal sealed interface ChatRow {
    val key: String

    data class Day(val date: LocalDate) : ChatRow {
        override val key: String = "day-$date"
    }

    data class Bubble(val message: Message) : ChatRow {
        override val key: String = "msg-${message.id}"
    }
}

/** The messages in order, a day label before the first message of each day. */
internal fun chatRows(messages: List<Message>, zone: ZoneId = ZoneId.systemDefault()): List<ChatRow> {
    val rows = ArrayList<ChatRow>(messages.size + 4)
    var day: LocalDate? = null
    for (message in messages) {
        val date = Instant.ofEpochMilli(message.timestamp).atZone(zone).toLocalDate()
        if (date != day) {
            rows += ChatRow.Day(date)
            day = date
        }
        rows += ChatRow.Bubble(message)
    }
    return rows
}

@Composable
private fun ChatHeader(isOnline: Boolean, onOpenHistory: () -> Unit, onNewChat: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIconButton(
            icon = OmnixMenuLineIcon,
            label = stringResource(R.string.omnix_chat_history_open),
            onClick = onOpenHistory
        )
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = stringResource(R.string.omnix_chat_title),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 17.sp),
                color = colors.textPrimary
            )
            Text(
                text = stringResource(
                    if (isOnline) R.string.omnix_chat_subtitle_online else R.string.omnix_chat_subtitle_on_device
                ),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = colors.textSecondary
            )
        }
        HeaderIconButton(
            icon = OmnixNewChatLineIcon,
            label = stringResource(R.string.omnix_chat_new),
            onClick = onNewChat
        )
    }
}

@Composable
private fun HeaderIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = OmnixTheme.colors.textPrimary, modifier = Modifier.size(22.dp))
    }
}

/**
 * «История»: прошлые разговоры, свежие первыми. Нажатие открывает разговор
 * (его можно продолжить), корзина удаляет его после подтверждения.
 */
@Composable
private fun ChatHistory(
    conversations: List<ConversationSummary>,
    currentConversationId: Long,
    modifier: Modifier,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    onDelete: (Long) -> Unit
) {
    val colors = OmnixTheme.colors
    var deleteArmed by remember { mutableStateOf<ConversationSummary?>(null) }
    val untitled = stringResource(R.string.omnix_chat_untitled)

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeaderIconButton(
                icon = OmnixBackLineIcon,
                label = stringResource(R.string.omnix_nav_back),
                onClick = onBack
            )
            Text(
                text = stringResource(R.string.omnix_chat_history_title),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 17.sp),
                color = colors.textPrimary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Hairline()

        if (conversations.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.Center
            ) {
                OmnixEmptyState(
                    title = stringResource(R.string.omnix_chat_history_empty_title),
                    description = stringResource(R.string.omnix_chat_history_empty_body)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(conversations, key = { it.id }) { conversation ->
                    ConversationRow(
                        title = conversation.title?.takeIf { it.isNotBlank() } ?: untitled,
                        lastMessageAt = conversation.lastMessageAt,
                        isOpen = conversation.id == currentConversationId,
                        onOpen = { onOpen(conversation.id) },
                        onDelete = { deleteArmed = conversation }
                    )
                }
            }
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
private fun ConversationRow(
    title: String,
    lastMessageAt: Long,
    isOpen: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = OmnixTheme.colors
    val whenText = remember(lastMessageAt) {
        DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.getDefault())
            .format(Instant.ofEpochMilli(lastMessageAt).atZone(ZoneId.systemDefault()))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = OmnixSans,
                    fontWeight = if (isOpen) FontWeight.Medium else FontWeight.Normal,
                    fontSize = 15.sp
                ),
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = whenText,
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = colors.textSecondary
            )
        }
        HeaderIconButton(
            icon = OmnixTrashLineIcon,
            label = stringResource(R.string.omnix_chat_delete),
            onClick = onDelete
        )
    }
}

@Composable
private fun Hairline() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(OmnixTheme.colors.textPrimary.copy(alpha = 0.06f))
    )
}

/** "TODAY", "YESTERDAY" or the date, centred in mono. */
@Composable
private fun DayLabel(date: LocalDate) {
    val today = remember { LocalDate.now() }
    val text = when (date) {
        today -> stringResource(R.string.omnix_chat_today)
        today.minusDays(1) -> stringResource(R.string.omnix_chat_yesterday)
        else -> remember(date) {
            DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault()).format(date).uppercase(Locale.getDefault())
        }
    }
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = OmnixMonoLabelStyle.copy(fontSize = 11.sp, letterSpacing = 0.04.em),
            color = OmnixTheme.colors.textTertiary
        )
    }
}

/**
 * One message. The user's words: a paper bubble on the right, up to 78 % of
 * the thread. OMNIX: a card bubble on the left, up to 82 %, signed when the
 * answer came from the cloud. A failed round trip: a red-tinted bubble with a
 * warning mark. Each message is one accessibility node ("You: …").
 */
@Composable
private fun MessageRow(message: Message, fromCloud: Boolean, threadWidth: Dp) {
    val colors = OmnixTheme.colors
    val isUser = message.role == MessageRole.USER
    val isError = message.role == MessageRole.ERROR
    val sender = stringResource(if (isUser) R.string.omnix_chat_you else R.string.omnix_chat_omnix)
    val cloudCaption = stringResource(R.string.omnix_chat_from_cloud)
    val description = if (fromCloud && !isUser) "$sender: ${message.text}. $cloudCaption" else "$sender: ${message.text}"

    if (isUser) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clearAndSetSemantics { contentDescription = description },
            contentAlignment = Alignment.CenterEnd
        ) {
            Text(
                text = message.text,
                style = BubbleText,
                color = colors.textPrimary,
                modifier = Modifier
                    .widthIn(max = threadWidth * 0.78f)
                    .clip(UserBubbleShape)
                    .background(colors.surfaceElevated)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (isError) {
            Row(
                modifier = Modifier
                    .widthIn(max = threadWidth * 0.82f)
                    .clip(AiBubbleShape)
                    .background(colors.errorBubble)
                    .border(BorderStroke(1.dp, colors.border), AiBubbleShape)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OmnixAlertIcon(color = colors.stateError, size = 16.dp)
                Text(text = message.text, style = BubbleText, color = colors.stateError)
            }
        } else {
            // Ответ OMNIX — обычный текст на всю ширину, без пузыря (как в ChatGPT).
            Text(text = message.text, style = BubbleText, color = colors.textPrimary)
        }
        if (fromCloud) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(OmnixCloudLineIcon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
                Text(
                    text = cloudCaption,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 11.sp),
                    color = colors.textTertiary
                )
            }
        }
    }
}

/**
 * The pause between send and answer: three dots breathe inside the card
 * bubble OMNIX answers in. Static under reduced motion (§29).
 */
@Composable
private fun ThinkingRow() {
    val colors = OmnixTheme.colors
    val reduced = LocalReducedMotion.current
    val label = stringResource(R.string.omnix_chat_thinking)

    val transition = rememberInfiniteTransition(label = "omnixThinking")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            tween(OmnixTheme.motion.executingCycleMs * 2, easing = LinearEasing)
        ),
        label = "omnixThinkingPhase"
    )

    Row(
        modifier = Modifier
            .clearAndSetSemantics { contentDescription = label }
            .clip(AiBubbleShape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), AiBubbleShape)
            .padding(horizontal = 16.dp, vertical = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        repeat(3) { index ->
            val pulse = if (reduced) {
                0.45f
            } else {
                0.25f + 0.75f * sin(((phase + index / 3f) % 1f) * PI).toFloat()
            }
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(colors.accentBrand.copy(alpha = pulse))
            )
        }
    }
}

/**
 * Consent first, privacy badge under it: the badge is a warning about text
 * that is still editable, the consent card is a decision that is already
 * pending. They never appear at the same time — the input is empty while
 * consent is pending.
 */
@Composable
private fun ConsentAndBadge(state: ChatUiState, onAllowCloud: () -> Unit, onKeepLocal: () -> Unit) {
    val consent = state.pendingCloudConsent
    var lastConsent by remember { mutableStateOf<PendingCloudConsentUi?>(null) }
    LaunchedEffect(consent) {
        if (consent != null) lastConsent = consent
    }
    val badgeLevel = state.privacyClassification.level
    val showBadge = state.inputText.isNotBlank() &&
        (badgeLevel == PrivacyLevel.PRIVATE || badgeLevel == PrivacyLevel.SENSITIVE)
    val fade = OmnixTheme.motion.contentFadeMs

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        AnimatedVisibility(
            visible = consent != null,
            enter = fadeIn(tween(fade)) + expandVertically(),
            exit = fadeOut(tween(fade)) + shrinkVertically()
        ) {
            lastConsent?.let { pending ->
                CloudConsentCard(
                    promptMessage = pending.promptMessage,
                    onAllow = onAllowCloud,
                    onKeepLocal = onKeepLocal
                )
            }
        }
        AnimatedVisibility(
            visible = showBadge,
            enter = fadeIn(tween(fade)) + expandVertically(),
            exit = fadeOut(tween(fade)) + shrinkVertically()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.End
            ) {
                PrivacyBadge(level = badgeLevel)
            }
        }
    }
}

/**
 * C-02: the cloud consent question, asked in writing exactly as the voice
 * layer asks it aloud. "Keep on device" is as prominent as the request —
 * the decline path is never a faint link (§36).
 */
@Composable
private fun CloudConsentCard(
    promptMessage: String,
    onAllow: () -> Unit,
    onKeepLocal: () -> Unit
) {
    val colors = OmnixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(CardShape)
            .background(colors.surface)
            .border(BorderStroke(OmnixHairline, colors.border), CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = promptMessage, style = BubbleText, color = colors.textPrimary)
        OmnixPrimaryButton(
            text = stringResource(R.string.omnix_chat_consent_allow),
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth()
        )
        OmnixSecondaryButton(
            text = stringResource(R.string.omnix_chat_consent_keep_local),
            onClick = onKeepLocal,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * CR-17: the debounced classification, shown while the text can still be
 * edited. Quiet by design — it is information, not an alarm.
 */
@Composable
private fun PrivacyBadge(level: PrivacyLevel) {
    val colors = OmnixTheme.colors
    val label = stringResource(
        if (level == PrivacyLevel.SENSITIVE) R.string.omnix_privacy_badge_sensitive else R.string.omnix_privacy_badge_private
    )
    val hint = stringResource(R.string.omnix_privacy_badge_hint)
    Row(
        modifier = Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $hint" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        OmnixLockIcon(color = colors.textSecondary, size = 13.dp)
        Text(
            text = label,
            style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
            color = colors.textSecondary
        )
    }
}

/**
 * The composer (owner mock 2026-10-21, like ChatGPT): the field with the
 * dictation microphone inside it, and one lime button — voice mode while the
 * field is empty, send once there is text. Dictation fills the field; the
 * user sends. The field grows while the user types and stays editable while
 * a request is in flight; the ViewModel guards double sends.
 */
@Composable
private fun ChatComposer(
    text: String,
    isSending: Boolean,
    isDictating: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleDictation: () -> Unit,
    onOpenVoiceMode: () -> Unit
) {
    val colors = OmnixTheme.colors
    val hasText = text.isNotBlank()
    val buttonLabel = stringResource(if (hasText) R.string.omnix_chat_send else R.string.omnix_chat_voice_mode)
    val dictationLabel = stringResource(
        if (isDictating) R.string.omnix_chat_dictation_stop else R.string.omnix_chat_dictation_start
    )
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(FieldShape)
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.border), FieldShape)
                .padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.omnix_chat_input_hint),
                        style = FieldText,
                        color = colors.textTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = false,
                    maxLines = 6,
                    textStyle = FieldText.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
            // Диктовка: текст попадает в поле, отправляет пользователь.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onToggleDictation)
                    .clearAndSetSemantics {
                        contentDescription = dictationLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = OmnixMicLineIcon,
                    contentDescription = null,
                    tint = if (isDictating) colors.accentBrand else colors.textSecondary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .omnixPressScale(interaction)
                .clip(CircleShape)
                .background(colors.accentBrand)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = !hasText || !isSending,
                    onClick = if (hasText) onSend else onOpenVoiceMode
                )
                .clearAndSetSemantics {
                    contentDescription = buttonLabel
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (hasText) OmnixSendLineIcon else OmnixVoiceWaveLineIcon,
                contentDescription = null,
                tint = colors.background,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

private val BubbleText = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp)
private val FieldText = TextStyle(fontFamily = OmnixSans, fontSize = 15.sp, lineHeight = 22.sp)
private val UserBubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
private val AiBubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 20.dp, bottomStart = 6.dp)
private val FieldShape = RoundedCornerShape(24.dp)
private val CardShape = RoundedCornerShape(18.dp)
