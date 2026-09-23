package com.omnix.assistant.agent.localai.mediapipe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM-тесты стража нативного краша при генерации.
 *
 * Инвариант: маркер со счётчиком стоит ТОЛЬКО пока идёт нативная генерация.
 * Каждая смерть процесса в генерации оставляет счётчик на диске; любая
 * завершённая (процесс выжил) генерация обнуляет цепочку. Порог запрета —
 * две последовательные смерти, потому что одну может устроить система
 * (force-stop, LMK) без вины модели.
 */
class InferenceCrashGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Маркер в общем каталоге — как в проде (рядом с файлом модели). */
    private fun markerPath(): File = File(tmp.newFolder(), "model.inference_crash")

    private fun guard(marker: File = markerPath()) = InferenceCrashGuard(marker)

    @Test
    fun `no marker means not banned`() {
        assertFalse(guard().isBanned())
        assertEquals(0, guard().leftoverStrikes())
    }

    @Test
    fun `one unfinished generation does not ban`() {
        val marker = markerPath()
        guard(marker).generationStarted()
        // Процесс умер внутри генерации: generationFinished() НЕ вызван.
        // «Новый запуск» — новый объект, тот же маркер на диске.
        val afterRestart = guard(marker)

        assertEquals(1, afterRestart.leftoverStrikes())
        assertFalse(afterRestart.isBanned())
    }

    @Test
    fun `two consecutive unfinished generations ban the model`() {
        val marker = markerPath()
        guard(marker).generationStarted() // смерть №1, маркер = 1
        guard(marker).generationStarted() // смерть №2, маркер = 2
        val afterRestart = guard(marker)

        assertEquals(2, afterRestart.leftoverStrikes())
        assertTrue(afterRestart.isBanned())
    }

    @Test
    fun `survived generation clears the chain`() {
        val marker = markerPath()
        // Один force-stop системой — не приговор модели.
        guard(marker).generationStarted()
        // Следующая генерация прошла успешно — процесс выжил.
        guard(marker).generationStarted()
        guard(marker).generationFinished()
        val afterRestart = guard(marker)

        assertEquals(0, afterRestart.leftoverStrikes())
        assertFalse(afterRestart.isBanned())
    }

    @Test
    fun `completed generation resets strikes even after a death`() {
        val marker = markerPath()
        guard(marker).generationStarted() // смерть, маркер = 1
        guard(marker).generationStarted() // вторая попытка...
        guard(marker).generationFinished() // ...выжила: цепочка разорвана
        guard(marker).generationStarted() // новая смерть снова с нуля
        val afterRestart = guard(marker)

        assertEquals(1, afterRestart.leftoverStrikes())
        assertFalse(afterRestart.isBanned())
    }

    @Test
    fun `reset allows fresh attempts`() {
        val marker = markerPath()
        guard(marker).generationStarted()
        guard(marker).generationStarted()
        assertTrue(guard(marker).isBanned())

        // Файл модели пришёл заново (загрузка/переустановка) — strikes обнулены.
        guard(marker).reset()

        assertFalse(guard(marker).isBanned())
        assertEquals(0, guard(marker).leftoverStrikes())
    }

    @Test
    fun `corrupted marker counts conservatively as one strike`() {
        val marker = markerPath()
        marker.parentFile?.mkdirs()
        marker.writeText("не число")

        assertEquals(1, guard(marker).leftoverStrikes())
        assertFalse(guard(marker).isBanned())
    }
}
