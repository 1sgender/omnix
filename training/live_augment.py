#!/usr/bin/env python3
"""Нарезка живой записи диктора на произнесения + аугментация (v0.2).

Зачем: живой голос (а не TTS) критичен для приёмки; записи личные, поэтому
скрипт работает с внешней папкой и ничего не кладёт в репозиторий.

Два режима:
  slice    длинная запись (много «Омни» с паузами) -> clips/<speaker>_NNN.wav
           + clips_manifest.json (split train/test; пометки excluded).
  augment  клипы со split=train -> N вариантов на клип в <raw>/train/positive/
           (speaker=<speaker>), клипы со split=test копируются в <raw>/test_live/
           БЕЗ размножения (честная проверка). Аугментации: pitch и скорость
           (phase vocoder, 0.8-1.25), шум разного SNR (цветной + «бормотание»
           из TTS-негативов), реверберация комнаты (синтетический RIR),
           дистанция (спад уровня + НЧ-фильтр + больше хвоста), лёгкий шёпот
           (LPC-шумовое возбуждение), микрофон носимого (фильтр/клиппинг).

Примеры:
  python training/live_augment.py slice --wav owner_omni_16k.wav --out wakeword-real/clips \
      --speaker owner --test-idx 3,7,11,14 --exclude 1,4,18,19,20
  python training/live_augment.py augment --clips wakeword-real/clips \
      --raw wakeword-real/raw_v2 --variants 30
"""
import argparse
import json
import random
import shutil
import wave
from pathlib import Path

import numpy as np
import scipy.io.wavfile as wavfile
from scipy.signal import butter, fftconvolve, lfilter, resample, sosfilt

SR = 16000


# ----------------------------- I/O --------------------------------------------
def read_wav(p):
    sr, x = wavfile.read(str(p))
    assert sr == SR, f"{p}: ожидается 16 кГц, получено {sr}"
    return x.astype(np.float32) / 32768.0 if x.dtype == np.int16 else x.astype(np.float32)


def write_wav(p, x):
    p.parent.mkdir(parents=True, exist_ok=True)
    wavfile.write(str(p), SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))


# ----------------------------- нарезка -----------------------------------------
def segment(x, hi=0.075, lo=0.05, min_gap=0.25, min_dur=0.25, min_peak=0.09, split_over=1.3):
    """Границы произнесений по огибающей RMS (гистерезис hi/lo), в секундах."""
    fr = int(0.025 * SR)
    n = len(x) // fr
    e = np.sqrt((x[:n * fr].reshape(n, fr) ** 2).mean(1))
    es = np.convolve(e, np.ones(3) / 3, mode="same")
    segs, s = [], None
    for i, v in enumerate(es):
        if s is None and v > hi:
            s = i
        elif s is not None and v < lo:
            segs.append([s, i])
            s = None
    if s is not None:
        segs.append([s, n])
    merged = []
    for a, b in segs:
        if merged and (a - merged[-1][1]) * 0.025 < min_gap:
            merged[-1][1] = b
        else:
            merged.append([a, b])
    out = []
    for a, b in merged:
        if (b - a) * 0.025 > split_over:  # возможно два слова подряд: режем по минимуму
            lo_i, hi_i = a + int((b - a) * 0.3), a + int((b - a) * 0.7)
            k = lo_i + int(np.argmin(es[lo_i:hi_i]))
            out += [[a, k], [k, b]]
        else:
            out.append([a, b])
    return [(a * 0.025, b * 0.025, float(es[a:b].max())) for a, b in out
            if (b - a) * 0.025 >= min_dur and es[a:b].max() >= min_peak]


def cmd_slice(args):
    x = read_wav(args.wav)
    segs = segment(x)
    test = {int(i) for i in args.test_idx.split(",") if i}
    excl = {int(i) for i in args.exclude.split(",") if i}
    out = Path(args.out)
    man = []
    margin = int(0.08 * SR)
    for i, (a, b, pk) in enumerate(segs, 1):
        s0, s1 = max(0, int(a * SR) - margin), min(len(x), int(b * SR) + margin)
        name = f"{args.speaker}_{i:03d}.wav"
        write_wav(out / name, x[s0:s1])
        split = "excluded" if i in excl else ("test" if i in test else "train")
        man.append(dict(file=name, start=round(a, 3), end=round(b, 3), dur=round(b - a, 2),
                        peak=round(pk, 3), speaker=args.speaker, split=split))
    (out.parent / "clips_manifest.json").write_text(json.dumps(man, indent=1, ensure_ascii=False))
    print(f"произнесений: {len(man)}  train={sum(m['split'] == 'train' for m in man)} "
          f"test={sum(m['split'] == 'test' for m in man)}")


