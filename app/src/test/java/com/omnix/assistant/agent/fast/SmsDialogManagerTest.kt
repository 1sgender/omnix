package com.omnix.assistant.agent.fast

import com.omnix.assistant.agent.fast.SmsDialogManager.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SmsDialogManagerTest {

    private val dialog = SmsDialogManager()

    @Test
    fun `full command sends immediately`() {
        assertEquals(Step.Send("Осману", "привет"), dialog.handle("отправь смс Осману привет", 0))
    }

    @Test
    fun `missing text is asked and next reply becomes the text`() {
        assertEquals(Step.Ask("Что написать Осману?"), dialog.handle("отправь смс Осману", 0))
        assertEquals(Step.Send("Осману", "буду через час"), dialog.handle("буду через час", 1_000))
        // диалог завершён: обычная реплика больше не перехватывается
        assertEquals(Step.NotHandled, dialog.handle("какая погода", 2_000))
    }

    @Test
    fun `missing recipient is asked first and then the text`() {
        assertEquals(Step.Ask("Кому отправить SMS?"), dialog.handle("отправь смс", 0))
        assertEquals(Step.Ask("Что написать Осману?"), dialog.handle("Осману", 1_000))
        assertEquals(Step.Send("Осману", "привет"), dialog.handle("привет", 2_000))
    }

    @Test
    fun `recipient answer may already contain the text`() {
        dialog.handle("отправь смс", 0)
        assertEquals(Step.Send("Осману", "привет"), dialog.handle("Осману привет", 1_000))
    }

    @Test
    fun `cancel word drops the dialog`() {
        dialog.handle("отправь смс маме", 0)
        assertTrue(dialog.handle("отмена", 1_000) is Step.Cancelled)
        assertEquals(Step.NotHandled, dialog.handle("привет", 2_000))
    }

    @Test
    fun `dialog expires`() {
        dialog.handle("отправь смс маме", 0)
        assertEquals(Step.NotHandled, dialog.handle("буду позже", SmsDialogManager.TTL_MS + 1))
    }

    @Test
    fun `another command is not taken as sms text`() {
        dialog.handle("отправь смс маме", 0)
        assertEquals(Step.NotHandled, dialog.handle("позвони Осману", 1_000, isOtherCommand = true))
        assertEquals(Step.NotHandled, dialog.handle("буду позже", 2_000))
    }

    @Test
    fun `new sms command replaces the pending one`() {
        dialog.handle("отправь смс маме", 0)
        assertEquals(Step.Ask("Что написать Осману?"), dialog.handle("отправь смс Осману", 1_000))
    }

    @Test
    fun `plain text without pending dialog is not handled`() {
        assertEquals(Step.NotHandled, dialog.handle("привет", 0))
    }
}
