#!/usr/bin/env python3
"""Стриминговый eval wake-word на ЖИВЫХ записях (recall по отложенным клипам).

Логика детекции та же, что в eval_streaming.py / на устройстве: embeddings
шагом 80 мс, окна [i:i+16] с шагом 1, patience=2 подряд >= threshold.

Режимы:
  --clips DIR   папка с короткими wav отдельных произнесений (16 кГц mono);
                с --manifest берутся только записи со split=test.
                Каждый клип кладётся в контекст (пад фоном) в нескольких
                условиях: silence / quiet / noisy, считается recall.
  --stream WAV  длинная запись (много произнесений подряд): считается число
                отдельных срабатываний; с --expect N выводится доля N.
  --neg DIR     (опц.) папка негативов: считается число ложных клипов.

Данные живых дикторов личные: в репозиторий не кладём, путь задаётся аргументами.

Пример:
  python training/eval_live.py --model training/model/omni_v0.2.onnx \
      --clips D:/wakeword-real/clips --manifest D:/wakeword-real/clips_manifest.json \
      --stream D:/wakeword-real/owner_omni_16k.wav --json report.json
"""
import argparse
import json
import random
from pathlib import Path

import numpy as np
import onnxruntime as ort
import scipy.io.wavfile as wavfile

from openwakeword.utils import AudioFeatures

ROOT = Path(__file__).resolve().parent
APK_DEFAULT = ROOT.parent / "app/src/main/assets/wakeword"
SR = 16000
TARGET = 64000  # 4 с: batch-of-1 workaround как в eval_streaming.py


def read_wav(p):
    sr, x = wavfile.read(str(p))
    assert sr == SR, f"{p}: ожидается 16 кГц, получено {sr}"
    if x.dtype == np.int16:
        return x.astype(np.float32) / 32768.0
    return x.astype(np.float32)


def colored_noise(n, rng, level):
    kind = rng.choice(["white", "pink", "brown"])
    w = np.random.RandomState(rng.randint(0, 2 ** 31)).randn(n).astype(np.float32)
    out = {"white": w, "pink": np.cumsum(w),
           "brown": np.convolve(w, np.ones(8) / 8, mode="same")}[kind]
    m = np.abs(out).max()
    return (out / m * level).astype(np.float32) if m > 0 else out


class Scorer:
    def __init__(self, model, apk):
        self.F = AudioFeatures(melspec_model_path=str(Path(apk) / "melspectrogram.onnx"),
                               embedding_model_path=str(Path(apk) / "embedding_model.onnx"), ncpu=1)
        self.sess = ort.InferenceSession(str(model), providers=["CPUExecutionProvider"])
        self.inp = self.sess.get_inputs()[0].name

    def scores(self, x_float, pad_to=None):
        if pad_to and len(x_float) < pad_to:
            x_float = np.pad(x_float, (0, pad_to - len(x_float)))
        x = (np.clip(x_float, -1, 1) * 32767).astype(np.int16)
        feats = self.F.embed_clips(x[None, :], batch_size=1, ncpu=1)[0]
        if len(feats) < 16:
            return np.array([])
        wins = np.stack([feats[i:i + 16] for i in range(len(feats) - 15)]).astype(np.float32)
        return np.array([self.sess.run(None, {self.inp: w[None]})[0].ravel()[0] for w in wins])


def detect(scores, thr, patience=2):
    run = 0
    for s in scores:
        run = run + 1 if s >= thr else 0
        if run >= patience:
            return True
    return False


def count_fires(scores, thr, patience=2, refractory=25):
    """Число срабатываний в стриме; refractory в окнах (25 окон = 2 с)."""
    fires, run, last = [], 0, -10 ** 9
    for i, s in enumerate(scores):
        run = run + 1 if s >= thr else 0
        if run == patience and i - last > refractory:
            fires.append(i)
            last = i
    return fires


