# Changelog

All notable repository changes are recorded here. The project did not contain a
release changelog or authoritative release tags in the provided workspace, so
entries are grouped under **Unreleased** rather than inventing published
versions or dates.

The format is based on Keep a Changelog, but semantic-version release history
must be added only when the repository owner creates an actual release.

## [Unreleased]

### Fixed
- **§34: отключившийся Clip больше не запирает шаг подтверждения.** Если Clip терял связь на шаге ClipPairing (села батарея, ушёл из радиуса), фаза уходила в LOST («Clip не найден»), а действия рендерились только для FOUND — экран оставался без единой кнопки, путь вперёд исчезал. Теперь оба шага клип-флоу делят одни действия по фазам: SEARCH — тихое «Пропустить», LOST — «Искать снова / Ввести код активации / Пропустить», FOUND — «Продолжить»; таймаут поиска действует и на подтверждении (ретрай оттуда тоже заканчивается «не найдено», а не вечной дугой). Дубль-строка `omnix_clip_continue` (идентична `omnix_continue` во всех трёх локалях) удалена.

### Changed
- **Поиск Clip больше не крутится вечно (мок: поиск завершается «не найден»).** Без таймаута LOST был достижим только через BT-off или устаревший Disconnected — экран «Clip не найден» с «Искать снова» и ссылкой на ввод кода почти никогда не показывался. Теперь через 30 с без результата фаза честно переходит в LOST (значение — именованная константа `CLIP_SEARCH_TIMEOUT_MS`, дизайнер может калибровать); фоновая проверка продолжается — подключившийся Clip мгновенно покажет FOUND; активное подключение (Connecting) таймаут не прерывает; «Искать снова» открывает новое 30-секундное окно. Чистый маппер `clipVisualPhase(clip, searchTimedOut)` — 4 новых JVM-теста; фаза кольца теперь вычисляется в экране один раз и течёт через слоты (кольцо/копия/кнопки видят одно состояние).

- **Карта зон в HANDOFF-second-developer.md актуализирована, документ переехал в репо** (раньше существовал только вне репозитория — второй разработчик не мог его прочитать; протокол §4.3.1): онбординг (`firstrun/`, `activation/`), дизайн-токены (`OmnixColors.kt`, `OmnixTheme.kt`) и `OmnixButton.kt` закреплены за агентом после мок-волны PR #96–#106; экран настроек «Me» закреплён за вторым разработчиком (PR #103). Документ больше не называет FirstRunScreen/ActivationScreen «свободными».

### Fixed
- **Активация: кнопка больше не прячется под клавиатурой.** Порт мока убрал скролл старого экрана — на низком экране с открытой IME кнопка «Активировать» могла уйти под клавиатуру (регрессия против старой скроллируемой версии). Структура теперь двухконтурная: контент (знак, заголовок, ячейки, ошибка) — в скроллируемой колонке с весом, кнопка — закреплена под ней, над клавиатурой (`adjustResize` в манифесте). На высоком экране поведение мока сохранено: кнопка прижата к нижнему краю.

