# Фраза вызова: как сравнивались движки

Воспроизведение замеров из `docs/WAKEWORD_NEURAL.md` («Фраза вызова «Окей, Омни»»).
Windows, Python 3.12.

```powershell
python -m pip install sherpa-onnx==1.13.8 sentencepiece onnxruntime numpy
```

Модели (из релизов k2-fsa/sherpa-onnx) распаковать в эту папку:

- `asr-models/sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16.tar.bz2` — русская модель (её и использует приложение);
- `kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2` — английская KWS-модель для сравнения.

Клипы синтезируются голосами Windows (Irina ru-RU, Zira en-US) и в репозиторий не входят:

```powershell
powershell -ExecutionPolicy Bypass -File synth.ps1 -OutDir clips
powershell -ExecutionPolicy Bypass -File synth_long.ps1 -OutDir clips
powershell -ExecutionPolicy Bypass -File synth_alt.ps1 -OutDir clips
powershell -ExecutionPolicy Bypass -File synth_ok_en.ps1 -OutDir clips
```

Сравнение фраз и моделей (последний аргумент — гауссов шум, 0.01 ≈ тихая комната):

```powershell
python compare.py 0
```

Прежняя модель приложения (openWakeWord `omni_v0.1`) на тех же клипах:

```powershell
python eval_oww.py ..\..\app\src\main\assets\wakeword 0.35
```

Что модель слышит в записи (обычное распознавание): `python transcribe.py 0 okomni_en`.

Ограничение: голоса синтетические. Настоящая проверка — на телефоне владельца
(«Я → ИИ-модель → Тест фразы вызова»).
