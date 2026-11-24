#!/usr/bin/env python3
"""wake-word v0.2 fp-fix: обучение головы «Omni» с отбором чекпойнтов по ложным срабатываниям.

Что исправляет по сравнению с train_omni.py (v0.2, первый прогон):
  * train_omni отбирает чекпойнты по val_n_fp/val_recall на 900 одиночных окнах и сливает top-5
    по recall - 0.02*FP; в итоге лучшие по recall (но FP-тяжёлые) чекпойнты побеждают, а модель
    уверенно срабатывает на близких по звучанию словах и обычной речи.
  * здесь копятся ВСЕ чекпойнты (save_all), каждый оценивается на независимой ВАЛИДАЦИИ
    (--val-neg: окна hard-негативов, --val-stream: поток речи+шума, которого нет ни в train, ни в
    тесте) и сравнивается с эталонной моделью (--ref-model, обычно omni_v0.1.onnx): порог выбирается
    как наименьший, при котором FP/ч на val-потоке и число FP на val-окнах НЕ ХУЖЕ эталона;
    чекпойнт оценивается recall на positive_features_test при этом пороге.
  * топ-K чекпойнтов усредняются (веса), порог пересчитывается на усреднённой модели.
  * вес негативов (--neg-weight), число семплов на класс, seed — параметры командной строки.

Выход: <out>/omni_<version>.onnx (вход [1,16,96], выход [1,1]), <out>/metrics_<version>.json
с выбранным порогом и метриками (на val). Тестовые метрики (eval_streaming.py / eval_live.py) считаются
отдельно и в отборе не участвуют.

Пример:
  python training/train_fpfix.py --features D:/wakeword-real/features_v2 --out model_v0.2fp \
      --version v0.2fp --ref-model app/src/main/assets/wakeword/omni_v0.1.onnx \
      --val-neg D:/wakeword-real/val/hn_features.npy --val-stream D:/wakeword-real/val/val_stream.npy
"""
import argparse
import copy
import json
from pathlib import Path

import numpy as np
import torch
from torch.utils.data import DataLoader, IterableDataset

import train_omni as T
from openwakeword.data import mmap_batch_generator

GRID = [0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.98, 0.99]


def stream_windows(stream):
    return np.stack([stream[i:i + 16] for i in range(len(stream) - 16)]).astype(np.float32)


def onnx_scores(path, wins):
    import onnxruntime as ort
    s = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    name = s.get_inputs()[0].name
    return np.array([s.run(None, {name: wins[i:i + 1]})[0].ravel()[0] for i in range(len(wins))])


