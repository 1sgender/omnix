"""Записи с диктофона → набор для обучения Piper (формат LJSpeech).

Каждый файл — один блок суфлёра: `ru-01.m4a` содержит фразы ru_0001…ru_0050 подряд,
с паузой ~1 с между фразами; ошибка — пауза 3 с и фраза целиком заново.

    python prepare_dataset.py --raw <папка с файлами> --out <папка набора> --asr <русская модель sherpa-onnx>

Шаги: декодирование → срез низа 70 Гц → шумоподавление по тишине файла → нарезка
по паузам → сопоставление кусков с фразами (динамическое программирование:
распознавание речи + ожидаемая длительность; кусок перед длинной паузой —
отброшенный дубль) → громкость −20 dBFS, пики не выше −1 dBFS → 22 050 Гц моно.
Пишет <out>/<язык>/wavs/*.wav, metadata.csv (`ru_0001.wav|текст`) и report.csv для проверки на слух.
"""
import argparse
import csv
import re
from difflib import SequenceMatcher
from pathlib import Path

import av
import noisereduce as nr
import numpy as np
import sherpa_onnx
from scipy.signal import butter, resample_poly, sosfiltfilt

HERE = Path(__file__).parent
BLOCK = 50
OUT_SR = 22050
FRAME_S = 0.02
SPLIT_GAP_S = 0.45      # пауза, по которой режем
RETAKE_GAP_S = 2.3      # пауза перед повтором фразы
PAD_S = 0.12
SECONDS_PER_CHAR = 0.075


# ------------------------------------------------------------------ аудио
def decode(path: Path) -> tuple[np.ndarray, int]:
    with av.open(str(path)) as f:
        stream = f.streams.audio[0]
        sr = stream.rate
        resampler = av.AudioResampler(format="flt", layout="mono", rate=sr)
        chunks = []
        for frame in f.decode(stream):
            for out in resampler.resample(frame):
                chunks.append(out.to_ndarray().reshape(-1))
    return np.concatenate(chunks).astype(np.float32), sr


def frame_energy(x: np.ndarray, sr: int) -> np.ndarray:
    n = int(sr * FRAME_S)
    k = len(x) // n
    return np.sqrt((x[: k * n].reshape(k, n) ** 2).mean(axis=1) + 1e-12)


