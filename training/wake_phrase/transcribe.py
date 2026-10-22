"""Что русская модель слышит в позитивных клипах (обычное распознавание)."""
import sys
from pathlib import Path

import numpy as np
import sherpa_onnx

sys.path.insert(0, str(Path(__file__).parent))
import eval_kws as ev  # noqa: E402

ROOT = Path(__file__).parent
M = ROOT / "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16"
rec = sherpa_onnx.OnlineRecognizer.from_transducer(
    tokens=str(M / "tokens.txt"), encoder=str(M / "encoder.int8.onnx"),
    decoder=str(M / "decoder.onnx"), joiner=str(M / "joiner.int8.onnx"),
    num_threads=1, decoding_method="modified_beam_search", max_active_paths=4,
)
noise = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
rng = np.random.default_rng(1)
rows = [l.split("\t") for l in (ROOT / "clips" / (sys.argv[2] if len(sys.argv) > 2 else "pos") / "index.tsv").read_text(encoding="utf-8-sig").splitlines() if l]
for name, rate, text in rows:
    a = ev.read_wav(ROOT / "clips" / (sys.argv[2] if len(sys.argv) > 2 else "pos") / name)
    a = np.concatenate([np.zeros(8000, np.float32), a, np.zeros(16000, np.float32)])
    if noise:
        a = a + rng.normal(0, noise, a.shape).astype(np.float32)
    s = rec.create_stream()
    s.accept_waveform(16000, a)
    s.input_finished()
    while rec.is_ready(s):
        rec.decode_stream(s)
    peak = float(np.abs(a).max())
    print(f"{rate:>2} peak={peak:.2f} {text!r:40} -> {rec.get_result(s)!r}")
