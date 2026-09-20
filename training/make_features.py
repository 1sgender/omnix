#!/usr/bin/env python3
"""Phase B v2: аугментация (чистый numpy/scipy, потоково) + фичи (модели из APK).

Вход:  raw/{train,test}/{positive,negative}/*.wav  (16 кГц int16)
Выход: features/*.npy (см. main())
"""
import random
from pathlib import Path

import numpy as np
import scipy.io.wavfile as wav
from scipy.signal import butter, sosfilt, resample as signal_resample
from openwakeword.utils import AudioFeatures

random.seed(123)
np.random.seed(123)

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
BG = ROOT / "bg"
FEAT = ROOT / "features"
APK = ROOT.parent / "app/src/main/assets/wakeword"
SR = 16000
TOTAL_LENGTH = 32000  # 2 сек
FP_MINUTES = 25

def read_wav(p):
    sr, x = wav.read(str(p))
    assert sr == SR, f"{p}: {sr}"
    return x.astype(np.float32) / 32768.0

def save_wav(p, x):
    p.parent.mkdir(parents=True, exist_ok=True)
    wav.write(str(p), SR, (np.clip(x, -1, 1) * 32767).astype(np.int16))

def colored_noise(kind, n):
    white = np.random.randn(n).astype(np.float32)
    if kind == "white":
        out = white
    elif kind == "pink":
        out = np.cumsum(white)
    elif kind == "brown":
        out = np.convolve(white, np.ones(8) / 8, mode="same")
    else:  # violet
        out = np.diff(white, prepend=0)
    m = np.abs(out).max()
    return (out / m * np.random.uniform(0.2, 0.9)).astype(np.float32) if m > 0 else out

def make_backgrounds():
    BG.mkdir(parents=True, exist_ok=True)
    for i in range(120):
        save_wav(BG / f"noise_{i:03d}.wav",
                 colored_noise(random.choice(["white", "pink", "brown", "violet"]),
                               int(SR * random.uniform(2, 8))))
    negs = sorted((RAW / "train" / "negative").glob("*.wav"))
    for i in range(40):
        clips = [read_wav(p) for p in random.sample(negs, random.randint(3, 6))]
        speech = np.concatenate(clips)
        noise = colored_noise(random.choice(["white", "pink"]), len(speech)) * np.random.uniform(0.05, 0.4)
        save_wav(BG / f"speechbg_{i:03d}.wav", np.clip(speech * 0.8 + noise, -1, 1))
    print("фоны готовы")

def mix_at_snr(x, bg, snr_db, rng):
    """Смешать сигнал x с фоном bg на заданном SNR (по мощности)."""
    x_power = np.mean(x ** 2) + 1e-9
    bg_power = np.mean(bg ** 2) + 1e-9
    bg = bg * np.sqrt(x_power / (bg_power * 10 ** (snr_db / 10)))
    return np.clip(x + bg, -1.0, 1.0)

