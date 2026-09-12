package com.jarvis.assistant.presentation.diagnostics

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.assistant.agent.localai.LocalModelManager
import com.jarvis.assistant.agent.localai.LocalModelState
import com.jarvis.assistant.agent.tools.accessibility.JarvisAccessibilityService
import com.jarvis.assistant.core.dispatcher.CoroutineDispatchers
import com.jarvis.assistant.core.license.LicenseManager
import com.jarvis.assistant.core.license.LicenseServerValidator
import com.jarvis.assistant.core.license.ServerLicenseValidationResult
import com.jarvis.assistant.core.network.NetworkMonitor
import com.jarvis.assistant.presentation.state.ClipRepository
import com.jarvis.assistant.voice.audio.BluetoothAudioRouter
import com.jarvis.assistant.voice.audio.BluetoothAudioState
import com.jarvis.assistant.voice.tts.TextToSpeechManager
import com.jarvis.assistant.voice.tts.TtsState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OMNIX DIAGNOSTICS (§21 ТЗ): исполняемые проверки подсистем.
 *
 * Каждая проверка — реальное измерение через менеджер, который владеет
 * подсистемой в проде, а не пересказ настроек:
 *
 * - Mic — пробный захват через AudioRecord (16 кГц, VOICE_RECOGNITION);
 * - STT — SpeechRecognizer.isRecognitionAvailable;
 * - Local AI — состояние [LocalModelManager];
 * - Cloud AI — бесплатный validate() лицензии (сервер достижим, AI-токены
 *   НЕ тратятся; прямого бесплатного health-check у AI-пути нет — это
 *   честно указано в детали);
 * - Bluetooth / Clip — [ClipRepository] + [BluetoothAudioRouter];
 * - Accessibility — служба реально включена в системе;
 * - Permissions — runtime-разрешения через PackageManager;
 * - TTS — состояние [TextToSpeechManager];
 * - License — локальная запись [LicenseManager];
 * - Network — [NetworkMonitor];
 * - Battery — липкий интент BatteryManager.
 *
 * Непредвиденное исключение пробы = FAIL с типом исключения, а не падение
 * экрана. Детали — короткие технические строки на английском: экран
 * инженерный, отчёт уходит в поддержку.
 */
