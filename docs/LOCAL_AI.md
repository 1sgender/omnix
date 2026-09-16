# Local AI — on-device LLM (Этап 2)

Локальная модель подключена как **execution backend** уже существующего
`ExecutionDecisionEngine`. Маршрутизация Этапа 1 не изменена.

```
FastCommandRouter
        ↓
ExecutionDecisionEngine          ← НЕ изменён
        ↓
LocalAiExecutor (порт Этапа 1)
        ↓
CompositeLocalAiExecutor
        ├── WorkflowExecutor      — процедурная память (макросы, мгновенно)
        └── OnDeviceLocalAi       — локальная LLM
                ↓
        LocalModelManager         — lifecycle, lazy load, unload, автозагрузка
                ↓
        LocalModelRuntime         — интерфейс инференса
                ↓
        MediaPipe LLM Inference   — нативный движок
```

---

## 0. ExecutionRouter: локальная обработка там, где cloud не нужен

Цель — **НЕ** «локальная LLM решает всё». Цель — каждая полоса получает
ровно тот класс запросов, для которого облако не требуется. Роутер — это
уже существующие `FastCommandRouter` + `ExecutionDecisionEngine` (новых
слоёв не вводилось):

| Полоса (spec) | Реализация в коде | Примеры |
|---|---|---|
| **LOCAL TOOL** | `FastCommandRouter.route()` → `ExecutionDecisionEngine.tryDeviceTool()` (P1, `DecisionReason.FAST_ROUTER_CONFIDENT`); без LLM вообще | «Открой Telegram», «Громкость 50%», «Поставь таймер», «Включи Bluetooth» |
| **LOCAL AI** | `OnDeviceLocalAi` (Qwen, авто-загрузка) + `WorkflowExecutor` (процедурные макросы) через `LocalAiExecutorAdapter` (P2); AGENT-полоса (P4, `CognitivePlanner` + локальные tools) — многошаговые планы тоже исполняются локально | классификация, простая интерпретация, **короткий перевод** (`LocalLlmTranslationProvider`), разрешение контекста (`WorkingMemory`/`AnaphoraContextEngine`), memory retrieval (локальная Room) |
| **CLOUD** | `runCloud()` (P3) + облачный `LlmTranslationProvider` | reasoning, research, long documents (перевод >500 символов), требует сеть (`requiresWeb`), большой контекст, всё, что локальные полосы честно не взяли |

Ключевые свойства (все закреплены тестами/инвариантами):

- **Local-first с честным fallback**: локальные полосы пробуются первыми;
  их неуспех (`Uncertain`/`Unsupported`/`ModelUnavailable`) передаётся
  следующей полосе — никакая не изображает успех (doctrine Fake-Success).
- **Перевод**: `LiveTranslatorEngine` сортирует провайдеры
  `sortedByDescending { isOffline }` — локальная модель (`local_llm`) отвечает
  первой, облако (`llm`) — fallback. Бонус приватности: PRIVATE/SENSITIVE
  тексты, которые облако блокирует (C-02), локальный провайдер переводит
  на устройстве.
