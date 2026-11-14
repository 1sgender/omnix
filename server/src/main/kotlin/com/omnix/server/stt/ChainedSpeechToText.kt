package com.omnix.server.stt

import com.omnix.server.observability.StructuredLogger
import com.omnix.server.provider.ProviderFailureKind

/**
 * Цепочка распознавателей по приоритету: следующий вызывается только при
 * сбое предыдущего (кроме BAD_REQUEST: запрос плохой, другой провайдер не
 * поможет). В логи идёт только имя провайдера и вид сбоя, без текста и аудио.
 */
class ChainedSpeechToText(
    private val providers: List<Pair<String, SpeechToText>>,
    private val logger: StructuredLogger? = null
) : SpeechToText {

    init {
        require(providers.isNotEmpty()) { "STT chain must not be empty" }
    }

    override suspend fun transcribe(wav: ByteArray, languageHint: String?): SttOutcome {
        var last: SttOutcome = SttOutcome.Failure(ProviderFailureKind.NOT_CONFIGURED)
        for ((name, stt) in providers) {
            val outcome = stt.transcribe(wav, languageHint)
            if (outcome is SttOutcome.Success) {
                logger?.info("stt recognized", "provider" to name)
                return outcome
            }
            last = outcome
            val kind = (outcome as SttOutcome.Failure).kind
            logger?.warn("stt provider failed", "provider" to name, "kind" to kind.name)
            if (kind == ProviderFailureKind.BAD_REQUEST) break
        }
        return last
    }

    companion object {
        /**
         * Порядок из STT_PROVIDERS ("groq,gemini"); неизвестные и ненастроенные
         * имена пропускаются. null, если не осталось ни одного провайдера.
         */
        fun fromConfig(
            order: String?,
            available: Map<String, SpeechToText>,
            logger: StructuredLogger? = null
        ): SpeechToText? {
            val names = (order ?: "groq").split(",").map { it.trim().lowercase() }
                .filter { it.isNotEmpty() }.distinct()
            val chain = names.mapNotNull { name -> available[name]?.let { name to it } }
            return when (chain.size) {
                0 -> null
                1 -> chain.single().second
                else -> ChainedSpeechToText(chain, logger)
            }
        }
    }
}
