package com.omnix.assistant.agent.localai.mediapipe

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import com.omnix.assistant.agent.localai.LocalModelManager
import com.omnix.assistant.agent.localai.LocalModelRuntime
import com.omnix.assistant.agent.localai.LocalModelSpec
import com.omnix.assistant.agent.localai.LocalModelState
import com.omnix.assistant.agent.localai.downloader.ModelDownloadPolicy
import com.omnix.assistant.agent.localai.downloader.ModelDownloadStatus
import com.omnix.assistant.agent.localai.downloader.ModelDownloader
import com.omnix.assistant.agent.localai.downloader.externalFallbackFile
import com.omnix.assistant.agent.localai.downloader.filesDirRelativePath
import com.omnix.assistant.agent.localai.pack.PackModelLocator
import com.omnix.assistant.agent.localai.throwableSummary
import com.omnix.assistant.core.dispatcher.CoroutineDispatchers
import com.omnix.assistant.data.preferences.SettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.coroutines.coroutineContext
import javax.inject.Singleton

/**
 * Жизненный цикл локальной модели (пункты 6 и 17 ТЗ).
 *
 * ```
 * первый Local AI запрос → загрузка (~1-3 с) → модель в памяти
 *          последующие запросы → инференс без перезагрузки
 *          idle > modelIdleUnloadMs → unload() (return idle, Battery)
 *          memory pressure     → unload()
 * ```
 *
 * Модель НЕ грузится при старте приложения: это добавило бы секунды к startup
 * и ~1 ГБ RSS пользователям, которые локальной моделью не пользуются.
 *
 * Battery: модель НЕ держится активной постоянно. После последнего
 * использования таймер ([IdleUnloadScheduler]) выгружает тяжёлую модель, и
 * система возвращается в idle; следующий запрос лениво грузит её заново.
 * Окно (5 минут) больше худшего инференса (инструментальные таймауты ≤ 4 с),
 * поэтому выгрузка не может закрыть нативный движок посреди генерации.
 *
 * Доставка файла модели (~521 МБ) — двумя путями:
 * Play-установка везёт install-time asset pack `localmodel` (в базу Play
 * файл не влезает — лимит 200 МБ), менеджер ставит его локально при старте
 * без сети и без согласия ([PackModelLocator]). Sideload-APK пака не имеет:
 * там файл качается через системный DownloadManager после одноразового
 * согласия. Отсутствие файла — штатное [LocalModelState.NotInstalled],
 * а не ошибка. Подробности — docs/LOCAL_AI.md.
 */
