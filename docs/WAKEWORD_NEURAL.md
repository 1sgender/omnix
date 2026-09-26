# Neural wake word (openWakeWord + ONNX Runtime)

Day-one реализация голосовой активации: предобученная модель
openWakeWord v0.5.1 (акустика v0.1 детектирует legacy-фразу «Hey Jarvis»;
целевая фраза — «Omni», кастомная модель — фаза 2), собственный тонкий
адаптер поверх ONNX Runtime, без STT-верификации (детект подтверждает
нейросеть, не распознанный текст).

> **Update (v0.1.1):** фраза «Omni» теперь детектируется собственной
> моделью `omni_v0.1.onnx` (синтетика Piper TTS, порог 0.35):
> стриминговый recall 0.95 @thr 0.3 / 0.90 @0.4 при FP 3–5% на
> speaker-disjoint негативах. Данные, метрики, ограничения —
> `training/OMNI_V0.1_REPORT.md`; hey_jarvis_v0.1 оставлен как фолбэк.

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
  `WakeWordConfig`, `WakeWordEngineError` (ModelMissing/ModelCorrupted громко
  падают на битой/подменённой модели, MicrophoneDeadSignal — на «мёртвом»
  микрофоне, `resetDetectionSeries` — сброс patience-серии без рестарта).
- `voice/wakeword/NeuralWakeWordEngine.kt` — Android-оболочка: AudioRecord
  16 кГц mono, владение микрофоном только пока запущен; сессии ONNX и
  пайплайн на уровне движка (один раз до destroy, owner review п.3).
- `voice/wakeword/oww/OpenWakeWordPipeline.kt` — чистый стриминг (сессии инжектятся).
- `voice/wakeword/oww/OwwInference.kt` — интерфейсы стадий + контракт.
- `voice/wakeword/oww/OrtOwwSessions.kt` — ORT-реализация (сессии переиспользуются)
  + проверка sha256 ассета ДО загрузки (`ModelDigestMismatch`).
- `voice/wakeword/ModelDigests.kt` — закреплённые в коде sha256 моделей (пин).
- `voice/wakeword/DetectionPolicy.kt` — threshold (+ per-кадр override) + patience
  + cooldown (чистый).
- `voice/wakeword/NoiseAdaptiveThreshold.kt` — шумовой пол (20-й перцентиль
  окна 25 кадров) + буст порога по SNR (чистый).
- `voice/wakeword/DeadSignalDetector.kt` — константный поток = мёртвый
  микрофон (чистый).
- `voice/wakeword/NearMissRecorder.kt` — near-miss WAV-захват для данных
  v0.2: решение о захвате, WAV, манифест, кап хранилища (чистый).
- `voice/wakeword/AudioRingBuffer.kt` — кольцо 640 мс (чистое).
- `voice/wakeword/WakeWordMetrics.kt` — счётчики, средние И P95 задержек
  стадий (чистое).
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
| `wakeword.threshold` | 0.35 | базовый порог скора (0.35 — по отчёту omni_v0.1; точный — по протоколу ниже) |
| `wakeword.patience_frames` | 2 | хитов подряд для срабатывания |
| `wakeword.cooldown_ms` | 2000 | тишина после срабатывания |
| `wakeword.debug_logging` | false | скор каждого 25-го чанка + onset в logcat (+ разбор стадий и шумовой пол) |
| `noiseAdaptiveThreshold` | true | динамический буст порога по шуму (см. ниже) |
| `wakeword.near_miss_capture` | **false** | near-miss WAV-захват для данных v0.2: только явное включение в «Настройки → Для разработчика» И только в dev/staging-сборках (flavor-гейт `BuildConfig.NEAR_MISS_CAPTURE_ENABLED`; в prod запись не существует) |
| `nearMissMinScore` | 0.25 | минимальный скор кадра для near-miss захвата |

UI-ручки настроек — будущая работа; движок читает DataStore напрямую.

## Харденинг (owner review 2026-09-26)

### Пиннинг моделей по sha256 (п.7)
`ModelDigests.EXPECTED` держит sha256 всех четырёх ONNX-ассетов в КОДЕ
(`SHA256SUMS` в assets — контроль бильда, его можно подменить вместе с
моделями). `OrtOwwSessions.openSession` проверяет дайджест байтов ДО
`createSession`: подмена/повреждение → `WakeWordEngineError.ModelCorrupted`
(ожидание и факт дайджеста) → оркестратор показывает Error-состояние. Тест
`ModelDigestsTest.expectedMatchesActualAssetFiles` держит пин в синхроне с
файлами: сменили модель в assets без обновления пина — CI падает.

