"""Защита от выпуска плохого слова вызова: прогоняет «Омни» так же, как приложение (две ступени),
на закрытом наборе записей владельца и звуке его комнаты, и падает, если цифры хуже порогов
в scripts/wake_gate_limits.json.

Пороги и правило берутся прямо из кода приложения (WakeVerifier.kt, WakeTextRule.kt), модели —
из app/src/main/assets/wakeword, поэтому проверяется ровно то, что уйдёт в релиз.

Использование:
  python scripts/wake_gate.py --testset <папка с wav> --gigaam <папка GigaAM: model.int8.onnx, tokens.txt>
Набор (личные записи, не в репозитории): Omni*.wav — фразы «Омни» владельца (old_*.wav — прежние),
other_*.wav — другие голоса, room_*.wav — звук комнаты без «Омни». Всё 16 кГц, моно.
"""
import argparse
import glob
import json
import os
import re
import sys
import wave

import numpy as np
import onnxruntime as ort
import sherpa_onnx

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "wakeword")
SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "omnix", "assistant", "voice", "wakeword")


def kotlin_const(path, name):
    m = re.search(rf"const val {name}\s*=\s*\"?([^\"\n]+?)\"?\s*$", open(path, encoding="utf-8").read(), re.M)
    if not m:
        sys.exit(f"нет {name} в {path}")
    return m.group(1).strip()


VERIFIER = os.path.join(SRC, "verify", "WakeVerifier.kt")
RULE_KT = os.path.join(SRC, "verify", "WakeTextRule.kt")
STAGE1 = float(kotlin_const(VERIFIER, "STAGE1_THRESHOLD").rstrip("f"))
BEFORE = int(kotlin_const(VERIFIER, "WINDOW_BEFORE_MS").replace("_", "")) / 1000
AFTER = int(kotlin_const(VERIFIER, "WINDOW_AFTER_MS").replace("_", "")) / 1000
OMNI_LIKE = kotlin_const(RULE_KT, "OMNI_LIKE").replace("\\\\", "\\")
LOOKALIKE = kotlin_const(RULE_KT, "LOOKALIKE").replace("\\\\", "\\")
RULE = re.compile(rf"(?:^| )(?:{OMNI_LIKE}|{LOOKALIKE})(?: |$)")
MODEL = os.path.join(ASSETS, "omni_v0.2fp.onnx")


def load(p):
    w = wave.open(p)
    assert w.getframerate() == 16000 and w.getnchannels() == 1, p
    return np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float32)


class Oww:
    def __init__(self):
        self.mel = ort.InferenceSession(os.path.join(ASSETS, "melspectrogram.onnx"))
        self.emb = ort.InferenceSession(os.path.join(ASSETS, "embedding_model.onnx"))
        self.clf = ort.InferenceSession(MODEL)
        self.mi, self.ei, self.ci = (s.get_inputs()[0].name for s in (self.mel, self.emb, self.clf))

    def scores(self, x):
        m = self.mel.run(None, {self.mi: x[None, :]})[0].squeeze() / 10 + 2
        e = np.array([self.emb.run(None, {self.ei: m[k - 76:k][None, :, :, None].astype(np.float32)})[0].reshape(-1)
                      for k in range(76, m.shape[0] + 1, 8)])
        if len(e) < 16:
            return np.zeros(0)
        return np.array([float(self.clf.run(None, {self.ci: e[i - 16:i][None].astype(np.float32)})[0].reshape(-1)[0])
                         for i in range(16, len(e) + 1)])


def candidates(oww, x):
    out, last = [], -10**9
    for i, v in enumerate(oww.scores(x)):
        if v >= STAGE1 and i - last >= 25:
            last = i
            out.append((76 + 8 * (i + 15)) * 160)
    return out


def utterances(x):
    f = 320
    lv = np.array([np.sqrt(np.mean(x[i:i + f] ** 2)) for i in range(0, len(x) - f, f)]) + 1e-3
    db = 20 * np.log10(lv)
    on = db > np.percentile(db, 30) + 10
    segs, start, gap = [], None, 0
    for i, v in enumerate(on):
        if v:
            start = i if start is None else start
            gap = 0
        elif start is not None:
            gap += 1
            if gap > 15:
                end = i - gap
                if end - start > 10:
                    segs.append((max(0, start - 15) * f, min(len(x), (end + 15) * f)))
                start, gap = None, 0
    return segs


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--testset", required=True)
    ap.add_argument("--gigaam", required=True)
    ap.add_argument("--limits", default=os.path.join(ROOT, "scripts", "wake_gate_limits.json"))
    a = ap.parse_args()
    limits = json.load(open(a.limits, encoding="utf-8"))
    asr = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
        model=os.path.join(a.gigaam, "model.int8.onnx"), tokens=os.path.join(a.gigaam, "tokens.txt"), num_threads=4)
    oww = Oww()

    def check(x, end):
        w = x[max(0, end - int(BEFORE * 16000)):min(len(x), end + int(AFTER * 16000))]
        s = asr.create_stream()
        s.accept_waveform(16000, (w / 32768).astype(np.float32))
        asr.decode_stream(s)
        return bool(RULE.search(s.result.text.strip().lower()))

    pad = np.zeros(16000, np.float32)

    def recall(pattern):
        n = ok = 0
        for p in sorted(glob.glob(os.path.join(a.testset, pattern))):
            x = load(p)
            for s, e in utterances(x):
                seg = np.concatenate([pad, x[s:e], pad])
                n += 1
                ok += any(check(seg, end) for end in candidates(oww, seg))
        return ok, n

    res = {"stage1": STAGE1, "window": [BEFORE, AFTER]}
    for key, pattern in (("owner", "Omni*.wav"), ("owner_old", "old_*.wav"), ("other", "other_*.wav")):
        ok, n = recall(pattern)
        res[key] = {"hit": ok, "total": n, "rate": round(ok / n, 3) if n else None}
    fa = hours = 0
    for p in sorted(glob.glob(os.path.join(a.testset, "room_*.wav"))):
        x = load(p)
        hours += len(x) / 16000 / 3600
        fa += sum(check(x, end) for end in candidates(oww, x))
    res["room"] = {"false": fa, "hours": round(hours, 3), "per_hour": round(fa / hours, 2) if hours else None}
    print(json.dumps(res, ensure_ascii=False, indent=1))

    fails = []
    for key in ("owner", "owner_old", "other"):
        r = res[key]["rate"]
        if r is not None and r < limits[f"min_{key}_rate"]:
            fails.append(f"{key}: {r} < {limits[f'min_{key}_rate']}")
    if res["room"]["per_hour"] is not None and res["room"]["per_hour"] > limits["max_room_false_per_hour"]:
        fails.append(f"room: {res['room']['per_hour']}/ч > {limits['max_room_false_per_hour']}")
    if fails:
        print("::error::Слово вызова хуже порогов: " + "; ".join(fails))
        sys.exit(1)
    print("слово вызова: пороги пройдены")


if __name__ == "__main__":
    main()
