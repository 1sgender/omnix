#!/usr/bin/env python3
"""Phase C+D: обучение wake-word модели «Omni» (DNN-голова openWakeWord),
merge лучших чекпойнтов, подбор порога, экспорт в ONNX [1,16,96]->[1,1].

Использует Model из официального openwakeword/train.py (v0.6.0) и
mmap_batch_generator. Фичи посчитаны ровно теми melspectrogram/embedding
моделями, что лежат в APK (проверено по SHA256SUMS).
"""
import copy
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader, TensorDataset, IterableDataset

# официальный Model-класс из openwakeword train.py
spec = importlib.util.spec_from_file_location("oww_train", str(Path(__file__).resolve().parent / "oww_train.py"))
oww_train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oww_train)
OWWModel = oww_train.Model
from openwakeword.data import mmap_batch_generator

ROOT = Path(__file__).resolve().parent
FEAT = ROOT / "features"
OUT = ROOT / "model"
OUT.mkdir(parents=True, exist_ok=True)

torch.manual_seed(2024)
np.random.seed(2024)

STEPS_1 = 25000
STEPS_2 = 3000
MAX_NEG_WEIGHT = 150
TARGET_FP_PER_HR = 1.0

def build_train_loader():
    files = {
        "positive": str(FEAT / "positive_features_train.npy"),
        "negative": str(FEAT / "negative_bg_features_train.npy"),
        "adversarial_negative": str(FEAT / "negative_features_train.npy"),
    }
    data_transforms = {k: (lambda x, n=16: x if x.shape[1] == n else x[:, :n, :]) for k in files}
    label_transforms = {
        "positive": lambda x: [1 for _ in x],
        "negative": lambda x: [0 for _ in x],
        "adversarial_negative": lambda x: [0 for _ in x],
    }
    gen = mmap_batch_generator(
        files,
        n_per_class={"positive": 64, "negative": 64, "adversarial_negative": 64},
        data_transform_funcs=data_transforms,
        label_transform_funcs=label_transforms,
    )

    class IterDS(IterableDataset):
        def __iter__(self):
            return gen

    return DataLoader(IterDS(), batch_size=None, num_workers=0)

def build_val():
    pos = np.load(FEAT / "positive_features_test.npy")      # (180, 16, 96)
    neg = np.load(FEAT / "negative_features_test.npy")      # (100, 16, 96)
    bg = np.load(FEAT / "negative_bg_features_train.npy")   # (80, 16, 96)
    X = np.concatenate([pos, neg, bg]).astype(np.float32)
    y = np.concatenate([np.ones(len(pos)), np.zeros(len(neg) + len(bg))]).astype(np.float32)
    ds = TensorDataset(torch.from_numpy(X), torch.from_numpy(y))
    loader = DataLoader(ds, batch_size=len(X))
    return loader, len(pos), len(neg) + len(bg)

def build_fp():
    stream = np.load(FEAT / "fp_stream.npy")  # (T, 96)
    windows = np.array([stream[i:i + 16] for i in range(0, len(stream) - 16, 1)], dtype=np.float32)
    labels = np.zeros(len(windows), np.float32)
    ds = TensorDataset(torch.from_numpy(windows), torch.from_numpy(labels))
    loader = DataLoader(ds, batch_size=2048)
    # каждое окно = 80 мс стриминга
    val_hrs = len(windows) * 0.08 / 3600
    return loader, val_hrs, windows

@torch.no_grad()
def score_all(model, windows_np, bs=2048):
    model.eval()
    out = []
    for i in range(0, len(windows_np), bs):
        x = torch.from_numpy(windows_np[i:i + bs])
        out.append(model(x).squeeze(-1).numpy())
    return np.concatenate(out)

def simulate_fires(scores, thr, patience=2):
    """Симуляция DetectionPolicy: patience подряд кадров >= thr => 1 срабатывание."""
    fires, run = 0, 0
    for s in scores:
        if s >= thr:
            run += 1
            if run == patience:
                fires += 1
        else:
            run = 0
    return fires

