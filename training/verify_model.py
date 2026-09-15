#!/usr/bin/env python3
"""Проверка кандидата wake-word классификатора перед укладкой в APK.

Контракт (измерен на hey_jarvis_v0.1.onnx через ORT 1.30):
  вход float32 [B, 16, 96] -> выход [B, 1], скоры в [0, 1].

Использование:
  python3 training/verify_model.py --model hey_jarvis_v0.1.onnx
Зависимость: pip install onnxruntime numpy
"""
import argparse
import hashlib
import sys

import numpy as np


def fail(msg):
    print(f"FAIL: {msg}")
    sys.exit(1)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", required=True, help="путь к .onnx классификатору")
    args = ap.parse_args()

    try:
        import onnxruntime as ort
    except ImportError:
        fail("нет onnxruntime: pip install onnxruntime")

    try:
        sess = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    except Exception as e:
        fail(f"модель не открылась: {e}")

    if len(sess.get_inputs()) != 1 or len(sess.get_outputs()) != 1:
        fail("ожидается ровно 1 вход и 1 выход")
    inp, out = sess.get_inputs()[0], sess.get_outputs()[0]
    print(f"input:  name={inp.name} shape={inp.shape} type={inp.type}")
    print(f"output: name={out.name} shape={out.shape} type={out.type}")

    dims = [str(d) for d in inp.shape]
    if len(dims) != 3 or dims[1:] != ["16", "96"]:
        fail(f"вход должен быть [B, 16, 96], получен {inp.shape}")
    if "float" not in inp.type:
        fail(f"вход должен быть float32, получен {inp.type}")

    name = inp.name
    zeros = np.zeros((1, 16, 96), dtype=np.float32)
    noise = np.random.RandomState(0).randn(1, 16, 96).astype(np.float32)
    try:
        s_zero = sess.run(None, {name: zeros})[0]
        s_noise = sess.run(None, {name: noise})[0]
    except Exception as e:
        fail(f"smoke-инференс упал: {e}")
    print(f"smoke: zeros={np.asarray(s_zero).ravel().tolist()} noise={np.asarray(s_noise).ravel().tolist()}")
    for tag, s in (("zeros", s_zero), ("noise", s_noise)):
        a = np.asarray(s, dtype=np.float32)
        if a.shape != (1, 1):
            fail(f"{tag}: выход {a.shape}, ожидался (1, 1)")
        if not np.all((a >= 0.0) & (a <= 1.0)):
            fail(f"{tag}: скор вне [0, 1]: {a.ravel().tolist()}")

    h = hashlib.sha256()
    with open(args.model, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    print(f"sha256: {h.hexdigest()}")
    print("PASS: контракт [B, 16, 96] -> [B, 1] соблюдён")


if __name__ == "__main__":
    main()
