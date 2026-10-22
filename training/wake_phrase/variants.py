"""Какие написания «Омни» ловят русское произношение и не дают ложных."""
import sys
from pathlib import Path

import sentencepiece as spm

sys.path.insert(0, str(Path(__file__).parent))
import eval_kws as ev  # noqa: E402

ROOT = Path(__file__).parent
sp = spm.SentencePieceProcessor(model_file=str(ev.MODEL / "bpe.model"))
spellings = sys.argv[1].split(",")
score = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
threshold = float(sys.argv[3]) if len(sys.argv) > 3 else 0.25

clips = {}
for kind in ("pos", "neg"):
    rows = [l.split("\t") for l in (ROOT / "clips" / kind / "index.tsv").read_text(encoding="utf-8-sig").splitlines() if l]
    clips[kind] = [(r[0][:2], r[2], ev.read_wav(ROOT / "clips" / kind / r[0])) for r in rows]

for word in spellings:
    tokens = " ".join(sp.encode(word, out_type=str))
    kw = ROOT / "kw_tmp.txt"
    kw.write_text(f"{tokens} @OMNI\n", encoding="utf-8")
    kws = ev.spotter(kw, score, threshold)
    res = {}
    for kind in ("pos", "neg"):
        for lang in ("ru", "en"):
            items = [c for c in clips[kind] if c[0] == lang]
            res[(kind, lang)] = (sum(1 for c in items if ev.hits(kws, c[2])), len(items))
    fa = [c[1] for c in clips["neg"] if ev.hits(kws, c[2])]
    long_fa = sum(len(ev.hits(kws, ev.read_wav(f))) for f in sorted((ROOT / "clips" / "long").glob("*.wav")))
    print(f"{word:10} [{tokens}]  pos ru {res[('pos','ru')]} en {res[('pos','en')]}  "
          f"FA ru {res[('neg','ru')]} en {res[('neg','en')]}  long5min {long_fa}  {sorted(set(fa))[:4]}")
