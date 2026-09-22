package com.omnix.assistant.agent.localai.mediapipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * JVM-тесты стража нативного краша при загрузке модели.
 *
 * Инвариант: маркер стоит ТОЛЬКО пока идёт нативный create(). Если процесс
 * умер внутри create() — маркер остаётся, и следующий запуск запрещает
 * повтор. Любой возврат в Java (успех/исключение/отмена) — маркер снят.
 */
class ModelInitCrashGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Маркер в общем каталоге — как в проде (рядом с файлом модели). */
    private fun markerPath() = java.io.File(tmp.newFolder(), "model.init_crash")

    private fun guard() = ModelInitCrashGuard(markerPath())

    @Test
    fun `no marker means no crash detected`() {
        val g = guard()

        assertFalse(g.previousAttemptCrashed())
    }

    @Test
    fun `marker survives process death during create`() {
        val marker = markerPath()
        ModelInitCrashGuard(marker).attemptStarted()
        // Процесс умер внутри create(): attemptFinished() НЕ вызван.
        // «Новый запуск» — новый объект, тот же маркер на диске.
        val gAfterRestart = ModelInitCrashGuard(marker)

        assertTrue(
            "Смерть процесса внутри create() обязана детектироваться",
            gAfterRestart.previousAttemptCrashed()
        )
    }

    @Test
    fun `finished attempt clears marker`() {
        val g = guard()

        g.attemptStarted()
        g.attemptFinished()

        assertFalse(g.previousAttemptCrashed())
    }

    @Test
    fun `caught java exception clears marker and allows retry`() {
        val g = guard()

        g.attemptStarted()
        // create() бросил Java-исключение — поймали, процесс жив.
        g.attemptFinished()
        assertFalse(g.previousAttemptCrashed())

        // Повторная попытка разрешена.
        g.attemptStarted()
        g.attemptFinished()
        assertFalse(g.previousAttemptCrashed())
    }

    @Test
    fun `reset allows one fresh attempt after new model file`() {
        val g = guard()
        g.attemptStarted() // прошлый файл убил процесс

        g.reset() // файл модели пришёл заново
        assertFalse(g.previousAttemptCrashed())
    }
}
