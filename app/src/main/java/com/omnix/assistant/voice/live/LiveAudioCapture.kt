package com.omnix.assistant.voice.live

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.omnix.assistant.voice.audio.BluetoothAudioRouter

/**
 * Свой захват звука синхронного перевода: микрофон телефона, наушника или оба сразу.
 *
 * Для наушника включается режим гарнитуры (SCO) через [BluetoothAudioRouter]. В режиме
 * [LiveMicMode.BOTH] открываются две записи с разными предпочтительными устройствами, и после
 * старта проверяется, откуда звук идёт на самом деле: многие телефоны дают писать только с
 * одного микрофона за раз. Если вторая запись не получилась, режим честно откатывается на
 * телефон ([Started.fellBack]).
 *
 * Кадры по 20 мс уходят в [start]-колбэк из отдельного потока.
 */
class LiveAudioCapture(
    context: Context,
    private val router: BluetoothAudioRouter
) {
    data class Started(val mode: LiveMicMode, val fellBack: Boolean)

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val mixer = DualMicMixer()

    @Volatile
    private var running = false
    private var thread: Thread? = null
    private var phoneRecord: AudioRecord? = null
    private var earbudRecord: AudioRecord? = null
    private var usesHeadsetMode = false

    /** Сколько миллисекунд речи (не тишины) уже прошло через захват. */
    @Volatile
    var voicedMs: Long = 0
        private set

    /** Режим, который реально работает (после возможного отката). */
    @Volatile
    var activeMode: LiveMicMode = LiveMicMode.PHONE
        private set

    /**
     * Открывает микрофоны. Блокирует поток на время включения гарнитуры (до ~1 с) —
     * вызывать не из главного потока. null — микрофон недоступен.
     */
    @Synchronized
    fun start(requested: LiveMicMode, onFrame: (ShortArray) -> Unit): Started? {
        stop()
        val wanted = if (requested != LiveMicMode.PHONE && earbudInput() == null && !router.isHeadsetConnected()) {
            // Наушника нет — слушаем телефоном.
            LiveMicMode.PHONE
        } else {
            requested
        }
        var fellBack = wanted != requested

        if (wanted != LiveMicMode.PHONE) {
            router.routeAudioToEarbud()
            usesHeadsetMode = true
            Thread.sleep(SCO_SETTLE_MS)
        } else {
            router.routeAudioToSpeaker()
        }

        val mode = when (wanted) {
            LiveMicMode.PHONE -> if (openPhone()) LiveMicMode.PHONE else null
            LiveMicMode.EARBUD -> if (openEarbud()) LiveMicMode.EARBUD else null
            LiveMicMode.BOTH -> if (openPhone() && openEarbud() && dualRouted()) LiveMicMode.BOTH else null
        } ?: run {
            // Не вышло так, как просили: откат на телефон.
            fellBack = true
            releaseRecords()
            if (usesHeadsetMode) {
                router.routeAudioToSpeaker()
                usesHeadsetMode = false
            }
            if (openPhone()) LiveMicMode.PHONE else return null
        }

        activeMode = mode
        voicedMs = 0
        running = true
        thread = Thread({ loop(onFrame) }, "omnix-live-capture").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "live capture requested=$requested active=$mode fellBack=$fellBack")
        return Started(mode, fellBack)
    }

    @Synchronized
    fun stop() {
        running = false
        thread?.interrupt()
        runCatching { thread?.join(JOIN_MS) }
        thread = null
        releaseRecords()
        if (usesHeadsetMode) {
            router.routeAudioToSpeaker()
            usesHeadsetMode = false
        }
    }

    /** Звук перевода идёт в гарнитуру (SCO): озвучивать надо потоком звонка. */
    val speaksThroughHeadset: Boolean get() = usesHeadsetMode

    private fun loop(onFrame: (ShortArray) -> Unit) {
        val phone = ShortArray(FRAME)
        val earbud = ShortArray(FRAME)
        while (running) {
            val frame = when (activeMode) {
                LiveMicMode.PHONE -> read(phoneRecord, phone)?.copyOf()
                LiveMicMode.EARBUD -> read(earbudRecord, earbud)?.copyOf()
                LiveMicMode.BOTH -> {
                    val p = read(phoneRecord, phone)
                    val e = read(earbudRecord, earbud)
                    if (p != null && e != null) mixer.mix(p, e) else null
                }
            } ?: continue
            if (DualMicMixer.levelDb(frame) > VOICED_DB) voicedMs += FRAME_MS
            onFrame(frame)
        }
    }

    private fun read(record: AudioRecord?, buffer: ShortArray): ShortArray? {
        val r = record ?: return null
        var off = 0
        while (off < buffer.size && running) {
            val n = r.read(buffer, off, buffer.size - off)
            if (n <= 0) return null
            off += n
        }
        return if (off == buffer.size) buffer else null
    }

    private fun openPhone(): Boolean {
        val record = build(MediaRecorder.AudioSource.VOICE_RECOGNITION, builtinInput()) ?: return false
        phoneRecord = record
        return true
    }

    private fun openEarbud(): Boolean {
        val device = earbudInput() ?: return false
        val record = build(MediaRecorder.AudioSource.VOICE_COMMUNICATION, device) ?: return false
        earbudRecord = record
        if (!settleAndCheck(record) { isEarbud(it) }) {
            Log.i(TAG, "earbud record routed to ${record.routedDevice?.type}")
            return false
        }
        return true
    }

    /** Оба потока действительно идут с разных микрофонов. */
    private fun dualRouted(): Boolean {
        val phone = phoneRecord ?: return false
        val routed = phone.routedDevice
        val ok = routed != null && !isEarbud(routed)
        if (!ok) Log.i(TAG, "dual capture not supported: phone record routed to ${routed?.type}")
        return ok
    }

    private fun settleAndCheck(record: AudioRecord, check: (AudioDeviceInfo) -> Boolean): Boolean {
        repeat(ROUTE_CHECKS) {
            val routed = record.routedDevice
            if (routed != null && check(routed)) return true
            Thread.sleep(ROUTE_CHECK_MS)
        }
        return false
    }

    @SuppressLint("MissingPermission") // Синхронный перевод запускается только с разрешением на микрофон.
    private fun build(source: Int, device: AudioDeviceInfo?): AudioRecord? = try {
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min, FRAME * 2 * BUFFER_FRAMES))
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            null
        } else {
            device?.let { record.setPreferredDevice(it) }
            record.startRecording()
            record
        }
    } catch (e: Exception) {
        Log.w(TAG, "microphone unavailable: ${e.javaClass.simpleName}")
        null
    }

    private fun releaseRecords() {
        listOf(phoneRecord, earbudRecord).forEach { record ->
            record?.let {
                runCatching { it.stop() }
                it.release()
            }
        }
        phoneRecord = null
        earbudRecord = null
    }

    private fun builtinInput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    private fun earbudInput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { isEarbud(it) }

    private fun isEarbud(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET

    companion object {
        private const val TAG = "LiveAudioCapture"
        const val SAMPLE_RATE = 16_000
        const val FRAME = 320
        private const val FRAME_MS = 20L
        private const val BUFFER_FRAMES = 8
        private const val SCO_SETTLE_MS = 600L
        private const val ROUTE_CHECKS = 5
        private const val ROUTE_CHECK_MS = 100L
        private const val JOIN_MS = 500L
        private const val VOICED_DB = -45f
    }
}
