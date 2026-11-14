#!/usr/bin/env python3
"""Замер качества распознавания речи: Groq Whisper против Gemini.

Только стандартная библиотека. Ключи берутся из окружения
(GROQ_API_KEY, GEMINI_API_KEY), в файлы и отчёт не попадают.
Запуск и формат данных описаны в README.md.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import statistics
import sys
import time
import unicodedata
import urllib.error
import urllib.request
import uuid
from pathlib import Path

GROQ_URL = "https://api.groq.com/openai/v1/audio/transcriptions"
GROQ_MODEL_DEFAULT = "whisper-large-v3"
GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
GEMINI_MODEL_DEFAULT = "gemini-2.5-flash"
TIMEOUT_S = 60

PROMPT = (
    "Дословно транскрибируй речь из аудио на языке оригинала. "
    "Не переводи, не добавляй комментариев, не исправляй слова.{hint} "
    "Если речи нет, верни пустой text. "
    "Верни JSON: text — расшифровка, language — ISO 639-1 код языка речи."
)


# ----------------------------------------------------------- метрики

def normalize(text: str) -> str:
    """Нижний регистр, без пунктуации, один пробел между словами."""
    text = unicodedata.normalize("NFC", text).lower()
    text = re.sub(r"[^\w\s]|_", " ", text, flags=re.UNICODE)
    return re.sub(r"\s+", " ", text).strip()


def edit_distance(a: list, b: list) -> int:
    """Расстояние Левенштейна между двумя последовательностями."""
    if not a:
        return len(b)
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer_counts(ref: str, hyp: str) -> tuple[int, int]:
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h), len(r)


def cer_counts(ref: str, hyp: str) -> tuple[int, int]:
    r, h = list(normalize(ref)), list(normalize(hyp))
    return edit_distance(r, h), len(r)


# ----------------------------------------------------------- провайдеры

def _post(url: str, headers: dict, body: bytes) -> dict:
    req = urllib.request.Request(url, data=body, headers=headers, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT_S) as resp:
            return json.loads(resp.read().decode("utf-8"))
    except urllib.error.HTTPError as e:
        # Тело ответа может содержать детали запроса, поэтому только код.
        raise RuntimeError(f"HTTP {e.code}") from None


def groq_transcribe(wav: bytes, lang: str | None, key: str) -> str:
    model = os.environ.get("GROQ_STT_MODEL") or GROQ_MODEL_DEFAULT
    fields = [("model", model), ("response_format", "verbose_json"), ("temperature", "0")]
    if lang:
        fields.append(("language", lang))
    boundary = "stt-eval-" + uuid.uuid4().hex
    parts = []
    for name, value in fields:
        parts.append(
            f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode()
        )
    parts.append(
        f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="speech.wav"\r\n'
        f"Content-Type: audio/wav\r\n\r\n".encode()
    )
    parts.append(wav)
    parts.append(f"\r\n--{boundary}--\r\n".encode())
    data = _post(
        GROQ_URL,
        {"Authorization": f"Bearer {key}", "Content-Type": f"multipart/form-data; boundary={boundary}"},
        b"".join(parts),
    )
    return str(data.get("text", "")).strip()


def gemini_transcribe(wav: bytes, lang: str | None, key: str) -> str:
    model = os.environ.get("STT_GEMINI_MODEL") or GEMINI_MODEL_DEFAULT
    hint = f" Язык записи, скорее всего: {lang}." if lang else ""
    body = {
        "contents": [{
            "role": "user",
            "parts": [
                {"text": PROMPT.format(hint=hint)},
                {"inline_data": {"mime_type": "audio/wav", "data": base64.b64encode(wav).decode()}},
            ],
        }],
        "generationConfig": {
            "temperature": 0,
            "responseMimeType": "application/json",
            "responseSchema": {
                "type": "OBJECT",
                "properties": {"text": {"type": "STRING"}, "language": {"type": "STRING"}},
                "required": ["text"],
            },
        },
    }
    data = _post(
        f"{GEMINI_BASE}/{model}:generateContent",
        {"x-goog-api-key": key, "Content-Type": "application/json"},
        json.dumps(body).encode("utf-8"),
    )
    parts = (data.get("candidates") or [{}])[0].get("content", {}).get("parts", [])
    answer = "".join(p.get("text", "") for p in parts).strip()
    try:
        return str(json.loads(answer).get("text", "")).strip()
    except (ValueError, AttributeError):
        return answer


PROVIDERS = {
    "groq": (groq_transcribe, "GROQ_API_KEY"),
    "gemini": (gemini_transcribe, "GEMINI_API_KEY"),
}


# ----------------------------------------------------------- данные

def load_references(path: Path) -> dict[str, str]:
    """tsv «имя<TAB>текст»; формат LJSpeech «имя|текст» тоже понимается."""
    refs: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        if not line.strip():
            continue
        sep = "\t" if "\t" in line else "|"
        name, _, text = line.partition(sep)
        refs[Path(name.strip()).stem] = text.strip()
    return refs


# ----------------------------------------------------------- замер

def evaluate(wav_dir: Path, refs: dict[str, str], providers: dict, lang: str | None,
             limit: int | None = None) -> dict:
    """providers: имя -> функция(wav, lang) -> текст. Возвращает сводку по каждому."""
    names = [n for n in sorted(refs) if (wav_dir / f"{n}.wav").is_file()]
    if limit:
        names = names[:limit]
    results = {}
    for pname, fn in providers.items():
        we = wn = ce = cn = errors = 0
        times: list[float] = []
        for n in names:
            wav = (wav_dir / f"{n}.wav").read_bytes()
            started = time.monotonic()
            try:
                hyp = fn(wav, lang)
            except Exception as e:  # noqa: BLE001 - сбой одного файла не должен ронять замер
                errors += 1
                print(f"[{pname}] {n}: ошибка {type(e).__name__}: {e}", file=sys.stderr)
                continue
            times.append(time.monotonic() - started)
            a, b = wer_counts(refs[n], hyp)
            c, d = cer_counts(refs[n], hyp)
            we, wn, ce, cn = we + a, wn + b, ce + c, cn + d
        results[pname] = {
            "files": len(names), "errors": errors,
            "wer": we / wn if wn else None, "cer": ce / cn if cn else None,
            "mean_s": statistics.fmean(times) if times else None,
            "p95_s": sorted(times)[max(0, int(len(times) * 0.95) - 1)] if times else None,
        }
    return results


def render_report(results: dict, lang: str | None, wav_dir: Path) -> str:
    def pct(x):
        return "-" if x is None else f"{x * 100:.1f}%"

    def sec(x):
        return "-" if x is None else f"{x:.2f}"

    lines = [
        "# Замер распознавания речи", "",
        f"Папка: `{wav_dir.name}`, подсказка языка: `{lang or 'нет'}`", "",
        "| Провайдер | Файлов | Ошибок | WER | CER | Среднее, с | p95, с |",
        "|---|---|---|---|---|---|---|",
    ]
    for name, r in results.items():
        lines.append(
            f"| {name} | {r['files']} | {r['errors']} | {pct(r['wer'])} | {pct(r['cer'])} "
            f"| {sec(r['mean_s'])} | {sec(r['p95_s'])} |"
        )
    lines += ["", "WER/CER считаются по суммарному числу правок на все слова/символы, "
              "регистр и пунктуация не учитываются.", ""]
    return "\n".join(lines)


def build_providers(names: list[str]) -> dict:
    out = {}
    for name in names:
        if name not in PROVIDERS:
            raise SystemExit(f"неизвестный провайдер: {name} (доступны: {', '.join(PROVIDERS)})")
        fn, env = PROVIDERS[name]
        key = os.environ.get(env, "").strip()
        if not key:
            raise SystemExit(f"нет ключа: задайте переменную окружения {env}")
        out[name] = (lambda wav, lang, fn=fn, key=key: fn(wav, lang, key))
    return out


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("wav_dir", type=Path, help="папка с <имя>.wav")
    ap.add_argument("refs", type=Path, help="tsv: имя<TAB>эталонный текст")
    ap.add_argument("--providers", default="groq,gemini", help="через запятую (groq, gemini)")
    ap.add_argument("--lang", default=None, help="ISO-подсказка языка, например tk, ru, en")
    ap.add_argument("--limit", type=int, default=None, help="взять только первые N файлов")
    ap.add_argument("--out", type=Path, default=Path("stt_eval_report.md"))
    args = ap.parse_args(argv)

    providers = build_providers([p.strip() for p in args.providers.split(",") if p.strip()])
    results = evaluate(args.wav_dir, load_references(args.refs), providers, args.lang, args.limit)
    report = render_report(results, args.lang, args.wav_dir)
    print(report)
    args.out.write_text(report, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