def augment_one(x, backgrounds, rng, snr_range=(-10, 15)):
    """Потоковая аугментация одного клипа до ровно TOTAL_LENGTH.

    Как в эталонном augment_clips: клип выравнивается к КОНЦУ окна
    (левый пад тишиной), дальше шум/фон/фильтры/гейн.
    """
    # pitch/speed-аугментация (50%): новый "диктор" тем же голосом
    if rng.random() < 0.5:
        f = rng.uniform(0.8, 1.25)
        new_len = max(200, int(len(x) / f))
        x = signal_resample(x, new_len).astype(np.float32)
    # длиннее 2 сек — оставляем конец (как truncate-start в эталоне)
    if len(x) > TOTAL_LENGTH:
        x = x[-TOTAL_LENGTH:]
    # РАНДОМИЗАЦИЯ ПОЗИЦИИ: слово целиком внутри окна, конец слова
    # равномерно в диапазоне ~1.0-1.7с. Учит модель "слово присутствует
    # в окне", а не "начало слова у последнего эмбеддинга" (баг лев-пада).
    if len(x) < TOTAL_LENGTH:
        tail_room = TOTAL_LENGTH - len(x)
        min_right = int(0.3 * SR)
        max_right = min(tail_room - int(0.4 * SR), int(1.0 * SR))
        if max_right < min_right:
            right = tail_room // 2
        else:
            right = rng.randint(min_right, max_right)
        x = np.pad(x, (TOTAL_LENGTH - len(x) - right, right))
    # гейн
    x = x * rng.uniform(0.3, 1.0)
    # 25%: notch/bandstop
    if rng.random() < 0.25:
        f = rng.uniform(300, 6000)
        sos = butter(2, [max(100, f - 150), min(7500, f + 150)], btype="bandstop", fs=SR, output="sos")
        x = sosfilt(sos, x).astype(np.float32)
    # 25%: цветной шум SNR 10..30 дБ
    if rng.random() < 0.25:
        x = mix_at_snr(x, colored_noise(rng.choice(["white", "pink", "brown"]), TOTAL_LENGTH),
                       rng.uniform(10, 30), rng)
    # 75%: фоновый клип SNR -10..15 дБ
    if rng.random() < 0.75 and backgrounds:
        bg_path = rng.choice(backgrounds)
        bg = read_wav(bg_path)
        if len(bg) < TOTAL_LENGTH:
            bg = np.pad(bg, (0, TOTAL_LENGTH - len(bg)))
        off = rng.randint(0, len(bg) - TOTAL_LENGTH)
        x = mix_at_snr(x, bg[off:off + TOTAL_LENGTH], rng.uniform(*snr_range), rng)
    return np.clip(x, -1.0, 1.0).astype(np.float32)

