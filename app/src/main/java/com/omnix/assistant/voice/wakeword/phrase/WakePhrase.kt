package com.omnix.assistant.voice.wakeword.phrase

/**
 * Чем и что слушать в фоне.
 *
 * Замеры 2026-10-22 (синтетическая речь, 5 минут посторонних разговоров,
 * docs/WAKEWORD_NEURAL.md): прежняя модель «Omni» срабатывала 38 раз за
 * 5 минут чужой речи — слово короткое и звучит как «помни», «о мне». Фраза
 * «Окей, Омни» на русской модели: 12 из 12 русских и 12 из 12 английских
 * произношений, ни одного ложного срабатывания.
 */
enum class WakeEngineKind(val storageKey: String) {
    /** sherpa-onnx keyword spotter на русской потоковой модели. */
    PHRASE("phrase"),

    /** Прежняя модель «Omni» (openWakeWord) — только для сравнения админом. */
    LEGACY_OMNI("legacy_omni");

    companion object {
        fun fromStorageKey(raw: String?): WakeEngineKind = entries.firstOrNull { it.storageKey == raw } ?: PHRASE
    }
}

/**
 * Фраза вызова для [WakeEngineKind.PHRASE]. [keywords] — токены BPE русской
 * модели (bpe.model из архива модели), через «/» — варианты произношения.
 * Токен, которого нет в tokens.txt, нативный код не прощает (процесс
 * завершается), поэтому строки проверяет инструментальный тест.
 */
enum class WakePhrase(val storageKey: String, val displayName: String, val keywords: String) {
    /**
     * По умолчанию (2026-10-22): владелец зовёт просто «Омни», ещё нужно «Омникс».
     * Одиночному «Омни» — свой порог строже (`:буст #порог`): на синтетике
     * единственное ложное — «Амнистия»; на 5 минутах речи ложных ноль.
     */
    ALL(
        "all",
        "Омни",
        "▁о к ей ▁о м ни @OKAY_OMNI/▁о к ей ▁у м ный @OKAY_OMNI/▁о м ни :1.0 #0.25 @OMNI/▁о м ник с @OMNIX"
    ),

    /** «Окей, Омни»; английское «Okay Omni» модель слышит как «окей умный». */
    OKAY_OMNI(
        "okay_omni",
        "Окей, Омни",
        "▁о к ей ▁о м ни @OKAY_OMNI/▁о к ей ▁у м ный @OKAY_OMNI"
    ),

    /** Одно «Омни»: ложных нет, но одиночное слово модель слышит как «помни» и пропускает. */
    OMNI("omni", "Омни", "▁о м ни @OMNI");

    companion object {
        fun fromStorageKey(raw: String?): WakePhrase = entries.firstOrNull { it.storageKey == raw } ?: ALL
    }
}
