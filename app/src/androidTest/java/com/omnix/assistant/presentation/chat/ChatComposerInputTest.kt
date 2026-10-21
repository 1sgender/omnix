package com.omnix.assistant.presentation.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.omnix.assistant.agent.decision.PrivacyClassification
import com.omnix.assistant.agent.decision.PrivacyLevel
import com.omnix.assistant.agent.decision.PrivacyReason
import com.omnix.assistant.domain.models.Message
import com.omnix.assistant.domain.models.MessageRole
import com.omnix.assistant.presentation.design.OmnixTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing into the chat composer must keep the screen alive (field report
 * 2026-10-20: the app closed as soon as the user started to write in Chat).
 * The screen is driven the way the ViewModel drives it: every keystroke
 * comes back as a new inputText.
 */
@RunWith(AndroidJUnit4::class)
class ChatComposerInputTest {

    @get:Rule
    val compose = createComposeRule()

    private val now = System.currentTimeMillis()
    private val dayMs = 24L * 60 * 60 * 1000

    private fun history() = listOf(
        Message(id = 1, role = MessageRole.USER, text = "Разбуди меня завтра в семь", timestamp = now - dayMs),
        Message(id = 2, role = MessageRole.ASSISTANT, text = "Готово, будильник на 07:00.", timestamp = now - dayMs + 1_000),
        Message(id = 3, role = MessageRole.USER, text = "Какая погода утром?", timestamp = now - 2_000),
        Message(id = 4, role = MessageRole.ERROR, text = "Ошибка сети.", timestamp = now - 1_000)
    )

    private fun typeInto(initial: ChatUiState, classify: (String) -> PrivacyClassification? = { null }) {
        var state by mutableStateOf(initial)
        compose.setContent {
            OmnixTheme {
                OmnixChatScreen(
                    state = state,
                    isOnline = true,
                    onInputChange = { text ->
                        state = state.copy(
                            inputText = text,
                            privacyClassification = classify(text) ?: state.privacyClassification
                        )
                    },
                    onSend = {},
                    onConfirm = {},
                    onCancel = {},
                    onNewChat = {},
                    onOpenHistory = {},
                    onCloseHistory = {},
                    onOpenConversation = {},
                    onDeleteConversation = {},
                    onAllowCloud = {},
                    onKeepLocal = {},
                    onToggleDictation = {}
                )
            }
        }
        val field = compose.onNode(hasSetTextAction())
        field.performClick()
        field.performTextInput("Привет")
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assert(hasText("Привет"))
    }

    @Test
    fun typingIntoAnEmptyChatKeepsTheScreenAlive() {
        typeInto(ChatUiState())
    }

    @Test
    fun typingUnderAThreadOfSeveralDaysKeepsTheScreenAlive() {
        typeInto(ChatUiState(messages = history(), cloudMessageIds = setOf(2L)))
    }

    @Test
    fun typingWhileThePrivacyBadgeAppearsKeepsTheScreenAlive() {
        typeInto(ChatUiState(messages = history())) {
            PrivacyClassification(PrivacyLevel.PRIVATE, setOf(PrivacyReason.NONE), complete = true)
        }
    }
}
