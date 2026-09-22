package com.omnix.assistant.agent.localai.mediapipe

import java.io.File

/**
 * Страж нативного краша при загрузке модели.
 *
 * Java-исключения из MediaPipe (битый zip и т.п.) ловятся в
 * [MediaPipeModelManager.initialize] честным Failed. Но C++-движок может
 * уронить ВЕСЬ ПРОЦЕСС (SIGSEGV/OOM-kill) — такой краш в Java не ловится
 * никак, а Android показывает диалог «приложение закрыто из-за ошибки»
 * и ломает инициализацию в цикл: каждое сообщение снова отдаёт модель
 * нативному create(), процесс снова умирает.
 *
 * Механика: перед нативным create() ставится маркер, после (успех ИЛИ
 * пойманное Java-исключение) снимается. Если процесс умер во время
 * create() — маркер ОСТАЁТСЯ, и следующий запуск видит:
 * «предыдущая попытка загрузки убила процесс» → create() больше не
 * вызывается для этого файла, чат уходит в облако (fallback). Одна
 * попытка на скачанный файл: сброс маркера — только когда файл модели
 * пришёл заново (новая загрузка / переустановка из пакa / удаление).
 *
 * Чистый класс без Android-зависимостей — покрывается JVM-тестами.
 */
class ModelInitCrashGuard(private val markerFile: File) {

    /** Вызывается НЕПОСРЕДСТВЕННО перед нативным create(). */
    fun attemptStarted() {
        runCatching {
            markerFile.parentFile?.mkdirs()
            markerFile.writeText(System.currentTimeMillis().toString())
        }
    }

    /**
     * Процесс выжил (create() вернулся или бросил Java-исключение) —
     * краха не было, повторные попытки разрешены.
     */
    fun attemptFinished() {
        runCatching { markerFile.delete() }
    }

    /** true — предыдущая попытка загрузки модели убила процесс. */
    fun previousAttemptCrashed(): Boolean = markerFile.exists()

    /** Новая версия файла модели — разрешаем одну свежую попытку. */
    fun reset() {
        runCatching { markerFile.delete() }
    }
}
