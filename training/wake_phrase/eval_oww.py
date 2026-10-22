"""Текущий движок приложения (openWakeWord omni_v0.1) на тех же клипах."""
import sys
from collections import deque
from pathlib import Path

import numpy as np
import onnxruntime as ort

sys.path.insert(0, str(Path(__file__).parent))
import eval_kws as ev  # noqa: E402

ROOT = Path(__file__).parent
ASSETS = Path(sys.argv[1])
THRESHOLD = float(sys.argv[2]) if len(sys.argv) > 2 else 0.35
PATIENCE = 2

mel = ort.InferenceSession(str(ASSETS / "melspectrogram.onnx"))
emb = ort.InferenceSession(str(ASSETS / "embedding_model.onnx"))
clf = ort.InferenceSession(str(ASSETS / "omni_v0.1.onnx"))


def detections(samples_f32):
    x = np.concatenate([np.zeros(8000), samples_f32 * 32768.0, np.zeros(16000)]).astype(np.float32)
    raw = np.zeros(0, dtype=np.float32)
    mel_buf, feat_buf = deque(maxlen=970), deque(maxlen=120)
    run, last_hit, hits = 0, -10**9, 0
    for k, i in enumerate(range(0, len(x) - 1280 + 1, 1280)):
        raw = np.concatenate([raw, x[i:i + 1280]])[-(1280 + 480) - 16000:]
        tail = raw[-(1280 + 480):]
        out = mel.run(None, {mel.get_inputs()[0].name: tail[None, :]})[0]
        for row in out[0][0]:
            mel_buf.append(row / 10.0 + 2.0)
        if len(mel_buf) < 76:
            continue
        window = np.array(list(mel_buf)[-76:], dtype=np.float32)[None, :, :, None]
        e = emb.run(None, {emb.get_inputs()[0].name: window})[0]
        feat_buf.append(e[0][0][0])
        if len(feat_buf) < 16:
            continue
        feats = np.array(list(feat_buf)[-16:], dtype=np.float32)[None]
        score = clf.run(None, {clf.get_inputs()[0].name: feats})[0][0][0]
        run = run + 1 if score >= THRESHOLD else 0
        t = k * 80
        if run >= PATIENCE and t - last_hit > 2000:
            hits += 1
            last_hit = t
            run = 0
    return hits


for kind in ("pos", "neg"):
    rows = [l.split("\t") for l in (ROOT / "clips" / kind / "index.tsv").read_text(encoding="utf-8-sig").splitlines() if l]
    for lang in ("ru", "en"):
        items = [r for r in rows if r[0].startswith(lang)]
        n = sum(1 for r in items if detections(ev.read_wav(ROOT / "clips" / kind / r[0])) > 0)
        fa = [r[2] for r in items if kind == "neg" and detections(ev.read_wav(ROOT / "clips" / kind / r[0])) > 0]
        print(kind, lang, f"{n}/{len(items)}", sorted(set(fa))[:6])
long_hits = sum(detections(ev.read_wav(f)) for f in sorted((ROOT / "clips" / "long").glob("*.wav")))
print("long 5 min false alarms:", long_hits)