- **Приватность сохраняет приоритет**: PRIVATE/SENSITIVE без согласия
  не доходят до облака в любой полосе (privacy-гейты engine'а).
- **Модель не в APK** (~521 МБ, скачивается сама): без модели и пока она
  качается локальные полосы честно сообщают `Unsupported`, продукт работает
  через облако как раньше.

### Метрики ExecutionRouter (`ExecutionRouterMetrics`)

Считается каждый запрос через `ExecutionDecisionEngine.execute`:

| Счётчик | Что это |
|---|---|
| `total_requests` | все запросы (включая отказы/уточнения) |
| `tool_requests` | полоса LOCAL TOOL |
| `local_requests` | полоса LOCAL AI (+`agent_requests`, +`direct_requests`) |
| `cloud_requests` | реально отправленные в облако (attempt) |
| `failed_local` | LOCAL AI честно отказал (без эскалации) |
| `cloud_escalations` | облако взяло запрос, который локальная полоса опросила и не взяла (skip по `requiresWeb` — НЕ эскалация) |

**Local Execution %** = (tool + agent + local + direct) / total · 100
**Cloud Execution %** = cloud / total · 100

Целевой ориентир первой версии — **Tool/local execution 60–70%+**. Это
метрика, а не жёсткое требование: на роутинг она не влияет; 80%+ без
ухудшения качества — отлично; деградация качества ради процента запрещена.
Проценты за период — в логе (`ExecRouterMetrics`, каждые 25 запросов) и в
`snapshot()` для будущего экрана диагностики.

### Voice Latency (`VoiceLatencyMetrics`): P50/P95/P99 по сегментам

```
Wake → STT → Router → AI → Tool → TTS
```

| Сегмент | Точка измерения |
|---|---|
| WAKE→STT | `VoiceInteractionOrchestrator`: детект wake-word → финальный STT |
| STT→Router | execution engine: `ExecutionRequest.originTimestampMs` → вход в роутинг (включает классификацию приватности и память) |
| Router→(dispatch) | execution engine: вход → выбрана полоса (FastCommandRouter + planner + preflight) |
| **AI** | длительность AI-фазы — **всегда с разрезом LOCAL/CLOUD** (LOCAL_AI/AGENT → LOCAL; CLOUD_AI → CLOUD) |
| Tool | исполнение DEVICE_TOOL-команды (LOCAL) |
| Tool→TTS | оркестратор: результат → вызов озвучки |

Хранение — кольцевой буфер 256 значений на серию; перцентили P50/P95/P99 в
`snapshot()` и периодической лог-сводке (AI LOCAL против AI CLOUD —
«реальная разница» из постановки). Часы — monotonic
(`SystemClock.elapsedRealtime`). Мусорные значения (>120 c) отбрасываются.
Цель «Simple command → local → response максимально быстро» проверяется
этими цифрами напрямую: local p95 против cloud p50.

---

## 1. Выбор runtime

| Runtime | Android | CPU | GPU/NPU | Модели | Интеграция с Kotlin | APK impact | Сложность | Лицензия | Вывод |
|---|---|---|---|---|---|---|---|---|---|
| **MediaPipe LLM Inference** (`tasks-genai`) | API 24+ | да | GPU (OpenCL), авто-fallback | Gemma 1/2/3, Qwen2.5, Phi-2, StableLM, Falcon | готовый Java/Kotlin API, AAR из Maven | ~26 МБ (arm64) | низкая | Apache 2.0 | **выбран** |
| llama.cpp (JNI) | любой | да, лучший на CPU | частично (Vulkan) | максимум форматов GGUF | нужен свой JNI-слой + NDK-сборка | 2-5 МБ + своя сборка | высокая | MIT | отклонён: нужен собственный C++/JNI и CI-сборка NDK |
| ONNX Runtime Mobile | API 21+ | да | NNAPI/QNN | ONNX; LLM-путь сырой | Java API есть | 10-20 МБ | средняя | MIT | отклонён: генеративный LLM-путь заметно менее готов, чем classification |
| MLC LLM / TVM | API 24+ | да | Vulkan | свой формат | нужна сборка из исходников | большой | высокая | Apache 2.0 | отклонён: сборка из исходников в CI, нет готового Maven-артефакта |

**Почему MediaPipe.** Решающими были три фактора, а не популярность:

1. **Готовый Maven-артефакт** — `com.google.mediapipe:tasks-genai` ставится
   одной строкой. Остальные варианты требуют NDK-сборки в CI, которой у
   проекта сейчас нет (GitHub Actions собирает обычный `assembleDebug`).
2. **Реальная отмена инференса** — в API есть
   `LlmInferenceSession.cancelGenerateResponseAsync()`. Это прямое требование
   пункта 18 ТЗ; проверено по фактическому API из AAR, а не по документации.
3. **Автоматический выбор бэкенда** — `Backend.DEFAULT` пробует GPU и
   откатывается на CPU. Жёстко фиксировать GPU нельзя: на части устройств
   инициализация OpenCL-делегата падает.

### ⚠️ Известный риск: API помечен deprecated

Начиная с **tasks-genai 0.10.33** Google пометил `LlmInference`,
`LlmInferenceSession` и `ProgressListener` как `@Deprecated` — идёт миграция
на LiteRT-LM. При этом:

- артефакта `com.google.ai.edge.litert:litert-lm` на Google Maven **ещё нет** —
  то есть цели миграции пока не существует;
- сам API полностью работоспособен.

Поэтому в `MediaPipeLlmRuntime.kt` стоит **точечный** `@file:Suppress("DEPRECATION")`
(в одном файле, не глобально — у проекта включён `warningsAsErrors`).

Когда LiteRT-LM появится в Maven, менять нужно будет **только**
`MediaPipeLlmRuntime.kt`: контракт `LocalModelRuntime` не изменится, а
`ExecutionDecisionEngine` не знает о существовании MediaPipe вообще.

---

## 2. Выбор модели — Qwen2.5-0.5B-Instruct (dynamic-int8, multi-prefill)

| Критерий | Значение |
|---|---|
| Размер файла | **521 МБ** (546 660 344 байта) |
| RAM (RSS) | ~1.36 ГБ (замер Google, CPU) |
| Контекст | 1280 токенов (KV-кэш файла `ekv1280`) |
| Decode | ~30 ток/с (CPU) |
| Prefill | ~250 ток/с (CPU) |
| Time-to-first-token | ~2.3 с (CPU) |
| Языки | русский — штатно поддерживаемый |
| Лицензия | **Apache 2.0** |

Источник цифр — официальная карточка
[litert-community/Qwen2.5-0.5B-Instruct](https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct)
(замеры на Samsung S24 Ultra, multi-prefill).

**Почему Qwen2.5-0.5B, а не Gemma 3 1B.** Изначально планировалась Gemma 3 1B
IT (529 МБ), но она распространяется через gated-репозиторий с обязательным
click-through лицензии Gemma Terms of Use — приложение не может скачать её
само, только разработчик вручную через adb. Qwen2.5-0.5B-Instruct под Apache 2.0
лежит открыто и прямо заявлен как совместимый с MediaPipe LLM Inference API,
поэтому выбран он: автозагрузка без ручных шагов важнее, чем разница в
1B-параметрах для коротких ответов ассистента.

**Почему именно 0.5B, а не 1.5B.** Ассистент работает в фоне рядом с STT, TTS,
Room и Compose UI. Qwen2.5-1.5B в q8 — это ~1.5 ГБ файл и ~2.5+ ГБ RSS, что на
устройстве с 6-8 ГБ приведёт к тому, что Android убьёт процесс в фоне. 0.5B
укладывается в ~1.36 ГБ и оставляет запас остальному приложению.

---

## 3. Доставка модели: PAD-пак (Play) или докачка (sideload)

521 МБ нельзя класть в базу AAB: лимит Google Play — 200 МБ для базового
модуля. Поэтому два пути:

- **Play-установка**: модель едет install-time asset pack'ом `localmodel`
  (fast-follow не подходит — его лимит 512 МБ). Пак виден через обычный
  AssetManager без Play-библиотеки; менеджер ставит его локально при старте
  без сети и без согласия (`PackModelLocator`). Ноль действий пользователя.
- **Sideload-APK** (GitHub-сборки): пака нет — работает сетевая докачка
  ниже. Первый запуск без модели — штатное `NotInstalled` → Cloud AI.

Схема докачки (реализована, а не запланирована):

```
после активации → одноразовый диалог (сейчас / по Wi-Fi / позже)
        ↓ согласие дано
DownloadManager качает 521 МБ → прогресс в шторке + в настройках
        ↓ финиш
сверка размера байт в байт → файл готов, грузится лениво при запросе
```

- **Одноразовое согласие** (`SettingsDataStore.localModelConsentFlow`):
  `unasked / any / wifi / later`. Диалог показывается один раз после
  активации (`MainActivity`); «Позже» больше не спрашивает — дальше
  управление только из AI-раздела настроек.
- **Загрузка переживает процесс**: очередь принадлежит системе. После
  перезапуска менеджер переподключается по сохранённому downloadId.
- **Wi-Fi-режим** реализован средствами DownloadManager
  (`setAllowedOverMetered(false)`) — система сама ждёт unmetered-сеть.
- **Целостность**: точный размер `expectedSizeBytes` из `LocalModelSpec`
  сверяется после финиша; несовпадение → `DownloadFailed`, битый файл
  удаляется и в рантайм не попадает.
- **Пока качается** — запросы возвращают `Unsupported` и спокойно уходят в
  Cloud AI (состояния `Downloading` / `DownloadFailed`).

Отсутствие модели остаётся **штатным состоянием**
`LocalModelState.NotInstalled`. Ошибки пользователь не увидит.

### Ручная установка для разработки (fallback)

```bash
# 1. Скачать (репозиторий открытый, логин не нужен)
#    https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct
#    файл: Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task

# 2. Положить во внутреннее хранилище приложения
adb push Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task /data/local/tmp/
adb shell run-as com.omnix.assistant mkdir -p files/llm
adb shell "cat /data/local/tmp/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task | run-as com.omnix.assistant tee files/llm/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task > /dev/null"

# 3. Проверить (размер обязан совпасть байт в байт)
adb shell run-as com.omnix.assistant ls -la files/llm/
# -rw-rw---- ... 546660344 ..._multi-prefill-seq_q8_ekv1280.task
```

Ожидаемый путь:
`/data/data/com.omnix.assistant/files/llm/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task`
(для staging-сборки пакет — `com.omnix.assistant.staging`).

---

## 4. Жизненный цикл модели

```
первый Local AI запрос → initialize() → загрузка (~1-3 с) → Ready
        последующие запросы → инференс без перезагрузки
        onTrimMemory / onLowMemory → unload()
        idle 5 мин → unload() (return idle, см. docs/BATTERY.md)
```

- **Lazy**: при старте приложения модель НЕ грузится — иначе +1.3 ГБ RSS и
  секунды к startup у пользователей, которые локальной моделью не пользуются.
  Скачанный файл тоже не грузится заранее: состояние `NotInitialized`
  означает «файл есть, включится при первом запросе».
- **Один экземпляр**: `Mutex` в `MediaPipeModelManager` не даёт параллельным
  запросам загрузить модель дважды.
- **Memory pressure**: менеджер подписан на `ComponentCallbacks2`; при
  `TRIM_MEMORY_RUNNING_LOW` и выше нативные ресурсы освобождаются.
- **Проверка RAM перед загрузкой**: если `ActivityManager.MemoryInfo.lowMemory`
  или доступно меньше `minRuntimeMemoryMb`, загрузка не начинается.

---

## 5. Границы ответственности Local AI

| Ситуация | Поведение | Итог |
|---|---|---|
| `requiresWeb = true` | не вызывается вовсе | `Unsupported` → Cloud AI |
| `requiresDeviceControl = true` | не вызывается | `Unsupported` → device path |
| device-команда («Открой Telegram») | до Local AI не доходит — забирает FastCommandRouter | `DEVICE_TOOL` |
| `privacyLevel = PRIVATE` | **обрабатывается локально** | `LOCAL_AI`, облако не вызывается |
| модель не установлена | `Unsupported` | Cloud AI |
| модель скачивается | `Unsupported` (с прогрессом в причине) | Cloud AI |
| загрузка не удалась | `Unsupported` (повтор из настроек) | Cloud AI |
| runtime упал | `Error` | `ExecutionResult.Error`, без эскалации в облако |
| запрос > 1200 символов | `Unsupported` | Cloud AI |

Локальная модель **только генерирует текст**: не запускает Activity, не жмёт
кнопки, не меняет настройки, не ходит в сеть. Это закреплено и в system prompt,
и в guard-условиях `OnDeviceLocalAi`.

**Почему `Error` не эскалируется в облако.** Движок Этапа 1 детерминирован
(пункт 16 ТЗ): один проход по цепочке. «Модель сломалась» — честная ошибка, а
не повод молча отправить, возможно приватный, запрос в сеть. Ситуация «модели
просто нет» — это `Unsupported`, и она в облако уходит штатно.

---

## 6. Threading и отмена

- Инференс идёт на `dispatchers.default` (существующий `CoroutineDispatchers`),
  никогда на Main.
- Загрузка модели — тоже на `default`.
- Опрос DownloadManager — раз в секунду из `lifecycleScope`, только пока
  идёт загрузка.
- Отмена корутины → `session.cancelGenerateResponseAsync()` через
  `invokeOnCancellation`. `CancellationException` пробрасывается наружу, а не
  превращается в `Error`.

---

## 7. Логи

Тег `LocalAI`:

```
model download started | id=42 | meteredOk=true
model downloaded | bytes=546660344
model = qwen2.5-0.5b-instruct-q8 | runtime = mediapipe-llm | loaded = true | loadTimeMs = 1842
inference started | runtime=mediapipe-llm | source=VOICE | privacy=NORMAL | maxTokens=192
inference completed | latencyMs=2310 | ttftMs=780 | promptChars=612 | responseChars=214 | ~tok/s=37.1
```

Содержимое запроса не логируется. Приватный текст дополнительно скрывается
через `ExecutionRequest.loggableText` (`<redacted:N chars>`).

---

## 8. Benchmark

Замеры на реальном устройстве в этой среде **не проводились** — Android SDK и
физического устройства в CI-песочнице нет. Приведённые в разделе 2 цифры взяты
из официальной карточки модели, а не измерены нами.

Что нужно измерить на реальном устройстве перед релизом:

```
model load time      (ожидание: 1-3 с, зависит от storage)
time to first token  (ожидание: ~2.3 с CPU)
decode tokens/sec    (ожидание: ~30 CPU)
peak RSS             (ожидание: ~1.36 ГБ)
```

Метрики уже собираются в коде (`InferenceMetrics`) и пишутся в logcat — на
устройстве достаточно снять `adb logcat -s LocalAI`.

---

## 9. Android-валидация

| Пункт | Статус |
|---|---|
| minSdk | 29 — выше требования MediaPipe (24) |
| ABI | `arm64-v8a`, `armeabi-v7a`, `x86_64`; `x86` исключён |
| Размер native libs | ~26 МБ arm64, ~19 МБ armeabi-v7a, ~29 МБ x86_64 |
| APK impact | +26 МБ на arm64-устройстве (при использовании ABI splits) |
| Модель в APK | нет — автозагрузка через DownloadManager (521 МБ) |
| R8 / ProGuard | правила добавлены (JNI-классы MediaPipe, protobuf, Guava) |
| Эмулятор | x86_64 поддержан; GPU-делегат на эмуляторе обычно недоступен → CPU |
| Фоновая работа | инференс запускается только по запросу пользователя |

---

## 10. Файлы

Создано:

- `agent/localai/LocalAiModels.kt` — `LocalAiResult`, `GenerationConfig`,
  `InferenceMetrics`, `LocalModelState`, `LocalModelSpec`
- `agent/localai/LocalAiContracts.kt` — `LocalAi`, `LocalModelRuntime`,
  `LocalModelManager`, `LocalPromptBuilder`
- `agent/localai/OmniLocalPromptBuilder.kt` — ChatML-шаблон Qwen2.5 + system prompt
- `agent/localai/OnDeviceLocalAi.kt` — правила и классификация исходов
- `agent/localai/downloader/ModelDownloader.kt` — `ModelDownloader`,
  `DownloadManagerModelDownloader`, `ModelDownloadPolicy`
- `agent/localai/mediapipe/MediaPipeModelManager.kt` — lifecycle + автозагрузка
- `agent/localai/mediapipe/MediaPipeLlmRuntime.kt` — инференс + отмена
- `presentation/localmodel/LocalModelConsentDialog.kt` — одноразовый диалог согласия
- `presentation/settings/LocalModelSettingsBlock.kt` — статус и управление в AI-разделе
- `agent/decision/LocalAiExecutorAdapter.kt` — `CompositeLocalAiExecutor`
- тесты: `OnDeviceLocalAiTest` (16), `LocalAiRoutingIntegrationTest` (8),
  `ModelDownloadPolicyTest` (6)

Изменено:

- `di/HiltModules.kt` — модуль `LocalAiModule`, порт переключён на
  `CompositeLocalAiExecutor`
- `agent/decision/ExecutionAdapters.kt` — удалён `ProceduralLocalAiExecutor`
  (его роль поглотил `CompositeLocalAiExecutor`)
- `data/preferences/SettingsDataStore.kt` — согласие + downloadId
- `presentation/MainActivity.kt` — одноразовый диалог после активации
- `presentation/settings/SettingsSectionRoute.kt`, `SettingsViewModel.kt` —
  строка локальной модели в AI-разделе
- `app/build.gradle.kts`, `gradle/libs.versions.toml` — зависимость + abiFilters
- `app/proguard-rules.pro` — правила R8

**Не изменялись:** `ExecutionDecisionEngine`, `FastCommandRouter`,
`ToolExecutor`, `OmniTool`, `AgentCognitiveLoop`, `CognitivePlanner`, STT, TTS.
