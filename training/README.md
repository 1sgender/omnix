# Фаза 2: кастомная модель «Omni»

Статус: **v0.1 (synthetic) — SHIPPED**: `omni_v0.1.onnx` обучена на
синтетике Piper TTS и подключена по умолчанию (отчёт и метрики —
`OMNI_V0.1_REPORT.md`). Осталось для v0.2: датасет живой речи (≥500
произнесений, ≥20 дикторов) и приёмка по протоколу ниже.
Day-one-модель `hey_jarvis_v0.1.onnx` (openWakeWord v0.5.1, детектирует
legacy-фразу «Hey Jarvis») сохранена в assets как фолбэк через
`WakeWordConfig.modelAssetPath` — удалить после приёмки v0.2.

## Спецификация датасета (минимум для старта обучения)

- ≥500 чистых произнесений «Omni» (цель: ≥2000), ≥20 дикторов,
  мужчины/женщины/дети, 16 кГц mono int16.
- Негативы: ≥10 ч фонов (тишина комнат, ТВ, улица, офис) + трудные
  негативы (похожие слова: «many», «money», «honey» — минимум 200 шт).
- Разбиение speaker-disjoint: дикторы train/test не пересекаются.
- Аугментация: шум MUSAN/noise-recipes, реверберация, кодек (Opus/SBC),
  сдвиги ±100 мс, громкость ±6 дБ.

## Обучение (референсный путь openWakeWord)

1. Эмбеддинги через `openwakeword.utils` (melspectrogram + embedding модели
   v0.5.1 — те же, что в APK).
2. Классификатор по `openwakeword.train`: вход `[16, 96]`, выход скор.
3. Экспорт в ONNX строго с контрактом: вход float32 `[1, 16, 96]`
   (batch динамический допустим), выход `[1, 1]`.
4. Порог по умолчанию: точка FAR/FRR на speaker-disjoint тесте
   (цель: FRR <5% при FAR <1 срабатывание/час фона).

## Проверка кандидата перед заменой в APK

`python3 training/verify_model.py --model <файл.onnx>`:

- модель открывается в onnxruntime;
- вход/выход совпадают с контрактом `[?, 16, 96]` → `[?, 1]`;
- smoke-инференс на нулях и шуме возвращает скоры в [0, 1];
- отчёт + sha256 для `assets/wakeword/SHA256SUMS`.

Без зелёного `verify_model.py` модель в `assets/wakeword/` не кладётся.
Замена имени/пути модели — через `WakeWordConfig.modelAssetPath`
(дефолт остаётся `hey_jarvis_v0.1.onnx`, пока фаза 2 не принята).

## Пайплайн v0.1 (как получен omni_v0.1.onnx)

Скрипты запускаются из корня репо, данные и выходы пишутся в `training/`
(в .gitignore). Зависимости: `training/requirements.txt`.

1. `python3 training/gen_raw.py` — Phase A: синтез raw-данных Piper TTS.
   Нужны голоса: каталог `training/voices/` или переменная `PIPER_VOICES`.
2. `python3 training/make_features.py` — Phase B: аугментация + фичи
   (melspectrogram/embedding — те же модели, что в APK).
3. `python3 training/train_omni.py` — Phase C+D: обучение DNN-головы,
   merge лучших чекпойнтов, подбор порога, экспорт ONNX `[?,16,96] -> [?,1]`.
4. `python3 training/eval_streaming.py [модель.onnx]` — стриминговый eval
   (recall / FP, как на устройстве).

`training/oww_train.py` — адаптированная копия openwakeword/train.py
(v0.6.0, класс Model); нужна для шага 3. Одиночные (оконные) метрики
v0.1 — `training/metrics_v0.1.json`; итоговая модель —
`app/src/main/assets/wakeword/omni_v0.1.onnx` (sha256 в SHA256SUMS).

## Критерии приёмки фазы 2

1. `verify_model.py` зелёный.
2. Офлайн-оценка на speaker-disjoint тесте не хуже hey_jarvis по FRR
   при том же FAR (протокол — `docs/WAKEWORD_NEURAL.md`).
3. `device-validation/06-wakeword-metrics.sh` на Clip: инференс чанка <80 мс,
   деградация батареи <2%/ч vs baseline без движка.
4. Обновлены NOTICE/SHA256SUMS/дока, старый файл модели удалён из assets.
