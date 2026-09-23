#!/usr/bin/env python3
"""Фаза 2 (v0.2): сбор ЖИВОЙ речи для датасета wake-word «Omni».

Дополняет синтетический пайплайн v0.1 (gen_raw.py — TTS): принимает
записи с телефона/диктофона, нормализует их в контракт пайплайна
(16 кГц, mono, int16 WAV) и раскладывает в training/raw/<split>/<kind>/.

Использование (из корня репо):

  # 1) проверить партию записей без копирования
  python3 training/collect_live.py add ~/recordings/batch1 \
      --speaker ivan --kind positive --dry

  # 2) залить в train-сплит (записи одного диктора — ВСЕГДА один сплит)
  python3 training/collect_live.py add ~/recordings/batch1 \
      --speaker ivan --kind positive --split train

  # 3) статус датасета против целей v0.2
  python3 training/collect_live.py report

Контракт записи: WAV (m4a/ogg не читаются — экспортируйте WAV);
лучше 16 кГц mono, но любое разрешение конвертируется (resample_poly).
Кандидаты короче 0.3 с / длиннее 3.0 с (positive) бракуются — окно
модели 16×96 покрывает ровно это; клиппинг >1% и «тишина» — предупреждение.
"""
import argparse
import csv
import shutil
import sys
import wave
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
SR = 16_000

# Цели датасета v0.2 (training/README.md «Спецификация датасета»).
TARGET_POS_MIN, TARGET_POS_GOAL = 500, 2000
TARGET_SPEAKERS = 20
TARGET_BG_HOURS = 10.0
TARGET_HARD_NEGS = 200
HARD_NEG_MARKERS = ("many", "money", "honey", "умн", "тон", "омн_", "hard")

# «Фон» (длинные фоновые записи) — отдельный вид: в negative он попадает
# сегментацией make_features.py, но для целей v0.2 считается отдельно.
BG_MIN_SECONDS = 30.0


def read_wav_any(path: Path):
    """WAV любой частоты/канальности → float32 [-1, 1] mono + sr; None при ошибке."""
    try:
        with wave.open(str(path), "rb") as w:
            n_ch = w.getnchannels()
            sr = w.getframerate()
            sw = w.getsampwidth()
            if sw != 2:
                return None, f"не int16 (sampwidth={sw} байт)"
            raw = w.readframes(w.getnframes())
        x = np.frombuffer(raw, dtype=np.int16).astype(np.float32) / 32768.0
        if n_ch > 1:
            x = x.reshape(-1, n_ch).mean(axis=1)
        return (x, sr), None
    except Exception as e:  # noqa: BLE001 — отчёт, а не падение
        return None, str(e)


def to_16k(x: np.ndarray, sr: int) -> np.ndarray:
    if sr == SR:
        return x
    g = np.gcd(sr, SR)
    return resample_poly(x, SR // g, sr // g).astype(np.float32)


def save_wav(path: Path, x: np.ndarray) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x, -1.0, 1.0) * 32767.0).astype(np.int16).tobytes())


def diagnostics(x: np.ndarray) -> dict:
    peak = float(np.max(np.abs(x))) if x.size else 0.0
    clip = float(np.mean(np.abs(x) >= 0.985)) if x.size else 0.0
    rms = float(np.sqrt(np.mean(x * x))) if x.size else 0.0
    return {"peak": peak, "clip_pct": clip * 100, "rms": rms}


