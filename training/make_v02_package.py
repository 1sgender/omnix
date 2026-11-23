#!/usr/bin/env python3
"""Собрать v0.2-package.zip для обучения на Kaggle/Colab (см. RUN_V0.2.md).

Структура архива повторяет репозиторий, чтобы скрипты находили свои пути:
  training/*.py, app/src/main/assets/wakeword/*.onnx, features_v2/, raw_v2/test/,
  live_test/ (личные записи владельца!), RUN.md, DATA_REPORT.md.
Пакет содержит живой голос: не коммитить, на Kaggle датасет только Private.

Пример:
  python training/make_v02_package.py --work D:/wakeword-real --out D:/wakeword-real/v0.2-package.zip
"""
import argparse
import csv
import json
import zipfile
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parent
SCRIPTS = ["train_omni.py", "oww_train.py", "eval_streaming.py", "eval_live.py",
           "verify_model.py", "requirements.txt"]
APK_FILES = ["melspectrogram.onnx", "embedding_model.onnx", "omni_v0.2fp.onnx"]


def data_report(work):
    rows = list(csv.DictReader(open(work / "raw_v2" / "meta.csv", encoding="utf-8")))
    stats = json.loads((work / "features_v2" / "stats.json").read_text(encoding="utf-8"))
    cnt = Counter((r["split"], r["kind"], r["lang"]) for r in rows)
    spk = {s: len({r["speaker"] for r in rows if r["split"] == s}) for s in ("train", "test")}
    lines = ["# Состав датасета omni_v0.2", "", "## Синтетика Piper (клипов до аугментации)", "",
             "| split | kind | lang | клипов |", "|---|---|---|---|"]
    for (s, k, l), n in sorted(cnt.items()):
        lines.append(f"| {s} | {k} | {l} | {n} |")
    lines += ["", f"Дикторов (voice+speaker_id): train={spk['train']}, test={spk['test']} (speaker-disjoint).",
              "", "## Окна признаков после аугментации (features_v2/stats.json)", "", "```json",
              json.dumps(stats, indent=1), "```", ""]
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--work", required=True, help="папка wakeword-real (raw_v2, features_v2, clips, ...)")
    ap.add_argument("--out", required=True)
    args = ap.parse_args()
    work, out = Path(args.work), Path(args.out)
    with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for s in SCRIPTS:
            z.write(ROOT / s, f"training/{s}")
        for a in APK_FILES:
            z.write(REPO / "app/src/main/assets/wakeword" / a, f"app/src/main/assets/wakeword/{a}")
        for p in sorted((work / "features_v2").glob("*.npy")) + [work / "features_v2" / "stats.json"]:
            z.write(p, f"features_v2/{p.name}")
        for p in sorted((work / "raw_v2" / "test").rglob("*.wav")):
            z.write(p, f"raw_v2/test/{p.relative_to(work / 'raw_v2' / 'test').as_posix()}")
        for p in sorted((work / "clips").glob("*.wav")):
            z.write(p, f"live_test/clips/{p.name}")
        z.write(work / "clips_manifest.json", "live_test/clips_manifest.json")
        z.write(work / "owner_omni_16k.wav", "live_test/owner_omni_16k.wav")
        z.write(ROOT / "RUN_V0.2.md", "RUN.md")
        z.writestr("DATA_REPORT.md", data_report(work))
    print(f"{out}: {out.stat().st_size / 1e6:.1f} МБ")


if __name__ == "__main__":
    main()