def aug_and_embed(paths, rounds, F, backgrounds, rng, pos_snr=None):
    """Потоково: чанк аугментации -> фичи -> yield. Память не копится."""
    out_feats = []
    all_paths = list(paths) * rounds
    rng.shuffle(all_paths)
    B = 96
    for i in range(0, len(all_paths), B):
        batch_paths = all_paths[i:i + B]
        if pos_snr is None:
            clips = np.stack([augment_one(read_wav(p), backgrounds, rng) for p in batch_paths])
        else:
            clips = np.stack([augment_one(read_wav(p), backgrounds, rng, snr_range=pos_snr)
                              for p in batch_paths])
        clips16 = (clips * 32767.0).astype(np.int16)
        feats = F.embed_clips(clips16, batch_size=96, ncpu=2)
        n16 = (feats.shape[1] // 16) * 16
        # каждые 16 подряд эмбеддингов = одно окно; берём ВСЕ непересекающиеся
        wins = [feats[:, j:j + 16, :] for j in range(0, n16, 16)]
        out_feats.append(np.concatenate(wins, axis=0))
    return np.concatenate(out_feats) if out_feats else np.zeros((0, 16, 96), np.float32)

def make_negative_segments(rng, minutes):
    """Сегменты как в FP-стриме: шум / речь-поверх-шума / почти-тишина / тоны."""
    negs = sorted((RAW / "train" / "negative").glob("*.wav"))
    segs, total = [], 0
    target = minutes * 60 * SR
    while total < target:
        kind = rng.random()
        dur = int(SR * rng.uniform(10, 45))
        if kind < 0.40:
            x = colored_noise(rng.choice(["white", "pink", "brown"]), dur) * rng.uniform(0.05, 0.7)
        elif kind < 0.75:
            noise = colored_noise(rng.choice(["white", "pink"]), dur)
            speech = np.zeros(dur, np.float32)
            pos = 0
            while pos < dur:
                c = read_wav(rng.choice(negs))
                end = min(dur, pos + len(c))
                speech[pos:end] += c[:end - pos]
                pos += len(c) + int(SR * rng.uniform(0.2, 1.5))
            x = mix_at_snr(speech, noise, rng.uniform(3, 20), rng) * rng.uniform(0.3, 0.9)
        elif kind < 0.9:
            x = colored_noise("white", dur) * rng.uniform(0.002, 0.02)
        else:
            t = np.arange(dur) / SR
            f = rng.choice([50, 60, 440, 1000])
            x = (0.3 * np.sin(2 * np.pi * f * t) + 0.1 * np.sin(2 * np.pi * f * 1.01 * t)).astype(np.float32)
            x *= rng.uniform(0.1, 0.5)
        segs.append(x.astype(np.float32))
        total += dur
    return np.concatenate(segs)[:target]

def stream_to_windows(F, stream):
    n_chunks = len(stream) // TOTAL_LENGTH
    chunks = stream[:n_chunks * TOTAL_LENGTH].reshape(n_chunks, TOTAL_LENGTH)
    feats = []
    for i in range(0, len(chunks), 96):
        c16 = (chunks[i:i + 96] * 32767.0).astype(np.int16)
        feats.append(F.embed_clips(c16, batch_size=96, ncpu=2))
    feats = np.concatenate(feats)
    # каждые 16 эмбеддингов чанка = окно; конкатенируем все окна
    n16 = (feats.shape[1] // 16) * 16
    wins = [feats[:, j:j + 16, :] for j in range(0, n16, 16)]
    return np.concatenate(wins, axis=0)

def build_fp_stream(F, minutes):
    stream = make_negative_segments(random.Random(777), minutes)
    return stream_to_windows(F, stream).reshape(-1, 96)

def main():
    make_backgrounds()
    backgrounds = sorted(BG.glob("*.wav"))
    F = AudioFeatures(
        melspec_model_path=str(APK / "melspectrogram.onnx"),
        embedding_model_path=str(APK / "embedding_model.onnx"),
        ncpu=2,
    )
    FEAT.mkdir(parents=True, exist_ok=True)

    jobs = [
        ("positive_features_train", RAW / "train" / "positive", 4, (0, 20)),
        ("negative_features_train", RAW / "train" / "negative", 4, None),
        ("positive_features_test", RAW / "test" / "positive", 1, (5, 25)),
        ("negative_features_test", RAW / "test" / "negative", 1, None),
    ]
    for name, folder, rounds, pos_snr in jobs:
        paths = sorted(folder.glob("*.wav"))
        rng = random.Random(hash(name) & 0xFFFF)
        feats = aug_and_embed(paths, rounds, F, backgrounds, rng, pos_snr=pos_snr)
        np.save(FEAT / f"{name}.npy", feats)
        print(f"[{name}] клипов={len(paths)} -> окон={feats.shape}", flush=True)

    # класс 'negative': DIVERSE-сегменты того же распределения, что FP-стрим
    # (шум/речь-поверх-шума/почти-тишина/тоны) — другой seed, ~15 минут аудио
    rng_bg = random.Random(31337)
    bg_stream = make_negative_segments(rng_bg, 12)
    # + цифровая тишина / почти-тишина (нулевые и околонулевые окна) —
    # критично: модель НЕ должна срабатывать на паузах
    silence_clips = []
    for i in range(300):
        lvl = rng_bg.choice([0.0, 0.0, 0.001, 0.004, 0.015])
        x = (np.random.randn(TOTAL_LENGTH).astype(np.float32) * lvl)
        silence_clips.append(x.astype(np.int16) if lvl == 0 else
                             (x * 32767).astype(np.int16))
    sil = np.stack(silence_clips)
    sil_feats = []
    for i in range(0, len(sil), 96):
        sil_feats.append(F.embed_clips(sil[i:i + 96], batch_size=96, ncpu=2))
    sil_feats = np.concatenate(sil_feats)[:, :16, :]
    bg_feats = np.concatenate([stream_to_windows(F, bg_stream), sil_feats])
    np.save(FEAT / "negative_bg_features_train.npy", bg_feats)
    print(f"[negative_bg] -> {bg_feats.shape}", flush=True)

    fp = build_fp_stream(F, FP_MINUTES)
    np.save(FEAT / "fp_stream.npy", fp)
    print(f"[fp_stream] {fp.shape} (~{len(fp) / 25 / 3600:.2f} ч)", flush=True)
    print("ГОТОВО")

if __name__ == "__main__":
    main()
