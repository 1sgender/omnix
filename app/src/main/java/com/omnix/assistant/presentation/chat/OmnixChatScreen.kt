package com.omnix.assistant.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import java.util.Locale
import com.omnix.assistant.presentation.design.OmnixMonoLabelStyle
import com.omnix.assistant.presentation.firstrun.OmnixRingMark
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Audiotrack
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.Close
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
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
    onToggleDictation: () -> Unit,
    pinnedIds: Set<Long> = emptySet(),
    onTogglePin: (Long) -> Unit = {},
    customTitles: Map<Long, String> = emptyMap(),
    onRename: (Long, String) -> Unit = { _, _ -> },
    onSwitchToFlash: () -> Unit = {},
    modelPicker: ChatModelPickerState? = null,
    onSelectModel: (com.omnix.assistant.agent.decision.AiModelChoice) -> Unit = {},
    onOpenTariffs: () -> Unit = {},
    onAttachmentPicked: (android.net.Uri) -> Unit = {},
    onClearAttachment: () -> Unit = {},
    onImagePicked: (android.net.Uri) -> Unit = {},
    onClearImage: () -> Unit = {}
) {
    val colors = OmnixTheme.colors
    val attachPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) onAttachmentPicked(uri) }
    // Системный Photo Picker (на старых телефонах сам превращается в обычный выбор файла).
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onImagePicked(uri) }
    // Камера: снимок кладётся во временный файл приложения, права на камеру не нужны.
    val chatContext = androidx.compose.ui.platform.LocalContext.current
    var cameraUri by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { saved ->
        val target = cameraUri
        if (saved && target != null) onImagePicked(target)
    }
    var modelSheetOpen by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        ChatThread(
            state = state,
            isOnline = isOnline,
            onInputChange = onInputChange,
            onSend = onSend,
            onNewChat = onNewChat,
            onOpenHistory = onOpenHistory,
            onAllowCloud = onAllowCloud,
            onKeepLocal = onKeepLocal,
            onToggleDictation = onToggleDictation,
            onSwitchToFlash = onSwitchToFlash,
            modelPicker = modelPicker,
            onOpenModelSheet = { modelSheetOpen = true },
            onAttach = { attachPicker.launch(arrayOf("*/*")) },
            onClearAttachment = onClearAttachment,
            onPickPhoto = {
                photoPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            },
            onTakePhoto = {
                val target = com.omnix.assistant.data.files.ChatImageStore.newCameraUri(chatContext)
                cameraUri = target
                try {
                    cameraLauncher.launch(target)
                } catch (_: android.content.ActivityNotFoundException) {
                    // Нет приложения камеры: остаётся галерея.
                }
            },
            onClearImage = onClearImage
        )
    }

    // Список чатов — панель слева на пол-экрана поверх чата (дизайн «Трафарет»).
    // Открывает и закрывает её ViewModel; тап по затемнению и «Назад» сообщаем ей.
    BackHandler(enabled = state.showHistory, onBack = onCloseHistory)
    AnimatedVisibility(
        visible = state.showHistory,
        enter = fadeIn(tween(HISTORY_ANIM_MS)),
        exit = fadeOut(tween(HISTORY_ANIM_MS))
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onCloseHistory
                )
        )
    }
    AnimatedVisibility(
        visible = state.showHistory,
        enter = slideInHorizontally(tween(HISTORY_ANIM_MS)) { -it },
        exit = slideOutHorizontally(tween(HISTORY_ANIM_MS)) { -it }
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(maxOf(maxWidth * HISTORY_PANEL_FRACTION, HISTORY_PANEL_MIN_WIDTH))
                    .clip(panelShape)
                    .background(colors.surface)
                    // Панель «глотает» тапы, чтобы они не доходили до затемнения.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .statusBarsPadding()
            ) {
                ChatDrawerContent(
                    conversations = state.conversations,
                    currentConversationId = state.currentConversationId,
                    onNewChat = onNewChat,
                    onOpen = onOpenConversation,
                    onDelete = onDeleteConversation,
                    pinnedIds = pinnedIds,
                    onTogglePin = onTogglePin,
                    customTitles = customTitles,
                    onRename = onRename
                )
            }
        }
    }

    // Лист выбора модели: чип у поля ввода → «Кто отвечает».
    if (modelSheetOpen && modelPicker != null) {
        ChatModelSheet(
            state = modelPicker,
            onSelect = onSelectModel,
            onOpenTariffs = onOpenTariffs,
            onDismiss = { modelSheetOpen = false }
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
    onToggleDictation: () -> Unit,
    onSwitchToFlash: () -> Unit,
    modelPicker: ChatModelPickerState?,
    onOpenModelSheet: () -> Unit,
    onAttach: () -> Unit,
    onClearAttachment: () -> Unit,
    onPickPhoto: () -> Unit,
    onTakePhoto: () -> Unit,
    onClearImage: () -> Unit
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

    // Для «Ещё раз»: к каждому ответу — последний вопрос пользователя перед ним.
    val repeatTargets = remember(state.messages) {
        val targets = HashMap<Long, String>()
        var lastQuestion: String? = null
        for (m in state.messages) {
            when (m.role) {
                MessageRole.USER -> lastQuestion = m.text
                MessageRole.ASSISTANT, MessageRole.ERROR -> lastQuestion?.let { targets[m.id] = it }
                else -> Unit
            }
        }
        targets
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
                        val question = repeatTargets[message.id]
                        MessageRow(
                            message = message,
                            fromCloud = message.id in state.cloudMessageIds,
                            adminLabel = state.adminLabels[message.id],
                            flashFallback = message.id in state.flashFallbackIds,
                            tier = state.answeredTiers[message.id],
                            onSwitchToFlash = if (message.id in state.proQuotaIds) onSwitchToFlash else null,
                            threadWidth = threadWidth,
                            onRepeat = if (question != null && !state.isSending) {
                                {
                                    onInputChange(question)
                                    onSend()
                                }
                            } else {
                                null
                            }
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

        if (modelPicker != null) {
            ChatModelChip(
                choice = modelPicker.choice,
                checking = modelPicker.applying != null,
                onClick = onOpenModelSheet,
                modifier = Modifier.padding(start = 16.dp)
            )
        }

        ChatComposer(
            text = state.inputText,
            isSending = state.isSending,
            isDictating = state.isVoiceDictating,
            onTextChange = onInputChange,
            onSend = onSend,
            onToggleDictation = onToggleDictation,
            attachment = state.attachment,
            attachmentError = state.attachmentError,
            attachmentProcessing = state.attachmentProcessing,
            onAttach = onAttach,
            onClearAttachment = onClearAttachment,
            imageId = state.pendingImageId,
            imageProcessing = state.imageProcessing,
            imageError = state.imageError,
            onPickPhoto = onPickPhoto,
            onTakePhoto = onTakePhoto,
            onClearImage = onClearImage
        )
    }
}

@Composable
private fun ChatHeader(isOnline: Boolean, onOpenHistory: () -> Unit, onNewChat: () -> Unit) {
    val colors = OmnixTheme.colors
    Column {
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
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.omnix_chat_title),
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    color = colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(
                        if (isOnline) R.string.omnix_chat_subtitle_online else R.string.omnix_chat_subtitle_on_device
                    ).uppercase(Locale.getDefault()),
                    style = OmnixMonoLabelStyle.copy(fontSize = 10.sp),
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
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(colors.border)
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
        stringResource(R.string.omnix_chat_suggestion_3),
        stringResource(R.string.omnix_chat_suggestion_4),
        stringResource(R.string.omnix_chat_suggestion_5)
    )
    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = stringResource(R.string.omnix_chat_empty_title),
                    style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 31.sp),
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.omnix_chat_empty_hint),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp, lineHeight = 20.sp),
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
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
    adminLabel: String? = null,
    flashFallback: Boolean = false,
    tier: com.omnix.assistant.agent.decision.AiTier? = null,
    onSwitchToFlash: (() -> Unit)? = null,
    onRepeat: (() -> Unit)? = null
) {
    val colors = OmnixTheme.colors
    val isUser = message.role == MessageRole.USER
    val isError = message.role == MessageRole.ERROR
    val sender = stringResource(if (isUser) R.string.omnix_chat_you else R.string.omnix_chat_omnix)
    // Админу (тест моделей) подпись называет модель; выбран Pro, а ответил
    // Flash — «Ответил Flash»; остальным — «Ответ из облака».
    val caption = adminLabel ?: when {
        flashFallback -> stringResource(R.string.omnix_tier_answered_flash)
        fromCloud -> stringResource(R.string.omnix_chat_from_cloud)
        else -> null
    }
    val description = if (caption != null && !isUser) "$sender: ${message.text}. $caption" else "$sender: ${message.text}"

    if (isUser) {
        // Файл из сообщения не показываем целиком: имя файла и вопрос.
        // Фото лежит в сообщении ссылкой: в ленте миниатюра, ссылка не видна.
        val image = remember(message.text) { com.omnix.assistant.domain.files.ChatImageCodec.decode(message.text) }
        val decoded = remember(message.text) { com.omnix.assistant.domain.files.ChatAttachmentCodec.decode(image.question) }
        val shown = decoded.question
        val fileLabel = decoded.fileName?.let { name -> listOfNotNull(name, fileSizeLabel(decoded.sizeBytes ?: -1)).joinToString(", ") }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = listOfNotNull("$sender:", fileLabel, shown.ifEmpty { null }).joinToString(" ") },
            contentAlignment = Alignment.CenterEnd
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                image.imageIds.forEach { id -> ChatImageThumb(imageId = id, size = 180.dp) }
                decoded.fileName?.let { name ->
                    ChatFileCard(
                        name = name,
                        kind = decoded.kind,
                        sizeBytes = decoded.sizeBytes ?: -1,
                        maxWidth = threadWidth * 0.78f
                    )
                }
                if (shown.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = shown,
                            style = BubbleText,
                            color = colors.onActionPrimary,
                            modifier = Modifier
                                .widthIn(max = threadWidth * 0.78f)
                                .clip(UserBubbleShape)
                                .background(colors.actionPrimary)
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
        return
    }

    if (isError) {
        // Спокойное сообщение в ленте: без плашки, тонкая линия слева как
        // единственный акцент, текст обычного размера и приглушённого цвета.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .clearAndSetSemantics { contentDescription = description },
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(colors.stateError.copy(alpha = 0.65f))
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = threadWidth * 0.9f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = message.text,
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 14.sp, lineHeight = 20.sp),
                    color = colors.textSecondary
                )
                if (onRepeat != null || onSwitchToFlash != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (onRepeat != null) {
                            ErrorAction(text = stringResource(R.string.omnix_chat_retry), onClick = onRepeat)
                        }
                        // Кончился лимит Pro: одно нажатие — и дальше отвечает Flash.
                        if (onSwitchToFlash != null) {
                            ErrorAction(text = stringResource(R.string.omnix_tier_switch_to_flash), onClick = onSwitchToFlash)
                        }
                    }
                }
            }
        }
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
    AssistantAvatar()
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        SelectionContainer(modifier = Modifier.semantics { contentDescription = description }) {
            ChatMarkdown(
                text = message.text,
                style = BubbleText,
                color = colors.textPrimary,
                codeBackground = colors.surfaceElevated,
                accent = colors.accentBrand
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CopyButton(text = message.text)
            if (onRepeat != null) {
                Text(
                    text = stringResource(R.string.omnix_chat_repeat),
                    style = TextStyle(fontFamily = OmnixSans, fontSize = 12.sp),
                    color = colors.textSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onRepeat)
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                )
            }
            // Моно-метка «чем отвечено»; не дублирует «Ответил Flash» и подпись админа.
            if (tier != null && adminLabel == null && !flashFallback) {
                Text(
                    text = if (tier == com.omnix.assistant.agent.decision.AiTier.PRO) "PRO" else "FLASH",
                    style = OmnixMonoLabelStyle.copy(fontSize = 10.sp),
                    color = colors.textTertiary
                )
            }
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
}