def cmd_add(src: Path, speaker: str, kind: str, split: str, dry: bool) -> int:
    if kind not in ("positive", "negative"):
        print(f"FAIL: --kind {kind} не поддерживается (positive|negative)")
        return 2
    if split not in ("train", "test"):
        print(f"FAIL: --split {split} не поддерживается (train|test)")
        return 2
    if not src.is_dir():
        print(f"FAIL: каталог не найден: {src}")
        return 2

    files = sorted(p for p in src.iterdir() if p.suffix.lower() == ".wav")
    if not files:
        print(f"FAIL: в {src} нет .wav (m4a/ogg не читаются — экспортируйте WAV)")
        return 2

    dst = RAW / split / kind
    manifest = []
    rejected = []
    for p in files:
        loaded, err = read_wav_any(p)
        if loaded is None:
            rejected.append((p.name, f"чтение: {err}"))
            continue
        x, sr = loaded
        x = to_16k(x, sr)
        dur = len(x) / SR
        d = diagnostics(x)
        # Позитивы — короткие фразы; негативы бывают длинными (фон/речь).
        if kind == "positive" and not (0.3 <= dur <= 3.0):
            rejected.append((p.name, f"длительность {dur:.2f} с вне 0.3–3.0"))
            continue
        warn = ""
        if d["clip_pct"] > 1.0:
            warn += f" клиппинг {d['clip_pct']:.1f}%"
        if d["rms"] < 0.005:
            warn += " почти тишина (rms<0.005)"
        out_name = f"live_{speaker}_{p.stem}.wav"
        manifest.append((p.name, out_name, dur, d, warn.strip()))
        if not dry:
            save_wav(dst / out_name, x)

    print(f"== add: {src} → {dst}/{'[DRY] ' if dry else ''}")
    print(f"   принято: {len(manifest)}, брак: {len(rejected)}")
    for name, why in rejected:
        print(f"   REJECT {name}: {why}")
    for name, out, dur, d, warn in manifest:
        print(f"   ok {name} → {out} ({dur:.2f}с peak={d['peak']:.2f}"
              + (f" WARN:{warn}" if warn else ""))
    if not dry and manifest:
        mf = RAW / f"collect_{split}_{kind}_{speaker}.csv"
        mf.parent.mkdir(parents=True, exist_ok=True)
        new = not mf.exists()
        with mf.open("a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if new:
                w.writerow(["src", "dst", "sec", "peak", "clip_pct", "rms", "warn"])
            for name, out, dur, d, warn in manifest:
                w.writerow([name, out, f"{dur:.3f}", f"{d['peak']:.3f}",
                            f"{d['clip_pct']:.2f}", f"{d['rms']:.4f}", warn])
        print(f"   манифест: {mf}")
    return 0


def cmd_report() -> int:
    pos = {"train": 0, "test": 0}
    neg = {"train": 0, "test": 0}
    speakers = set()
    bg_seconds = 0.0
    hard_negs = 0
    neg_seconds = 0.0
    for split in ("train", "test"):
        for p in sorted((RAW / split / "positive").glob("*.wav")):
            pos[split] += 1
            if p.name.startswith("live_"):
                speakers.add(p.name.split("_")[1])
        for p in sorted((RAW / split / "negative").glob("*.wav")):
            neg[split] += 1
            with wave.open(str(p), "rb") as w:
                dur = w.getnframes() / w.getframerate()
            neg_seconds += dur
            if dur >= BG_MIN_SECONDS:
                bg_seconds += dur
            stem = p.stem.lower()
            if any(m in stem for m in HARD_NEG_MARKERS):
                hard_negs += 1

    total_pos = pos["train"] + pos["test"]
    print("== dataset report (цели v0.2 — training/README.md) ==")
    def line(label, value, target=""):
        suffix = f"   (цель: {target})" if target else ""
        print(f"   {label:<38} {value}{suffix}")

    line(f"positives (train/test)", f"{pos['train']}/{pos['test']} — всего {total_pos}",
         f"мин {TARGET_POS_MIN}, цель {TARGET_POS_GOAL}")
    line("уникальные live-дикторы", len(speakers), f"≥{TARGET_SPEAKERS}")
    line("негативы (файлы)", f"{neg['train']}/{neg['test']} — всего {sum(neg.values())}")
    line("фоновые часы (файлы ≥30 с)", f"{bg_seconds / 3600:.2f} ч", f"≥{TARGET_BG_HOURS} ч")
    line("трудные негативы (по имени)", hard_negs, f"≥{TARGET_HARD_NEGS}")
    line("негативы всего, длительность", f"{neg_seconds / 3600:.2f} ч")

    ok = True
    if total_pos < TARGET_POS_MIN:
        print(f"   [НЕДОВОД] positives < {TARGET_POS_MIN}")
        ok = False
    if len(speakers) < TARGET_SPEAKERS:
        print(f"   [НЕДОВОД] live-дикторов < {TARGET_SPEAKERS}")
        ok = False
    if bg_seconds / 3600 < TARGET_BG_HOURS:
        print(f"   [НЕДОВОД] фон < {TARGET_BG_HOURS} ч")
        ok = False
    if hard_negs < TARGET_HARD_NEGS:
        print(f"   [НЕДОВОД] трудные негативы < {TARGET_HARD_NEGS}")
        ok = False
    print("   СТАТУС: " + ("ДИАСЕТ ГОТОВ к обучению v0.2" if ok else "СБОР ПРОДОЛЖАЕТСЯ"))
    return 0 if ok else 1


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("add", help="залить партию записей в raw/<split>/<kind>/")
    a.add_argument("src", type=Path)
    a.add_argument("--speaker", required=True, help="метка диктора (латиницей, напр. ivan)")
    a.add_argument("--kind", required=True, choices=["positive", "negative"])
    a.add_argument("--split", default="train", choices=["train", "test"])
    a.add_argument("--dry", action="store_true", help="проверить без копирования")

    sub.add_parser("report", help="статус датасета против целей v0.2")

    args = ap.parse_args()
    if args.cmd == "add":
        return cmd_add(args.src, args.speaker, args.kind, args.split, args.dry)
    return cmd_report()


if __name__ == "__main__":
    sys.exit(main())