CONDITIONS = {  # (уровень пад-шума по пику, пре-пауза с)
    "silence": 0.0,
    "quiet": 0.02,
    "noisy": 0.15,
}


def clip_in_context(word, level, rng, pre=1.6, post=0.6):
    if level == 0:
        a = np.zeros(int(SR * pre), np.float32)
        b = np.zeros(int(SR * post), np.float32)
    else:
        a = colored_noise(int(SR * pre), rng, level)
        b = colored_noise(int(SR * post), rng, level)
        word = word + colored_noise(len(word), rng, level)
    return np.concatenate([a, word, b])


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True)
    ap.add_argument("--apk", default=str(APK_DEFAULT))
    ap.add_argument("--clips")
    ap.add_argument("--manifest")
    ap.add_argument("--split", default="test", help="какой split из manifest брать (test|train|all)")
    ap.add_argument("--stream")
    ap.add_argument("--expect", type=int, default=0)
    ap.add_argument("--neg")
    ap.add_argument("--thr", default="0.2,0.3,0.35,0.4,0.5,0.6")
    ap.add_argument("--json")
    args = ap.parse_args()
    thrs = [float(t) for t in args.thr.split(",")]
    sc = Scorer(args.model, args.apk)
    report = {"model": str(args.model)}

    if args.clips:
        files = sorted(Path(args.clips).glob("*.wav"))
        if args.manifest:
            man = {m["file"]: m for m in json.load(open(args.manifest, encoding="utf-8"))}
            files = [p for p in files if p.name in man and args.split in (man[p.name]["split"], "all")]
        print(f"позитивы: {len(files)} клипов (split={args.split})")
        report["clips"] = [p.name for p in files]
        rows = {}
        for cond, level in CONDITIONS.items():
            rng = random.Random(555)
            smax = []
            for p in files:
                s = sc.scores(clip_in_context(read_wav(p), level, rng), pad_to=TARGET)
                smax.append(float(s.max()) if len(s) else 0.0)
                rows.setdefault(cond, {})[p.name] = smax[-1]
            print(f"[{cond}] max-скоры: " + " ".join(f"{v:.2f}" for v in smax))
            for thr in thrs:
                rec = float(np.mean([v >= thr for v in smax]))  # patience=2 проверяем ниже
                print(f"   thr={thr}: recall(max>=thr)={rec:.2f}")
        # честный recall с patience=2
        report["recall"] = {}
        for cond, level in CONDITIONS.items():
            for thr in thrs:
                rng = random.Random(555)
                hit = [detect(sc.scores(clip_in_context(read_wav(p), level, rng), pad_to=TARGET), thr)
                       for p in files]
                report["recall"][f"{cond}@{thr}"] = f"{sum(hit)}/{len(hit)}"
                print(f"recall patience=2 [{cond}] thr={thr}: {sum(hit)}/{len(hit)}")
        report["max_scores"] = rows

    if args.stream:
        x = read_wav(args.stream)
        s = sc.scores(x)
        report["stream"] = {}
        print(f"стрим {len(x) / SR:.1f} с, окон {len(s)}")
        for thr in thrs:
            f = count_fires(s, thr)
            times = [round(i * 0.08 + 1.28, 1) for i in f]
            report["stream"][str(thr)] = {"fires": len(f), "times_s": times}
            tail = f" из {args.expect} ({len(f) / args.expect:.2f})" if args.expect else ""
            print(f"thr={thr}: срабатываний {len(f)}{tail} @ {times}")

    if args.neg:
        files = sorted(Path(args.neg).glob("*.wav"))
        rng = random.Random(7)
        mx = [float(sc.scores(clip_in_context(read_wav(p), 0.02, rng), pad_to=TARGET).max()) for p in files]
        report["neg"] = {str(t): f"{sum(v >= t for v in mx)}/{len(mx)}" for t in thrs}
        print("негативы:", report["neg"])

    if args.json:
        Path(args.json).write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")


if __name__ == "__main__":
    main()