/** Круглый аватар OMNIX слева от ответа: знак на тёмной подложке (макет «Чат»). */
@Composable
private fun AssistantAvatar() {
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(OmnixTheme.colors.surfaceElevated),
        contentAlignment = Alignment.Center
    ) {
        OmnixRingMark(width = 14.dp, height = 15.dp)
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

/** Пауза между отправкой и ответом: три точки, по которым бежит волна яркости. */
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
            // В режиме уменьшенного движения цикл равен 0, а нулевая длительность
            // даёт деление на ноль в VectorizedInfiniteRepeatableSpec.
            tween((OmnixTheme.motion.executingCycleMs * 2).coerceAtLeast(1), easing = LinearEasing)
        ),
        label = "omnixThinkingPhase"
    )
    Row(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        repeat(THINKING_DOTS) { index ->
            val wave = if (reduced) {
                0.6f
            } else {
                0.25f + 0.75f * ((sin((phase - index / THINKING_DOTS.toFloat()) * 2 * PI) + 1) / 2).toFloat()
            }
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(colors.textPrimary.copy(alpha = wave))
            )
        }
    }
}

private const val THINKING_DOTS = 3

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
    onToggleDictation: () -> Unit,
    attachment: PendingAttachmentUi? = null,
    attachmentError: com.omnix.assistant.domain.files.FileTextExtractor.Reason? = null,
    attachmentProcessing: AttachmentWork? = null,
    onAttach: () -> Unit = {},
    onClearAttachment: () -> Unit = {},
    imageId: String? = null,
    imageProcessing: Boolean = false,
    imageError: com.omnix.assistant.domain.files.ChatImageRules.Reject? = null,
    onPickPhoto: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onClearImage: () -> Unit = {}
) {
    val colors = OmnixTheme.colors
    val hasText = text.isNotBlank() || attachment != null || imageId != null
    var attachMenuOpen by remember { mutableStateOf(false) }
    val attachLabel = stringResource(R.string.omnix_chat_attach)
    val sendLabel = stringResource(R.string.omnix_chat_send)
    val dictationLabel = stringResource(
        if (isDictating) R.string.omnix_chat_dictation_stop else R.string.omnix_chat_dictation_start
    )

    Column(modifier = Modifier.fillMaxWidth()) {
    ImageStrip(imageId, imageProcessing, imageError, onClearImage)
    AttachmentStrip(attachment, attachmentError, attachmentProcessing, onClearAttachment)
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
                textStyle = FieldText.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.textPrimary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default
                ),
                modifier = Modifier
                    .testTag(CHAT_COMPOSER_FIELD_TAG)
                    .fillMaxWidth()
                    // Высота ограничена, дальше поле прокручивается само.
                    .heightIn(max = 140.dp)
            )
        }

        // Скрепка: меню «Файл / Фото из галереи / Снимок»; выбранное уйдёт со следующим сообщением.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable { attachMenuOpen = true }
                .clearAndSetSemantics {
                    contentDescription = attachLabel
                    role = Role.Button
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.AttachFile,
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(22.dp)
            )
            androidx.compose.material3.DropdownMenu(
                expanded = attachMenuOpen,
                onDismissRequest = { attachMenuOpen = false }
            ) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.omnix_chat_attach_file)) },
                    leadingIcon = { Icon(Icons.Outlined.Description, contentDescription = null) },
                    onClick = {
                        attachMenuOpen = false
                        onAttach()
                    }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.omnix_chat_attach_photo)) },
                    leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
                    onClick = {
                        attachMenuOpen = false
                        onPickPhoto()
                    }
                )
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(stringResource(R.string.omnix_chat_attach_camera)) },
                    leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
                    onClick = {
                        attachMenuOpen = false
                        onTakePhoto()
                    }
                )
            }
        }

        // Диктовка: текст дописывается в поле, отправляет пользователь.
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (isDictating) colors.accentBrandSoft else colors.actionPrimary)
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
                tint = if (isDictating) colors.accentBrand else colors.onActionPrimary,
                modifier = Modifier.size(20.dp)
            )
        }

        // Без анимаций появления и пружины нажатия: краш-отчёты 2026-11-02
        // показали падение (divide by zero) именно здесь, при появлении кнопки.
        if (hasText || isSending) {
            Box(
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (hasText && !isSending) colors.textPrimary else colors.surfaceElevated)
                    .clickable(
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
}

/** Над полем ввода: чип приложенного файла или причина, почему файл не принят. */
@Composable
private fun AttachmentStrip(
    attachment: PendingAttachmentUi?,
    error: com.omnix.assistant.domain.files.FileTextExtractor.Reason?,
    processing: AttachmentWork?,
    onClear: () -> Unit
) {
    val colors = OmnixTheme.colors
    if (attachment != null) {
        val removeLabel = stringResource(R.string.omnix_chat_attach_remove)
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 6.dp)
                .clip(CircleShape)
                .background(colors.surfaceElevated)
                .padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = fileKindIcon(attachment.kind),
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(18.dp)
            )
            androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = attachment.name,
                style = FieldText,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 200.dp)
            )
            fileSizeLabel(attachment.sizeBytes)?.let { size ->
                Text(
                    text = " · $size",
                    style = FieldText,
                    color = colors.textTertiary,
                    maxLines = 1
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClear)
                    .clearAndSetSemantics {
                        contentDescription = removeLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (attachment.truncated) {
            Text(
                text = stringResource(R.string.omnix_chat_attach_truncated),
                style = FieldText,
                color = colors.textTertiary,
                modifier = Modifier.padding(start = 20.dp, top = 2.dp)
            )
        }
    } else if (processing != null) {
        Text(
            text = stringResource(
                when (processing) {
                    AttachmentWork.READING -> R.string.omnix_chat_attach_reading
                    AttachmentWork.TRANSCRIBING -> R.string.omnix_chat_attach_transcribing
                }
            ),
            style = FieldText,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp)
        )
    } else if (error != null) {
        Text(
            text = stringResource(
                when (error) {
                    com.omnix.assistant.domain.files.FileTextExtractor.Reason.TOO_LARGE -> R.string.omnix_chat_attach_too_large
                    com.omnix.assistant.domain.files.FileTextExtractor.Reason.UNSUPPORTED -> R.string.omnix_chat_attach_unsupported
                    com.omnix.assistant.domain.files.FileTextExtractor.Reason.EMPTY -> R.string.omnix_chat_attach_empty
                    com.omnix.assistant.domain.files.FileTextExtractor.Reason.UNREADABLE -> R.string.omnix_chat_attach_unreadable
                    com.omnix.assistant.domain.files.FileTextExtractor.Reason.TRANSCRIPTION_FAILED ->
                        R.string.omnix_chat_attach_transcription_failed
                }
            ),
            style = FieldText.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = colors.stateError,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                .clip(ErrorShape)
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.stateError.copy(alpha = 0.4f)), ErrorShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** Поле ввода чата; в боковом меню есть второе поле — поиск. */
internal const val CHAT_COMPOSER_FIELD_TAG = "chat-composer-field"

private const val HISTORY_PANEL_FRACTION = 0.5f
private const val HISTORY_ANIM_MS = 220
private val HISTORY_PANEL_MIN_WIDTH = 280.dp
private val BubbleText =TextStyle(fontFamily = OmnixSans, fontSize = 16.sp, lineHeight = 24.sp)
private val FieldText = TextStyle(fontFamily = OmnixSans, fontSize = 16.sp, lineHeight = 22.sp)
private val UserBubbleShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomEnd = 6.dp, bottomStart = 20.dp)
private val ErrorShape = RoundedCornerShape(16.dp)
private val SuggestionShape = RoundedCornerShape(16.dp)
private val ComposerShape = RoundedCornerShape(28.dp)
private val CardShape = RoundedCornerShape(20.dp)

