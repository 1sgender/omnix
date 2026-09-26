package com.omnix.assistant.presentation.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omnix.assistant.R
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixAlertIcon
import com.omnix.assistant.presentation.components.OmnixArrowUpIcon
import com.omnix.assistant.presentation.components.OmnixBackIcon
import com.omnix.assistant.presentation.components.OmnixEmptyState
import com.omnix.assistant.presentation.components.OmnixHairline
import com.omnix.assistant.presentation.components.OmnixIconButton
import com.omnix.assistant.presentation.components.OmnixLockIcon
import com.omnix.assistant.presentation.components.OmnixMicIcon
import com.omnix.assistant.presentation.components.OmnixPrimaryButton
import com.omnix.assistant.presentation.components.OmnixSecondaryButton
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.components.omnixPressScale
import com.omnix.assistant.presentation.design.LocalReducedMotion
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest
import kotlin.math.PI
import kotlin.math.sin

/**
 * Chat — the quiet alternative to speaking (§21, §24).
 *
 * Stage 2 of the Apple HIG rebuild, bubbles pass (mock 2026-09-26): both
 * sides of the dialogue now speak in bubbles — the user's words in a light
 * bubble on the right, OMNIX's answers in a dark grey bubble on the left,
 * each with its sender label directly above as one block. A failed round
 * trip is its own message kind ([MessageRole.ERROR]): a dim red-tinted
 * bubble with a warning mark, so an error is never mistaken for an answer.
 * Regular font weight everywhere except the screen title; the composer is a
 * one-line capsule with compact round mic and send buttons beside it. No
 * avatars, no model picker, no regenerate button.
 *
 * The screen wires three capabilities the ViewModel already exposes but the
 * old view never rendered: the dictation control (partial results land in the
 * input field), the debounced privacy classification (CR-17: a badge warns
 * while the text is still editable, before anything is sent) and the cloud
 * consent question (C-02: allow or keep on device, the same decision the
 * voice path asks out loud).
 */
@Composable
fun OmnixChatScreen(
    state: ChatUiState,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onClear: () -> Unit,
    onAllowCloud: () -> Unit,
    onKeepLocal: () -> Unit,
    onToggleDictation: () -> Unit
) {
    val spacing = OmnixTheme.spacing
    val listState = rememberLazyListState()

    // One-shot entry animation per message id: rows scrolled back into view
    // are recognised and do not animate again.
    val animatedIds = remember { mutableSetOf<Long>() }

    // The thinking row closes the list while a round trip is in flight; the
    // confirmation paths replace it with their own surfaces.
    val showThinking = state.isSending &&
        state.pendingConfirmation == null &&
        state.pendingCloudConsent == null
    val conversationSize = state.messages.size + if (showThinking) 1 else 0

    LaunchedEffect(conversationSize) {
        if (conversationSize > 0) {
            listState.animateScrollToItem(conversationSize - 1)
        }
    }

    // Clearing is destructive and irreversible: the shared confirmation sheet
    // asks once before the history is deleted (§17, §36).
    var clearArmed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal)
                .padding(top = spacing.md, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OmnixIconButton(
                onClick = onBack,
                contentDescription = stringResource(R.string.omnix_nav_back)
            ) {
                OmnixBackIcon(color = OmnixTheme.colors.textSecondary)
            }
            Spacer(Modifier.width(spacing.xs))
            // Mock 2026-09-26: the title is the single bold element on the
            // screen (20 sp, semibold) — everything inside the thread stays
            // regular weight.
            Text(
                text = stringResource(R.string.omnix_chat_title),
                style = OmnixTheme.typography.heading,
                color = OmnixTheme.colors.textPrimary
            )
            Spacer(Modifier.weight(1f))
            if (state.messages.isNotEmpty()) {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_chat_clear),
                    onClick = { clearArmed = true }
                )
            }
        }

        if (conversationSize == 0) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
                contentAlignment = Alignment.Center
            ) {
                OmnixEmptyState(
                    title = stringResource(R.string.omnix_chat_empty_title),
                    description = stringResource(R.string.omnix_chat_empty_body)
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = spacing.screenHorizontal),
                contentPadding = PaddingValues(vertical = spacing.sm),
                verticalArrangement = Arrangement.spacedBy(spacing.lg)
            ) {
                items(state.messages, key = { it.id }) { message ->
                    val animateEntry = remember(message.id) { animatedIds.add(message.id) }
                    ChatMessageRow(
                        message = message,
                        animateEntry = animateEntry
                    )
                }
                if (showThinking) {
                    item(key = "omnix-thinking") {
                        ThinkingRow()
                    }
                }
            }
        }

        // Consent first, privacy badge under it: the badge is a warning about
        // text that is still editable, the consent card is a decision that is
        // already pending. They never appear at the same time — the input is
        // empty while consent is pending.
        val consent = state.pendingCloudConsent
        var lastConsent by remember { mutableStateOf<PendingCloudConsentUi?>(null) }
        LaunchedEffect(consent) {
            if (consent != null) {
                lastConsent = consent
            }
        }
        val badgeLevel = state.privacyClassification.level
        val showBadge = state.inputText.isNotBlank() &&
            (badgeLevel == PrivacyLevel.PRIVATE || badgeLevel == PrivacyLevel.SENSITIVE)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal)
        ) {
            AnimatedVisibility(
                visible = consent != null,
                enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)) +
                    expandVertically(
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs)) +
                    shrinkVertically(
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
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
                enter = fadeIn(tween(OmnixTheme.motion.contentFadeMs)) +
                    expandVertically(
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    ),
                exit = fadeOut(tween(OmnixTheme.motion.contentFadeMs)) +
                    shrinkVertically(
                        spring(
                            dampingRatio = Spring.DampingRatioNoBouncy,
                            stiffness = Spring.StiffnessMediumLow
                        )
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = spacing.xs),
                    horizontalArrangement = Arrangement.End
                ) {
                    PrivacyBadge(level = badgeLevel)
                }
            }
        }

        ChatComposer(
            text = state.inputText,
            isSending = state.isSending,
            isDictating = state.isVoiceDictating,
            onTextChange = onInputChange,
            onSend = onSend,
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

    if (clearArmed) {
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_chat_clear_confirm_title),
                detail = stringResource(R.string.omnix_chat_clear_confirm_body),
                confirmLabel = stringResource(R.string.omnix_chat_clear),
                cancelLabel = stringResource(R.string.omnix_cancel),
                voiceEnabled = false
            ),
            onConfirm = {
                clearArmed = false
                onClear()
            },
            onCancel = { clearArmed = false }
        )
    }
}