def operating_point(pos_s, neg_s, stream_s, hrs, ref):
    """-> (thr, recall, n_neg_fp, fph) для наименьшего порога из GRID, не хуже эталона
    (ref: {'neg_fp': .., 'fph': ..}); None, если такого порога нет."""
    for thr in GRID:
        neg_fp = int((neg_s >= thr).sum())
        fph = T.simulate_fires(stream_s, thr, patience=2) / hrs
        if neg_fp <= ref["neg_fp"] and fph <= ref["fph"]:
            return thr, float((pos_s >= thr).mean()), neg_fp, fph
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--features", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--version", default="v0.2fp")
    ap.add_argument("--steps1", type=int, default=25000)
    ap.add_argument("--steps2", type=int, default=3000)
    ap.add_argument("--neg-weight", type=float, default=150, help="максимум линейного роста веса негативов")
    ap.add_argument("--n-pos", type=int, default=64)
    ap.add_argument("--n-bg", type=int, default=64, help="семплов фона (negative_bg) на батч")
    ap.add_argument("--n-adv", type=int, default=64, help="семплов hard-негативов (negative_features_train) на батч")
    ap.add_argument("--extra-neg", action="append", default=[], help="доп. npy негативов (N,16,96), n=--n-extra")
    ap.add_argument("--n-extra", type=int, default=64)
    ap.add_argument("--seed", type=int, default=2024)
    ap.add_argument("--lr1", type=float, default=1e-4)
    ap.add_argument("--n-val-points", type=int, default=40, help="сколько чекпойнтов копить за каждую последовательность")
    ap.add_argument("--ref-model", required=True, help="onnx эталона (v0.1): по нему задаются допустимые FP")
    ap.add_argument("--val-neg", required=True, help="npy окон hard-негативов для валидации (N,16,96)")
    ap.add_argument("--val-stream", required=True, help="npy потока эмбеддингов (T,96) для валидации FP/ч")
    ap.add_argument("--top-k", type=int, default=5)
    a = ap.parse_args()

    T.FEAT, T.VERSION = Path(a.features), a.version
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    torch.manual_seed(a.seed)
    np.random.seed(a.seed)

    # --- обучающий поток ---
    files = {"positive": str(T.FEAT / "positive_features_train.npy"),
             "negative": str(T.FEAT / "negative_bg_features_train.npy"),
             "adversarial_negative": str(T.FEAT / "negative_features_train.npy")}
    n_per = {"positive": a.n_pos, "negative": a.n_bg, "adversarial_negative": a.n_adv}
    for i, p in enumerate(a.extra_neg):
        files[f"extra{i}"] = p
        n_per[f"extra{i}"] = a.n_extra
    cut = lambda x, n=16: x if x.shape[1] == n else x[:, :n, :]
    gen = mmap_batch_generator(
        files, n_per_class=n_per,
        data_transform_funcs={k: cut for k in files},
        label_transform_funcs={k: (lambda x: [1] * len(x)) if k == "positive" else (lambda x: [0] * len(x))
                               for k in files})

    class IterDS(IterableDataset):
        def __iter__(self):
            return gen

    X_train = DataLoader(IterDS(), batch_size=None, num_workers=0)
    X_val, n_pos, n_neg = T.build_val()
    fp_loader, val_hrs, _ = T.build_fp()

    # --- валидация (независимая от train и теста) ---
    pos_test = np.load(T.FEAT / "positive_features_test.npy").astype(np.float32)[:, :16, :]
    val_neg = np.load(a.val_neg).astype(np.float32)[:, :16, :]
    val_stream_w = stream_windows(np.load(a.val_stream))
    hrs = len(val_stream_w) * 0.08 / 3600
    ref = {"neg_fp": None, "fph": None}
    sn = onnx_scores(a.ref_model, val_neg)
    ss = onnx_scores(a.ref_model, val_stream_w)
    ref_pos = onnx_scores(a.ref_model, pos_test)
    # эталон оценивается при своём рабочем пороге 0.5
    ref["neg_fp"] = int((sn >= 0.5).sum())
    ref["fph"] = T.simulate_fires(ss, 0.5, patience=2) / hrs
    print(f"эталон@0.5: val_neg_fp={ref['neg_fp']}/{len(val_neg)} val_stream_fph={ref['fph']:.0f} "
          f"recall(pos_test)={(ref_pos >= 0.5).mean():.3f}", flush=True)

    model = T.OWWModel(n_classes=1, input_shape=(16, 96), model_type="dnn", layer_dim=128, n_blocks=1,
                       seconds_per_example=1280 * 16 / 16000)

    def run(steps, lr, wmax, tag):
        weights = np.linspace(1, wmax, int(steps)).tolist()
        val_steps = np.linspace(1, steps, a.n_val_points).astype(np.int64).tolist()
        model.train_model(X=X_train, X_val=X_val, false_positive_val_data=fp_loader, max_steps=steps,
                          negative_weight_schedule=weights, val_steps=val_steps, warmup_steps=steps // 5,
                          hold_steps=steps // 3, lr=lr, val_set_hrs=val_hrs, save_all=True)
        print(f"[{tag}] чекпойнтов в пуле: {len(model.best_models)}", flush=True)

    run(a.steps1, a.lr1, a.neg_weight, "seq1")
    run(a.steps2, a.lr1 / 10, a.neg_weight, "seq2")

    # --- отбор ---
    rows = []
    for i, m in enumerate(model.best_models):
        op = operating_point(T.score_all(m, pos_test), T.score_all(m, val_neg), T.score_all(m, val_stream_w), hrs, ref)
        rows.append((i, op))
        print(f"  ckpt[{i}] step={model.best_model_scores[i]['training_step_ndx']} op={op}", flush=True)
    ok = sorted([(i, op) for i, op in rows if op], key=lambda r: -r[1][1])
    if not ok:
        raise SystemExit("ни один чекпойнт не достиг уровня эталона по FP на валидации")
    top = [i for i, _ in ok[:a.top_k]]
    print("top:", [(i, round(op[1], 3), op[0]) for i, op in ok[:a.top_k]], flush=True)
    combined = model.average_models(models=[model.best_models[i] for i in top])
    op = operating_point(T.score_all(combined, pos_test), T.score_all(combined, val_neg),
                         T.score_all(combined, val_stream_w), hrs, ref)
    if op is None:  # усреднение ухудшило FP: берём лучший одиночный
        combined, op = model.best_models[top[0]], dict(rows)[top[0]]
    thr = op[0]
    report = {"version": a.version, "args": vars(a), "reference_at_0.5": ref,
              "selected_checkpoints": top, "threshold": thr,
              "val": {"recall_pos_test": op[1], "val_neg_fp": op[2], "val_stream_fph": op[3]}}
    print(f"выбран порог {thr}: val recall={op[1]:.3f} neg_fp={op[2]} fph={op[3]:.0f}", flush=True)

    import onnx
    m = copy.deepcopy(combined).to("cpu").eval()
    path = out / f"omni_{a.version}.onnx"
    try:
        torch.onnx.export(m, torch.rand(1, 16, 96), str(path), dynamo=False)
    except TypeError:
        torch.onnx.export(m, torch.rand(1, 16, 96), str(path))
    onnx.save_model(onnx.load(str(path)), str(path), save_as_external_data=False)
    (out / f"metrics_{a.version}.json").write_text(json.dumps(report, indent=2, ensure_ascii=False))
    print("ГОТОВО", path)


if __name__ == "__main__":
    main()
