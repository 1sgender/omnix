package com.omnix.assistant.voice.offline

import android.content.Context
import android.util.Log
import com.omnix.assistant.agent.localai.downloader.ModelDownloadPolicy
import com.omnix.assistant.agent.localai.downloader.ModelDownloadStatus
import com.omnix.assistant.agent.localai.downloader.ModelDownloader
import com.omnix.assistant.agent.localai.downloader.ModelFileIntegrity
import com.omnix.assistant.agent.localai.downloader.ModelIntegrity
import com.omnix.assistant.agent.localai.downloader.externalFallbackFile
import com.omnix.assistant.agent.localai.downloader.filesDirRelativePath
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** Состояние одной офлайн-модели речи. */
sealed interface OfflineModelState {
    /** Пакет ещё не опубликован — скачивать нечего. */
    data object ComingSoon : OfflineModelState

    data object NotInstalled : OfflineModelState

    data class Downloading(val progressPercent: Int) : OfflineModelState

    /** Скачано, идёт проверка хеша и распаковка. */
    data object Installing : OfflineModelState

    data object Installed : OfflineModelState

    data class Failed(val reason: String) : OfflineModelState
}

/**
 * Скачивание, проверка и хранение офлайн-моделей речи. Файл качает системный
 * DownloadManager (переживает смерть процесса), целостность проверяется размером
 * и SHA-256, затем zip распаковывается в приватную папку приложения.
 */
@Singleton
class OfflineSpeechModelStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: ModelDownloader,
    private val dispatchers: CoroutineDispatchers
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow(initialStates())
    val states: StateFlow<Map<String, OfflineModelState>> = _states.asStateFlow()

    private val root: File get() = File(context.filesDir, ROOT_DIR)

    /** Папка с распакованной моделью. */
    fun dirOf(model: OfflineSpeechModel): File = File(root, model.dirName)

    /** Модель установлена: маркер на месте и все нужные файлы существуют. */
    fun isInstalled(model: OfflineSpeechModel): Boolean {
        val dir = dirOf(model)
        return File(dir, INSTALLED_MARKER).isFile && model.requiredFiles.all { File(dir, it).isFile }
    }

    private fun initialStates(): Map<String, OfflineModelState> =
        OfflineSpeechModels.ALL.associate { it.id to stateOnDisk(it) }

    private fun stateOnDisk(model: OfflineSpeechModel): OfflineModelState = when {
        isInstalled(model) -> OfflineModelState.Installed
        !model.isPublished -> OfflineModelState.ComingSoon
        else -> OfflineModelState.NotInstalled
    }

    private fun setState(model: OfflineSpeechModel, state: OfflineModelState) {
        _states.update { it + (model.id to state) }
    }

    /** Пересчитать состояния с диска (при открытии экрана). */
    fun refresh() {
        OfflineSpeechModels.ALL.forEach { model ->
            val current = _states.value[model.id]
            if (current is OfflineModelState.Downloading || current is OfflineModelState.Installing) return@forEach
            setState(model, stateOnDisk(model))
        }
    }

    /**
     * Запускает скачивание; повторный вызов во время загрузки ничего не делает.
     * [overMetered] = false — только по Wi-Fi (мобильная сеть не тратится).
     */
    fun download(model: OfflineSpeechModel, overMetered: Boolean = true) {
        if (!model.isPublished) return
        synchronized(jobs) {
            if (jobs[model.id]?.isActive == true) return
            jobs[model.id] = scope.launch { runDownload(model, overMetered) }
        }
    }

    /** Модель скачивается или распаковывается прямо сейчас. */
    fun isBusy(model: OfflineSpeechModel): Boolean =
        _states.value[model.id].let { it is OfflineModelState.Downloading || it is OfflineModelState.Installing }

    /** Удаляет модель с телефона. */
    fun remove(model: OfflineSpeechModel) {
        synchronized(jobs) {
            jobs.remove(model.id)?.cancel()
        }
        scope.launch {
            dirOf(model).deleteRecursively()
            zipOf(model).let { ModelFileIntegrity.deleteWithMarker(it) }
            setState(model, stateOnDisk(model))
        }
    }

    private fun zipOf(model: OfflineSpeechModel): File = File(root, "${model.dirName}.zip")

    private suspend fun runDownload(model: OfflineSpeechModel, overMetered: Boolean) {
        val zip = zipOf(model)
        try {
            setState(model, OfflineModelState.Downloading(0))
            val id = downloader.enqueue(
                url = model.url,
                destFile = zip,
                title = "OMNIX · ${model.id}",
                allowedOverMetered = overMetered
            )
            while (true) {
                delay(ModelDownloadPolicy.POLL_INTERVAL_MS)
                val observed = downloader.observe(id)
                    ?: return fail(model, "Загрузка потеряна системой")
                when (observed.status) {
                    ModelDownloadStatus.SUCCESS -> break
                    ModelDownloadStatus.FAILED -> return fail(model, ModelDownloadPolicy.reasonText(observed.reason))
                    else -> setState(
                        model,
                        OfflineModelState.Downloading(
                            ModelDownloadPolicy.progressPercent(observed.downloadedBytes, observed.totalBytes)
                        )
                    )
                }
            }
            setState(model, OfflineModelState.Installing)
            moveFromExternalFallbackIfNeeded(zip)
            val verdict = ModelFileIntegrity.verify(zip, model.sizeBytes, model.sha256, dispatchers.io)
            if (verdict != ModelIntegrity.VALID) {
                ModelFileIntegrity.deleteWithMarker(zip)
                return fail(model, "Файл повреждён при загрузке ($verdict)")
            }
            val ok = withContext(dispatchers.io) { unpack(model, zip) }
            ModelFileIntegrity.deleteWithMarker(zip)
            if (ok) setState(model, OfflineModelState.Installed) else fail(model, "Не удалось распаковать модель")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "download ${model.id} failed", e)
            fail(model, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun fail(model: OfflineSpeechModel, reason: String) {
        setState(model, OfflineModelState.Failed(reason))
    }

    /** Распаковка во временную папку, затем подмена: недокачанная модель не считается установленной. */
    private fun unpack(model: OfflineSpeechModel, zip: File): Boolean {
        val target = dirOf(model)
        val staging = File(root, "${model.dirName}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()
        val stagingPath = staging.canonicalPath + File.separator
        try {
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val out = File(staging, entry.name)
                    // Защита от zip-slip: распаковываем только внутрь staging.
                    if (!(out.canonicalPath + if (entry.isDirectory) File.separator else "").startsWith(stagingPath)) {
                        throw SecurityException("bad zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zin.copyTo(it) }
                    }
                }
            }
            if (!model.requiredFiles.all { File(staging, it).isFile }) return false
            File(staging, INSTALLED_MARKER).writeText(model.sha256)
            target.deleteRecursively()
            return staging.renameTo(target)
        } catch (e: Exception) {
            Log.e(TAG, "unpack ${model.id} failed", e)
            return false
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun moveFromExternalFallbackIfNeeded(file: File) {
        if (file.exists()) return
        val relPath = filesDirRelativePath(file, context.filesDir) ?: return
        val ext = externalFallbackFile(context, relPath) ?: return
        if (!ext.exists()) return
        file.parentFile?.mkdirs()
        runCatching {
            if (!ext.renameTo(file)) {
                ext.copyTo(file, overwrite = true)
                ext.delete()
            }
        }.onFailure { Log.e(TAG, "external fallback move failed", it) }
    }

    private companion object {
        const val TAG = "OfflineSpeechStore"
        const val ROOT_DIR = "offline-speech"
        const val INSTALLED_MARKER = "installed.ok"
    }
}
