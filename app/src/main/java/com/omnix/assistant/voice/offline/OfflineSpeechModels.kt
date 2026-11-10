package com.omnix.assistant.voice.offline

/**
 * Офлайн-модели речи OMNIX (решение владельца 2026-11-09: офлайн — одна из главных
 * фишек). Модели не входят в APK и не лежат в публичном репозитории: приложение
 * скачивает один zip-пакет на модель после нажатия «Скачать» и проверяет размер и
 * SHA-256, как для локальной языковой модели.
 *
 * Пока пакет не опубликован ([isPublished] == false), в настройках честно написано
 * «скоро», а скачивание не предлагается.
 */
data class OfflineSpeechModel(
    val id: String,
    /** Папка внутри filesDir/offline-speech, куда распаковывается пакет. */
    val dirName: String,
    /** Прямая ссылка на zip без авторизации; пустая — пакет ещё не опубликован. */
    val url: String,
    /** Точный размер zip в байтах. */
    val sizeBytes: Long,
    /** Эталонный SHA-256 zip в нижнем hex. */
    val sha256: String,
    /** Файлы, без которых модель считается неустановленной (пути внутри папки). */
    val requiredFiles: List<String>
) {
    val isPublished: Boolean get() = url.isNotBlank() && sizeBytes > 0 && sha256.length == SHA256_HEX_LENGTH

    val approxSizeMb: Int get() = ((sizeBytes + MB - 1) / MB).toInt()

    private companion object {
        const val SHA256_HEX_LENGTH = 64
        const val MB = 1024L * 1024L
    }
}

object OfflineSpeechModels {

    /**
     * Туркменский голос OMNIX: Piper (VITS) в формате sherpa-onnx + данные espeak-ng.
     * Настройки чёткости D: noise 0.3, noise_w 0.5, length 1.15.
     */
    val TK_VOICE = OfflineSpeechModel(
        id = "tk-voice",
        dirName = "tk-voice",
        url = "https://omnix.144.31.14.236.sslip.io/models/tk-voice.zip",
        sizeBytes = 67_528_197L,
        sha256 = "265b2e52d4c325dfb96664394ad8c308cf35e9728677172da0975098af10e270",
        requiredFiles = listOf("model.onnx", "tokens.txt", "espeak-ng-data/phontab")
    )

    /**
     * Туркменское распознавание речи: Whisper base, дообученный, int8, формат sherpa-onnx.
     * Не опубликовано намеренно: после сжатия в int8 ошибка слов 55,6% (до сжатия 33%), а локальный
     * результат стоит в приложении раньше сервера. Файл tk-asr.zip уже лежит на сервере
     * (134 656 024 байт, sha256 b3c52b88…a173e30); включить, когда качество подтянем.
     */
    val TK_ASR = OfflineSpeechModel(
        id = "tk-asr",
        dirName = "tk-asr",
        url = "",
        sizeBytes = 0L,
        sha256 = "",
        requiredFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt")
    )

    val ALL: List<OfflineSpeechModel> = listOf(TK_VOICE, TK_ASR)
}
