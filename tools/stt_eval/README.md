# Замер качества распознавания речи (Groq Whisper и Gemini)

Скрипт `eval_stt.py` гоняет одни и те же записи через выбранных провайдеров и
считает WER (по словам), CER (по символам) и время ответа. Только стандартная
библиотека Python 3.9+; WER/CER считаются встроенным расстоянием Левенштейна
(регистр и пунктуация не учитываются).

## Данные

- Папка с `<имя>.wav` (любая частота, лучше моно 16 кГц, до 30 секунд).
- Файл эталонов: tsv `имя<TAB>текст` (расширение в имени не важно). Строки
  формата `имя|текст` (как `metadata.csv` из датасетов голоса) тоже читаются.

Аудио и эталоны в репозиторий не коммитим.

### Туркменские записи с этого компьютера

В `omnix-voice-work/dataset_v6/tk/` лежат `wavs/tkl_NNNN.wav` и `metadata.csv`
(`tkl_0001|Agşamyňyz haýyrly bolsun. ...`). Это фразы с известным текстом,
подходят как эталон. Копировать ничего не нужно: скрипт читает файлы на месте
и ничего в них не меняет.

```powershell
$src = "C:\Users\User\Desktop\claude edit\omnix-voice-work\dataset_v6\tk"
python tools/stt_eval/eval_stt.py "$src\wavs" "$src\metadata.csv" --lang tk --limit 50
```

Так же для `dataset_v2`, `dataset_v3`, `dataset_v6_B`: смотрите там `metadata.csv`.

## Запуск, когда будут ключи

Ключи только из окружения, в файлы не пишутся:

```powershell
$env:GROQ_API_KEY = "..."
$env:GEMINI_API_KEY = "..."
$env:STT_GEMINI_MODEL = "gemini-2.5-flash"   # необязательно
python tools/stt_eval/eval_stt.py "<папка wav>" "<эталоны.tsv>" --providers groq,gemini --lang tk --out report.md
```

Отчёт печатается в stdout и сохраняется в `--out` (по умолчанию
`stt_eval_report.md`). Параметры: `--providers` (groq, gemini), `--lang`
(ISO-подсказка: tk, ru, en), `--limit N`. Сбой на отдельном файле считается
ошибкой и в WER/CER не входит. Лимиты бесплатных ключей малы: для первого
прогона берите `--limit 30`.

## Самопроверка

```powershell
python tools/stt_eval/test_eval_stt.py
```

Тест идёт на подменённых провайдерах, без сети и ключей.

## Серверная сторона

- `STT_PROVIDERS` - порядок по приоритету: `groq` (по умолчанию), `groq,gemini`,
  `gemini,groq`. Следующий провайдер вызывается только при сбое предыдущего.
- `STT_GEMINI_MODEL` - модель Gemini для распознавания (по умолчанию как `GEMINI_MODEL`).
- Ключи: `GROQ_API_KEY`, `GEMINI_API_KEY` (несколько через запятую).
