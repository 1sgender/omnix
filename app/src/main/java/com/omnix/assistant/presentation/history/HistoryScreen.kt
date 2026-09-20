package com.omnix.assistant.presentation.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.omnix.assistant.R
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.presentation.components.ConfirmationSheet
import com.omnix.assistant.presentation.components.OmnixEmptyState
import com.omnix.assistant.presentation.components.OmnixTextButton
import com.omnix.assistant.presentation.design.OmnixTheme
import com.omnix.assistant.presentation.state.ConfirmationRequest
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/**
 * History — what OMNIX has already done (§22, §39).
 *
 * The past is a quiet, scannable list, not a chat transcript: each entry is
 * what the user asked and what happened, grouped by day. There are no
 * avatars, no bubbles and no counters (§84).
 *
 * Stage 4: the title and the clear action are pinned above the list — the
 * destructive control no longer hides at the end of the scroll, and it asks
 * for confirmation through the shared sheet, exactly like the chat's clear.
 * The speaker hierarchy is now size as well as colour: what the user said
 * reads at body, what OMNIX answered settles into subheadline.
 */
@Composable
fun HistoryScreen(
    messages: List<Message>,
    modifier: Modifier = Modifier,
    onClear: () -> Unit = {}
) {
    val spacing = OmnixTheme.spacing
    val grouped = groupByDay(messages)
    var clearArmed by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = spacing.screenHorizontal)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.lg, bottom = spacing.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.omnix_history_title),
                style = OmnixTheme.typography.screenTitle,
                color = OmnixTheme.colors.textPrimary
            )
            Spacer(Modifier.weight(1f))
            if (messages.isNotEmpty()) {
                OmnixTextButton(
                    text = stringResource(R.string.omnix_history_clear),
                    onClick = { clearArmed = true }
                )
            }
        }

        if (messages.isEmpty()) {
            // An empty state states context, reason and next action — never
            // just "No data" (§19, §51). It sits in the space the list will
            // occupy, under the title, so the screen never loses its shape.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                OmnixEmptyState(
                    title = stringResource(R.string.omnix_history_empty_title),
                    description = stringResource(R.string.omnix_history_empty_body)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                grouped.forEach { (dayLabel, dayMessages) ->
                    item(key = "header_$dayLabel") {
                        Text(
                            text = dayLabel,
                            style = OmnixTheme.typography.overline,
                            color = OmnixTheme.colors.textTertiary,
                            modifier = Modifier.padding(top = spacing.md, bottom = spacing.xs)
                        )
                    }
                    items(dayMessages, key = { it.id }) { message ->
                        HistoryRow(message)
                    }
                }
                item { Spacer(Modifier.height(spacing.xl)) }
            }
        }
    }

    // History and chat share one log; the confirmation copy says exactly that
    // and asks before anything is deleted (§17, §36).
    if (clearArmed) {
        ConfirmationSheet(
            request = ConfirmationRequest(
                title = stringResource(R.string.omnix_chat_clear_confirm_title),
                detail = stringResource(R.string.omnix_chat_clear_confirm_body),
                confirmLabel = stringResource(R.string.omnix_history_clear),
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

@Composable
private fun HistoryRow(message: Message, modifier: Modifier = Modifier) {
    val spacing = OmnixTheme.spacing
    val isUser = message.role == MessageRole.USER

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = spacing.xs)
    ) {
        Text(
            text = message.text,
            style = if (isUser) {
                OmnixTheme.typography.body
            } else {
                OmnixTheme.typography.subheadline
            },
            // What the user said is primary; what OMNIX answered is secondary.
            // Size and colour carry the distinction together, never colour
            // alone (§55).
            color = if (isUser) {
                OmnixTheme.colors.textPrimary
            } else {
                OmnixTheme.colors.textSecondary
            }
        )
        Text(
            text = formatTime(message.timestamp),
            style = OmnixTheme.typography.caption,
            color = OmnixTheme.colors.textTertiary
        )
    }
}

/**
 * Groups messages into Today / Yesterday / Earlier.
 *
 * The labels are resolved by the caller's locale through the resource system,
 * so the grouping is meaningful in both languages.
 */
@Composable
private fun groupByDay(messages: List<Message>): List<Pair<String, List<Message>>> {
    val today = stringResource(R.string.omnix_history_today)
    val yesterday = stringResource(R.string.omnix_history_yesterday)
    val earlier = stringResource(R.string.omnix_history_earlier)

    val now = Calendar.getInstance()
    val startOfToday = (now.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val startOfYesterday = startOfToday - 24L * 60 * 60 * 1000

    val buckets = linkedMapOf(
        today to mutableListOf<Message>(),
        yesterday to mutableListOf(),
        earlier to mutableListOf()
    )
    messages.sortedByDescending { it.timestamp }.forEach { message ->
        val key = when {
            message.timestamp >= startOfToday -> today
            message.timestamp >= startOfYesterday -> yesterday
            else -> earlier
        }
        buckets.getValue(key).add(message)
    }
    return buckets.filterValues { it.isNotEmpty() }.map { it.key to it.value.toList() }
}

private fun formatTime(timestamp: Long): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))