@Singleton
class MediaPipeModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
    private val runtimeFactory: MediaPipeRuntimeFactory,
    private val spec: LocalModelSpec,
    private val downloader: ModelDownloader,
    private val settings: SettingsDataStore,
    private val packLocator: PackModelLocator
) : LocalModelManager {

    private companion object {
        const val TAG = "LocalAI"

        /** Подкаталог во внутреннем хранилище: /data/data/<pkg>/files/llm/ */
        const val MODEL_DIR = "llm"
    }

    /** Защищает загрузку/выгрузку: параллельные запросы не грузят модель дважды. */
    private val lifecycleMutex = Mutex()
    private val lifecycleJob = SupervisorJob()
    private val lifecycleScope = CoroutineScope(lifecycleJob + dispatchers.default)

    @Volatile
    private var currentState: LocalModelState = LocalModelState.NotInitialized

    private val _stateFlow = MutableStateFlow<LocalModelState>(currentState)

    /** Единственная точка смены состояния — снимок и поток всегда синхронны. */
    private fun setState(next: LocalModelState) {
        currentState = next
        _stateFlow.value = next
    }

    @Volatile
    private var runtime: LocalModelRuntime? = null

    /** Наблюдение за активной загрузкой (опрос DownloadManager). */
    @Volatile
    private var downloadJob: Job? = null

    /**
     * CR-24: держим сильную ссылку на зарегистрированный ComponentCallbacks2,
     * чтобы GC не собрал его (аналогично PhoneStateListener до Android 12) и
     * чтобы мы могли симметрично unregister его в [close].
     */
    @Volatile
    private var trimMemoryCallback: ComponentCallbacks2? = null

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Battery: окно неактивности до выгрузки тяжёлой модели. 5 минут —
     * компромисс: диалоговый сценарий (несколько запросов подряд) не платит
     * перезагрузкой, а после паузы модель освобождает память и связанные
     * ресурсы (см. docs/BATTERY.md).
     */
    val modelIdleUnloadMs: Long = 5 * 60_000L

    /**
     * Idle-планировщик выгрузки (часы monotonic). Объявлен ПОСЛЕ lifecycleScope
     * и runtime — инициализаторы свойств исполняются по порядку объявления.
     */
    private val idleUnloadScheduler = IdleUnloadScheduler(
        clock = { android.os.SystemClock.elapsedRealtime() },
        idleMs = modelIdleUnloadMs,
        scope = lifecycleScope,
        onIdle = {
            if (runtime != null) {
                Log.i(TAG, "model idle > ${modelIdleUnloadMs}ms — выгружаю тяжёлую модель (return idle)")
                unload()
            }
        }
    )

    override val state: LocalModelState get() = currentState

    override val stateFlow: StateFlow<LocalModelState> get() = _stateFlow

    /** Путь, где ожидается файл модели. */
    val modelFile: File get() = File(File(context.filesDir, MODEL_DIR), spec.fileName)

    init {
        registerTrimMemoryCallback()
        // Загрузка принадлежит системе и переживает смерть процесса:
        // при старте переподключаемся к уже идущей очереди.
        lifecycleScope.launch { runCatching { reattachDownload() } }
    }

    /**
     * CR-24: регистрация ComponentCallbacks2 с сильной ссылкой на callback.
     * Идемпотентна — повторный вызов не регистрирует второй callback.
     */
    private fun registerTrimMemoryCallback() {
        if (trimMemoryCallback != null) return
        val cb = object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (closed.get()) return
                if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
                    Log.i(TAG, "onTrimMemory(level=$level) — выгружаю локальную модель")
                    lifecycleScope.launch { runCatching { unload() } }
                }
            }

            override fun onLowMemory() {
                if (closed.get()) return
                Log.i(TAG, "onLowMemory — выгружаю локальную модель")
                lifecycleScope.launch { runCatching { unload() } }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        try {
            context.registerComponentCallbacks(cb)
            trimMemoryCallback = cb
        } catch (t: Throwable) {
            Log.w(TAG, "registerComponentCallbacks failed", t)
        }
    }

    /**
     * Симметричная unregister-очистка. Идемпотентна. Может вызываться
     * при необходимости освободить все ресурсы (инструментарий/тесты/
     * полное выключение локального AI). Как @Singleton в обычном lifecycle
     * процесса не вызывается — процесс уходит целиком.
     */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        Log.d(TAG, "close: releasing MediaPipe resources")
        idleUnloadScheduler.cancel()
        downloadJob?.cancel()
        downloadJob = null
        // Системную загрузку НЕ отменяем: она принадлежит DownloadManager и
        // переживёт процесс; при следующем старте переподключимся по stored id.
        lifecycleJob.cancel()
        trimMemoryCallback?.let { cb ->
            runCatching { context.unregisterComponentCallbacks(cb) }
                .onFailure { Log.w(TAG, "unregisterComponentCallbacks failed", it) }
        }
        trimMemoryCallback = null
        closeRuntime()
    }

    override fun isReady(): Boolean = currentState is LocalModelState.Ready && runtime != null

    override suspend fun runtimeOrNull(): LocalModelRuntime? {
        runtime?.let {
            idleUnloadScheduler.noteUsed()
            return it
        }
        initialize()
        if (runtime != null) idleUnloadScheduler.noteUsed()
        return runtime
    }

    override suspend fun initialize(): LocalModelState = lifecycleMutex.withLock {
        // Уже готово — повторная загрузка не нужна (идемпотентность).
        runtime?.let { return@withLock currentState }

        val file = modelFile
        if (!isModelFileValid(file)) {
            if (file.exists()) {
                // Частичный или битый остаток — в рантайм такое отдавать нельзя.
                Log.w(TAG, "model file size mismatch | got=${file.length()} | want=${spec.expectedSizeBytes}")
                runCatching { file.delete() }
            }
            // Если согласие уже дано (например, после перезапуска) — загрузка
            // стартует сама; без согласия честно возвращаем NotInstalled.
            val afterEnsure = ensureModelLocked()
            if (afterEnsure is LocalModelState.Downloading) return@withLock afterEnsure
            setState(LocalModelState.NotInstalled(file.absolutePath))
            Log.i(TAG, "model not installed | expected=${file.absolutePath}")
            return@withLock currentState
        }

        if (!hasEnoughMemory()) {
            setState(LocalModelState.InsufficientMemory(spec.minRuntimeMemoryMb))
            Log.w(TAG, "model load skipped: недостаточно памяти (нужно ~${spec.minRuntimeMemoryMb} МБ)")
            return@withLock currentState
        }

        setState(LocalModelState.Loading)
        Log.i(TAG, "model loading | id=${spec.modelId} | sizeMb=${spec.approxSizeMb}")

        var createdDuringAttempt: LocalModelRuntime? = null
        return@withLock try {
            val startedAt = System.currentTimeMillis()
            val created = withContext(dispatchers.default) {
                val candidate = runtimeFactory.create(modelPath = file.absolutePath, spec = spec)
                createdDuringAttempt = candidate
                try {
                    // Native creation itself is blocking, but cancellation while
                    // it runs must close the freshly created engine immediately
                    // rather than publishing/leaking it after the caller left.
                    coroutineContext.ensureActive()
                    candidate
                } catch (cancelled: CancellationException) {
                    (candidate as? AutoCloseable)?.close()
                    createdDuringAttempt = null
                    throw cancelled
                }
            }
            // Also cover cancellation after the worker block completes but before
            // withContext dispatches its value back to this coroutine.
            coroutineContext.ensureActive()
            val loadTimeMs = System.currentTimeMillis() - startedAt

            runtime = created
            createdDuringAttempt = null // ownership transferred to the manager
            setState(LocalModelState.Ready(modelId = spec.modelId, loadTimeMs = loadTimeMs))
            idleUnloadScheduler.noteUsed()
            Log.i(
                TAG,
                "model = ${spec.modelId} | runtime = ${created.runtimeId} | loaded = true | " +
                    "loadTimeMs = $loadTimeMs"
            )
            currentState
        } catch (e: CancellationException) {
            (createdDuringAttempt as? AutoCloseable)?.close()
            createdDuringAttempt = null
            runtime = null
            setState(LocalModelState.NotInitialized)
            throw e
        } catch (e: Throwable) {
            // Ловим Throwable: нативная библиотека может кинуть UnsatisfiedLinkError
            // или OutOfMemoryError, и это не должно ронять приложение.
            runtime = null
            // Класс + первая строка сообщения: голый simpleName («RuntimeException»)
            // не диагностируется без logcat, которого у пользователя нет.
            val reason = throwableSummary(e)
            setState(LocalModelState.Failed(reason))
            Log.e(TAG, "model load failed | id=${spec.modelId}", e)
            currentState
        }
    }

    override suspend fun unload() = lifecycleMutex.withLock {
        closeRuntime()
    }

    // ------------------------------------------------------ model download

    override suspend fun ensureModel(): LocalModelState = lifecycleMutex.withLock {
        runtime?.let { return@withLock currentState }
        ensureModelLocked()
    }

    /**
     * Тело [ensureModel] без мьютекса — вызывается либо из [ensureModel],
     * либо из [initialize], который мьютекс уже держит.
     */
    private suspend fun ensureModelLocked(): LocalModelState {
        if (currentState is LocalModelState.Downloading) return currentState

        val file = modelFile
        if (isModelFileValid(file)) {
            // Файл докачался раньше (например, пережив перезапуск) — грузиться
            // будет лениво при первом запросе, память заранее не трогаем.
            if (currentState !is LocalModelState.Ready &&
                currentState !is LocalModelState.Loading
            ) {
                setState(LocalModelState.NotInitialized)
            }
            return currentState
        }

        // Play-установка: пак ставится локально вместо сетевой загрузки
        // (перестраховка на случай, если стартовый reattach не сработал).
        if (packLocator.installFromPackIfPresent(file, spec.expectedSizeBytes)) {
            if (currentState !is LocalModelState.Ready &&
                currentState !is LocalModelState.Loading
            ) {
                setState(LocalModelState.NotInitialized)
            }
            return currentState
        }

        val consent = settings.localModelConsentFlow.first()
        if (!ModelDownloadPolicy.mayDownload(consent)) return currentState

        // Переподключение к системной очереди (смерть процесса/перезагрузка).
        val storedId = settings.localModelDownloadIdFlow.first()
        if (storedId != ModelDownloadPolicy.NO_DOWNLOAD_ID) {
            val obs = runCatching { downloader.observe(storedId) }.getOrNull()
            if (obs != null &&
                (obs.status == ModelDownloadStatus.RUNNING || obs.status == ModelDownloadStatus.PAUSED)
            ) {
                startObserving(storedId)
                return currentState
            }
            if (obs != null && obs.status == ModelDownloadStatus.SUCCESS &&
                isModelFileValid(file)
            ) {
                settings.setLocalModelDownloadId(ModelDownloadPolicy.NO_DOWNLOAD_ID)
                setState(LocalModelState.NotInitialized)
                return currentState
            }
            // Протухший id — ниже встанем в очередь заново.
        }

        if (file.exists()) runCatching { file.delete() }
        val id = try {
            downloader.enqueue(
                url = spec.downloadUrl,
                destFile = file,
                title = "OMNIX · локальная модель",
                allowedOverMetered = ModelDownloadPolicy.allowedOverMetered(consent)
            )
        } catch (e: Exception) {
            Log.e(TAG, "model download enqueue failed", e)
            // Причину — в UI: без этого диагностика «не удалось начать загрузку»
            // требует logcat, которого у пользователя нет.
            setState(
                LocalModelState.DownloadFailed(
                    "Не удалось начать загрузку: ${e.message ?: e.javaClass.simpleName}"
                )
            )
            return currentState
        }
        settings.setLocalModelDownloadId(id)
        Log.i(TAG, "model download started | id=$id | meteredOk=${ModelDownloadPolicy.allowedOverMetered(consent)}")
        startObserving(id)
        return currentState
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        lifecycleScope.launch {
            val id = settings.localModelDownloadIdFlow.first()
            if (id != ModelDownloadPolicy.NO_DOWNLOAD_ID) {
                runCatching { downloader.cancel(id) }
                settings.setLocalModelDownloadId(ModelDownloadPolicy.NO_DOWNLOAD_ID)
            }
            if (currentState is LocalModelState.Downloading ||
                currentState is LocalModelState.DownloadFailed
            ) {
                setState(LocalModelState.NotInstalled(modelFile.absolutePath))
            }
        }
    }

    /**
     * Полное удаление файла модели: внутренний путь + внешнее зеркало
     * fallback (иначе зеркало воскресит тот же битый файл при следующей
     * проверке валидности). Состояние — честный [LocalModelState.NotInstalled]:
     * дальнейшее — обычный путь [ensureModel] с прежним сетевым согласием.
     */
    override suspend fun deleteModel(): LocalModelState = lifecycleMutex.withLock {
        downloadJob?.cancel()
        downloadJob = null
        val storedId = settings.localModelDownloadIdFlow.first()
        if (storedId != ModelDownloadPolicy.NO_DOWNLOAD_ID) {
            runCatching { downloader.cancel(storedId) }
            settings.setLocalModelDownloadId(ModelDownloadPolicy.NO_DOWNLOAD_ID)
        }
        closeRuntime()
        runCatching { modelFile.delete() }
        filesDirRelativePath(modelFile, context.filesDir)?.let { relPath ->
            externalFallbackFile(context, relPath)?.let { mirror ->
                runCatching { mirror.delete() }
            }
        }
        Log.i(TAG, "model file deleted | ${modelFile.absolutePath}")
        setState(LocalModelState.NotInstalled(modelFile.absolutePath))
        currentState
    }

    /** Переподключение к системной загрузке при старте процесса. */
    private suspend fun reattachDownload() {
        if (currentState is LocalModelState.Downloading) return
        val id = settings.localModelDownloadIdFlow.first()
        if (id == ModelDownloadPolicy.NO_DOWNLOAD_ID) {
            // Play-установка: модель лежит в install-time паке — ставим её
            // локально без сети и без согласия (трафика нет, capacity ноль
            // действий пользователя). Sideload-APK: метод вернёт false.
            if (packLocator.installFromPackIfPresent(modelFile, spec.expectedSizeBytes)) {
                if (currentState is LocalModelState.NotInstalled ||
                    currentState is LocalModelState.NotInitialized
                ) {
                    setState(LocalModelState.NotInitialized)
                }
                return
            }
            // Честное стартовое состояние: NotInitialized означает «файл есть,
            // грузиться будет лениво», а не «не знаем, что происходит».
            if (currentState is LocalModelState.NotInitialized && !isModelFileValid(modelFile)) {
                setState(LocalModelState.NotInstalled(modelFile.absolutePath))
            }
            return
        }
        val obs = runCatching { downloader.observe(id) }.getOrNull()
        when {
            obs == null -> settings.setLocalModelDownloadId(ModelDownloadPolicy.NO_DOWNLOAD_ID)
            obs.status == ModelDownloadStatus.SUCCESS -> onDownloadSucceeded()
            obs.status == ModelDownloadStatus.FAILED ->
                onDownloadFailed(id, ModelDownloadPolicy.reasonText(obs.reason))

            else -> startObserving(id)
        }
    }

    private fun startObserving(downloadId: Long) {
        downloadJob?.cancel()
        setState(
            LocalModelState.Downloading(
                progressPercent = 0,
                downloadedBytes = 0L,
                totalBytes = spec.expectedSizeBytes
            )
        )
        downloadJob = lifecycleScope.launch {
            while (true) {
                delay(ModelDownloadPolicy.POLL_INTERVAL_MS)
                val obs = runCatching { downloader.observe(downloadId) }.getOrNull()
                if (obs == null) {
                    // Система забыла загрузку — не выдумываем успех.
                    onDownloadFailed(downloadId, "Загрузка потеряна системой")
                    return@launch
                }
                when (obs.status) {
                    ModelDownloadStatus.RUNNING, ModelDownloadStatus.PAUSED -> {
                        val total = if (obs.totalBytes > 0) obs.totalBytes else spec.expectedSizeBytes
                        setState(
                            LocalModelState.Downloading(
                                progressPercent = ModelDownloadPolicy.progressPercent(
                                    obs.downloadedBytes,
                                    total
                                ),
                                downloadedBytes = obs.downloadedBytes,
                                totalBytes = total
                            )
                        )
                    }

                    ModelDownloadStatus.SUCCESS -> {
                        onDownloadSucceeded()
                        return@launch
                    }

                    ModelDownloadStatus.FAILED -> {
                        onDownloadFailed(downloadId, ModelDownloadPolicy.reasonText(obs.reason))
                        return@launch
                    }
                }
            }
        }
    }

    private suspend fun onDownloadSucceeded() {
        settings.setLocalModelDownloadId(ModelDownloadPolicy.NO_DOWNLOAD_ID)
        val file = modelFile
        if (isModelFileValid(file)) {
            Log.i(TAG, "model downloaded | bytes=${file.length()}")
            // Ленивая загрузка при первом запросе: 1.3 ГБ RSS без нужды не занимаем.
            setState(LocalModelState.NotInitialized)
        } else {
            Log.w(
                TAG,
                "downloaded size mismatch | got=${if (file.exists()) file.length() else "missing"}" +
                    " | want=${spec.expectedSizeBytes}"
            )
            if (file.exists()) runCatching { file.delete() }
            setState(
                LocalModelState.DownloadFailed("Скачанный файл повреждён (несовпадение размера)")
            )
        }
    }

    private suspend fun onDownloadFailed(downloadId: Long, reason: String) {
        runCatching { downloader.cancel(downloadId) }
        settings.setLocalModelDownloadId(ModelDownloadPolicy.NO_DOWNLOAD_ID)
        Log.w(TAG, "model download failed | $reason")
        setState(LocalModelState.DownloadFailed(reason))
    }

    /**
     * Файл валиден, только если размер совпал с ожидаемым байт в байт.
     *
     * Перед проверкой переносит файл из внешнего зеркала, если загрузка
     * ушла в fallback (file:// во внутреннее хранилище отвергнут прошивкой —
     * [com.omnix.assistant.agent.localai.downloader.externalFallbackFile]).
     */
    private fun isModelFileValid(file: File): Boolean {
        moveFromExternalFallbackIfNeeded(file)
        return file.exists() && file.length() == spec.expectedSizeBytes
    }

    /**
     * Fallback кладёт файл во внешний app-каталог. Переносим на ожидаемый
     * внутренний путь: сначала rename (дёшево), при неудаче — copy+delete
     * (каталоги на разных томах/мэппингах). Тишина при отсутствии зеркала —
     * это норма для 99% загрузок, шедших основным путём.
     */
    private fun moveFromExternalFallbackIfNeeded(file: File) {
        if (file.exists()) return
        val relPath = filesDirRelativePath(file, context.filesDir) ?: return
        val ext = externalFallbackFile(context, relPath) ?: return
        if (!ext.exists()) return
        file.parentFile?.mkdirs()
        try {
            if (ext.renameTo(file)) return
            ext.copyTo(file, overwrite = true)
            ext.delete()
        } catch (e: Exception) {
            Log.e(TAG, "external fallback move failed: ${ext.path} -> ${file.path}", e)
        }
    }

    private fun closeRuntime() {
        val current = runtime ?: return
        runtime = null
        setState(LocalModelState.NotInitialized)
        try {
            (current as? AutoCloseable)?.close()
            Log.i(TAG, "model unloaded")
        } catch (e: Exception) {
            Log.w(TAG, "model unload: сбой освобождения нативных ресурсов", e)
        }
    }

    /**
     * Грубая проверка доступной памяти. Цель — не «точно предсказать», а не
     * пытаться грузить ~1 ГБ на устройстве, которое уже в lowMemory.
     */
    private fun hasEnoughMemory(): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return true // не смогли проверить — не блокируем

        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)

        if (info.lowMemory) return false

        val availableMb = info.availMem / (1024 * 1024)
        val enough = availableMb >= spec.minRuntimeMemoryMb
        if (!enough) {
            Log.w(TAG, "available RAM = ${availableMb}MB < required ${spec.minRuntimeMemoryMb}MB")
        }
        return enough
    }
}