def clean(x: np.ndarray, sr: int) -> np.ndarray:
    x = sosfiltfilt(butter(4, 70, "highpass", fs=sr, output="sos"), x).astype(np.float32)
    e = frame_energy(x, sr)
    n = int(sr * FRAME_S)
    quiet = np.argsort(e)[: max(25, len(e) // 10)]  # самые тихие 10 % кадров — профиль шума
    noise = np.concatenate([x[i * n:(i + 1) * n] for i in sorted(quiet)])
    return nr.reduce_noise(y=x, sr=sr, y_noise=noise, stationary=True, prop_decrease=0.85).astype(np.float32)


def segments(x: np.ndarray, sr: int) -> list[tuple[int, int]]:
    """Куски речи (начало, конец) в сэмплах; паузы короче SPLIT_GAP_S не режут."""
    e = frame_energy(x, sr)
    floor = np.percentile(e, 10)
    thr = max(floor * 3.5, np.percentile(e, 95) * 0.06)
    voiced = e > thr
    n = int(sr * FRAME_S)
    gap = int(SPLIT_GAP_S / FRAME_S)
    out, start, silent = [], None, 0
    for i, v in enumerate(voiced):
        if v:
            if start is None:
                start = i
            silent = 0
        elif start is not None:
            silent += 1
            if silent >= gap:
                out.append((start, i - silent + 1))
                start, silent = None, 0
    if start is not None:
        out.append((start, len(voiced)))
    return [(a * n, b * n) for a, b in out if (b - a) * FRAME_S >= 0.25]


def finish(x: np.ndarray, sr: int) -> np.ndarray:
    y = resample_poly(x, OUT_SR, sr).astype(np.float32) if sr != OUT_SR else x
    rms = np.sqrt(np.mean(y ** 2) + 1e-12)
    y = y * (10 ** (-20 / 20) / rms)
    peak = np.abs(y).max()
    if peak > 10 ** (-1 / 20):
        y = y * (10 ** (-1 / 20) / peak)
    return y


def write_wav(path: Path, y: np.ndarray):
    import wave
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(OUT_SR)
        w.writeframes((np.clip(y, -1, 1) * 32767).astype(np.int16).tobytes())


# ------------------------------------------------------------------ текст
TK_TO_CYR = {"a": "а", "b": "б", "ç": "ч", "d": "д", "e": "е", "ä": "э", "f": "ф", "g": "г", "h": "х", "i": "и",
             "j": "дж", "ž": "ж", "k": "к", "l": "л", "m": "м", "n": "н", "ň": "н", "o": "о", "ö": "ё", "p": "п",
             "r": "р", "s": "с", "ş": "ш", "t": "т", "u": "у", "ü": "ю", "w": "в", "y": "ы", "ý": "й", "z": "з"}


def norm(text: str, lang: str) -> str:
    t = text.lower().replace("ё", "е")
    if lang == "tk":
        t = "".join(TK_TO_CYR.get(c, c) for c in t)
    return re.sub(r"[^а-яa-z]", "", t)


def similarity(heard: str, expected: str) -> float:
    if not heard or not expected:
        return 0.0
    return SequenceMatcher(None, heard, expected).ratio()


# ------------------------------------------------------------- сопоставление
def align(segs, heard, phrases, lang, sr):
    """DP: каждая фраза — 1–3 подряд идущих куска; лишние куски — дубли/шум (отброшены)."""
    n, m = len(phrases), len(segs)
    exp = [norm(t, lang) for _, t in phrases]
    gaps_after = [(segs[j + 1][0] - segs[j][1]) / sr if j + 1 < m else 99 for j in range(m)]
    INF = 1e9
    cost = np.full((n + 1, m + 1), INF)
    back = {}
    cost[0][0] = 0
    for i in range(n + 1):
        for j in range(m + 1):
            c = cost[i][j]
            if c >= INF:
                continue
            if j < m:  # отбросить кусок: дубль перед длинной паузой — дёшево
                skip = 0.15 if gaps_after[j] >= RETAKE_GAP_S else 0.7
                if c + skip < cost[i][j + 1]:
                    cost[i][j + 1] = c + skip
                    back[(i, j + 1)] = ("skip", i, j)
            if i < n:
                for k in range(j + 1, min(m, j + 3) + 1):
                    text = "".join(heard[j:k])
                    dur = (segs[k - 1][1] - segs[j][0]) / sr
                    want = max(0.6, len(exp[i]) * SECONDS_PER_CHAR)
                    dpen = min(1.0, abs(np.log(dur / want))) * 0.5
                    step = (1 - similarity(text, exp[i])) + dpen + 0.1 * (k - j - 1)
                    if c + step < cost[i + 1][k]:
                        cost[i + 1][k] = c + step
                        back[(i + 1, k)] = ("take", i, j)
    # фраз может быть меньше в записи (блок не дочитан): берём лучший конец
    i_end = max(range(n + 1), key=lambda i: (cost[i][m] < INF, i))
    picks, i, j = {}, i_end, m
    while (i, j) != (0, 0):
        kind, pi, pj = back[(i, j)]
        if kind == "take":
            picks[pi] = (pj, j)
        i, j = pi, pj
    return picks


# ------------------------------------------------------------------- main
def load_phrases(lang: str) -> list[tuple[str, str]]:
    rows = []
    for line in (HERE / f"phrases_{lang}.txt").read_text(encoding="utf-8").splitlines():
        pid, text = line.split("|", 1)
        rows.append((pid, text))
    return rows


def recognizer(model_dir: Path):
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(model_dir / "tokens.txt"), encoder=str(model_dir / "encoder.int8.onnx"),
        decoder=str(model_dir / "decoder.onnx"), joiner=str(model_dir / "joiner.int8.onnx"),
        num_threads=4, decoding_method="greedy_search")


def transcribe(rec, x: np.ndarray, sr: int) -> str:
    s = rec.create_stream()
    s.accept_waveform(sr, x)
    s.accept_waveform(sr, np.zeros(int(sr * 0.5), np.float32))
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    return rec.get_result(s)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--asr", type=Path, required=True)
    args = ap.parse_args()
    rec = recognizer(args.asr)
    all_phrases = {lang: load_phrases(lang) for lang in ("ru", "tk")}
    for f in sorted(args.raw.iterdir()):
        mt = re.match(r"(ru|tk)[-_ ]?0*(\d+)", f.stem.lower())
        if not mt or f.suffix.lower() not in {".m4a", ".mp3", ".wav", ".aac", ".ogg", ".opus", ".3gp", ".amr", ".flac"}:
            continue
        lang, block = mt.group(1), int(mt.group(2))
        phrases = all_phrases[lang][(block - 1) * BLOCK: block * BLOCK]
        x, sr = decode(f)
        x = clean(x, sr)
        segs = segments(x, sr)
        heard = [norm(transcribe(rec, x[a:b], sr), "ru") for a, b in segs]
        picks = align(segs, heard, phrases, lang, sr)
        out_dir = args.out / lang
        report_rows = []
        pad = int(sr * PAD_S)
        for i, (pid, text) in enumerate(phrases):
            if i not in picks:
                report_rows.append([pid, "", "", "", "НЕ НАЙДЕНА", text])
                continue
            j, k = picks[i]
            a, b = max(0, segs[j][0] - pad), min(len(x), segs[k - 1][1] + pad)
            clip = x[a:b]
            y = finish(clip, sr)
            write_wav(out_dir / "wavs" / f"{pid}.wav", y)
            sim = similarity("".join(heard[j:k]), norm(text, lang))
            raw_peak = float(np.abs(clip).max())
            flags = []
            # Туркменскую речь распознаёт русская модель — сходство там мало что значит,
            # поэтому для неё полагаемся на длительность.
            if lang == "ru" and sim < 0.55:
                flags.append("проверить текст")
            want = max(0.6, len(norm(text, lang)) * SECONDS_PER_CHAR)
            if abs(np.log((len(y) / OUT_SR) / want)) > 0.7:
                flags.append("проверить длину")
            if raw_peak > 0.98:
                flags.append("перегруз")
            if raw_peak < 0.05:
                flags.append("тихо")
            report_rows.append([pid, f"{sim:.2f}", f"{len(y) / OUT_SR:.1f}", f"{j}-{k}", "; ".join(flags), text])
        meta = out_dir / "metadata.csv"
        existing = {}
        if meta.is_file():
            for line in meta.read_text(encoding="utf-8").splitlines():
                name, t = line.split("|", 1)
                existing[name.removesuffix(".wav")] = t
        for pid, text in phrases:
            if (out_dir / "wavs" / f"{pid}.wav").is_file():
                existing[pid] = text
        # Piper: первая колонка — имя файла в audio_dir, с расширением.
        meta.write_text("".join(f"{p}.wav|{t}\n" for p, t in sorted(existing.items())), encoding="utf-8")
        with open(out_dir / f"report-{lang}-{block:02d}.csv", "w", encoding="utf-8-sig", newline="") as fh:
            w = csv.writer(fh)
            w.writerow(["id", "сходство", "секунд", "куски", "пометки", "текст"])
            w.writerows(report_rows)
        found = sum(1 for r in report_rows if r[4] != "НЕ НАЙДЕНА")
        flagged = sum(1 for r in report_rows if r[4] and r[4] != "НЕ НАЙДЕНА")
        print(f"{f.name}: кусков {len(segs)}, фраз найдено {found}/{len(phrases)}, на проверку {flagged}")


if __name__ == "__main__":
    main()
