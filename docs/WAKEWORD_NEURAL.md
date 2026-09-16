# Neural wake word (openWakeWord + ONNX Runtime)

Day-one реализация голосовой активации: предобученная модель
openWakeWord v0.5.1 (акустика v0.1 детектирует legacy-фразу «Hey Jarvis»;
целевая фраза — «Omni», кастомная модель — фаза 2), собственный тонкий
адаптер поверх ONNX Runtime, без STT-верификации (детект подтверждает
нейросеть, не распознанный текст).

## Почему собственный адаптер, а не готовая библиотека

Проверено по исходникам (сентябрь 2026):

1. Готовые Android-порты (`Re-MENTIA/OpenWakeWord-Android`,
   `miyashita-code/OpenWakeWord-for-Android`) создают **новый OrtSession
   для mel и embedding на каждые 80 мс аудио** — production-киллер
   для CPU и батареи. Наш `OrtOwwSessions` открывает сессии один раз
   и переиспользует.
2. У них **неверное масштабирование входа**: `short / 32768` (±1.0),
   а эталонный `openwakeword/utils.py` требует int16 и кидает
   `ValueError` на другом типе. Наш пайплайн — строго по эталону
   (int16-range float32, без нормализации).

## Тензорный контракт (измерен, не переписан из README)

Живой прогон через ONNX Runtime 1.30 CPU на реальных моделях v0.5.1
(синтетический шум в int16-диапазоне):

| Стадия | Вход | Выход | Замер |
|---|---|---|---|
| mel | float32 `[1, N]` (значения в диапазоне int16!) | `[T, 1, 1, 32]` → squeeze → `[T, 32]`, дальше `x/10+2` | 1760 сэмплов → 8 кадров |
| embedding | float32 `[1, 76, 32, 1]` | `[B, 1, 1, 96]` → reshape → `[B, 96]` | B=1 в стриминге |
| classifier | float32 `[1, 16, 96]` | `[1, 1]` скор | шум → 0.159 |

Стриминг 1:1 с `utils.py`: чанк 1280 сэмплов (80 мс @16 кГц),
оверлап mel 480, окно 76 шагом 8, буферы 970 мел-кадров / 120 эмбеддингов,
классификатор на последних 16. Patience/threshold/cooldown — в `DetectionPolicy`.

## Карта файлов

- `voice/wakeword/WakeWordEngine.kt` — интерфейс, `WakeWordDetection`,
  `WakeWordConfig`, `WakeWordEngineError` (ModelMissing громко падает на сборке без модели).
- `voice/wakeword/NeuralWakeWordEngine.kt` — Android-оболочка: AudioRecord
  16 кГц mono, владение микрофоном только пока запущен.
- `voice/wakeword/oww/OpenWakeWordPipeline.kt` — чистый стриминг (сессии инжектятся).
- `voice/wakeword/oww/OwwInference.kt` — интерфейсы стадий + контракт.
- `voice/wakeword/oww/OrtOwwSessions.kt` — ORT-реализация (сессии переиспользуются).
- `voice/wakeword/DetectionPolicy.kt` — threshold + patience + cooldown (чистый).
- `voice/wakeword/AudioRingBuffer.kt` — кольцо 640 мс (чистое).
- `voice/wakeword/WakeWordMetrics.kt` — счётчики и задержки стадий (чистое).
- `assets/wakeword/` — 3 модели + SHA256SUMS (~3.7 МБ в APK).
- `training/` — фаза 2: спецификация и проверка кастомной модели «Omni».
- `device-validation/06-wakeword-metrics.sh` — протокол замеров на устройстве.

## Микрофонный хэндофф

Движок запущен ТОЛЬКО в `STANDBY_WAKE_WORD`. Детекция:
`engine.stop()` (микрофон свободен) → chime → STT слушает КОМАНДУ.
Финиш/ошибка STT → `startStandbyMode()` → `engine.start()`.
Двух захватов микрофона нет по построению. Self-trigger невозможен
архитектурно: chime и TTS звучат при остановленном движке.

## Честные ограничения

1. **Ring buffer нельзя скормить системному STT** — у SpeechRecognizer нет
   API приёма байтов. Кольцо 640 мс используется для метрик и будущего
   локального STT; зазор маскируется UX chime-then-speak.
2. **Холодный старт ~1.3 с**: после `reset()` первые кадры не детектят,
   пока не накопятся 16 эмбеддингов (вместо прогрева случайным шумом в эталоне).
3. **Только 16 кГц**: ресемплинг не реализован; AudioRecord запрашивает 16000.
4. **Одна модель**: акустика v0.1 детектирует «Hey Jarvis» (openWakeWord v0.5.1).
   Кастомный «Omni» — фаза 2 (`training/`; retrain BLOCKER).
5. Точность/батарея/FA — NOT TESTED до прогона `06-wakeword-metrics.sh` на устройстве.

## Конфигурация (DataStore `wakeword.*`)

| Ключ | Дефолт | Смысл |
|---|---|---|
| `wakeword.enabled` | true | мастер-выключатель |
| `wakeword.threshold` | 0.5 | порог скора (дефолт oWW; точное значение — по протоколу ниже) |
| `wakeword.patience_frames` | 2 | хитов подряд для срабатывания |
| `wakeword.cooldown_ms` | 2000 | тишина после срабатывания |
| `wakeword.debug_logging` | false | скор каждого 25-го чанка + onset в logcat |

UI-ручки настроек — будущая работа; движок читает DataStore напрямую.

## Подбор порога (протокол, на устройстве)

1. Включить `wakeword.debug_logging`, прогнать `06-wakeword-metrics.sh`.
2. Набрать ≥50 произнесений «Hey Jarvis» (фраза акустики v0.1; 3 диктора, тихо/шум/улица)
   и ≥2 ч фонового аудио (ТВ, разговоры без фразы).
3. По скору построить FAR/FRR-кривую; выбрать порог с FRR <5% при FAR <1/час,
   затем проверить patience 1–3 на дубликатах.
4. Зафиксировать значения в этом документе и в дефолтах `WakeWordConfig`.

## Threshold tuning log

| Дата | Порог | Patience | FAR | FRR | Устройство |
|---|---|---|---|---|---|
| — | 0.5 (дефолт oWW) | 2 | NOT TESTED | NOT TESTED | — |
