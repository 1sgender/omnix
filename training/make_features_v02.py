#!/usr/bin/env python3
"""Phase B v0.2: аугментация + фичи (melspectrogram/embedding из APK) для raw_v2.

Использует функции make_features.py (v0.1), но с внешними папками и тремя
отличиями:
  * вход raw_v2/{train,test}/{positive,negative}/ из gen_raw_v2.py и
    live_augment.py (живые клипы диктора лежат в train/positive с префиксом
    <speaker>_..., его отложенные клипы — в raw_v2/test_live/ и в train НЕ попадают);
  * позитивы живых дикторов (по умолчанию префикс "owner_") получают больше
    раундов аугментации (--live-rounds), синтетика — --rounds;
  * детерминированные seed (v0.1 использовала hash(), зависящий от процесса).

Выход (в --out): positive_features_{train,test}.npy, negative_features_{train,test}.npy,
negative_bg_features_train.npy, fp_stream.npy, stats.json.
Пример:
  python training/make_features_v02.py --raw D:/wakeword-real/raw_v2 --out D:/wakeword-real/features_v2
"""
import argparse
import json
import random
import zlib
from pathlib import Path

import numpy as np

import make_features as mf
from openwakeword.utils import AudioFeatures


def seeded(name):
    return random.Random(zlib.crc32(name.encode()))


def silence_windows(F, rng, n=300):
    clips = []
    for _ in range(n):
        lvl = rng.choice([0.0, 0.0, 0.001, 0.004, 0.015])
        x = np.random.randn(mf.TOTAL_LENGTH).astype(np.float32) * lvl
        clips.append((x * 32767).astype(np.int16))
    sil = np.stack(clips)
    feats = [F.embed_clips(sil[i:i + 96], batch_size=96, ncpu=2) for i in range(0, len(sil), 96)]
    return np.concatenate(feats)[:, :16, :]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--apk", default=str(mf.APK))
    ap.add_argument("--rounds", type=int, default=3, help="раундов аугментации синтетики (позитивы/негативы train)")
    ap.add_argument("--live-rounds", type=int, default=5, help="раундов для живых позитивов train")
    ap.add_argument("--live-prefix", default="owner_")
    ap.add_argument("--fp-minutes", type=int, default=mf.FP_MINUTES)
    args = ap.parse_args()

    raw, out = Path(args.raw), Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    mf.RAW, mf.BG, mf.FEAT = raw, out / "bg", out
    random.seed(123)
    np.random.seed(123)
    mf.make_backgrounds()
    backgrounds = sorted(mf.BG.glob("*.wav"))
    apk = Path(args.apk)
    F = AudioFeatures(melspec_model_path=str(apk / "melspectrogram.onnx"),
                      embedding_model_path=str(apk / "embedding_model.onnx"), ncpu=2)
    stats = {}

    def run(name, paths, rounds, pos_snr):
        rng = seeded(name)
        feats = mf.aug_and_embed(paths, rounds, F, backgrounds, rng, pos_snr=pos_snr)
        stats[name] = {"clips": len(paths), "rounds": rounds, "windows": int(len(feats))}
        print(f"[{name}] клипов={len(paths)} x{rounds} -> окон={feats.shape}", flush=True)
        return feats

    pos_all = sorted((raw / "train" / "positive").glob("*.wav"))
    live = [p for p in pos_all if p.name.startswith(args.live_prefix)]
    synth = [p for p in pos_all if not p.name.startswith(args.live_prefix)]
    parts = [run("positive_synth_train", synth, args.rounds, (0, 20))]
    if live:
        parts.append(run("positive_live_train", live, args.live_rounds, (0, 20)))
    np.save(out / "positive_features_train.npy", np.concatenate(parts))

    np.save(out / "negative_features_train.npy",
            run("negative_train", sorted((raw / "train" / "negative").glob("*.wav")), args.rounds, None))
    np.save(out / "positive_features_test.npy",
            run("positive_test", sorted((raw / "test" / "positive").glob("*.wav")), 1, (5, 25)))
    np.save(out / "negative_features_test.npy",
            run("negative_test", sorted((raw / "test" / "negative").glob("*.wav")), 1, None))

    rng_bg = random.Random(31337)
    bg_stream = mf.make_negative_segments(rng_bg, 12)
    bg_feats = np.concatenate([mf.stream_to_windows(F, bg_stream), silence_windows(F, rng_bg)])
    np.save(out / "negative_bg_features_train.npy", bg_feats)
    stats["negative_bg"] = {"windows": int(len(bg_feats))}
    fp = mf.build_fp_stream(F, args.fp_minutes)
    np.save(out / "fp_stream.npy", fp)
    stats["fp_stream"] = {"frames": int(len(fp)), "hours": round(len(fp) / 12.5 / 3600, 3)}
    (out / "stats.json").write_text(json.dumps(stats, indent=1), encoding="utf-8")
    print("ГОТОВО", json.dumps(stats))


if __name__ == "__main__":
    main()
