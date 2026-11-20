# Обучение omni_v0.2 на Kaggle (шаблон RUN.md пакета v0.2-package.zip)

Локального GPU нет, поэтому обучение голов-классификатора идёт на Kaggle
(бесплатный GPU T4/P100; модель крошечная, на CPU тоже пройдёт, но дольше).
Фичи (melspectrogram/embedding) уже посчитаны локально, поэтому на Kaggle не
нужны ни Piper, ни аудио-аугментация.

## Что внутри пакета

```
training/            скрипты (train_omni.py, oww_train.py, eval_*.py, verify_model.py)
app/src/main/assets/wakeword/   melspectrogram.onnx, embedding_model.onnx, omni_v0.1.onnx (для сравнения)
features_v2/         готовые фичи: positive/negative_features_{train,test}.npy, negative_bg..., fp_stream.npy, stats.json
raw_v2/test/         синтетический speaker-disjoint тест (для eval_streaming.py)
live_test/           ОТЛОЖЕННЫЕ живые клипы владельца + вся запись + manifest (ЛИЧНЫЕ ДАННЫЕ)
DATA_REPORT.md       состав датасета: голоса, языки, число клипов
RUN.md               этот файл
```

Пакет содержит голос владельца: на Kaggle создавайте датасет только **Private**.

## 1. Загрузка в Kaggle

1. kaggle.com -> Datasets -> New Dataset -> перетащить `v0.2-package.zip`
   (Kaggle сам распакует). Название `omni-v02`, видимость **Private**.
2. Code -> New Notebook -> Add Input -> ваш датасет `omni-v02`.
3. Settings -> Accelerator: GPU T4 x2 (или P100); Internet: On (нужен только pip).

## 2. Команды (ячейки ноутбука)

Ячейка 1, зависимости (без тяжёлого `openwakeword[full]`, train_omni.py сам
ставит заглушки на speechbrain/torchaudio/...):

```python
!pip -q install openwakeword==0.6.0 --no-deps
!pip -q install onnx onnxruntime torchinfo torchmetrics pyyaml scikit-learn tqdm
```

Ячейка 2, поиск пакета и обучение (~15-40 мин на GPU):

```python
import glob, os
P = os.path.dirname(os.path.dirname(glob.glob('/kaggle/input/**/training/train_omni.py', recursive=True)[0]))
print(P)
!cd {P} && python training/train_omni.py --features features_v2 --out /kaggle/working/model --version v0.2
```

Результат: `/kaggle/working/model/omni_v0.2.onnx` (усреднённые лучшие чекпойнты, это
главный файл), `omni_v0.2_final.onnx` (последний шаг) и `metrics_v0.2.json`
(recall/FP по порогам, выбранный порог `chosen_threshold`).

Ячейка 3, проверка контракта (должно закончиться `PASS`, вывод sha256 нужен
для `assets/wakeword/SHA256SUMS`):

```python
!cd {P} && python training/verify_model.py --model /kaggle/working/model/omni_v0.2.onnx
```

Ячейка 4, стриминговый eval на синтетическом speaker-disjoint тесте
(как на устройстве; сравните с v0.1 той же командой):

```python
!cd {P} && python training/eval_streaming.py /kaggle/working/model/omni_v0.2.onnx raw_v2
!cd {P} && python training/eval_streaming.py app/src/main/assets/wakeword/omni_v0.1.onnx raw_v2
```

Ячейка 5, живая проверка на отложенных произнесениях владельца
(в train они НЕ попадали) и на всей записи:

```python
!cd {P} && python training/eval_live.py --model /kaggle/working/model/omni_v0.2.onnx \
   --clips live_test/clips --manifest live_test/clips_manifest.json --split test \
   --stream live_test/owner_omni_16k.wav --expect 14 --json /kaggle/working/eval_live_v0.2.json
!cd {P} && python training/eval_live.py --model app/src/main/assets/wakeword/omni_v0.1.onnx \
   --clips live_test/clips --manifest live_test/clips_manifest.json --split test \
   --stream live_test/owner_omni_16k.wav --expect 14
```

## 3. Что забрать

Правая панель Notebook -> Output (или Save Version -> Save & Run All, затем вкладка
Output) -> скачать:

- `model/omni_v0.2.onnx`  главный результат;
- `model/metrics_v0.2.json`, `eval_live_v0.2.json` для отчёта.

Положить `omni_v0.2.onnx` в `C:\Users\User\Desktop\claude edit\omnix-voice-work\wakeword-real\`
и локально повторить проверки:

```powershell
python training\verify_model.py --model "<путь>\omni_v0.2.onnx"
python training\eval_live.py --model "<путь>\omni_v0.2.onnx" --clips "<wakeword-real>\clips" --manifest "<wakeword-real>\clips_manifest.json" --stream "<wakeword-real>\owner_omni_16k.wav" --expect 14
```

## 4. Критерии «годится» (README.md, критерии приёмки фазы 2)

- verify_model.py: PASS;
- recall на отложенных живых клипах не хуже v0.1 (по v0.1: 3/4) при FP не хуже;
- на синтетическом holdout нет регрессии относительно v0.1 (метрики v0.1 в
  `OMNI_V0.1_REPORT.md`);
- подбор порога: `chosen_threshold` из metrics_v0.2.json, итоговый дефолт согласуйте
  после замера на живом фоне (FP/час на реальных записях пока не измерен).

Дальше: положить модель в `app/src/main/assets/wakeword/`, обновить SHA256SUMS,
NOTICE и доку отдельным PR (не в этом пакете).
