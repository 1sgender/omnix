package com.omnix.assistant.domain.files

/** Откуда облачный клиент берёт саму картинку по id из сообщения (готовый JPEG в base64). */
fun interface ChatImageSource {
    fun base64Jpeg(id: String): String?

    companion object {
        /** Без картинок (тесты, legacy-вызовы). */
        val NONE: ChatImageSource = ChatImageSource { null }
    }
}
