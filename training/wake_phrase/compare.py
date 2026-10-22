"""Сравнение фраз-активаторов: (модель, ключевые строки) на наборах клипов."""
import os
import sys
from pathlib import Path

import numpy as np
import sentencepiece as spm

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))


def load(model_dir):
    os.environ["KWS_MODEL"] = model_dir
    import importlib
    import eval_kws
    return importlib.reload(eval_kws)


def run(model_dir, words, sets, score=1.0, threshold=0.25, noise=0.0):
    ev = load(model_dir)
    sp = spm.SentencePieceProcessor(model_file=str(ev.MODEL / "bpe.model"))
    kw = ROOT / "kw_cmp.txt"
    kw.write_text("".join(" ".join(sp.encode(w, out_type=str)) + " @WAKE\n" for w in words), encoding="utf-8")
    kws = ev.spotter(kw, score, threshold)
    rng = np.random.default_rng(3)
    out = []
    for name in sets:
        d = ROOT / "clips" / name
        if name == "long":
            n = sum(len(ev.hits(kws, ev.read_wav(f), noise, rng)) for f in sorted(d.glob("*.wav")))
            out.append(f"{name}:{n}FA")
            continue
        rows = [l.split("\t") for l in (d / "index.tsv").read_text(encoding="utf-8-sig").splitlines() if l]
        hit = [r[2] for r in rows if ev.hits(kws, ev.read_wav(d / r[0]), noise, rng)]
        out.append(f"{name}:{len(hit)}/{len(rows)}" + (f" {sorted(set(hit))[:3]}" if name.startswith("neg") else ""))
    return "  ".join(out)


if __name__ == "__main__":
    RU = "sherpa-onnx-streaming-zipformer-small-ru-vosk-int8-2025-08-16"
    EN = "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01"
    noise = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
    negs = ["neg", "neg2_ru", "long"]
    print("RU омникс     ", run(RU, ["омникс"], ["omnix_ru", "omnix_en"] + negs, noise=noise))
    print("RU окей омни  ", run(RU, ["окей омни"], ["okomni_ru"] + negs, noise=noise))
    print("RU омни       ", run(RU, ["омни"], ["pos", "okomni_ru"] + negs, noise=noise))
    print("EN OMNIX      ", run(EN, ["OMNIX"], ["omnix_ru", "omnix_en"] + negs, noise=noise))
    print("EN OMNI       ", run(EN, ["OMNI", "AMNI"], ["pos", "okomni_ru"] + negs, noise=noise))
