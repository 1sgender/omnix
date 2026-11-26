package com.omnix.assistant.agent.translator

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Синхронный перевод в ухо: собеседник говорит, перевод звучит кусками, не дожидаясь конца фразы.
 *
 * Промежуточный текст распознавания режет [LiveChunker]; куски переводятся строго по очереди
 * (порядок речи сохраняется) с контекстом уже сказанного. Если перевод отстаёт и куски
 * копятся, они склеиваются в один запрос: лучше чуть длиннее кусок, чем растущая задержка.
 *
 * Сеть, голос и распознаватель приходят снаружи ([translate], [speak]), поэтому логика
 * проверяется юнит-тестами без Android.
 */
class LiveEarSession(
    private val scope: CoroutineScope,
    private val translate: suspend (fragment: String, previous: String) -> TranslationResult,
    private val speak: (String) -> Unit,
    private val describeFailure: (TranslationResult) -> String,
    private val pauseMs: Long = DEFAULT_PAUSE_MS,
    chunkWords: Int = LiveChunker.DEFAULT_CHUNK_WORDS,
    /** Отсекает собственный перевод, попавший обратно в микрофон; null — без проверки. */
    private val echoGuard: EchoGuard? = EchoGuard(),
    private val now: () -> Long = System::currentTimeMillis,
    /**
     * Фильтр языка: true — сейчас звучит не язык собеседника. Такой кусок ждёт [recheckMs] и
     * проверяется снова (собеседник мог только что заговорить), потом отбрасывается.
     */
    private val isForeign: () -> Boolean = { false },
    private val recheckMs: Long = DEFAULT_RECHECK_MS
) {
    /** Что показывать на экране: живой оригинал и накопленный перевод. */
    data class View(
        val source: String = "",
        val translation: String = "",
        val error: String? = null
    )

    private val _view = MutableStateFlow(View())
    val view: StateFlow<View> = _view.asStateFlow()

    private val chunker = LiveChunker(chunkWords)
    private val queue = Channel<String>(Channel.UNLIMITED)
    private val history = ArrayDeque<String>()
    private var pauseJob: Job? = null
    private val worker: Job = scope.launch { consume() }

    fun onPartial(text: String) {
        _view.update { it.copy(source = text) }
        chunker.onPartial(text)?.let(::enqueue)
        pauseJob?.cancel()
        pauseJob = scope.launch {
            delay(pauseMs)
            chunker.onPause()?.let(::enqueue)
        }
    }

    fun onFinal(text: String) {
        pauseJob?.cancel()
        _view.update { it.copy(source = text) }
        chunker.onFinal(text)?.let(::enqueue)
    }

    fun stop() {
        pauseJob?.cancel()
        queue.close()
        worker.cancel()
    }

    /** Сколько кусков отброшено фильтром языка. */
    @Volatile
    var foreignDropped: Int = 0
        private set

    /** Сколько кусков отброшено как эхо собственного перевода. */
    @Volatile
    var echoDropped: Int = 0
        private set

    private fun enqueue(piece: String) {
        if (echoGuard?.isEcho(piece, now()) == true) {
            echoDropped++
            return
        }
        queue.trySend(piece)
    }

    private suspend fun consume() {
        for (first in queue) {
            val piece = buildString {
                append(first)
                while (true) {
                    val more = queue.tryReceive().getOrNull() ?: break
                    append(' ').append(more)
                }
            }
            if (isForeign()) {
                // Порядок сохраняется: следующие куски ждут в очереди.
                delay(recheckMs)
                if (isForeign()) {
                    foreignDropped++
                    continue
                }
            }
            val result = translate(piece, previousContext())
            remember(piece)
            if (result is TranslationResult.Success) {
                val text = result.translatedText.trim()
                _view.update { it.copy(translation = tail(it.translation, text), error = null) }
                echoGuard?.onSpoken(text, now())
                speak(text)
            } else {
                _view.update { it.copy(error = describeFailure(result)) }
            }
        }
    }

    private fun remember(piece: String) {
        LiveChunker.tokenize(piece).forEach { history.addLast(it) }
        while (history.size > CONTEXT_WORDS) history.removeFirst()
    }

    private fun previousContext(): String = history.joinToString(" ")

    private fun tail(current: String, add: String): String {
        val joined = if (current.isEmpty()) add else "$current $add"
        return if (joined.length <= SCREEN_CHARS) joined else joined.substring(joined.length - SCREEN_CHARS).trimStart()
    }

    companion object {
        /** Пауза собеседника, после которой отдаём недосказанный кусок. */
        const val DEFAULT_PAUSE_MS = 700L

        /** Сколько слов уже сказанного даём переводчику как контекст. */
        const val CONTEXT_WORDS = 30

        /** Сколько ждать перед повторной проверкой языка подозрительного куска. */
        const val DEFAULT_RECHECK_MS = 1_200L

        /** Хвост перевода на экране. */
        const val SCREEN_CHARS = 400
    }
}