@Singleton
class DiagnosticsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dispatchers: CoroutineDispatchers,
    private val localModelManager: LocalModelManager,
    private val ttsManager: TextToSpeechManager,
    private val bluetoothAudioRouter: BluetoothAudioRouter,
    private val clipRepository: ClipRepository,
    private val licenseManager: LicenseManager,
    private val serverValidator: LicenseServerValidator,
    private val networkMonitor: NetworkMonitor
) {
    /** Запускает одну проверку, замеряя длительность. Не бросает исключений. */
    suspend fun runCheck(id: DiagnosticCheckId): DiagnosticResult =
        withContext(dispatchers.io) {
            val started = SystemClock.elapsedRealtime()
            val (status, detail) = try {
                when (id) {
                    DiagnosticCheckId.MIC -> checkMic()
                    DiagnosticCheckId.STT -> checkStt()
                    DiagnosticCheckId.LOCAL_AI -> checkLocalAi()
                    DiagnosticCheckId.CLOUD_AI -> checkCloudAi()
                    DiagnosticCheckId.BLUETOOTH -> checkBluetooth()
                    DiagnosticCheckId.CLIP -> checkClip()
                    DiagnosticCheckId.ACCESSIBILITY -> checkAccessibility()
                    DiagnosticCheckId.PERMISSIONS -> checkPermissions()
                    DiagnosticCheckId.TTS -> checkTts()
                    DiagnosticCheckId.LICENSE -> checkLicense()
                    DiagnosticCheckId.NETWORK -> checkNetwork()
                    DiagnosticCheckId.BATTERY -> checkBattery()
                }
            } catch (t: Throwable) {
                DiagnosticStatus.FAIL to "probe crashed: ${t.javaClass.simpleName}"
            }
            DiagnosticResult(
                id = id,
                status = status,
                detail = detail,
                durationMs = SystemClock.elapsedRealtime() - started
            )
        }

    // ------------------------------------------------------------ checks

    private fun checkMic(): Pair<DiagnosticStatus, String> {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return DiagnosticStatus.FAIL to "RECORD_AUDIO not granted"
        }
        var recorder: AudioRecord? = null
        try {
            val minBuf = AudioRecord.getMinBufferSize(
                MIC_SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuf <= 0) {
                return DiagnosticStatus.FAIL to "no valid input config (code=$minBuf)"
            }
            recorder = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(MIC_SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf)
                .build()
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                return DiagnosticStatus.FAIL to
                    "input init failed (state=${recorder.state}, mic may be busy)"
            }
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                return DiagnosticStatus.WARNING to "startRecording refused (mic busy?)"
            }
            val buf = ShortArray(minBuf / 2)
            val read = recorder.read(buf, 0, buf.size)
            runCatching { recorder.stop() }
            return if (read > 0) {
                DiagnosticStatus.OK to "captured $read samples @${MIC_SAMPLE_RATE}Hz"
            } else {
                DiagnosticStatus.WARNING to "no samples (read=$read, mic may be busy)"
            }
        } catch (se: SecurityException) {
            return DiagnosticStatus.FAIL to "SecurityException: permission revoked?"
        } finally {
            runCatching { recorder?.release() }
        }
    }

    private fun checkStt(): Pair<DiagnosticStatus, String> =
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            DiagnosticStatus.OK to "recognition service available"
        } else {
            DiagnosticStatus.FAIL to "no recognition service on device"
        }

    private fun checkLocalAi(): Pair<DiagnosticStatus, String> =
        when (val state = localModelManager.state) {
            is LocalModelState.Ready ->
                DiagnosticStatus.OK to "ready: ${state.modelId} (${state.loadTimeMs}ms load)"
            is LocalModelState.Downloading ->
                DiagnosticStatus.WARNING to "downloading: ${state.progressPercent}%"
            is LocalModelState.NotInstalled ->
                DiagnosticStatus.WARNING to "model not installed"
            is LocalModelState.Loading ->
                DiagnosticStatus.WARNING to "loading into memory"
            is LocalModelState.NotInitialized ->
                DiagnosticStatus.WARNING to "not initialized yet"
            is LocalModelState.DownloadFailed ->
                DiagnosticStatus.FAIL to "download failed: ${state.reason}"
            is LocalModelState.Failed ->
                DiagnosticStatus.FAIL to "model failed: ${state.reason}"
        }

    private suspend fun checkCloudAi(): Pair<DiagnosticStatus, String> {
        // Бесплатный validate лицензии: доказывает достижимость сервера без
        // траты AI-токенов. Прямого бесплатного health-check у AI-пути нет.
        val verdict = serverValidator.validate(licenseManager.getDeviceId())
        val suffix = "free license endpoint, no AI tokens spent"
        return when (verdict) {
            is ServerLicenseValidationResult.Valid ->
                DiagnosticStatus.OK to "server reachable, license valid ($suffix)"
            ServerLicenseValidationResult.ServiceUnavailable ->
                DiagnosticStatus.FAIL to "server unreachable"
            ServerLicenseValidationResult.RateLimited ->
                DiagnosticStatus.WARNING to "server reachable, rate-limited ($suffix)"
            else ->
                DiagnosticStatus.WARNING to
                    "server reachable, license verdict=${verdict.javaClass.simpleName} ($suffix)"
        }
    }

    private fun checkBluetooth(): Pair<DiagnosticStatus, String> {
        if (!clipRepository.hasBluetoothHardware()) {
            return DiagnosticStatus.NA to "no bluetooth radio on device"
        }
        if (!clipRepository.isBluetoothEnabled()) {
            return DiagnosticStatus.WARNING to "adapter off"
        }
        return when (val audio = bluetoothAudioRouter.audioState.value) {
            is BluetoothAudioState.Connected ->
                DiagnosticStatus.OK to "on, audio connected: ${audio.deviceName}"
            BluetoothAudioState.Connecting ->
                DiagnosticStatus.OK to "on, audio connecting"
            BluetoothAudioState.Disconnected ->
                DiagnosticStatus.OK to "on, no audio device"
        }
    }

    private fun checkClip(): Pair<DiagnosticStatus, String> {
        if (!clipRepository.hasBluetoothHardware()) {
            return DiagnosticStatus.NA to "no bluetooth radio on device"
        }
        if (!clipRepository.isBluetoothEnabled()) {
            return DiagnosticStatus.WARNING to "bluetooth off, clip state unknown"
        }
        // Честный текущий факт: подключён ли Clip как аудио-устройство.
        // Историю спаривания и батарею прошивка не отдаёт — не выдумываем.
        return when (val audio = bluetoothAudioRouter.audioState.value) {
            is BluetoothAudioState.Connected ->
                DiagnosticStatus.OK to "connected: ${audio.deviceName}"
            BluetoothAudioState.Connecting ->
                DiagnosticStatus.WARNING to "connecting"
            BluetoothAudioState.Disconnected ->
                DiagnosticStatus.WARNING to "no clip connected"
        }
    }

    private fun checkAccessibility(): Pair<DiagnosticStatus, String> {
        val expected = ComponentName(context, JarvisAccessibilityService::class.java)
            .flattenToString()
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty().split(':')
        return if (enabled.any { it.equals(expected, ignoreCase = true) }) {
            DiagnosticStatus.OK to "JarvisAccessibilityService enabled"
        } else {
            DiagnosticStatus.WARNING to "service not enabled (device actions limited)"
        }
    }

    private fun checkPermissions(): Pair<DiagnosticStatus, String> {
        val missing = buildList {
            if (!isGranted(Manifest.permission.RECORD_AUDIO)) add("RECORD_AUDIO")
            if (Build.VERSION.SDK_INT >= 33 &&
                !isGranted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add("POST_NOTIFICATIONS")
            }
            if (Build.VERSION.SDK_INT >= 31 &&
                !isGranted(Manifest.permission.BLUETOOTH_CONNECT)
            ) {
                add("BLUETOOTH_CONNECT")
            }
        }
        return when {
            missing.isEmpty() ->
                DiagnosticStatus.OK to "audio+notifications+bt granted"
            "RECORD_AUDIO" in missing ->
                DiagnosticStatus.FAIL to "missing: ${missing.joinToString()}"
            else ->
                DiagnosticStatus.WARNING to "missing: ${missing.joinToString()}"
        }
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    private fun checkTts(): Pair<DiagnosticStatus, String> =
        when (ttsManager.ttsState.value) {
            TtsState.Ready -> DiagnosticStatus.OK to "engine ready"
            TtsState.Speaking -> DiagnosticStatus.OK to "speaking now"
            TtsState.Done -> DiagnosticStatus.OK to "engine ready (last utterance done)"
            TtsState.Initializing -> DiagnosticStatus.WARNING to "initializing"
            TtsState.Idle -> DiagnosticStatus.WARNING to "not initialized yet"
            TtsState.Error -> DiagnosticStatus.FAIL to "engine in error state"
        }

    private fun checkLicense(): Pair<DiagnosticStatus, String> {
        val info = licenseManager.getLicenseInfo()
        return when {
            info.isExpired -> DiagnosticStatus.FAIL to "license expired"
            info.isActivated ->
                DiagnosticStatus.OK to "activated (plan=${info.planId.ifEmpty { "?" }})"
            else -> DiagnosticStatus.WARNING to "not activated"
        }
    }

    private fun checkNetwork(): Pair<DiagnosticStatus, String> =
        if (networkMonitor.isCurrentlyOnline()) {
            DiagnosticStatus.OK to "online (validated transport)"
        } else {
            DiagnosticStatus.FAIL to "offline"
        }

    private fun checkBattery(): Pair<DiagnosticStatus, String> {
        val intent = ContextCompat.registerReceiver(
            context,
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        ) ?: return DiagnosticStatus.NA to "battery intent unavailable"
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return DiagnosticStatus.NA to "battery level unreadable"
        }
        val pct = (level * 100) / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return if (pct <= LOW_BATTERY_PCT && !charging) {
            DiagnosticStatus.WARNING to "$pct%, discharging (low)"
        } else {
            DiagnosticStatus.OK to "$pct%${if (charging) ", charging" else ", discharging"}"
        }
    }

    private companion object {
        const val MIC_SAMPLE_RATE = 16000
        const val LOW_BATTERY_PCT = 20
    }
}