/**
 * One message. Bubbles on both sides (mock 2026-09-26): the user's words in
 * a light bubble on the right, OMNIX's answers in a dark grey bubble on the
 * left, a failed round trip in a dim red-tinted bubble with a warning mark.
 * The sender label sits directly above its bubble — 4 dp apart, same side —
 * so label and bubble read as one block, and the whole message is one
 * accessibility node ("You: …" / "OMNIX: …"): alignment alone never
 * encodes meaning (§29).
 */
@Composable
private fun ChatMessageRow(
    message: Message,
    animateEntry: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val reduced = LocalReducedMotion.current
    val isUser = message.role == MessageRole.USER
    val isError = message.role == MessageRole.ERROR

    var settled by remember(message.id) { mutableStateOf(!animateEntry) }
    LaunchedEffect(Unit) { settled = true }
    val progress by animateFloatAsState(
        targetValue = if (settled) 1f else 0f,
        animationSpec = if (reduced) {
            snap()
        } else {
            spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow
            )
        },
        label = "omnixChatMessageEnter"
    )

    val label = stringResource(if (isUser) R.string.omnix_chat_you else R.string.omnix_chat_omnix)
    val bubbleBackground = when {
        isUser -> colors.bubbleUser
        isError -> colors.errorBubble
        else -> colors.bubbleAi
    }
    val ink = when {
        isUser -> colors.onBubbleUser
        isError -> colors.stateError
        else -> colors.onBubbleAi
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "$label: ${message.text}" }
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 12.dp.toPx()
            },
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            text = label,
            style = OmnixTheme.typography.overline.copy(letterSpacing = 0.sp),
            color = colors.textSecondary
        )
        Spacer(Modifier.height(spacing.xxs))
        Row(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(OmnixTheme.radius.medium))
                .background(bubbleBackground)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
        ) {
            if (isError) {
                OmnixAlertIcon(
                    color = colors.stateError,
                    size = 16.dp
                )
            }
            Text(
                text = message.text,
                style = OmnixTheme.typography.subheadline,
                color = ink
            )
        }
    }
}

/**
 * The pause between send and answer. Three dots breathe inside the same
 * dark bubble OMNIX answers in (mock 2026-09-26) — it reads as "OMNIX is
 * typing", not as a separate status widget. Motion answers "what changed?"
 * (the request is working) and collapses to static dots under reduced motion
 * (§29).
 */
