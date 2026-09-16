package com.omnix.assistant.agent.localai.pack

import android.content.Context
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Установка модели из install-time asset pack (Play Asset Delivery).
 *
 * Пак `localmodel` (см. assetpacks/localmodel) едет вместе с установкой из
 * Play и виден через обычный AssetManager — отдельная Play-библиотека для
 * install-time не нужна. В sideload-APK (GitHub-сборки) пака нет: методы
 * честно возвращают false, и менеджер идёт обычным путём сетевой докачки.
 *
 * Установка — локальное копирование без сети, поэтому согласие на загрузку
 * (оно про платный трафик) не требуется.
 */
interface PackModelLocator {
    /**
     * Копирует модель из пака в [dest], если пак есть в установке.
     *
     * Идемпотентна: готовый файл правильного размера не трогает.
     * Тяжёлая часть выполняется на IO-диспетчере — безопасно звать с Main.
     *
     * @return true, если файл теперь на месте и размер совпал с [expectedBytes].
     */
    suspend fun installFromPackIfPresent(dest: File, expectedBytes: Long): Boolean
}

@Singleton
class AssetManagerPackModelLocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers
) : PackModelLocator {

    override suspend fun installFromPackIfPresent(dest: File, expectedBytes: Long): Boolean {
        // Быстрый путь: файл уже на месте (прошлая установка или докачка).
        if (dest.isFile && dest.length() == expectedBytes) return true
        // Проба пака: в sideload-APK open() бросает IOException — это норма.
        try {
            context.assets.open(PACK_MODEL_PATH).use { /* probe only */ }
        } catch (e: IOException) {
            return false
        }
        return withContext(dispatchers.io) {
            // Повторная проверка: пока переключали диспетчер, системная
            // загрузка могла довезти файл сама.
            if (dest.isFile && dest.length() == expectedBytes) return@withContext true
            try {
                context.assets.open(PACK_MODEL_PATH).use { input ->
                    PackModelFiles.copyAndVerify(input, dest, expectedBytes)
                }
            } catch (e: IOException) {
                false
            }
        }
    }

    companion object {
        /**
         * Путь внутри AssetManager: install-time пак сливается в общие assets.
         * Имя файла повторяет LocalModelSpec.fileName; дублируется строкой,
         * т.к. пак собирается Gradle, а не кодом (см. assetpacks/localmodel).
         */
        const val PACK_MODEL_PATH = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task"
    }
}

/** Чистые файловые операции установки — без Android, покрыты JVM-тестом. */
object PackModelFiles {

    /**
     * Копирует [input] в [dest] через временный файл с проверкой размера.
     * Не бросает исключений: при любой проблеме (место, обрыв) возвращает
     * false, недописанный хвост удаляет.
     */
    fun copyAndVerify(input: InputStream, dest: File, expectedBytes: Long): Boolean {
        return try {
            dest.parentFile?.mkdirs()
            val tmp = File(dest.parentFile, dest.name + ".part")
            input.use { src ->
                tmp.outputStream().use { out -> src.copyTo(out) }
            }
            if (tmp.length() != expectedBytes) {
                tmp.delete()
                return false
            }
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return false
            }
            true
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }
}
