"""Переводит набор фраз в другой тембр (OpenVoice V2 tone-color converter, MIT).

Нужен, когда записал диктор, чей тембр не подходит: произношение и интонация остаются его,
меняется только окраска голоса. Целевой тембр — эталон из LibriTTS-R (CC BY 4.0): указание
автора эталона обязательно в файле NOTICE при выпуске голоса.

usage: convert_timbre.py <dataset_dir> <out_dir>
dataset_dir: результат prepare_dataset.py (wavs/ + metadata.csv)
out_dir:     те же имена файлов и metadata.csv в целевом тембре — вход для обучения Piper.

Рядом с файлом должны лежать (в репозиторий не входят): openvoice-src/ (клон myshell-ai/OpenVoice),
ov-ckpt/converter/{config.json,checkpoint.pth} (OpenVoiceV2) и vc_trial/refs/libritts_3752.wav
(~25 с речи диктора 3752 из LibriTTS-R).
"""
import shutil
import sys
import types
from pathlib import Path

import torch

W = Path(__file__).parent
sys.path.insert(0, str(W / "openvoice-src"))
_text = types.ModuleType("openvoice.text")
_text.text_to_sequence = None
sys.modules["openvoice.text"] = _text
from openvoice.api import OpenVoiceBaseClass, ToneColorConverter  # noqa: E402

TARGET_REF = W / "vc_trial" / "refs" / "libritts_3752.wav"
TARGET_SE = W / "voice_b_se.pt"
TAU = 0.3


def converter():
    conv = ToneColorConverter.__new__(ToneColorConverter)
    OpenVoiceBaseClass.__init__(conv, str(W / "ov-ckpt" / "converter" / "config.json"), device="cpu")
    conv.watermark_model = None
    conv.version = getattr(conv.hps, "_version_", "v1")
    conv.load_ckpt(str(W / "ov-ckpt" / "converter" / "checkpoint.pth"))
    return conv


def main():
    src = Path(sys.argv[1])
    out = Path(sys.argv[2])
    (out / "wavs").mkdir(parents=True, exist_ok=True)
    conv = converter()

    # Тембр B фиксирован один раз и сохраняется: все блоки — одним голосом.
    if TARGET_SE.exists():
        tgt_se = torch.load(TARGET_SE)
    else:
        tgt_se = conv.extract_se([str(TARGET_REF)])
        torch.save(tgt_se, TARGET_SE)

    wavs = sorted((src / "wavs").glob("*.wav"))
    # Тембр исходного диктора — по всему набору, а не по одной фразе.
    src_se = conv.extract_se([str(p) for p in wavs[:200]])
    for i, wav in enumerate(wavs, 1):
        target = out / "wavs" / wav.name
        if target.exists():
            continue
        conv.convert(audio_src_path=str(wav), src_se=src_se, tgt_se=tgt_se, output_path=str(target), tau=TAU)
        if i % 25 == 0:
            print(f"{i}/{len(wavs)}", flush=True)
    shutil.copy(src / "metadata.csv", out / "metadata.csv")
    print("done", len(wavs))


if __name__ == "__main__":
    main()