def main():
    X_train = build_train_loader()
    X_val, n_pos, n_neg = build_val()
    fp_loader, val_hrs, fp_windows = build_fp()
    print(f"train loader готов; val: pos={n_pos} neg={n_neg}; "
          f"fp: {len(fp_windows)} окон (~{val_hrs:.2f} ч)", flush=True)

    model = OWWModel(n_classes=1, input_shape=(16, 96), model_type="dnn",
                     layer_dim=128, n_blocks=1,
                     seconds_per_example=1280 * 16 / 16000)

    def run_sequence(steps, lr, max_neg_weight, tag):
        weights = np.linspace(1, max_neg_weight, int(steps)).tolist()
        val_steps = np.linspace(1, steps, 20).astype(np.int64).tolist()
        model.train_model(
            X=X_train, X_val=X_val,
            false_positive_val_data=fp_loader,
            max_steps=steps,
            negative_weight_schedule=weights,
            val_steps=val_steps,
            warmup_steps=steps // 5,
            hold_steps=steps // 3,
            lr=lr,
            val_set_hrs=val_hrs,
        )
        print(f"[{tag}] best_val_fp={getattr(model, 'best_val_fp', '?')} "
              f"checkpoints={len(model.best_models)}", flush=True)

    print("=== Sequence 1 ===", flush=True)
    run_sequence(STEPS_1, 0.0001, MAX_NEG_WEIGHT, "seq1")

    print("=== Sequence 2 ===", flush=True)
    max_w = MAX_NEG_WEIGHT * (2 if getattr(model, "best_val_fp", 0) > TARGET_FP_PER_HR else 1)
    run_sequence(STEPS_2, 0.00001, max_w, "seq2")

    # merge: топ-5 по сбалансированному скору recall - 0.02*val_n_fp
    combined = model.model
    if model.best_models:
        scores = model.best_model_scores
        for i, sc in enumerate(scores):
            print(f"  ckpt[{i}]: fp={float(sc['val_n_fp']):.0f} recall={float(sc['val_recall']):.3f}", flush=True)
        order = sorted(range(len(scores)),
                       key=lambda i: -(float(scores[i]["val_recall"]) - 0.02 * float(scores[i]["val_n_fp"])))
        top = order[:5]
        combined = model.average_models(models=[model.best_models[i] for i in top])
        print(f"merge: top{len(top)}/{len(model.best_models)}: "
              f"{[(float(scores[i]['val_n_fp']), round(float(scores[i]['val_recall']), 3)) for i in top]}", flush=True)
    print("пул чекпойнтов см. выше; финальная модель сохраняется параллельно", flush=True)

    # ===== Оценка и подбор порога =====
    pos_test = np.load(FEAT / "positive_features_test.npy").astype(np.float32)
    neg_test = np.concatenate([
        np.load(FEAT / "negative_features_test.npy"),
        np.load(FEAT / "negative_bg_features_train.npy"),
    ]).astype(np.float32)

    pos_scores = score_all(combined, pos_test)
    neg_scores = score_all(combined, neg_test)
    fp_scores = score_all(combined, fp_windows)

    report = {
        "pos_scores": {"min": float(pos_scores.min()), "mean": float(pos_scores.mean()),
                       "p10": float(np.percentile(pos_scores, 10)),
                       "max": float(pos_scores.max())},
        "neg_scores": {"max": float(neg_scores.max()), "mean": float(neg_scores.mean())},
        "fp_stream_max": float(fp_scores.max()),
        "thresholds": {},
    }
    print("\n=== Подбор порога (patience=2, cooldown игнорируем) ===")
    print(f"{'thr':>5} {'recall':>8} {'negFP':>6} {'fp/hr':>8}")
    best_thr, best_score = 0.5, -1
    for thr in [0.3, 0.4, 0.5, 0.6, 0.7, 0.8]:
        recall = float((pos_scores >= thr).mean())
        neg_fp = int((neg_scores >= thr).sum())
        fires = simulate_fires(fp_scores, thr, patience=2)
        fp_hr = fires / val_hrs
        report["thresholds"][str(thr)] = {"recall": recall, "neg_windows_fp": neg_fp,
                                          "fp_per_hr": fp_hr}
        print(f"{thr:5.1f} {recall:8.3f} {neg_fp:6d} {fp_hr:8.2f}")
        # приоритет: ноль ложных на негативах, потом recall
        score = recall - (100 if neg_fp > 0 else 0) - min(fp_hr, 10)
        if score > best_score:
            best_score, best_thr = score, thr
    report["chosen_threshold"] = best_thr
    # per-voice breakdown: первые 90 — en_GB-jenny, вторые 90 — ru_RU-ruslan
    report["per_voice_recall_0.5"] = {
        "jenny_en": float((pos_scores[:90] >= 0.5).mean()),
        "ruslan_ru": float((pos_scores[90:] >= 0.5).mean()),
    }
    print(f"\nвыбран порог: {best_thr}")

    # ===== Экспорт ONNX (контракт hey_jarvis: вход [1,16,96], выход [1,1]) =====
    import onnx

    def export_nn(nn_model, path):
        model_to_save = copy.deepcopy(nn_model).to("cpu").eval()
        torch.onnx.export(model_to_save, torch.rand(1, 16, 96), str(path))
        m = onnx.load(str(path))
        onnx.save_model(m, str(path), save_as_external_data=False)

    export_nn(combined, OUT / "omni_v0.1.onnx")
    export_nn(model.model, OUT / "omni_v0.1_final.onnx")
    (OUT / "metrics.json").write_text(json.dumps(report, indent=2))
    print(f"\nсохранено: {onnx_path}")
    print("ГОТОВО")

if __name__ == "__main__":
    main()