# ----------------------------- DSP ---------------------------------------------
def phase_vocoder(x, rate, n_fft=512, hop=128):
    """Растяжение по времени без смены высоты: rate>1 быстрее (короче)."""
    win = np.hanning(n_fft).astype(np.float32)
    pad = np.pad(x, (n_fft, n_fft))
    frames = np.stack([pad[i:i + n_fft] * win for i in range(0, len(pad) - n_fft, hop)])
    S = np.fft.rfft(frames, axis=1)
    steps = np.arange(0, S.shape[0] - 1, rate)
    omega = 2 * np.pi * hop * np.arange(S.shape[1]) / n_fft
    phase = np.angle(S[0])
    out = np.zeros((len(steps), S.shape[1]), np.complex64)
    for k, t in enumerate(steps):
        i = int(t)
        frac = t - i
        mag = (1 - frac) * np.abs(S[i]) + frac * np.abs(S[i + 1])
        out[k] = mag * np.exp(1j * phase)
        dphi = np.angle(S[i + 1]) - np.angle(S[i]) - omega
        dphi -= 2 * np.pi * np.round(dphi / (2 * np.pi))
        phase = phase + omega + dphi
    y = np.fft.irfft(out, axis=1) * win
    res = np.zeros(hop * (len(y) - 1) + n_fft, np.float32)
    norm = np.zeros_like(res)
    for k in range(len(y)):
        res[k * hop:k * hop + n_fft] += y[k]
        norm[k * hop:k * hop + n_fft] += win ** 2
    res /= np.maximum(norm, 1e-3)
    return res[n_fft:-n_fft].astype(np.float32)


def pitch_shift(x, factor):
    """factor>1 выше тоном, длительность прежняя."""
    stretched = phase_vocoder(x, 1.0 / factor)  # длиннее в factor раз
    return resample(stretched, len(x)).astype(np.float32)


def colored_noise(kind, n, rng):
    w = np.random.RandomState(rng.randint(0, 2 ** 31)).randn(n).astype(np.float32)
    if kind == "pink":
        w = np.cumsum(w)
        w -= np.convolve(w, np.ones(2000) / 2000, mode="same")
    elif kind == "brown":
        w = np.convolve(w, np.ones(8) / 8, mode="same")
    return w / (np.abs(w).max() + 1e-9)


def add_at_snr(x, noise, snr_db):
    px, pn = np.mean(x ** 2) + 1e-9, np.mean(noise ** 2) + 1e-9
    return x + noise * np.sqrt(px / (pn * 10 ** (snr_db / 10)))


def make_rir(rng, rt60, direct=1.0, wet=1.0):
    n = int(SR * min(1.2, rt60 * 1.1))
    t = np.arange(n) / SR
    tail = np.random.RandomState(rng.randint(0, 2 ** 31)).randn(n) * np.exp(-6.9 * t / rt60)
    tail[: int(0.004 * SR)] = 0
    rir = tail.astype(np.float32) * wet * 0.15
    for _ in range(rng.randint(3, 7)):  # ранние отражения
        rir[int(rng.uniform(0.003, 0.04) * SR)] += rng.uniform(0.2, 0.6) * rng.choice([-1, 1])
    rir[0] += direct
    return rir


def reverb(x, rng, rt60=None, direct=1.0, wet=1.0):
    rt60 = rt60 or rng.uniform(0.2, 0.9)
    y = fftconvolve(x, make_rir(rng, rt60, direct, wet))[:len(x) + int(0.3 * SR)]
    return (y * (np.sqrt(np.mean(x ** 2) + 1e-9) / (np.sqrt(np.mean(y ** 2) + 1e-9)))).astype(np.float32)


def lowpass(x, fc):
    return sosfilt(butter(2, min(fc, 7800), btype="low", fs=SR, output="sos"), x).astype(np.float32)


def highpass(x, fc):
    return sosfilt(butter(2, fc, btype="high", fs=SR, output="sos"), x).astype(np.float32)


def lpc(frame, order=16):
    r = np.correlate(frame, frame, "full")[len(frame) - 1:len(frame) + order]
    if r[0] < 1e-9:
        return np.r_[1.0, np.zeros(order)]
    a = np.zeros(order + 1)
    a[0], e = 1.0, r[0]
    for i in range(1, order + 1):
        k = -(r[i] + np.dot(a[1:i], r[i - 1:0:-1])) / e
        a[1:i + 1] = np.r_[a[1:i] + k * a[i - 1:0:-1], k]
        e *= 1 - k * k
        if e <= 0:
            break
    return a


