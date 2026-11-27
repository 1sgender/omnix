package com.omnix.assistant.voice.wakeword.personal

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Примеры для личного фильтра «Омни» и сам фильтр — только в приватной папке приложения.
 *
 *  - «я» (positives): позвал «Омни» и сразу дал команду; записи экрана тренировки;
 *  - «не я» (negatives): ложные включения (после них тишина), срабатывания фоновой проверки;
 *  - общие чужие примеры — из обучения модели (assets), чтобы фильтр не переучился на мало данных.
 *
 * Звук примеров (2 с WAV) хранится рядом с ограничением по количеству: его можно выгрузить
 * для глубокого переобучения модели. Фильтр переобучается после каждого нового примера.
 */
@Singleton
class PersonalWakeStore @Inject constructor(@ApplicationContext private val context: Context) {

    private val dir: File get() = File(context.filesDir, DIR).apply { mkdirs() }
    private val clipsDir: File get() = File(dir, "clips").apply { mkdirs() }

    @Volatile
    private var cached: PersonalWakeVerifier? = null

    @Volatile
    private var loaded = false

    /** Готовый фильтр, если примеров «я» достаточно; иначе null. */
    @Synchronized
    fun verifier(): PersonalWakeVerifier? {
        if (!loaded) {
            loaded = true
            val f = File(dir, MODEL)
            cached = if (f.isFile && positiveCount() >= MIN_POSITIVES) PersonalWakeVerifier.fromBytes(f.readBytes()) else null
        }
        return cached
    }

    fun positiveCount(): Int = count(POS)

    fun negativeCount(): Int = count(NEG)

    /** Добавить пример «я» и переобучить фильтр. [audio] — 16 кГц моно для выгрузки (необязательно). */
    @Synchronized
    fun addPositive(features: FloatArray, audio: ShortArray? = null) {
        append(POS, features, MAX_POSITIVES)
        audio?.let { saveClip("pos", it) }
        retrain()
    }

    /** Добавить пример «не я» и переобучить фильтр. */
    @Synchronized
    fun addNegative(features: FloatArray, audio: ShortArray? = null) {
        append(NEG, features, MAX_NEGATIVES)
        audio?.let { saveClip("neg", it) }
        retrain()
    }

    /** Все записи для выгрузки (WAV). */
    fun clips(): List<File> = clipsDir.listFiles { f -> f.extension == "wav" }?.sortedBy { it.name }.orEmpty()

    /** Сбросить личное обучение. */
    @Synchronized
    fun clear() {
        dir.deleteRecursively()
        cached = null
        loaded = true
    }

    @Synchronized
    fun retrain() {
        val pos = read(POS)
        if (pos.size < MIN_POSITIVES) {
            cached = null
            loaded = true
            return
        }
        val neg = read(NEG) + genericNegatives()
        val model = PersonalWakeVerifier.train(pos, neg) ?: return
        File(dir, MODEL).writeBytes(model.toBytes())
        cached = model
        loaded = true
        Log.i(TAG, "personal wake filter retrained: pos=${pos.size} neg=${neg.size}")
    }

    private fun genericNegatives(): List<FloatArray> = try {
        val bytes = context.assets.open(GENERIC_ASSET).use { it.readBytes() }
        decode(bytes)
    } catch (e: Exception) {
        Log.w(TAG, "generic negatives unavailable: ${e.javaClass.simpleName}")
        emptyList()
    }

    private fun count(name: String): Int = (File(dir, name).length() / RECORD_BYTES).toInt()

    private fun read(name: String): List<FloatArray> {
        val f = File(dir, name)
        return if (f.isFile) decode(f.readBytes()) else emptyList()
    }

    private fun append(name: String, v: FloatArray, cap: Int) {
        require(v.size == WakeFeatures.DIM)
        val f = File(dir, name)
        val buf = ByteBuffer.allocate(RECORD_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        v.forEach { buf.putFloat(it) }
        f.appendBytes(buf.array())
        val total = (f.length() / RECORD_BYTES).toInt()
        if (total > cap) {
            // Оставляем самые свежие примеры.
            val keep = f.readBytes().copyOfRange((total - cap) * RECORD_BYTES, total * RECORD_BYTES)
            f.writeBytes(keep)
        }
    }

    private fun saveClip(kind: String, audio: ShortArray) {
        try {
            val file = File(clipsDir, "$kind-${System.currentTimeMillis()}.wav")
            writeWav(file, audio)
            val all = clips()
            if (all.size > MAX_CLIPS) all.take(all.size - MAX_CLIPS).forEach { it.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "clip save failed: ${e.javaClass.simpleName}")
        }
    }

    companion object {
        private const val TAG = "PersonalWakeStore"
        private const val DIR = "wake_personal"
        private const val POS = "positives.bin"
        private const val NEG = "negatives.bin"
        private const val MODEL = "filter.bin"
        const val GENERIC_ASSET = "wakeword/generic_neg_192.bin"
        private const val RECORD_BYTES = WakeFeatures.DIM * 4

        /** Сколько примеров «я» нужно, чтобы фильтр заработал. */
        const val MIN_POSITIVES = 8
        private const val MAX_POSITIVES = 400
        private const val MAX_NEGATIVES = 800
        private const val MAX_CLIPS = 300
        private const val SAMPLE_RATE = 16_000

        fun decode(bytes: ByteArray): List<FloatArray> {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val n = bytes.size / RECORD_BYTES
            return List(n) { FloatArray(WakeFeatures.DIM) { buf.float } }
        }

        fun writeWav(file: File, samples: ShortArray) {
            val dataBytes = samples.size * 2
            RandomAccessFile(file, "rw").use { raf ->
                val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
                h.put("RIFF".toByteArray()).putInt(36 + dataBytes).put("WAVE".toByteArray())
                h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                h.putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
                h.put("data".toByteArray()).putInt(dataBytes)
                raf.write(h.array())
                val body = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
                samples.forEach { body.putShort(it) }
                raf.write(body.array())
                raf.setLength(44L + dataBytes)
            }
        }
    }
}
