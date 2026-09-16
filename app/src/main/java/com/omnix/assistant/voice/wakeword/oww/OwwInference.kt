package com.omnix.assistant.voice.wakeword.oww

/**
 * Три стадии openWakeWord как интерфейсы (§3 ТЗ).
 *
 * Тензорный контракт проверен живым прогоном через ONNX Runtime 1.30
 * на реальных моделях v0.5.1 (см. docs/WAKEWORD_NEURAL.md):
 * - mel: float32 [1, N] (значения в диапазоне int16!) -> [T, 32] после /10+2;
 * - embedding: float32 [1, 76, 32, 1] -> [B, 96];
 * - classifier: float32 [1, 16, 96] -> скор [0, 1].
 *
 * ВАЖНО: вход mel — int16-range, как в эталонном openwakeword/utils.py
 * (там ValueError на не-int16). Нормализация /32768 из сторонних
 * Android-портов НЕ применяется — это их отклонение от эталона.
 */
interface MelComputer {
    /**
     * @param samples PCM в int16-диапазоне как float32, моно 16 кГц, >= 400 сэмплов.
     * @return мел-спектрограмма [кадры, 32] после трансформы x/10+2.
     */
    fun compute(samples: FloatArray): Array<FloatArray>
}

interface EmbeddingComputer {
    /**
     * @param window окно [1, 76, 32, 1] из мел-буфера.
     * @return эмбеддинги [batch, 96].
     */
    fun embed(window: Array<Array<Array<FloatArray>>>): Array<FloatArray>
}

interface WakeClassifier {
    /**
     * @param features последние 16 эмбеддингов [1, 16, 96].
     * @return сырой скор классификатора.
     */
    fun score(features: Array<Array<FloatArray>>): Float
}
