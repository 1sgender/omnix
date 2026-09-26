package com.omnix.assistant.voice.wakeword

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * Near-miss захват для сбора данных модели v0.2 (owner review 2026-09-26, п.1).
 *
 * В бете/деве включается флагом [WakeWordConfig.nearMissCapture] (по
 * умолчанию ВЫКЛ — сырое аудио не записывается ни при каких штатных
 * условиях). Захватывается аудио-хвост кадра, где скор классификатора ≥
 * [WakeWordConfig.nearMissMinScore] (0.25) но детекция НЕ подтверждена
 * порогом/patience/cooldown. Такие «почти-детекции» — будущие NEGATIVE-ы
 * retrain'а, ценнее синтетических adversarial-фраз (реальные голоса,
 * реальный шум устройства).
 *
 * Приватность: файлы живут ТОЛЬКО в частном каталоге приложения
 * (app-private filesDir), наружу не покидают (нет upload-пути), суммарно
 * не превышают [maxTotalBytes] (самые старые удаляются), и их можно удалить
 * целиком (clearAll). Никаких метаданных пользователя в именах/манифесте —
 * только время, скор, модель и пороги.
 *
 * WAV-запись и политика решений — чистые, покрыты JVM-тестами.
 */
class NearMissRecorder(
    private val minScore: Float,
    private val minIntervalMs: Long = 30_000L,
    private val maxTotalBytes: Long = 40L * 1024 * 1024,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private var lastCaptureMs = -1L

    /** Решение о захвате кадра. [capture] true = писать файл. */
    data class Decision(val capture: Boolean, val reason: String)

    /**
     * Оценивать кадр: захватывать near-miss?
     * - `fired` (детекция подтверждена политикой) — не захватываем: это
     *   позитив, а он и так в истории как обычная реплика;
     * - скор ниже [minScore] — нет смысла (шумовой фон);
     * - дебаунс [minIntervalMs] — одна фраза «Omni» даёт ~16 кадров с
     *   растущим скором, писать их все бессмысленно.
     */
    fun shouldCapture(score: Float, fired: Boolean, nowMs: Long = clockMs()): Decision {
        if (fired) return Decision(false, "fired")
        if (score < minScore) return Decision(false, "below-min-score")
        if (lastCaptureMs >= 0 && nowMs - lastCaptureMs < minIntervalMs) {
            return Decision(false, "debounce")
        }
        lastCaptureMs = nowMs
        return Decision(true, "near-miss")
    }

    /** Сброс дебаунса (перезапуск движка). */
    fun reset() {
        lastCaptureMs = -1L
    }

    /**
     * Записать PCM16-моно WAV. [samples] в int16-диапазоне. Возвращает
     * размер файла в байтах.
     */
    fun writeWav(file: File, samples: ShortArray, sampleRate: Int): Long {
        file.parentFile?.mkdirs()
        val dataSize = samples.size * 2
        val header = ByteBuffer.allocate(WAV_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        header.put(WAV_RIFF)
        header.putInt(36 + dataSize)
        header.put(WAV_WAVE)
        header.put(WAV_FMT)
        header.putInt(16)
        header.putShort(WAVE_FORMAT_PCM)
        header.putShort(1)
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)
        header.putShort(2)
        header.putShort(16)
        header.put(WAV_DATA)
        header.putInt(dataSize)
        file.outputStream().use { out ->
            out.write(header.array())
            if (dataSize > 0) {
                val buf = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
                for (s in samples) buf.putShort(s)
                out.write(buf.array())
            }
        }
        return file.length()
    }

    /**
     * Строка манифеста (JSONL): время, скор, модель, пороги. Только
     * числовые/фиксированные поля — метаданных пользователя нет.
     */
    fun manifestLine(epochMs: Long, score: Float, model: String, threshold: Float, snrDb: Float?): String {
        val snr = snrDb?.let { String.format(Locale.US, "%.1f", it) } ?: "null"
        return "{\"epochMs\":$epochMs,\"score\":$score,\"model\":\"$model\",\"threshold\":$threshold,\"snrDb\":$snr}"
    }

    /**
     * Кап хранилища: вернуть файлы (от старых к новым) для удаления, пока
     * общий размер не уложится в [maxTotalBytes]. [files] — уже отсортированы
     * по имени (имена содержат timestamp — лексический порядок = хронологический).
     */
    fun filesToDelete(files: List<File>): List<File> {
        var total = files.sumOf { it.length() }
        val toDelete = mutableListOf<File>()
        for (f in files) {
            if (total <= maxTotalBytes) break
            toDelete.add(f)
            total -= f.length()
        }
        return toDelete
    }

    companion object {
        /** Хвост аудио, который пишется в near-miss файл (2.5 с @16 кГц). */
        const val CAPTURE_WINDOW_MS = 2500
        const val SAMPLE_RATE = 16000
        const val WAV_HEADER_SIZE = 44
        private val WAV_RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
        private val WAV_WAVE = "WAVE".toByteArray(Charsets.US_ASCII)
        private val WAV_FMT = "fmt ".toByteArray(Charsets.US_ASCII)
        private val WAV_DATA = "data".toByteArray(Charsets.US_ASCII)
        private val WAVE_FORMAT_PCM = 1.toShort()
    }
}

/** Каталог near-miss захвата в app-private хранилище (только для записи). */
fun File.nearMissDir(): File = File(this, "nearmiss")

/** Все near-miss WAV, от старых к новым (по имени = по времени). */
fun File.listNearMissWavs(): List<File> =
    nearMissDir().listFiles { f -> f.name.endsWith(".wav") }
        ?.sortedBy { it.name }
        ?: emptyList()

/** Удалить все near-miss файлы и манифест. Возвращает число удалённых. */
fun File.clearNearMiss(): Int {
    val dir = nearMissDir()
    val wavs = dir.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray()
    File(dir, "manifest.jsonl").delete()
    wavs.forEach { it.delete() }
    dir.deleteRecursively()
    return wavs.size
}
