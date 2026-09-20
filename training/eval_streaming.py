#!/usr/bin/env python3
"""Стриминговый eval: симуляция детекции как на устройстве.

Для каждого test-клина: слово кладём в контекст (пад background),
считаем embeddings (шаг 80 мс), скользим окнами [i:i+16] с шагом 1,
скор каждого окна из omni-модели, применяем DetectionPolicy
(threshold, patience=2). Recall = доля клипов с подтверждённой детекцией.
"""
import random
import sys
from pathlib import Path

import numpy as np
import onnxruntime as ort
import scipy.io.wavfile as wavfile

from openwakeword.utils import AudioFeatures

ROOT = Path(__file__).resolve().parent
APK = ROOT.parent / "app/src/main/assets/wakeword"
MODEL = sys.argv[1] if len(sys.argv) > 1 else str(ROOT / "model" / "omni_v0.1.onnx")

F = AudioFeatures(melspec_model_path=str(APK / "melspectrogram.onnx"),
                  embedding_model_path=str(APK / "embedding_model.onnx"), ncpu=2)
sess = ort.InferenceSession(MODEL, providers=["CPUExecutionProvider"])
IN = sess.get_inputs()[0].name

rng = random.Random(555)

def read_wav(p):
    sr, x = wavfile.read(str(p))
    return x.astype(np.float32) / 32768.0

def make_colored_noise(n):
    kind = rng.choice(["white", "pink", "brown"])
    w = np.random.randn(n).astype(np.float32)
    out = {"white": w, "pink": np.cumsum(w), "brown": np.convolve(w, np.ones(8) / 8, mode="same")}[kind]
    m = np.abs(out).max()
    return (out / m * rng.uniform(0.05, 0.3)).astype(np.float32) if m > 0 else out

def stream_scores(x_float):
    """Embeddings через полный клип (шаг 80 мс) + скоры всех окон с шагом 1."""
    TARGET = 64000  # ровно 4 сек (batch-of-1 workaround + длинный контекст)
    if len(x_float) < TARGET:
        x_float = np.pad(x_float, (0, TARGET - len(x_float)))
    x_float = x_float[:TARGET]
    x = (np.clip(x_float, -1, 1) * 32767).astype(np.int16)
    feats = F.embed_clips(x[None, :], batch_size=1, ncpu=1)[0]  # [T, 96]
    if len(feats) < 16:
        return np.array([])
    windows = np.stack([feats[i:i + 16] for i in range(0, len(feats) - 15, 1)])
    return np.array([sess.run(None, {IN: w[None].astype(np.float32)})[0].flatten()[0]
                     for w in windows])

def detect(scores, thr, patience=2):
    run = 0
    for s in scores:
        if s >= thr:
            run += 1
            if run >= patience:
                return True
        else:
            run = 0
    return False

def build_eval_clip(word, pre=1.6, post=0.4):
    """Слово в реальном контексте: пад фоном с обеих сторон."""
    pad_pre = make_colored_noise(int(16000 * pre)) 
    pad_post = make_colored_noise(int(16000 * post))
    return np.concatenate([pad_pre, word, pad_post])

def main():
    for thr in [0.3, 0.4, 0.5, 0.6, 0.7]:
        results = {}
        for split, folder in [("pos_test", ROOT / "raw" / "test" / "positive"),
                              ("neg_test", ROOT / "raw" / "test" / "negative")]:
            fired = 0
            files = sorted(folder.glob("*.wav"))
            scores_max = []
            for p in files:
                word = read_wav(p)
                clip = build_eval_clip(word)
                scores = stream_scores(clip)
                if len(scores):
                    scores_max.append(scores.max())
                    if detect(scores, thr):
                        fired += 1
            results[split] = (fired, len(files), float(np.mean(scores_max)))
        p_f, p_n, p_m = results["pos_test"]
        n_f, n_n, n_m = results["neg_test"]
        print(f"thr={thr}: recall={p_f}/{p_n} ({p_f/p_n:.2f}) | "
              f"neg-FP={n_f}/{n_n} | maxscore pos~{p_m:.2f} neg~{n_m:.2f}")

if __name__ == "__main__":
    main()
