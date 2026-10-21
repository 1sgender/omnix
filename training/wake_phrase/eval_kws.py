"""Прогон sherpa-onnx KWS по клипам: сколько «Omni» поймано и сколько ложных."""
import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx

ROOT = Path(__file__).parent
import os
MODEL = ROOT / os.environ.get("KWS_MODEL", "sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01")
FILES = {
    "en": ("encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx", "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
           "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
    "ru": ("encoder.int8.onnx", "decoder.onnx", "joiner.int8.onnx"),
}["ru" if "-ru-" in str(MODEL) else "en"]


def read_wav(path):
    with wave.open(str(path)) as w:
        assert w.getframerate() == 16000 and w.getnchannels() == 1
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16)
    return data.astype(np.float32) / 32768.0


def spotter(keywords_file, score, threshold):
    return sherpa_onnx.KeywordSpotter(
        tokens=str(MODEL / "tokens.txt"),
        encoder=str(MODEL / FILES[0]),
        decoder=str(MODEL / FILES[1]),
        joiner=str(MODEL / FILES[2]),
        num_threads=1,
        keywords_file=str(keywords_file),
        keywords_score=score,
        keywords_threshold=threshold,
        num_trailing_blanks=1,
        provider="cpu",
    )


def hits(kws, samples, noise=0.0, rng=None):
    pad = np.zeros(8000, dtype=np.float32)
    audio = np.concatenate([pad, samples, pad, pad])
    if noise > 0:
        audio = audio + rng.normal(0, noise, audio.shape).astype(np.float32)
    s = kws.create_stream()
    found = []
    for i in range(0, len(audio), 1280):  # 80 мс, как в приложении
        s.accept_waveform(16000, audio[i:i + 1280])
        while kws.is_ready(s):
            kws.decode_stream(s)
            r = kws.get_result(s)
            if r:
                found.append(r)
                kws.reset_stream(s)
    return found


def main():
    keywords = ROOT / "keywords_omni.txt"
    score = float(sys.argv[1]) if len(sys.argv) > 1 else 1.0
    threshold = float(sys.argv[2]) if len(sys.argv) > 2 else 0.25
    noise = float(sys.argv[3]) if len(sys.argv) > 3 else 0.0
    kws = spotter(keywords, score, threshold)
    rng = np.random.default_rng(7)
    for kind in ("pos", "neg"):
        rows = [l.split("\t") for l in (ROOT / "clips" / kind / "index.tsv").read_text(encoding="utf-8-sig").splitlines() if l]
        n_hit = 0
        misses = []
        for name, rate, text in rows:
            found = hits(kws, read_wav(ROOT / "clips" / kind / name), noise, rng)
            if found:
                n_hit += 1
            if (kind == "pos") != bool(found):
                misses.append(f"{rate:>2} {text}")
        label = "detected" if kind == "pos" else "false alarms"
        print(f"{kind}: {label} {n_hit}/{len(rows)}")
        for m in misses:
            print("   ", "MISS" if kind == "pos" else "FA  ", m)


if __name__ == "__main__":
    main()