/** Лёгкое действие под сообщением об ошибке: тонкая пилюля, ≥48dp зона нажатия. */
@Composable
private fun ErrorAction(text: String, onClick: () -> Unit) {
    val colors = OmnixTheme.colors
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = OmnixSans, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color = colors.textPrimary,
            modifier = Modifier
                .clip(CircleShape)
                .border(BorderStroke(1.dp, colors.border), CircleShape)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** Миниатюра фото из сообщения: читается из файла приложения, не из базы. */
@Composable
private fun ChatImageThumb(imageId: String, size: Dp) {
    val colors = OmnixTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember(context) { com.omnix.assistant.data.files.ChatImageStore(context.applicationContext) }
    val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, imageId) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            store.thumbnail(imageId, 512)
        }
    }
    val label = stringResource(R.string.omnix_chat_image_description)
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceElevated)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        val loaded = bitmap
        if (loaded != null) {
            androidx.compose.foundation.Image(
                bitmap = loaded.asImageBitmap(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Outlined.Image,
                contentDescription = null,
                tint = colors.textTertiary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

/** Над полем ввода: превью выбранного фото, «готовлю фото…» или причина отказа. */
@Composable
private fun ImageStrip(
    imageId: String?,
    processing: Boolean,
    error: com.omnix.assistant.domain.files.ChatImageRules.Reject?,
    onClear: () -> Unit
) {
    val colors = OmnixTheme.colors
    if (imageId != null) {
        val removeLabel = stringResource(R.string.omnix_chat_image_remove)
        Box(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp)) {
            ChatImageThumb(imageId = imageId, size = 72.dp)
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surface)
                    .clickable(onClick = onClear)
                    .clearAndSetSemantics {
                        contentDescription = removeLabel
                        role = Role.Button
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    } else if (processing) {
        Text(
            text = stringResource(R.string.omnix_chat_image_processing),
            style = FieldText,
            color = colors.textTertiary,
            modifier = Modifier.padding(start = 20.dp, top = 6.dp)
        )
    } else if (error != null) {
        Text(
            text = stringResource(
                when (error) {
                    com.omnix.assistant.domain.files.ChatImageRules.Reject.UNSUPPORTED -> R.string.omnix_chat_image_unsupported
                    com.omnix.assistant.domain.files.ChatImageRules.Reject.TOO_LARGE -> R.string.omnix_chat_image_too_large
                    com.omnix.assistant.domain.files.ChatImageRules.Reject.UNREADABLE -> R.string.omnix_chat_image_unreadable
                }
            ),
            style = FieldText.copy(fontSize = 14.sp, lineHeight = 20.sp),
            color = colors.stateError,
            softWrap = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                .clip(ErrorShape)
                .background(colors.surface)
                .border(BorderStroke(1.dp, colors.stateError.copy(alpha = 0.4f)), ErrorShape)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

/** Значок по типу файла. */
private fun fileKindIcon(kind: com.omnix.assistant.domain.files.FileKind?): androidx.compose.ui.graphics.vector.ImageVector =
    when (kind) {
        com.omnix.assistant.domain.files.FileKind.PDF -> Icons.Outlined.PictureAsPdf
        com.omnix.assistant.domain.files.FileKind.XLSX,
        com.omnix.assistant.domain.files.FileKind.SPREADSHEET_TEXT -> Icons.Outlined.TableChart
        com.omnix.assistant.domain.files.FileKind.PPTX -> Icons.Outlined.Slideshow
        com.omnix.assistant.domain.files.FileKind.AUDIO -> Icons.Outlined.Audiotrack
        com.omnix.assistant.domain.files.FileKind.IMAGE -> Icons.Outlined.Image
        com.omnix.assistant.domain.files.FileKind.TEXT -> Icons.Outlined.Article
        else -> Icons.Outlined.Description
    }

/** «1,2 МБ» по правилам языка системы; null — размер неизвестен. */
@Composable
private fun fileSizeLabel(sizeBytes: Long): String? {
    if (sizeBytes < 0) return null
    val context = androidx.compose.ui.platform.LocalContext.current
    return android.text.format.Formatter.formatShortFileSize(context, sizeBytes)
}

/** Карточка файла в пузыре сообщения: значок, имя, тип и размер. */
@Composable
private fun ChatFileCard(
    name: String,
    kind: com.omnix.assistant.domain.files.FileKind?,
    sizeBytes: Long,
    maxWidth: Dp
) {
    val colors = OmnixTheme.colors
    val extension = name.substringAfterLast('.', "").uppercase().take(6)
    val details = listOfNotNull(extension.ifEmpty { null }, fileSizeLabel(sizeBytes)).joinToString(" · ")
    Row(
        modifier = Modifier
            .widthIn(max = maxWidth)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceElevated)
            .border(BorderStroke(1.dp, colors.border), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = fileKindIcon(kind),
                contentDescription = null,
                tint = colors.textSecondary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column {
            Text(
                text = name,
                style = FieldText,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = FieldText.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.textTertiary,
                    maxLines = 1
                )
            }
        }
    }
}
