package com.omnix.assistant.voice.live

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit

/**
 * Подаёт наш звук (16 кГц, моно, PCM 16 бит) распознавателю Android через канал
 * (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, Android 13+).
 *
 * Распознаватель после паузы закрывает сессию и открывает новую — на каждую сессию
 * [newSession] создаёт новый канал. Пока новой сессии нет, звук копится в очереди
 * (до [MAX_QUEUE_FRAMES], старое выбрасывается) и уходит в новую сессию: слова на стыке
 * сессий не теряются. Запись неблокирующая: зависший распознаватель не держит поток.
 */
class PcmPipeFeeder {

    private val queue = LinkedBlockingDeque<ShortArray>(MAX_QUEUE_FRAMES)

    @Volatile
    private var writeEnd: ParcelFileDescriptor? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "omnix-live-pipe").apply {
            isDaemon = true
            start()
        }
    }

    fun push(frame: ShortArray) {
        while (!queue.offerLast(frame)) queue.pollFirst()
    }

    /** Канал для новой сессии распознавания: читающий конец отдаётся распознавателю. */
    fun newSession(): ParcelFileDescriptor? = try {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Os.fcntlInt(write.fileDescriptor, OsConstants.F_SETFL, OsConstants.O_NONBLOCK)
        val old = writeEnd
        writeEnd = write
        old?.let { runCatching { it.close() } }
        read
    } catch (e: Exception) {
        Log.w(TAG, "pipe failed: ${e.javaClass.simpleName}")
        null
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        writeEnd?.let { runCatching { it.close() } }
        writeEnd = null
        queue.clear()
    }

    private fun loop() {
        while (running) {
            val fd = writeEnd
            if (fd == null) {
                // Сессии нет: звук ждёт в очереди.
                if (!sleepQuietly(IDLE_MS)) return
                continue
            }
            val frame = try {
                queue.pollFirst(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                return
            } ?: continue
            if (!writeAll(fd, toBytes(frame))) {
                // Распознаватель закрыл сессию: кадр вернуть в начало, ждать новую.
                queue.offerFirst(frame)
                if (writeEnd === fd) writeEnd = null
            }
        }
    }

    /** false — канал закрыт с той стороны. */
    private fun writeAll(fd: ParcelFileDescriptor, bytes: ByteArray): Boolean {
        var off = 0
        var waited = 0L
        while (off < bytes.size) {
            try {
                off += Os.write(fd.fileDescriptor, bytes, off, bytes.size - off)
            } catch (e: ErrnoException) {
                when (e.errno) {
                    OsConstants.EAGAIN -> {
                        // Распознаватель не успевает читать: немного ждём, потом кадр выбрасываем.
                        if (waited >= MAX_WAIT_MS || !sleepQuietly(RETRY_MS)) return true
                        waited += RETRY_MS
                    }
                    else -> return false
                }
            } catch (e: Exception) {
                return false
            }
        }
        return true
    }

    private fun sleepQuietly(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (e: InterruptedException) {
        false
    }

    companion object {
        private const val TAG = "PcmPipeFeeder"

        /** 2 секунды звука по 20 мс. */
        const val MAX_QUEUE_FRAMES = 100
        private const val POLL_MS = 100L
        private const val IDLE_MS = 10L
        private const val RETRY_MS = 5L
        private const val MAX_WAIT_MS = 200L

        fun toBytes(frame: ShortArray): ByteArray {
            val out = ByteArray(frame.size * 2)
            for (i in frame.indices) {
                val v = frame[i].toInt()
                out[2 * i] = (v and 0xFF).toByte()
                out[2 * i + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return out
        }
    }
}