### Динамический порог по шуму (п.2b)
`NoiseAdaptiveThreshold` считает RMS каждого 80-мс чанка (один проход,
без аллокаций), шумовой пол — 20-й перцентиль окна 25 кадров (2 с): он не
гоняется за громкой речью, но быстро оседает в тишине. Кадр с SNR ≥ 10 дБ —
базовый порог; ниже — линейный буст до +0.10 при SNR = 0 дБ; кадр тише пола
буста не даёт. Эффективный порог жмётся в [0; 0.6]. Выключается
`noiseAdaptiveThreshold=false` (тогда — статичный `threshold`, поведение
до ревью). Per-user калибровка (3–5 произнесений при первом запуске) —
отдельная задача с UI.

### Мёртвый микрофон (п.5)
`DeadSignalDetector`: окно 250 чанков (20 с); если размах (max−min) ВСЕГО
окна ≤ 4 LSB — поток константа (баг прошивок носимых устройств: AudioRecord
«живой», микрофон глухой). Живая тишина всегда несёт ADC-джиттер (размах
десятки LSB) — ложных срабатываний на тишине нет. Детект →
`WakeWordEngineError.MicrophoneDeadSignal` → Error-состояние вместо
молчаливой работы вслепую.

### Потеря аудиопути (п.6)
Отключение клипа/наушников (не-headset-only режим) →
`wakeWordEngine.resetDetectionSeries()`: недодетектированная patience-серия
сбрасывается явно — «Omni», ударенное в старой акустике, не подтвердится в
новой. В headset-only режиме standby и так останавливается (`stopAll`).

### Задержки по стадиям + p95 (п.4)
`WakeWordMetrics.Snapshot` несёт средние И p95 задержки mel/embedding/
classifier (окно 64 кадра): среднее маскирует всплески (GC, теплота CPU),
p95 их показывает — узкое место для INT8-квантизации видно точечно.
Debug-лог дополнен разбором стадий: `score=… infer=…ms (mel=… emb=… clf=…)
thr=… floor=…` — формат `infer=NNNms` для 06-скрипта сохранён.

### Near-miss захват для v0.2 (п.1)
Флаг `nearMissCapture` (дефолт **false** — штатно сырое аудио НЕ
записывается). Включается только в двух шагах: явное опциональное
включение в «Настройки → Для разработчика» (ключ `wakeword.near_miss_capture`)
**и** dev/staging-сборка (flavor-гейт `BuildConfig.NEAR_MISS_CAPTURE_ENABLED`;
в prod запись не существует ни при каком состоянии DataStore — защита в
глубину, гейт — чистая функция `resolveNearMissCapture` с JVM-тестом).
Включённый захват пишет WAV (16 кГц mono, 2.5 с хвост до кадра) на кадры со
скором ≥ 0.25, которые НЕ стали детекцией: это будущие negative-ы retrain'а
на реальных голосах/шуме, ценнее синтетических adversarial. Приватность:
только app-private каталог (`filesDir/nearmiss/`), наружу не покидает
(upload-пути нет), кап 40 МБ (уходят самые старые), манифест
`manifest.jsonl` — только время/скор/модель/порог/SNR, без метаданных
пользователя. Дебаунс 30 с (одна фраза — один файл). Кнопка «Удалить
записи» — немедленный privacy-выход. Рабочий процесс сбора —
`training/COLLECTING.md` §5.

### Холодный старт (п.3) — статус
Инфраструктура готова: сессии ONNX и пайплайн переехали с уровня
runLoop на уровень движка — каждый вход в STANDBY больше не перечитывает
~3.7 МБ ассетов и не создаёт три OrtSession заново. Сами 1.3 с «оглушения»
(16 эмбеддингов контекста) закрываются ТОЛЬКО если движок получает свежее
аудио во время фаз без STT: единственный безопасный слот — `AI_THINKING`
(микрофон свободен, OMNIX молчит; в `TTS_SPEAKING` запуск невозможен —
self-trigger). Это изменение ядра голосового контура — отдельная задача,
здесь намеренно не затронута.

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
