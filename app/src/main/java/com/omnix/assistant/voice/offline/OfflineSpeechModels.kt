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
     * Русский голос OMNIX: Piper (VITS) в формате sherpa-onnx, дообучен на записях одного диктора
     * корпуса SOVA (CC BY 4.0, атрибуция в NOTICE и на экране «Лицензии»). Необязательная загрузка:
     * без неё русский говорит системным голосом.
     */
    val RU_VOICE = OfflineSpeechModel(
        id = "ru-voice",
        dirName = "ru-voice",
        url = "https://omnix.144.31.14.236.sslip.io/models/ru-voice.zip",
        sizeBytes = 68_193_351L,
        sha256 = "708cfee0563b598e0c0d6fe427863c2f8ff27228b3da8bfa62dfc8be0fffce60",
        requiredFiles = listOf("model.onnx", "tokens.txt", "espeak-ng-data/phontab")
    )

    /**
     * Туркменское распознавание речи: whisper-base-turkmen-v3 (derkar00, CC BY-NC 4.0 — только
     * некоммерческое использование; автор разрешил владельцу, до продажи подписок заменить или
     * получить другую лицензию). Переведена в sherpa-onnx: кодировщик fp32, декодер int8.
     * Замер 27.11 на записи с подтверждённым текстом: WER 36,7% / CER 10,4% против 39,3% / 9,9% у
     * прежней Omnilingual 300M, пакет 192 МБ вместо 270 МБ. Текст модели проходит [WhisperText].
     */
    val TK_ASR = OfflineSpeechModel(
        id = "tk-asr-whisper-v3",
        dirName = "tk-asr-whisper-v3",
        url = "https://omnix.144.31.14.236.sslip.io/models/tk-asr-whisper-v3.zip",
        sizeBytes = 201_610_887L,
        sha256 = "90ab86439d195b914b0302357f7be389e38d112b29e13ba85b0910f4049e1de3",
        requiredFiles = listOf("encoder.onnx", "decoder.int8.onnx", "tokens.txt")
    )

    /** Папки прежних туркменских моделей: удаляются, когда новая уже на телефоне. */
    val LEGACY_DIRS: List<String> = listOf("tk-asr-omni")

    /**
     * Русское распознавание речи «улучшенное»: GigaAM v3 CTC (SberDevices, MIT), int8, сборка
     * sherpa-onnx. На русских бенчмарках заметно точнее маленькой встроенной модели Vosk.
     * Скачивается по желанию; пока пакет не скачан, работает встроенная модель.
     */
    val RU_ASR = OfflineSpeechModel(
        id = "ru-asr-gigaam",
        dirName = "ru-asr-gigaam",
        url = "https://omnix.144.31.14.236.sslip.io/models/ru-asr-gigaam.zip",
        sizeBytes = 159216698L,
        sha256 = "b2aa0122501e9cc93d80f0f3e3e9eb32803d851fbf3c76ea2762033c0e6bb355",
        requiredFiles = listOf("model.int8.onnx", "tokens.txt")
    )

    val ALL: List<OfflineSpeechModel> = listOf(TK_VOICE, RU_VOICE, TK_ASR, RU_ASR)
}
