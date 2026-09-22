package com.omnix.assistant.core.crash

import android.content.Context
import android.util.Log
import com.omnix.assistant.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Хранилище краш-отчётов приложения: файлы в files/crash/.
 *
 * Чистая часть (запись/ротация/чтение) выделена в [CrashFileStore] и
 * покрывается JVM-тестами; Android-склейка (обработчик непойманных
 * исключений, сбор информации о сборке/устройстве) — в [CrashCapture].
 *
 * Зачем: приложение sideload-ится без Play Console — краш-стеков у нас
 * нет НИКАК (свой logcat приложению недоступен). Теперь Java-краши
 * пишутся в файл и попадают в OMNIX DIAGNOSTICS → EXPORT REPORT.
 * Нативные краши (SIGSEGV в C++-библиотеках) сюда НЕ попадают — их
 * ловит [com.omnix.assistant.agent.localai.mediapipe.ModelInitCrashGuard]
 * для загрузки модели.
 */
class CrashFileStore(private val dir: File) {

    companion object {
        const val FILE_PREFIX = "crash_"
        const val FILE_SUFFIX = ".txt"

        /** Храним последние N крашей — ротация по количеству. */
        const val MAX_FILES = 5
    }

    /**
     * Пишет краш-файл и возвращает его (null — только при полной
     * неработоспособности файловой системы; молча, чтобы не уронить
     * обработчик исключений).
     */
    fun write(
        deviceInfo: String,
        threadName: String,
        stackTrace: String,
        nowMs: Long = System.currentTimeMillis()
    ): File? = try {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
        val file = File(dir, FILE_PREFIX + stamp + FILE_SUFFIX)
        // В одну секунду два краша — не затираем предыдущий.
        var candidate = file
        var seq = 1
        while (candidate.exists()) {
            candidate = File(dir, FILE_PREFIX + stamp + "_" + seq++ + FILE_SUFFIX)
        }
        candidate.writeText(
            buildString {
                appendLine("time: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))}")
                appendLine(deviceInfo)
                appendLine("thread: $threadName")
                appendLine()
                appendLine(stackTrace.trim())
            }
        )
        rotate()
        candidate
    } catch (e: Exception) {
        null
    }

    /** Последние краш-файлы, новые первее. */
    fun latest(limit: Int = MAX_FILES): List<File> =
        dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) && f.name.endsWith(FILE_SUFFIX) }
            ?.sortedByDescending { it.name }
            ?.take(limit)
            .orEmpty()

    /**
     * Готовые к вставке в отчёт диагностики блоки:
     * «--- crash_<имя> ---» + содержимое (обрезано до разумного предела).
     */
    fun latestReports(limit: Int = 3, maxCharsPerFile: Int = 4000): List<String> =
        latest(limit).mapNotNull { file ->
            runCatching { file.readText() }.getOrNull()
                ?.take(maxCharsPerFile)
                ?.let { body -> "--- ${file.name} ---\n$body" }
        }

    private fun rotate() {
        dir.listFiles { f -> f.name.startsWith(FILE_PREFIX) }
            ?.sortedByDescending { it.name }
            ?.drop(MAX_FILES)
            ?.forEach { runCatching { it.delete() } }
    }
}

/**
 * Установка перехватчика непойманных исключений. Обязательно вызывается
 * ДО super.onCreate()-логики приложения, чтобы поймать и ранние краши.
 *
 * ВАЖНО: предыдущий обработчик (системный — он показывает диалог
 * «приложение закрыто») вызывается после записи файла: поведение системы
 * не меняется, мы только добавляем запись.
 */
object CrashCapture {

    private const val TAG = "CrashCapture"
    private const val DIR = "crash"

    @Volatile
    private var store: CrashFileStore? = null

    fun install(appContext: Context) {
        val fileStore = CrashFileStore(File(appContext.filesDir, DIR))
        store = fileStore
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            // Запись НИКОГДА не должна помешать системной обработке краша.
            runCatching {
                fileStore.write(
                    deviceInfo = deviceInfo(),
                    threadName = thread.name,
                    stackTrace = Log.getStackTraceString(throwable)
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** Последние краш-отчёты для OMNIX DIAGNOSTICS. */
    fun latestReports(appContext: Context, limit: Int = 3): List<String> =
        (store ?: CrashFileStore(File(appContext.filesDir, DIR))).latestReports(limit)

    private fun deviceInfo(): String =
        "app: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
            "device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, " +
            "android ${android.os.Build.VERSION.RELEASE} (sdk ${android.os.Build.VERSION.SDK_INT})"
}