def whisper(x, rng, mix=1.0):
    """Шёпот: огибающая спектра по LPC, возбуждение шумом вместо гортанного тона."""
    n_fft, hop = 400, 200
    win = np.hanning(n_fft)
    out = np.zeros(len(x) + n_fft, np.float32)
    noise = np.random.RandomState(rng.randint(0, 2 ** 31)).randn(len(x) + n_fft)
    for i in range(0, len(x) - n_fft, hop):
        fr = x[i:i + n_fft] * win
        a = lpc(fr)
        exc = noise[i:i + n_fft] * win
        y = lfilter([1.0], a, exc)
        g = np.sqrt((fr ** 2).mean() / ((y ** 2).mean() + 1e-12))
        out[i:i + n_fft] += (y * g * np.hanning(n_fft)).astype(np.float32)
    out = out[:len(x)]
    out *= np.sqrt(np.mean(x ** 2) + 1e-9) / (np.sqrt(np.mean(out ** 2)) + 1e-9)
    return (mix * out + (1 - mix) * x).astype(np.float32)


def mic_wearable(x, rng):
    x = highpass(x, rng.uniform(120, 300))
    x = lowpass(x, rng.uniform(4500, 7500))
    drive = rng.uniform(1.0, 3.0)
    return (np.tanh(x * drive) / np.tanh(drive)).astype(np.float32)


def augment_variant(word, rng, babble=None):
    """Одна случайная комбинация аугментаций. Возвращает float32 клип (слово+поля)."""
    f_speed = rng.uniform(0.8, 1.25)
    f_pitch = rng.uniform(0.85, 1.18)
    x = word.astype(np.float32)
    if rng.random() < 0.85:
        x = phase_vocoder(x, f_speed)
    if rng.random() < 0.7:
        x = pitch_shift(x, f_pitch)
    ops = []
    if rng.random() < 0.20:
        x = whisper(x, rng, mix=rng.uniform(0.6, 1.0))
        ops.append("whisper")
    if rng.random() < 0.55:
        x = reverb(x, rng)
        ops.append("reverb")
    if rng.random() < 0.30:  # дистанция 1-4 м: слабее, глуше, больше хвоста, шумовой пол
        d = rng.uniform(1.0, 4.0)
        x = reverb(x, rng, rt60=rng.uniform(0.3, 0.8), direct=1.0 / d, wet=1.0)
        x = lowpass(x, 7500 / np.sqrt(d))
        x *= 1.0 / d
        ops.append(f"dist{d:.1f}")
    if rng.random() < 0.30:
        x = mic_wearable(x, rng)
        ops.append("mic")
    r = rng.random()
    if r < 0.80:  # шум
        if babble is not None and rng.random() < 0.35:
            b = babble[rng.randint(0, len(babble) - 1)]
            b = np.resize(b, len(x)) if len(b) < len(x) else b[:len(x)]
            x = add_at_snr(x, b, rng.uniform(3, 22))
            ops.append("babble")
        else:
            x = add_at_snr(x, colored_noise(rng.choice(["white", "pink", "brown"]), len(x), rng),
                           rng.uniform(0, 25))
            ops.append("noise")
    x = x * rng.uniform(0.3, 1.0) / (np.abs(x).max() + 1e-9)
    return x.astype(np.float32), ops


def cmd_augment(args):
    clips = Path(args.clips)
    man = json.loads((clips.parent / "clips_manifest.json").read_text(encoding="utf-8")) \
        if not args.manifest else json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    raw = Path(args.raw)
    rng = random.Random(2026)
    babble = None
    neg_dir = raw / "train" / "negative"
    if neg_dir.exists():
        files = sorted(neg_dir.glob("*.wav"))[:400]
        if files:
            babble = [np.concatenate([read_wav(p) for p in rng.sample(files, min(4, len(files)))])
                      for _ in range(30)]
    n_train = n_aug = 0
    for m in man:
        src = clips / m["file"]
        if m["split"] == "test":
            (raw / "test_live").mkdir(parents=True, exist_ok=True)
            shutil.copy(src, raw / "test_live" / m["file"])
            continue
        if m["split"] != "train":
            continue
        n_train += 1
        word = read_wav(src)
        for v in range(args.variants):
            y, ops = augment_variant(word, rng, babble)
            write_wav(raw / "train" / "positive" / f"{Path(m['file']).stem}_v{v:02d}.wav", y)
            n_aug += 1
        write_wav(raw / "train" / "positive" / f"{Path(m['file']).stem}_orig.wav", word)
    print(f"train-клипов: {n_train}, вариантов: {n_aug} (+ оригиналы), speaker={man[0]['speaker']}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    s = sub.add_parser("slice")
    s.add_argument("--wav", required=True)
    s.add_argument("--out", required=True)
    s.add_argument("--speaker", default="owner")
    s.add_argument("--test-idx", default="")
    s.add_argument("--exclude", default="")
    a = sub.add_parser("augment")
    a.add_argument("--clips", required=True)
    a.add_argument("--manifest")
    a.add_argument("--raw", required=True)
    a.add_argument("--variants", type=int, default=30)
    args = ap.parse_args()
    {"slice": cmd_slice, "augment": cmd_augment}[args.cmd](args)


if __name__ == "__main__":
    main()
