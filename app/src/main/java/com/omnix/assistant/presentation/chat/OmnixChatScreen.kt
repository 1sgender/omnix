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
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixAlertIcon
import com.omnix.assistant.presentation.components.OmnixCheckLineIcon
import com.omnix.assistant.presentation.components.OmnixCloudLineIcon
import com.omnix.assistant.presentation.components.OmnixCopyLineIcon
import com.omnix.assistant.presentation.components.OmnixHairline
import com.omnix.assistant.presentation.components.OmnixLockIcon
import com.omnix.assistant.presentation.components.OmnixMenuLineIcon
import com.omnix.assistant.presentation.components.OmnixMicLineIcon
import com.omnix.assistant.presentation.components.OmnixNewChatLineIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixSendLineIcon
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.LocalReducedMotion
import com.omnix.assistant.presentation.design.OmnixSans
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Чат в духе ChatGPT (отзыв владельца на v344, скриншоты ChatGPT):
 *
 * - слева выезжает меню: поиск, «Новый чат», прошлые разговоры по датам;
 * - пустой чат спрашивает «Чем могу помочь?» и предлагает три готовые просьбы;
 * - слова пользователя — в пузыре справа, ответ OMNIX — обычный текст на всю
 *   ширину с разметкой, его можно выделить и скопировать;
 * - поле ввода — «пилюля» с диктовкой и кнопкой отправки внутри.
 *
 * Отдельного голосового режима нет: голос — это сам OMNIX («Омни»).
 * Состояния, которые макет не рисует, работают как раньше: «думает», ошибка
 * (красный пузырь), вопрос о согласии на облако (C-02), метка приватности при
 * вводе (CR-17) и общий лист подтверждения.
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
    onToggleDictation: () -> Unit
) {
    val colors = OmnixTheme.colors
    val drawerState = rememberDrawerState(if (state.showHistory) DrawerValue.Open else DrawerValue.Closed)
    val historyOpen by rememberUpdatedState(state.showHistory)
    val closeHistory by rememberUpdatedState(onCloseHistory)

    // Меню открывает и закрывает ViewModel (кнопка, выбор чата); свайп
    // пользователя сообщаем обратно, чтобы состояние не разошлось.
    LaunchedEffect(state.showHistory) {
        if (state.showHistory) drawerState.open() else drawerState.close()
    }
    LaunchedEffect(drawerState) {
        snapshotFlow { drawerState.currentValue }.collect { value ->
            if (value == DrawerValue.Closed && historyOpen) closeHistory()
        }
    }
    BackHandler(enabled = state.showHistory, onBack = onCloseHistory)

    ModalNavigationDrawer(
        drawerState = drawerState,
        modifier = modifier,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.background,
                drawerShape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp),
                modifier = Modifier.width(304.dp)
            ) {
                ChatDrawerContent(
                    conversations = state.conversations,
                    currentConversationId = state.currentConversationId,
                    onNewChat = onNewChat,
                    onOpen = onOpenConversation,
                    onDelete = onDeleteConversation
                )
            }
        }
    ) {
        ChatThread(
            state = state,
            isOnline = isOnline,
            onInputChange = onInputChange,
            onSend = onSend,
            onNewChat = onNewChat,
            onOpenHistory = onOpenHistory,
            onAllowCloud = onAllowCloud,
            onKeepLocal = onKeepLocal,
            onToggleDictation = onToggleDictation
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

@Composable
private fun ChatThread(
    state: ChatUiState,
    isOnline: Boolean,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onNewChat: () -> Unit,
    onOpenHistory: () -> Unit,
    onAllowCloud: () -> Unit,
    onKeepLocal: () -> Unit,
    onToggleDictation: () -> Unit
) {
    val colors = OmnixTheme.colors
    val listState = rememberLazyListState()

    // The thinking row closes the list while a round trip is in flight; the
    // confirmation paths replace it with their own surfaces.
    val showThinking = state.isSending &&
        state.pendingConfirmation == null &&
        state.pendingCloudConsent == null
    val localOnly = state.localOnlyAnswer
    val rowCount = state.messages.size + (if (showThinking) 1 else 0) + (if (localOnly != null) 1 else 0)

    LaunchedEffect(rowCount) {
        if (rowCount > 0) listState.animateScrollToItem(rowCount - 1)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
            .imePadding()
    ) {
        ChatHeader(isOnline = isOnline, onOpenHistory = onOpenHistory, onNewChat = onNewChat)

        if (rowCount == 0) {
            EmptyChat(
                modifier = Modifier.weight(1f),
                onSuggestion = { text ->
                    onInputChange(text)
                    onSend()
                }
            )
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
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    items(state.messages, key = { "msg-${it.id}" }) { message ->
                        MessageRow(
                            message = message,
                            fromCloud = message.id in state.cloudMessageIds,
                            adminLabel = state.adminLabels[message.id],
                            threadWidth = threadWidth
                        )
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

        ChatComposer(
            text = state.inputText,
            isSending = state.isSending,
            isDictating = state.isVoiceDictating,
            onTextChange = onInputChange,
            onSend = onSend,
            onToggleDictation = onToggleDictation
        )
    }
}

@Composable
private fun ChatHeader(isOnline: Boolean, onOpenHistory: () -> Unit, onNewChat: () -> Unit) {
    val colors = OmnixTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HeaderIconButton(
            icon = OmnixMenuLineIcon,
            label = stringResource(R.string.omnix_chat_history_open),
            onClick = onOpenHistory
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = stringResource(R.string.omnix_chat_title),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                color = colors.textPrimary
            )
            Text(
                text = stringResource(
                    if (isOnline) R.string.omnix_chat_subtitle_online else R.string.omnix_chat_subtitle_on_device
                ),
                style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
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

/** «Чем могу помочь?» по центру и три готовые просьбы над полем ввода. */
@Composable
private fun EmptyChat(modifier: Modifier, onSuggestion: (String) -> Unit) {
    val colors = OmnixTheme.colors
    val suggestions = listOf(
        stringResource(R.string.omnix_chat_suggestion_1),
        stringResource(R.string.omnix_chat_suggestion_2),
        stringResource(R.string.omnix_chat_suggestion_3)
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.omnix_chat_empty_title),
                style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 31.sp),
                color = colors.textPrimary,
                textAlign = TextAlign.Center
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            suggestions.forEach { text ->
                Text(
                    text = text,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp, lineHeight = 19.sp),
                    color = colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .clip(SuggestionShape)
                        .border(BorderStroke(1.dp, colors.border), SuggestionShape)
                        .clickable { onSuggestion(text) }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Одно сообщение. Пользователь — пузырь справа, до 78 % ширины. OMNIX —
 * текст на всю ширину с разметкой, под ним «Скопировать» и, если ответ из
 * облака, подпись. Ошибка — красноватый пузырь со значком.
 */
@Composable
private fun MessageRow(
    message: Message,
    fromCloud: Boolean,
    threadWidth: Dp,
    adminLabel: String? = null
) {
    val colors = OmnixTheme.colors
    val isUser = message.role == MessageRole.USER
    val isError = message.role == MessageRole.ERROR
    val sender = stringResource(if (isUser) R.string.omnix_chat_you else R.string.omnix_chat_omnix)
    // Админу (тест моделей) подпись называет модель, остальным — «Ответ из облака».
    val caption = adminLabel ?: if (fromCloud) stringResource(R.string.omnix_chat_from_cloud) else null
    val description = if (caption != null && !isUser) "$sender: ${message.text}. $caption" else "$sender: ${message.text}"

    if (isUser) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = description },
            contentAlignment = Alignment.CenterEnd
        ) {
            SelectionContainer {
                Text(
                    text = message.text,
                    style = BubbleText,
                    color = colors.textPrimary,
                    modifier = Modifier
                        .widthIn(max = threadWidth * 0.78f)
                        .clip(UserBubbleShape)
                        .background(colors.surfaceElevated)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
        return
    }

    if (isError) {
        Row(
            modifier = Modifier
                .widthIn(max = threadWidth * 0.9f)
                .clip(ErrorShape)
                .background(colors.errorBubble)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .clearAndSetSemantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OmnixAlertIcon(color = colors.stateError, size = 16.dp)
            Text(text = message.text, style = BubbleText, color = colors.stateError)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SelectionContainer(modifier = Modifier.semantics { contentDescription = description }) {
            ChatMarkdown(
                text = message.text,
                style = BubbleText,
                color = colors.textPrimary,
                codeBackground = colors.surfaceElevated
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CopyButton(text = message.text)
            if (caption != null) {
                if (fromCloud) {
                    Icon(OmnixCloudLineIcon, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(12.dp))
                }
                Text(
                    text = caption,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 11.sp),
                    color = colors.textTertiary
                )
            }
        }
    }
}

@Composable
private fun CopyButton(text: String) {
    val colors = OmnixTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val label = stringResource(if (copied) R.string.omnix_chat_copied else R.string.omnix_chat_copy)
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable {
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (copied) OmnixCheckLineIcon else OmnixCopyLineIcon,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(16.dp)
        )
    }
}

/** Пауза между отправкой и ответом: одна мягко пульсирующая точка, без пузыря. */
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
    val pulse = if (reduced) 0.8f else 0.55f + 0.45f * sin(phase * PI).toFloat()

    Box(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clearAndSetSemantics { contentDescription = label }
            .size(12.dp * pulse)
            .clip(CircleShape)
            .background(colors.textPrimary)
    )
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
 * Поле ввода как в ChatGPT: одна «пилюля», внутри — текст, микрофон диктовки
 * и круглая кнопка отправки (появляется, когда есть текст). Enter переносит
 * строку, отправляет кнопка. Диктовка дописывает текст в поле; отправляет
 * пользователь. Поле растёт до шести строк и остаётся редактируемым, пока
 * идёт запрос; двойную отправку отсекает ViewModel.
 */
@Composable
private fun ChatComposer(
    text: String,
    isSending: Boolean,
    isDictating: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleDictation: () -> Unit
) {
    val colors = OmnixTheme.colors
    val hasText = text.isNotBlank()
    val sendLabel = stringResource(R.string.omnix_chat_send)
    val dictationLabel = stringResource(
        if (isDictating) R.string.omnix_chat_dictation_stop else R.string.omnix_chat_dictation_start
    )
    val interaction = remember { MutableInteractionSource() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp)
            .heightIn(min = 52.dp)
            .clip(ComposerShape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.border), ComposerShape)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(top = 8.dp, bottom = 8.dp),
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
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                modifier = Modifier
                    .testTag(CHAT_COMPOSER_FIELD_TAG)
                    .fillMaxWidth()
                    .heightIn(max = 140.dp)
                    .verticalScroll(rememberScrollState())
            )
        }

        // Диктовка: текст дописывается в поле, отправляет пользователь.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isDictating) colors.accentBrandSoft else colors.surface)
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

        AnimatedVisibility(visible = hasText || isSending, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .omnixPressScale(interaction)
                    .clip(CircleShape)
                    .background(if (hasText && !isSending) colors.textPrimary else colors.surfaceElevated)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        enabled = hasText && !isSending,
                        onClick = onSend
                    )
                    .clearAndSetSemantics {
                        contentDescription = sendLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = OmnixSendLineIcon,
                    contentDescription = null,
                    tint = if (hasText && !isSending) colors.background else colors.textTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/** Поле ввода чата; в боковом меню есть второе поле — поиск. */
internal const val CHAT_COMPOSER_FIELD_TAG = "chat-composer-field"

private val BubbleText = TextStyle(fontFamily = OmnixSans, fontSize = 16.sp, lineHeight = 24.sp)
private val FieldText = TextStyle(fontFamily = OmnixSans, fontSize = 16.sp, lineHeight = 22.sp)
private val UserBubbleShape = RoundedCornerShape(22.dp)
private val ErrorShape = RoundedCornerShape(16.dp)
private val SuggestionShape = RoundedCornerShape(16.dp)
private val ComposerShape = RoundedCornerShape(28.dp)
private val CardShape = RoundedCornerShape(20.dp)
