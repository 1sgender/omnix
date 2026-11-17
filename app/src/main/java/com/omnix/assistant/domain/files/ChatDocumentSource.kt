package com.omnix.assistant.domain.files

/** Откуда облачный клиент берёт PDF по id из сообщения (base64). */
fun interface ChatDocumentSource {
    fun base64Pdf(id: String): String?

    companion object {
        /** Без документов (тесты, legacy-вызовы). */
        val NONE: ChatDocumentSource = ChatDocumentSource { null }
    }
}