### Changed
- **Активация по моку 2026-09-25 (6 ячеек):** одно поле в рамке заменено шестью ячейками 38×48dp с подчёркиванием 2dp (fill-уровень; под фокусом и в полном коде — ink, при ошибке — err, переход 0.2s) — длина кода видна сразу, курсор сам идёт к следующей цифре, вставка шести цифр заполняет всё, бэкспейс в пустой ячейке уходит назад. Кнопка «Активировать» выключена (surfaceFilled + приглушённый текст), пока код не введён целиком — явное состояние вместо «серой, но нажимаемой». При ошибке цифры и линии красные, снизу короткое сообщение («Такой код не найден. Проверьте цифры и попробуйте снова.») с liveRegion для скринридера. Декоративное кольцо и вордмарка убраны: верх — знак 26×20 (параметризован), заголовок 22sp/600 и подзаголовок 15sp серым — легче и тише клип-экрана, как в моке. Ссылка «Отсканировать камерой» НЕ перенесена — функции сканирования в приложении нет (разрешение дизайнера в письме мока). Новые токены: `surfaceFilled` (уровень заливки, --faint моков: #2C2C2E/#E5E5EA/#ночь #1C1C1E) и параметры выключенных цветов у `OmnixPrimaryButton` (по умолчанию — прежние). Логика ячеек вынесена в чистые `sanitizeActivationInput`/`cellInputResult` с JVM-тестами. **Открытый продукт-вопрос (в PR):** домен принимает коды 8–64 символа (`MIN_CODE_LENGTH=8`), мок предполагает короткий 6-значный код с карточки — шести ячеек недостаточно для существующих длинных кодов; нужно решение владельца/бэкенда (короткие box-коды или другой формат ввода).

### Added
- **A11y-семантики мока подключения Clip:** копирайт клип-флоу — «живая» область (`liveRegion = Polite`, аналог aria-live="polite" из мока): смена фазы «Поиск → Не найден → Подключён» объявляется скринридеру без взгляда на экран; знак-логотип получил contentDescription «OMNIX» (role="img" aria-label="OMNIX" из мока) — до этого знак для TalkBack был невидимкой. Кольцо остаётся декоративным (в моке — aria-hidden): состояние несёт текст, не картинка (§28).

### Fixed
- **«Ввести код активации» — честное действие вместо тихого прыжка:** ссылка на экране «Clip не найден» прыгала на шаг микрофона, не показывая ввода кода. Теперь она открывает настоящий `ActivationScreen` внутри потока онбординга (онбординг идёт ДО гейта лицензии — код из комплекта Clip активирует лицензию прямо здесь): кроссфейд по motion-токенам, назад — системным жестом к живому Bluetooth-поиску (поиск не останавливается), успешная активация ведёт на шаг микрофона — тот же, что и «Пропустить», но заработанный реальным действием. Вопрос дизайнеру зафиксирован в PR: если «код активации» в моке означает код ПАРИНГА Clip (не лицензии) — для этого нет бэкенд-механизма, нужна отдельная спека.

### Changed
- **Clip-флоу по пересмотру мока (2026-09-25):** композиция выправлена под HTML — кольцо стало «сценой» между знаком-лого и копирайтом (заголовок/подзаголовок теперь ПОД кольцом, не над ним); зона действий фиксированной минимальной высоты 150dp с содержимым, прижатым к низу (margin-top 22dp, нижний отступ 28dp) — фазы меняются, кольцо и копирайт стоят на месте; зазор заголовок→подзаголовок 10dp. Ссылка «Ввести код активации» — системный синий: новый токен `actionLink` (#0A84FF тёмная / #0071E3 светлая; дизайнер подтвердил системный вместо брендового #3D7ECB — вопрос из PR #100 закрыт). Слоты композиции выделены в `StepHeadingSlot`/`StepCoreSlot`/`StepActionsSlot`: клип-флоу и обычные шаги онбординга расходятся только порядком блоков, содержимое слотов общее. Тема по-прежнему следует системе (OmnixAppearance.System) — светлый/тёмный вариант мока покрывается существующими схемами. Не-клип шаги не затронуты (заголовок над кольцом, мок онбординга).

### Added
- **Экран подключения Clip по HTML-моку (2026-09-24):** декор убран — тонкое кольцо 132dp (трек faint 2dp + дуга ink 2dp, круглая кромка) вместо ядра на флоу DeviceDetection/ClipPairing. Фазы: SEARCH — дуга 22% бежит по кольцу 1.4s linear (iOS-индикатор); LOST — дуга исчезает, в центре тихий серый «!»; FOUND — кольцо замыкается (dash 100%) и проявляется галочка (задержка 0.25s). Переходы dash 0.6s cubic-bezier(.2,.8,.2,1) / opacity 0.4s; reduced-motion останавливает. Копия по фазам: «Поиск Clip / Держите Clip рядом», «Clip не найден / Убедитесь, что Clip заряжен, а Bluetooth включён» (чек-лист → одна строка), «Clip подключён / Всё готово». Кнопки: белая капсула «Искать снова», синяя ссылка «Ввести код активации», серое «Пропустить» (15sp). Логотип флоу — маленький знак-кольцо с орбитой, без надписи. Типографика флоу: 28sp/600 трекинг −0.022em + 17sp серый. Отклонение от мока: на время поиска остаётся тихое «Пропустить» — §34 не даёт запереть пользователя. Маппер `clipRingPhase` — JVM-тест на все 8 состояний ClipState.
- **Единая композиция онбординга (мок Welcome → все шаги):** заголовок и подзаголовок каждого шага стоят НАД кольцом (блок 82%, AnimatedContent с тем же кроссфейдом), под кольцом — только действия. Кольцо не двигается между шагами — заголовок обновляется на месте (§30 «один объект реагирует»); Welcome сохраняет брендовый синий и волну, остальные шаги говорят состоянием ядра. `StepScaffold` → `StepHeading` + отдельные Actions-композаблы на шаг; все строки и действия шагов без изменений.
- **Калибровка Welcome по повторному рендеру мока (2026-09-24):** точные цвета из PNG — `accentBrand` #3D7ECB, `accentBrandSoft` #8FB8E6; волна — 4 тонких столбика (было 7), 30%×40% диаметра кольца, крайние приглушены до ~88% яркости (огибающая красит и цвет); активная точка прогресса — светлая капсула 20dp (синий остался только на кольце), неактивные — едва заметные нейтральные.
- **Welcome-онбординг по моку дизайнера (2026-09-24), 6 поправок:** брендовый синий `accentBrand` #3878C8 (в тон лого) + мягкий `accentBrandSoft` #88B8E0 — единственный цветный акцент вне монохрома и красного ERROR; кольцо Welcome несёт смысл — синее, с анимированной волной «слушающих» столбиков внутри (`OnboardingWaveform`: детерминированные фазовые синусы, reduced-motion замирает; иллюстрация, не слой данных — контраст с реальной амплитудой §33); заголовок «Голос в вашем ухе» и подзаголовок над кольцом в одну композицию с ограничением ширины 82% (логичный перенос без обрыва слова); точки прогресса «шаг 1 из 3» вместо подчёркивания (`OnboardingProgressDots`, три вехи: знакомство/настройка/первая команда, a11y — одной меткой); вертикальный баланс weight(1f) вокруг кольца; кнопка — запас xxxl от жест-бара. Для бренд-момента у `OmnixCore` появился явный `ringColor`-override, состояние ядра на остальных экранах не затронуто. TK-строка a11y — на вычитку носителем.

### Added
- **Дуга OTA на ядре (матрица слоёв):** `OtaDownloadMonitor` — живой ход загрузки OTA от реального опроса DownloadManager (1 Гц, независимо от видимости диалога обновления: скрыли окно — дуга продолжает показывать прогресс; успех/ошибка/исчезновение записи гасят её). Сигнал проведён через `OmnixUiState.otaDownloadProgress` (доля 0..1; `null` при неизвестном итоге — дуга без знаменателя это ложный прогресс) в `OmnixCore.progress`; показывает только в IDLE (контракт CoreLayers). Диалог обновления не переписан: монитор подключён хирургически в точках старта/завершения загрузки. Попутно исправлена неточность PR #90: OTA-самообновление (70ccd96) УЖЕ встроено в MainActivity — не встроен был только ход загрузки до ядра.
- **Бейдж CLOUD на ядре (мок №3 дизайнера)**: `CloudProcessingMonitor` — честный сигнал «ответ считается в облаке» от реального трафика, а не от предположения о маршруте: `OmnixApiClient` отмечает начало/конец каждого облачного запроса (счётчик — параллельные голос+чат не гасят бейдж; `finally` покрывает успех, ошибки и отмену корутины). Сигнал проведён через `OmnixUiState.isCloudProcessing`; `HomeScreen` докует `CoreBadge.CLOUD` на занятых состояниях (RECOGNIZING/THINKING/EXECUTING), приоритет над WIFI_OFF. Отказ до сети (невалидный токен) бейджем не считается. JVM-тесты: счётчик, перекрытие запросов, активность в полёте, освобождение при ошибке.

### Changed
- **Заморозка авто-релизов OTA на время редизайна интерфейса** (решение владельца): `signed-release` срабатывает по workflow_run только при `OTA_AUTO_RELEASE=true` (репо-переменная Actions). Приложение обновится одним пакетным релизом по завершении всего интерфейса; ручной workflow_dispatch (хотфикс) по-прежнему доступен. Переменная установлена в `false`.

### Added
- **Слои на ядре (PR-C): wifi-off бейдж на домашнем экране.** По матрице состояний: бейдж «нет сети» докуется на ядро в IDLE — «почему ничего не отвечает» читается до первого слова. Первый реальный потребитель слоя бейджей из PR-A.
- **Слои на ядре (PR-B дизайн-матрицы): амплитудные штрихи по периметру + волна ленты + хвост распознавания.** 11 штрихов вокруг кольца питаются кольцевым буфером реального уровня микрофона (новейший сэмпл сверху, история течёт по часовой, ~0.55 с окна; чистый `AmplitudeRing` с JVM-тестами) — длина и яркость каждого штриха от его сэмпла. Лента LISTENING стала волнистее (9 лопастей вместо 6, амплитуда по-прежнему от громкости). При выходе из аудио-состояний штрихи гаснут за 500 мс (хвост RECOGNIZING), а не обрываются; в reduced motion слой отключён целиком.
- **Слои на ядре (PR-A дизайн-матрицы): дуга-прогресс с цифрой в центре + ортогональные бейджи.** `OmnixCore` принял `progress` (0..1) и `badge` (CLOUD/WIFI_OFF). Дуга разрешена только в IDLE (фоновая OTA-загрузка) и EXECUTING, в THINKING — никогда (у LLM нет честного процента); правила вынесены в чистый `CoreLayers` с JVM-тестами. Бейджи докуются на кольцо под 45° и сосуществуют с любым слоем (амплитуда + cloud при распознавании). Механизм морфинга сохранён: дуга кроссфейдит кольцо, а не подменяет его. Виден в вызывающем коде после встройки сигналов (OTA-прогресс, факт облачной обработки) — следующий PR.
- **Страж нативного краша при генерации (InferenceCrashGuard)** — закрывает незакрытую дыру пути инференса: `addQueryChunk`/`generateResponseAsync` — нативные вызовы, SIGSEGV внутри которых ронял приложение без фолбэка и без следов (страж #81 прикрывал только загрузку модели). Две последовательные смерти процесса в генерации → модель запрещается до перескачивания, чат уходит в облако с сообщением (тот же fallback-контур, что и для ошибок инициализации). Одна незавершённая генерация не банит — её могла устроить система (force-stop/LMK). Маркер сбрасывается при новой загрузке/переустановке/удалении модели.


### Changed
- **Core (визуальное ядро) стал монохромным: состояние передаётся яркостью и темпом, а не цветом.** Все состояния — белый/графит (idle тусклый, рабочие — ярче, thinking пульсирует ~4× быстрее дыхания idle 5.2 с); цвет остался только у ERROR — резкая вспышка + рывок формы + тревожно-красный. Прежние циан/зелёный/жёлтый/синий/фиолетовый оттенки состояний убраны во всех трёх схемах (dark/night/light).


### Added

- Офлайн-льгота лицензии (PR #77): при недоступном сервере (нет сети,
  5xx, 429 — не вердикт) вход по непросроченному кэшу лицензии до конца
  её срока; диск в офлайне не пишется; явные вердикты (Invalid/Expired/
  Revoked/Unauthorized/WrongDevice) блокируют сразу. Тесты:
  OfflineGraceDecisionTest (JVM) + 2 офлайн в LicenseManagerInstrumentedTest.

- Cloud-fallback при провале инициализации локальной модели (PR #79,
  решение владельца 2026-09-21): новый исход `LocalAiResult.FailedToFallback`
  (исключение из runtimeOrNull и LocalModelState.Failed) — запрос уходит
  в облако по обычному privacy-гейту (PRIVATE/SENSITIVE без согласия
  блокируются как раньше), а пользователь видит в истории SYSTEM-сообщение
  «Офлайн-модель недоступна (причина). Отвечаю из облака.» (дедуп по
  причине за сессию VM, не озвучивается, идёт перед ответом). Ошибки
  ИНФЕРЕНСА остаются честным Error; «тихий» InsufficientMemory не тронут.

- SHA-256 верификация файла модели (PR #80, регрессия v97: повреждённый
  .task прошёл проверку по размеру и убил рантайм «Unable to open zip
  archive»): `LocalModelSpec.expectedSha256` (эталон сверен с Hugging Face
  `lfs.oid`), `ModelFileIntegrity` — размер + SHA-256 с sidecar-маркером
  `<file>.sha256` (хеш+длина+mtime: 521 МБ хешируются один раз на файл,
  замена файла инвалидирует маркер). Проверяются все точки: финиш загрузки
  (`DownloadFailed «SHA-256 не совпал»`), initialize (self-healing —
  битый файл удаляется и перекачивается), ensureModel, reattach, пак.

- In-app захват крашей + страж нативного краша модели (PR #81, репорт
  2026-09-22 «приложение выкидывает»): CrashCapture/CrashFileStore —
  непойманные Java-исключения пишутся в files/crash/ (сборка+устройство+
  поток+стек, ротация 5) и включаются в OMNIX DIAGNOSTICS → EXPORT REPORT
  (приложение sideload-ится, других источников стека нет; системный диалог
  сохраняется). ModelInitCrashGuard — маркер вокруг нативного create():
  смерть процесса внутри create() (неловимый SIGSEGV/OOM-kill) блокирует
  повтор для того же файла (Failed → облако + уведомление, краш-луп
  невозможен); каждый новый файл модели = ровно одна свежая попытка
  (загрузка/пак/удаление сбрасывают маркер).

### Changed

- Плагины переведены на алиасы version-catalog (PR #83): версии только в
  `gradle/libs.versions.toml` ([plugins] +3: android-asset-pack, kotlin-jvm,
  detekt); root/app/server/assetpacks — `alias(libs.plugins.*)`; удалено
  мёртвое объявление `com.android.library` (не применял ни один модуль).

### Fixed

- R8 вырезал protо-классы MediaPipe LLM (PR #78): keep-правило для
  `com.google.mediapipe.tasks.genai.llminference.jni.proto.**` — рефлексия
  modelPath_ падала с «Field modelPath_ not found» в release-сборке.

### Removed

- Мёртвый код по ревизии проекта 2026-09-22 (PR #82): неиспользуемый
  импорт `kotlinx.coroutines.flow.map` (OmnixViewModel), зависимость
  `androidx.compose.ui:ui-tooling-preview` (0 использований @Preview во
  всём app/src) + 2 записи из app/gradle.lockfile. Вне git удалены битые
  артефакты скачивания («404: Not Found») и OCR-скратч.

### Added

- Wake word «Omni»: собственная модель `omni_v0.1.onnx` (синтетика Piper
  TTS, 20 голосов, adversarial-негативы) подключена по умолчанию;
  порог 0.35. Стриминговый recall 0.90–0.95, FP 3–5% на speaker-disjoint
  негативах; известные ограничения (нет живой речи в обучении) —
  training/OMNI_V0.1_REPORT.md. `hey_jarvis_v0.1.onnx` сохранён как
  фолбэк до приёмки real-data v0.2.

- Ребрендинг JARVIS → OMNIX / OMNI: пакеты `com.omnix.*`, applicationId
  `com.omnix.assistant`, классы `Omni*` (AI-слой) / `Omnix*` (продукт),
  env `OMNIX_*`, метрики `omnix_*`, коды лицензий `OMX-` (legacy `JRV-`
  принимаются), артефакт `OMNIX-v0.2-dev.apk`. Намеренно сохранено:
  `hey_jarvis_v0.1.onnx` (акустика v0.1 детектирует legacy-фразу «Hey Jarvis»;
  целевая фраза «Omni», phase-2 retrain — BLOCKER),
  `JARVIS-CLIP-ATTEST-v1` (домен-разделитель общий с firmware Clip),
  фолбэк `JARVIS_*`-секретов в release-пайплайне.

- v0.3 Stabilization (docs/V03_STABILIZATION.md): ветка
  release/v0.3-stabilization от main@122ba09, baseline v0.2.0 зафиксирован,
  22 фазы протокола сведены к честным статусам (PASS с evidence / BLOCKED —
  hardware unavailable / NOT MEASURED). Backlog: P0 подтверждённых нет
  (первый release build — потенциальный источник), P1-1 chase-hint FIXED
  (брендовый банк без слова «bank» пропускал full-screen capture банковского
  экрана к LLM; regression test существовал и теперь зелёный), P1-2 release
  build валидирует CI. Scorecard 7/10: контракты stabilized, device-evidence
  отсутствует. 4 V03-STAB-инварианта (157 всего).
- Тестовая матрица финального тестирования (docs/TEST_MATRIX.md): 7 разделов —
  Voice (33 сценария, 51 автотест роутера), Tools (34 тулa по семьям),
  Permissions (11 denied-сценариев), Network (Online/Slow/Offline/Reconnect),
  AI (local/cloud/429/500/fallback), Bluetooth (6 сценариев Ear Mode),
  Security (invalid token/expired/wrong device/fake device/replay/modified
  client). Каждая строка помечена: AUTO (JVM-тест, файл), PG (integration),
  DEVICE (device-validation) или честный GAP (единственный: целостность APK
  на AI-пути без Play Integrity). 7 TEST-MATRIX-инвариантов (153 всего).
- Observability: единый request ID (`omx_01J…`, ULID: время+случайность,
  лексикографическая сортировка) сквозь весь путь запроса —
  Voice → Router → Tool → AI → Server → Provider (docs/OBSERVABILITY.md).
  Раньше OmnixApiClient рождал отдельный UUID на каждый HTTP-вызов —
  клиентский и серверный следы одного запроса не коррелировали. Теперь id
  генерируется один раз (оркестратор на финальном STT / SendPromptUseCase),
  несётся в `ExecutionRequest.requestId` (copy-стабилен), пишется в каждый
  route-лог и metadata результата, уходит на сервер (который уже принимал
  клиентский requestId: ai_usage_records UNIQUE(client_id, request_id) —
  идемпотентность ретраев — и эхо в ответах). Админка: /admin/logs (CLOUD)
  отдаёт колонку requestId. Тексты по-прежнему не логируются — id коррелирует
  только метаданные. 8 тестов (формат ULID, сортировка, уникальность,
  сквозная корреляция движка, серверный surface), 9 OBSERVABILITY-инвариантов.
- Admin Panel: аудит control plane против MVP-дерева (docs/CONTROL_PLANE.md
  §12) — панель уже покрывает Dashboard/Users(+devices,subscription)/Devices/
  Licenses/AI(providers,health,usage)/Requests(cloud)/System в одном JVM без
  BI/CRM/K8s. Закрыт единственный пробел дерева: список лицензий без фильтра
  active/expired — `GET /v1/admin/licenses?status=` (bind-param SQL, неизвестный
  статус = 400, не тихий «показать всё»), UI-вкладки ALL/ISSUED/ACTIVE/EXPIRED/
  REVOKED/DISABLED. 2 теста (surface), 6 ADMIN-инвариантов (137 всего).
- Agent Core: принцип «не использовать агента там, где достаточно Tool»
  (docs/AGENT_CORE.md). Одиночный tool_call из ответа облачной модели больше
  не запускает cognitive loop — идёт прямым путём команды устройства
  (privacy gate → policy → честный итог, `CLOUD_PLAN_SINGLE_TOOL`); агент
  (Plan→Act→Observe→Verify→Replan, MAX_REPLANS=2, бюджет 8 c) остаётся для
  многошаговых планов. Закрыта дыра policy: fail-closed гейт внешнего
  раскрытия (`mayDiscloseExternally`) теперь применяется и к plans из ответа
  модели — раньше он был только у детерминированных планов (LLM предлагает —
  policy решает). 3 теста (одно- vs многошаговый cloud-план, privacy-гейт),
  8 AGENT-CORE-инвариантов (131 всего).
- Memory: три уровня (Conversation / Session / Long-term) + retrieval-стадия
  перед LLM — «Query → Memory retrieval → Relevant memories only → AI»
  (docs/MEMORY.md). Главный фикс: `buildPromptMemoryContext()` существовал,
  но не вызывался ни разу — long-term память доходила до модели только через
  явный RecallMemoryTool (лишний round-trip). Теперь `SendPromptUseCase`
  кладёт top-3 релевантных воспоминаний (≤800 символов) в новый
  `ExecutionRequest.memoryContext`; cloud executor добавляет блок к
  systemPrompt, локальный промпт-билдер — офлайн-модели (recall без сети).
  Память включена в privacy-классификацию: приватный факт из памяти не уходит
  в облако под безобидным запросом. 7 тестов, 7 MEMORY-инвариантов (123 всего).
- Ear Mode: аудит и фиксы прерываний непрерывного переводчика
  (Clip → Bluetooth → SCO → STT → перевод → TTS → Clip), docs/EAR_MODE.md.
  Audio focus: `TextToSpeechManager` держит `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`
  (USAGE_ASSISTANT) на время речи, LOSS → stop — музыка/нотификации больше не
  играют поверх перевода (раньше `requestAudioFocus` не вызывался нигде).
  Disconnect/reconnect: `routeAudioToEarbud` без гарнитуры больше не ставит
  `MODE_IN_COMMUNICATION`+SCO (было — на каждую фразу), выбор устройства
  BT-first (SCO/BLE важнее проводных), ACL-коннект считается наушником только
  при реальном аудиовыходе (часы/машина больше не перехватывают роутинг).
  Phone call: `resumeAfterPhoneCall` восстанавливает `LIVE_EAR_INTERPRETER`,
  экран переводчика ставит/снимает паузу через `orchestrator.currentMode`.
  Microphone: экран переводчика останавливает wake-word AudioRecord на время
  слушания и возвращает его сервису (конфликт захватчиков с Android 10).
  Speaker: выход из Ear Mode/пауза/закрытие экрана возвращают аудиорежим к
  дефолту (`restoreDefaultRouting`). 12 EAR-MODE-инвариантов (116 всего),
  тест политики focus.
- Battery: idle-выгрузка тяжёлой модели («return idle»). `MediaPipeModelManager`
  больше не держит модель (~529 МБ) резидентной навсегда после первого
  инференса (раньше unload был только по memory pressure): новый
  `IdleUnloadScheduler` выгружает её после 5 минут неактивности
  (`modelIdleUnloadMs`); каждый запрос через `runtimeOrNull()` продлевает окно
  (`noteUsed()`), следующий после паузы — лениво перезагружает (~1–3 c). Окно
  больше худшего tool-таймаута (≤4 c) — выгрузка не может закрыть движок
  посреди генерации; `close()` отменяет таймер. Тесты на виртуальном времени
  (runTest). Фазовый аудит батареи (Idle/Wake/Listening/Local
  inference/Bluetooth/TTS/Background): docs/BATTERY.md; 6 BATTERY-инвариантов.
- Voice Latency (`VoiceLatencyMetrics`): сегменты голосового пайплайна
  Wake→STT→Router→AI→Tool→TTS с P50/P95/P99 (кольцевой буфер 256/серия,
  monotonic clock) и обязательным разрезом LOCAL/CLOUD для AI-фазы — видно
  реальную разницу локального и облачного ответа. Точки: оркестратор
  (wake/STT/TTS), execution engine (STT→Router через новый
  `ExecutionRequest.originTimestampMs`, dispatch полосы, длительность AI и
  Tool). На роутинг не влияет. 8 тестов (перцентили, разрез, кольцо,
  интеграция по полосам); 5 VOICE-LATENCY-инвариантов. См. docs/LOCAL_AI.md.
- Cost Control (server): Request → Cost estimation → Budget policy →
  Provider. Класс сложности запроса (SIMPLE/MEDIUM/HARD) детерминирован по
  форме (длина промпта/истории, requiresWeb — `RequestCostEstimator`, без
  LLM); класс через `ProviderRequirements.costClass` переключает веса
  Smart-политики отбора: SIMPLE — цена доминирует (0.5, «cheapest
  acceptable») + урезанный бюджет ответа (≤256 токенов), HARD — качество
  доминирует (надёжность 0.5, цена исключена, «best quality»), MEDIUM —
  прежний баланс. Полная картина «Simple → Local → $0»: короткие команды
  остаются на устройстве (полосы LOCAL TOOL/AI), серверная классификация
  защищает дошедшие запросы. Тесты: классификатор + переключение победителя
  классом при одной и той же раскладке метрик (8 сценариев); 6
  COST-CONTROL-инвариантов. См. docs/CLOUD_ROUTER.md.
- Контракт Fallback-бюджета (server): худший случай платных вызовов на одну
  команду задокументирован и закреплён — maxProviderAttempts=2 ×
  (1+maxRetriesPerProvider=0) = 2 ≤ 3 (spec «max 2–3 attempts»); 429 не
  ретраится у того же провайдера (fallback к следующему), retry только для
  TIMEOUT/CONNECTION/SERVER_ERROR, AUTH/NOT_CONFIGURED permanent; клиент
  повторов не делает (не умножает платные вызовы). Новые тесты: конечность
  комбинированных retry×fallback (2×2), дефолт = ровно 2 платных попытки
  (третий провайдер не трогается); 5 FALLBACK-инвариантов.
  См. docs/CLOUD_ROUTER.md «Fallback и бюджет попыток».
- Smart Cloud Router (server): выбор лучшего провайдера по измерениям, а не
  random/статический приоритет. `ProviderPerformanceTracker` хранит на каждого
  провайдера latency (EMA успешных вызовов), errors (success rate), 429
  (RATE_LIMITED счётчик и доля); availability — circuit breaker
  (`ProviderHealthTracker`), cost — конфигурируемые цены USD/1М токенов из
  admin settings (секция cost, читаются на каждый отбор без рестарта).
  `SmartProviderSelectionPolicy`: score = 0.35·latency + 0.35·reliability +
  0.15·(1−429share) + 0.15·cost, min-max нормализация, нейтраль при
  недостатке данных, tie-break и cold start — статический приоритет
  (поведение до порога 5 измерений не меняется). `ProviderManager` —
  единственная точка записи исходов. Тесты: 9 сценариев Smart Router;
  6 SMART-ROUTER-инвариантов. См. docs/CLOUD_ROUTER.md.
- Метрики Local-first ExecutionRouter (`ExecutionRouterMetrics`):
  total_requests / tool_requests / local_requests (+agent/direct) /
  cloud_requests / failed_local / cloud_escalations и проценты
  Local Execution % / Cloud Execution %; целевой ориентир первой версии
  60–70%+ — метрика, не жёсткое правило (на роутинг не влияет). Сводка в лог
  каждые 25 запросов; эскалация считается только когда локальная полоса была
  реально опрошена (skip по requiresWeb — не эскалация). 7 JVM-тестов
  подсчёта в ExecutionDecisionEngineTest.
- Local-first перевод (`LocalLlmTranslationProvider`): короткие реплики
  живого переводчика обрабатываются on-device Gemma (полоса LOCAL AI
  ExecutionRouter), длинные документы (>500 символов) и «модель не готова»
  честно уступают облаку (движок провайдеров уже сортирует offline-first).
  Приватный бонус: PRIVATE/SENSITIVE тексты, заблокированные облачным
  провайдером (C-02), локально переводятся без сети. ExecutionRouter-маппинг
  (LOCAL TOOL / LOCAL AI / CLOUD) задокументирован в docs/LOCAL_AI.md §0;
  тесты локального провайдера и каскада local→cloud; инварианты.
- Криптографическая привязка OMNIX Clip (server V008 + Android core/clip):
  идентичность = пара ключей EC P-256 (не имя/MAC). Производство регистрирует
  публичный ключ (`/v1/admin/clips/provision`; serial, public key, owner,
  license/account, status в clip_devices). Подключение: server-challenge
  (single-use, TTL 120s) → Clip подписывает каноническое сообщение
  (JARVIS-CLIP-ATTEST-v1) → сервер проверяет подпись зарегистрированным
  ключом → VALID + первая привязка владельца; revoked/чужой аккаунт/чужой
  ключ/replay — отказ. Android: ClipAttestationProtocol/ClipIdentityVerifier
  (fail-closed ECDSA), EncryptedClipTrustStore (ключ закрепляется только из
  серверных ответов), ClipAttestationManager (онлайн-сервер / офлайн-локально
  со свежестью), ClipTransport — честный контракт для firmware (фейковой
  реализации нет: TransportUnavailable, никогда не фейковый VALID). Тесты:
  интеграция на реальном Postgres (6 сценариев), JVM-тесты Android-стека;
  8 CLIP-инвариантов. См. docs/OMNIX_CLIP_BINDING.md.
- Device binding API-токенов (server V007): клиент — не источник истины.
  omx_-токен привязывается к устройству при redeem (`api_tokens.device_hash`);
  AI-исполнение проверяет `X-Omnix-Device` на КАЖДОМ запросе
  (`LicenseTokenAuthenticator.authenticate(header, deviceHeader)`): нет
  заголовка — отказ, чужое устройство — отказ, украденный токен бесполезен.
  Legacy-токены (до V007) на AI-пути отвергаются и само-залечиваются при
  первом успешном `/v1/license/validate` (клиент всегда проходит его до
  разблокировки UI); привязка одноразовая. entitlement по-прежнему
  перечитывается с сервера на каждом запросе (лицензия/план/биллинг/срок).
  Клиент: `AuthInterceptor` шлёт `X-Omnix-Device` (тот же device id, что в
  redeem/validate). Тесты: биндинг + само-залечивание в
  LicenseApiIntegrationTest; 6 LICENSE-инвариантов. См. docs/LICENSE_BILLING.md.
- Accessibility Lockdown: экранный контент не покидает устройство — пайплайн
  Accessibility → capture UI → privacy filter → LLM вместо «полный экран →
  Cloud LLM». Слой 3: контентный санитайзер `ScreenTextSanitizer` (OTP 6–8
  цифр, короткие коды в код-контексте, картоподобные 13–19 цифр → «••••» на
  этапе capture; время/суммы/телефоны не трогает). Слой 4: маркер
  `containsScreenContent` через PlanExecutionSummary → ExecutionResult →
  PromptExecutionResult; в БД сообщений (SendPromptUseCase + bypass в
  ChatViewModel) пишется placeholder вместо текста экрана — история чата
  больше не несёт экральный контент в облачный запрос. `SENSITIVE_PACKAGE_HINTS`
  расширен реальными пакетами: Chase/Tinkoff/Sber/Privat24 (банки без «bank»),
  PayPal/Coinbase/Binance/Revolut/Venmo/CashApp/Alipay/Samsung Pay/GPay,
  Authy/FreeOTP/Aegis/Steam Guard. Документация — `docs/ACCESSIBILITY_PRIVACY.md`;
  5 A11Y-инвариантов; тесты реальных сценариев.
- Policy Engine безопасности действий (`agent/policy/`): LLM только предлагает
  действие (`ProposedAction` = toolId + arguments + origin), решение о риске и
  подтверждении принимает `ActionPolicyEngine` — категория по toolId, детектор
  денежных сумм (`MoneyAmountDetector`: «50 000», «50 тысяч», «$100», денежные
  глаголы), сопоставление доверенных контактов (`TrustedContactMatcher`),
  статический пол риска инструмента. Форсированные правила (нельзя отключить):
  деньги в исходящих сообщениях/платёжных инструментах, DELETE, accessibility-
  запись, AUTOMATION-происхождение для звонков/сообщений (S-3: триггер
  автоматизации не звонит/не пишет сам). Настраиваемые политики звонков и
  сообщений (ALWAYS/TRUSTED_ONLY/NEVER, MONEY_ONLY) + доверенные контакты
  (`ActionPolicySettings`, in-memory провайдер; UI/DataStore — следующий шаг).
  Интеграция: `ToolPermissionManager.preflight` (порядок capability →
  разрешения → политика), `ActionOrigin` протянут через `ToolExecutor.execute/
  executeAll`, `PersonalAutomationEngine` объявляет AUTOMATION. Документация —
  `docs/ACTION_POLICY.md`; 20+ JVM-тестов контрактов политики.
- Единый контракт Tool Registry 2.0 (`OmniTool`): `requiredPermissions`
  (контрактный член; CapabilityAwareTool выводит его из capability-контракта,
  preflight блокирует plain-инструменты с невыданными разрешениями),
  `verify(arguments, draft)` (фаза Verification: ToolExecutor вызывает её после
  каждого успешного `execute()` внутри общего tool-таймаута; дефолт —
  pass-through) и `mapError(arguments, error)` (единый error mapping:
  SecurityException → PERMISSION_REQUIRED с объявленными разрешениями,
  ActivityNotFoundException → USER_ACTION_REQUIRED, остальное → FAILURE).
  Tier 1 (open_app, volume, brightness, alarm/timer, bluetooth, wi-fi) переведён
  на разделение фаз execute/verify; для bluetooth/wi-fi/open_app pass-through
  задокументирован (чтения самодостаточны / публичного API верификации нет).
- Execute → verify → SUCCESS: read-back верификация результатов инструментов.
  Новый чистый модуль `agent/tools/verification/ExecutionVerification.kt`
  (правила решения + поллинг) и покрытие в инструментах: громкость
  (`SetVolumeTool`, `MediaControlTool` — read-back `getStreamVolume`),
  яркость (`SetBrightnessTool` — возврат `putInt` + read-back), DND
  (`DoNotDisturbTool` — applied + current interruption filter), фонарик
  (`FlashlightTool` — подтверждение через `CameraManager.TorchCallback` +
  выбор камеры по признаку вспышки вместо «первой в списке»), буфер обмена
  (`ClipboardTool` — read-back записи, которая с API 29 может молча
  игнорироваться в фоне), будильник (`AlarmTimerTool` — подтверждение через
  `AlarmManager.nextAlarmClockInfo`, иначе `ALARM_UNVERIFIED`).

### Changed

- Инструменты больше не сообщают «готово» без подтверждения системы:
  `DoNotDisturbTool` без policy-доступа возвращает `USER_ACTION_REQUIRED`
  (раньше — `SUCCESS` с `actionRequiresUser = true`); громкость «громче» на
  максимуме — `FAILURE VOLUME_AT_LIMIT` (раньше — «Громкость увеличена»);
  `media.control` формулирует play/pause/next как отправленную команду
  плееру, а не неподтверждаемый результат; таймер — «отправлен в приложение
  часов» (публичного API верификации таймера нет); rollback громкости
  подтверждается read-back'ом.
- `FastCommandRouter`: предзаготовленные реплики для tool-путей переведены
  в intent-формулировки («Включаю фонарик», «Ставлю музыку на паузу»,
  «Устанавливаю громкость на N%») — итог всегда озвучивается из реального
  `ToolExecutionResult`; `scripts/verify-architectural-invariants.sh` получил
  VERIFY-гварды против регрессии (запрет result-формулировок до выполнения).
- Инвариант H-04 актуализирован после удаления `ManualWakeWordTrigger`/
  `MainViewModel` frontend-rebuild'ом `0e9bf4b` (до фикса CI-шаг инвариантов
  падал на HEAD): проверка теперь требует, чтобы пайплайн запускался только
  через `OmnixVoiceService`, а presentation-слой не вызывал его напрямую.
- `docs/ANDROID_CAPABILITIES.md`: раздел «Верификация результата» с таблицей
  механизма подтверждения и честного отказа по каждому инструменту.

### Added

- OMNIX Control Plane (merged from `feat/control-plane`): admin HTTP API with
  RBAC and audit log, admin sessions/passwords, settings and feature flags,
  provider runtime overrides, cost model, operational UI, `rawQuery` plumbing
  through `HttpRequestContext`, and `V006__control_plane.sql` migration.
  Covered by 44 new tests (unit, surface, integration, UI).

### Changed

- CI: `anchore/sbom-action` bumped 0.24.0 -> 0.24.2 (SHA-pinned; pin verified
  against the upstream `v0.24.2` tag object).

### Fixed

- Restored executable bits on `scripts/*.sh` and `device-validation/*.sh`
  that were dropped by the control-plane branch (CI invokes them via `bash`,
  but the manual on-device kit relies on the exec bit).

### Deferred (dependency bumps rejected during the 2026-09-01 branch audit)

- `kotlin 1.9.24 -> 2.4.10`, `ksp -> 2.3.11`, `coroutines -> 1.11.0`,
  `room -> 2.8.4`, `androidx.test:runner -> 1.7.0`: all five Dependabot PRs
  omit the matching `gradle/verification-metadata.xml` entries, so the build
  fails dependency verification. The Kotlin/KSP jumps additionally conflict
  with the pinned Compose compiler `1.5.14` (Kotlin 1.9.x). These must be
  redone as coordinated upgrades (toolchain + Compose compiler + lockfiles +
  verification metadata) rather than merged as-is.



- Release signing pipeline: env/keystore.properties-driven `signingConfig`,
  manual "Release OMNIX (signed)" workflow (AAB+APK, `apksigner verify`),
  `OMNIX_REQUIRE_SIGNED_RELEASE` fail-fast, R8 release smoke on every PR,
  `docs/RELEASE.md`.
- Accessibility privacy boundary: per-package policy
  (`AccessibilityPrivacyPolicy` + `AccessibilityPrivacyStore`), lock-screen/system
  packages never accessible, password fields never read or typed,
  honest `SCREEN_BLOCKED_BY_PRIVACY_POLICY` /
  `APP_BLOCKED_BY_PRIVACY_POLICY` / `PASSWORD_FIELD_USER_INPUT_REQUIRED`
  results, package-only audit logging, 16 JVM policy tests.
- Prometheus metrics export: `GET /v1/admin/metrics/prometheus`
  (Bearer + VIEW_ADMIN, `text/plain; version=0.0.4`), stable `omnix_*`
  metric names, endpoint and format tests.
- Operational runbook `docs/RUNBOOK.md` (metrics map, alert table,
  provider/rate-limit/401/usage/Postgres/rollback playbooks, backup RPO/RTO,
  reconciliation procedure) plus `deploy/prometheus/{prometheus,alerts}.yml`.
- `ReconciliationWorker`: read-only visibility for orders stuck in
  `RECONCILIATION_REQUIRED` (aging metric + warn logs, no state guessing),
  `JdbcBillingRepository.findStaleReconciliationOrders`, lifecycle wiring.
- Provider contract tests over recorded fixtures
  (`server/src/test/resources/provider-contracts/`) for Groq/OpenRouter/Gemini:
  success parsing, 429/401 classification, honest schema-drift failures.

### Changed

- `ToolExecutionResult.failure(...)` gained optional structured `data`.
### Removed

- Documentation cleanup: removed agent-session and meta documentation
  (`docs/AUDIT_2026_08_29.md`, `docs/OMNIX_V03_PLAN_VERIFICATION.md`,
  `docs/BENCHMARK.md` + `docs/benchmark/`, `docs/DEPENDENCY_UPDATE_PLAN.md`,
  `docs/SUPPLY_CHAIN_SECURITY.md`, `docs/EXECUTION_DECISION_ENGINE.md`,
  `docs/SERVER_AI_LAYER.md`, `docs/TEST_QUALITY.md`,
  `docs/ROOM_SCHEMA_POLICY.md`, `docs/PHASE2_DEVICE_AND_PROVIDER_VALIDATION.md`,
  `docs/ANDROID_ENVIRONMENTS.md`, `docs/adr/`). Operational docs kept:
  RUNBOOK, RELEASE, PRODUCTION_DEPLOYMENT, LICENSE_BILLING,
  ANDROID_CAPABILITIES, LOCAL_AI. The single-instance decision (former
  ADR-0001) is now stated inline in the enforcing code
  (`DeploymentSecurityConfig`, `PostgresSingleInstanceGuard`) and asserted
  by `SharedStateArchitectureTest` against code, not prose.

### Added

- JaCoCo coverage configuration for Android JVM tests and the server JVM module.
- `phase3Coverage`, `phase3StaticAnalysis`, and `phase3Quality` Gradle entry
  points, plus XML/HTML/CSV coverage reports and coverage verification.
- Reviewed JaCoCo CI floors: 24% lines / 20% branches for Android JVM tests and
  80% lines / 35% branches for the full PostgreSQL-backed server suite.
- Detekt static analysis with a reviewed high-signal rule configuration and
  HTML/XML/SARIF reports.
- Behavioral tests for activation ViewModel, settings ViewModel, repositories,
  OMNIX API network handling, and Android system/device tools.
- Android instrumentation tests for encrypted `LicenseManagerImpl`, DataStore
  persistence, and Compose component semantics.
- Repository security disclosure process in `SECURITY.md`.
- Project-license decision placeholder in `LICENSE`; no license was selected on
  behalf of the owner.
- Dependency update assessment and test-quality documentation.
- CI execution for the dev JVM suite, JaCoCo gates, Detekt, lint,
  PostgreSQL-backed server verification, and dev app/test APK compilation;
  quality reports and the dev app APK are published as workflow artifacts.
- A dedicated KVM-backed Android API 34 CI job that runs the ordinary
  instrumentation suite, generates Android coverage, and publishes test and
  coverage evidence. The corrected job completed successfully in hosted CI.

### Changed

- Replaced the arithmetic-only `LicenseManagerTest` with instrumentation that
  exercises the production encrypted Android implementation and fail-closed
  server behavior.
- Updated compatible patch/minor AndroidX lifecycle, activity, DataStore, and
  Android test dependencies without beginning the separate Kotlin/AGP/Compose
  major migration; strict dependency locks and verification metadata were
  regenerated for the reviewed graph.
- Excluded the legacy `kotlin-stdlib-common` metadata artifact from Android
  configurations so AGP lint artifact views remain compatible with strict
  locks; the Android/JVM implementation stays pinned to Kotlin stdlib 1.9.24.
- Kept the lint baseline deterministic at 28 reviewed Android findings. External
  dependency-recency IDs moved to Dependabot/dependency-review/lock/Trivy policy
  after CI proved their `latest available` metadata is environment-dependent;
  all 28 source/resource signatures remain unchanged.
- CI now runs explicit dev-flavor coverage verification, Detekt, deterministic
  lint, PostgreSQL-backed server tests, report publication, and dev app/test APK
  assembly instead of relying on a single generic debug path.
- Android instrumentation now packages the authentic Room v5 schema for
  `MigrationTestHelper`; the optional real-model test checks model presence
  before constructing the MediaPipe/Hilt runtime and skips cleanly when the
  separately distributed model is absent.
- Battery status tool now returns a structured failure when Android does not
  provide battery data or supplies an invalid status instead of fabricating a
  100% charge value.
- Removed unused constructor dependencies, parameters, and constants identified
  by static analysis; execution behavior is otherwise unchanged.
- Benchmark text formatting now uses `Locale.ROOT` for deterministic reports.

### Removed

- One-time handoff artifacts from the patch-import session: `stage1-6.patch`
  (content fully merged into the tree) and `NEXT_SESSION_README.md`.
- `archive/` prototype sources (`OrchestratorEngine`, `ChatUiStateMachine`);
  superseded implementations that were never compiled by Gradle and remain
  recoverable from Git history.
- `audit/` working reports and committed scanner evidence; Trivy/SBOM/gitleaks
  outputs are regenerated by CI on every push and published as workflow
  artifacts, so the checked-in copies were stale duplicates.
- Dead `BluetoothAudioManager` (superseded by `BluetoothAudioRouter`),
  unused `AutomationRuleDto`/`AutomationActionDto` DTOs (superseded by
  `AutomationEntity` plus explicit JSON), `ObserveNetworkStateUseCase`,
  the `ExecutionStep` typealias, the `OmnixGreenGlow` color, the unused
  server `ApiException`, and 39 verified-unused Kotlin imports.
- Eight unused legacy string resources (`status_*`, `btn_*`) together with
  their eight `UnusedResources` lint-baseline entries; the deterministic lint
  baseline shrinks from 28 to 20 reviewed findings.

### Existing unreleased work documented from the repository

- Android `dev`, `staging`, and `prod` flavors with strict production endpoint
  policy.
- AlarmManager-backed persisted automation scheduler and Room v5 legacy archive
  policy.
- MediaPipe local-model lifecycle and real-device test harness.
- Capability-driven AGENT-010 cloud fallback and regenerated benchmark data.
- R8/resource shrinking and narrow MediaPipe/JNI keep rules.
- PostgreSQL-backed server license, billing, rate-limit, and usage architecture.
- Supply-chain workflows, dependency locking/checksum verification, full-history
  secret-scan script, Trivy policy, and SBOM generation.

### Added
- **Экран «Загрузка локальной модели» со спокойной премиальной анимацией (решение владельца 2026-09-24).** Вместо «планетарного кольца» с вращающейся дугой: тонкий круг сам себя дорисовывает по кругу (~1,5 с, ease-in-out), на мгновение замыкается, мягко гаснет — и уходит на новый цикл. Буквы «OMNIX» появляются по одной — staggered fade-in снизу вверх, друг за другом, а не все разом. Экран покрывает состояние `Loading` (модель грузится в память при первом запросе); подпись — правдивая и самодостаточная («Loading local model…» / «Локальная модель загружается в память…»): состояние именно загрузки в память, не скачивания (скачивание на экране не блокируется и рендерится иначе). Reduced motion — статичная ¾-дуга и мгновенный текст. Downloading намеренно не блокируется: путь в настройки с отменой загрузки сохраняется. Токен типографики `splashWordmark` — единственный крупный wordmark-момент, как и было задумано в §6.

- **Экран микрофона онбординга по моку (2026-09-24): три честных состояния.** До системного диалога — «Доступ к микрофону» с «Разрешить» и «Пропустить», без ссылки на настройки (назвать отказом то, о чём ещё не спрашивали, нечестно). Состояние «отказано» появляется только после реального показа диалога — факт персистится во флаг `OmnixExperienceStore.microphonePrompted`, живёт через перезапуск процесса: приглушённый микрофон с диагональной чертой, первичная кнопка «Открыть настройки». «Готово»: тонкое кольцо 150 dp / 2 dp (трек = border-токен, как #2c2c2e в моке) дуга закрывает полный круг сверху за 600 мс ease-out, микрофон гаснет (250 мс), галочка проявляется (300 мс, reduced motion — мгновенно), шаг уходит сам после ~1.6 с, когда текст успели прочитать; возврат жестом назад на уже готовый шаг показывает «Продолжить» вместо рикошета. Кнопка «Пропустить» в обоих ожидающих состояниях — флоу не запирает без микрофона (первая команда и так умеет пройти без него). RU-копия — дословно из мока владельца, EN/TK — новые.

- **Экран настроек (Профиль) по моку (2026-09-25): сетка иконок и четыре смысловые группы.** У каждой строки — линейная иконка в серой плашке 26 dp (iOS Settings: список сканируется иконкой до слов; 12 новых Canvas-глифов в `OmnixIcons`, штрих 1.8/24, круглые концы). Пункты сгруппированы: «Режимы» (Перевод, Диалог), «Основное» (Голос, Приватность, Устройства, AI-модель, Язык, Уведомления, Оформление), «Поддержка» (О программе, Диагностика), «Разработчикам» (Для разработчика) — раньше семь строк и обе группы шли без заголовков. «AI» переименован в «AI-модель». Стрелка у «Для разработчика» теперь у верхнего края строки (многострочный ряд: `OmnixSettingRow` принял `verticalAlignment`), иконка тоже. Статус Bluetooth: заголовок строки подсвечен предупреждающим янтарём — новый токен `stateWarning` (тёмная #F5C451 из мока, ночная приглушённая, светлая тёмно-янтарная для контраста); точка сохранена. Заголовок «Настройки» снижен до `title2` (28→22 sp) — вес SemiBold как у всех экранов. RU-подпись «Для разработчика» — дословно из мока; EN/TK — новые.

### Changed
- **Перевод-экран и таб-бар по Apple-минималистичному моку (2026-09-25):** языковая пара — одна интерактивная «pill»-капсула «Русский → English» с иконкой смены (чтение Apple Translate): разрозненная подпись-направление и отдельная текстовая кнопка «Поменять языки» убраны, вся капсула — одна кнопка-своп (a11y — «Поменять языки», роль button; новая канвас-иконка `OmnixSwapIcon`). Центр экрана — новое «кольцо перевода»: приглушённо-синий тонкий контур (новый токен `accentRing`: тёмная #3E6C9F / ночная #2C4C72 / светлая #33629A — спокойнее логотипного `accentBrand`) с белой волной из 6 столбиков, без halo и свечения; в покое — статичный глиф мока, при активном слушании столбики живут реальным уровнем микрофона (тот же чистый `AmplitudeRing`, §33 — не таймер; тишина держит 20 % высоты, reduced motion — всегда статичный глиф). Заголовок «Перевод» по центру (`title2` — тот же вес, что у остальных экранов) + компактный back-chevron слева, с отступом от статус-бара; композиция сбалансирована weight-слотами — «дыра» в центре убрана; «Начать»/«Остановить» — во всю ширину. Ядро на экране заменено кольцом (явное решение мока: там нужен спокойный системный знак, а не halo-ядро) — модель состояний не тронута: слушание читается живой волной, «думаю» — укладывающимся текстом результата. Таб-бар: активный пункт белым и жирным, остальные приглушены (Regular + приглушённый цвет) — «где я» за один взгляд; под-экраны держат вкладку родителя (iOS-чтение): режимы OMNIX (Перевод, Чат) — центральный пункт OMNIX, всё, что открыто из «Я» (устройства, приватность, секции настроек) — «Я». Чистые `tabForRoute` и `waveformBarHeight` — JVM-тесты.

## Release history

**[OWNER ACTION REQUIRED]** Add entries here only for real tags/releases, with
release date, migration notes, supported Android/server versions, and links to
release artifacts.