@Composable
private fun ThinkingRow(modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
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

    Column(
        modifier = modifier.clearAndSetSemantics { contentDescription = label }
    ) {
        Text(
            text = stringResource(R.string.omnix_chat_omnix),
            style = OmnixTheme.typography.overline.copy(letterSpacing = 0.sp),
            color = colors.textSecondary
        )
        Spacer(Modifier.height(spacing.xxs))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(OmnixTheme.radius.medium))
                .background(colors.bubbleAi)
                .padding(horizontal = spacing.md, vertical = spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(spacing.xs)
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
                        .background(colors.stateThinking.copy(alpha = pulse))
                )
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
    onKeepLocal: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
            .clip(RoundedCornerShape(OmnixTheme.radius.large))
            .background(colors.surface)
            .border(
                width = OmnixHairline,
                color = colors.border,
                shape = RoundedCornerShape(OmnixTheme.radius.large)
            )
            .padding(spacing.lg),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        Text(
            text = stringResource(R.string.omnix_chat_omnix),
            style = OmnixTheme.typography.overline,
            color = colors.textTertiary
        )
        Text(
            text = promptMessage,
            style = OmnixTheme.typography.body,
            color = colors.textPrimary
        )
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
private fun PrivacyBadge(level: PrivacyLevel, modifier: Modifier = Modifier) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val label = stringResource(
        if (level == PrivacyLevel.SENSITIVE) {
            R.string.omnix_privacy_badge_sensitive
        } else {
            R.string.omnix_privacy_badge_private
        }
    )
    val hint = stringResource(R.string.omnix_privacy_badge_hint)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(OmnixTheme.radius.pill))
            .background(colors.surfaceElevated)
            .border(
                width = OmnixHairline,
                color = colors.border,
                shape = RoundedCornerShape(OmnixTheme.radius.pill)
            )
            .padding(horizontal = spacing.sm, vertical = spacing.xxs)
            .semantics(mergeDescendants = true) { contentDescription = "$label. $hint" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs)
    ) {
        OmnixLockIcon(
            color = colors.textTertiary,
            size = 13.dp
        )
        Text(
            text = label,
            style = OmnixTheme.typography.caption,
            color = colors.textSecondary
        )
    }
}

/**
 * The composer (mock 2026-09-26): a one-line input capsule with the mic and
 * send as compact round buttons OUTSIDE it — 44 dp of touch target each
 * (§57), a 32 dp visible circle. The placeholder never wraps, so the bar
 * stays one line; the field only grows vertically while the user types. The
 * field stays editable while a request is in flight — the ViewModel guards
 * double sends.
 */
@Composable
private fun ChatComposer(
    text: String,
    isSending: Boolean,
    isDictating: Boolean,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onToggleDictation: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = OmnixTheme.colors
    val spacing = OmnixTheme.spacing
    val fieldShape = RoundedCornerShape(OmnixTheme.radius.pill)

    val dictationLabel = stringResource(
        if (isDictating) R.string.omnix_chat_dictation_stop
        else R.string.omnix_chat_dictation_start
    )
    val sendLabel = stringResource(R.string.omnix_chat_send)
    val canSend = text.isNotBlank() && !isSending

    val dictationInteraction = remember { MutableInteractionSource() }
    val sendInteraction = remember { MutableInteractionSource() }

    Column(
        modifier = modifier.fillMaxWidth()
    ) {
        // Hairline between the thread and the composer, as in the mock.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(OmnixHairline)
                .background(colors.border)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.screenHorizontal)
                .padding(top = spacing.xs, bottom = spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(fieldShape)
                    .background(colors.surfaceElevated)
                    .border(width = OmnixHairline, color = colors.border, shape = fieldShape)
                    .padding(horizontal = spacing.md, vertical = spacing.xs)
            ) {
                if (text.isEmpty()) {
                    Text(
                        text = stringResource(R.string.omnix_chat_input_hint),
                        style = OmnixTheme.typography.subheadline,
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
                    textStyle = OmnixTheme.typography.subheadline.copy(color = colors.textPrimary),
                    cursorBrush = SolidColor(colors.textPrimary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 132.dp)
                        .verticalScroll(rememberScrollState())
                )
            }

            Spacer(Modifier.width(spacing.xs))

            Box(
                modifier = Modifier
                    .size(spacing.touchTarget)
                    .omnixPressScale(dictationInteraction)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = dictationInteraction,
                        indication = null,
                        onClickLabel = dictationLabel,
                        onClick = onToggleDictation
                    ),
                contentAlignment = Alignment.Center
            ) {
                OmnixMicIcon(
                    color = if (isDictating) colors.stateListening else colors.textSecondary,
                    size = 18.dp
                )
            }

            Spacer(Modifier.width(spacing.xs))

            Box(
                modifier = Modifier
                    .size(spacing.touchTarget)
                    .omnixPressScale(sendInteraction)
                    .clickable(
                        interactionSource = sendInteraction,
                        indication = null,
                        enabled = canSend,
                        onClickLabel = sendLabel,
                        onClick = onSend
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(spacing.xxl)
                        .clip(CircleShape)
                        .background(if (canSend) colors.actionPrimary else colors.surfaceElevated)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        OmnixArrowUpIcon(
                            color = if (canSend) colors.onActionPrimary else colors.textDisabled,
                            size = 16.dp
                        )
                    }
                }
            }
        }
    }
}
