"""Собирает train_colab.ipynb — обучение голоса OMNIX в Google Colab (GPU).

Набор (из prepare_dataset.py) лежит на Google Диске: MyDrive/omnix-voice/dataset/<язык>/
{metadata.csv, wavs/}. Чекпоинты и готовая модель пишутся туда же, в train/<язык>/:
сессия Colab может оборваться, следующий запуск продолжит с последнего чекпоинта.
"""
import json
from pathlib import Path

PIPER_TAG = "v1.8.0"  # OHF-Voice/piper1-gpl, 2026-09-04: есть --model.warmstart_ckpt
BASE_CKPT = ("https://huggingface.co/datasets/rhasspy/piper-checkpoints/resolve/main/"
             "en/en_US/libritts_r/medium/best.ckpt")


def md(text):
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(True)}


def code(text):
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip("\n").splitlines(True)}


cells = [
    md("""
# Голос OMNIX — обучение

1. **Среда выполнения → Сменить среду → GPU (T4)**.
2. Положите набор на Google Диск: `MyDrive/omnix-voice/dataset/ru/` (`metadata.csv` и папка `wavs/`).
3. Выберите язык в первой ячейке и запустите все ячейки по порядку.

Обучение долгое (часы). Если Colab отключился — просто запустите всё заново: обучение продолжится
с последнего чекпоинта на Диске.
"""),
    code("""
LANG = "ru"   # "ru" или "tk"
EPOCHS = 800  # для ~600 фраз; больше — дольше и обычно чище

ESPEAK = {"ru": "ru", "tk": "tk"}[LANG]
LOCALE = {"ru": "ru_RU", "tk": "tk_TM"}[LANG]
VOICE = f"{LOCALE}-omnix-medium"
"""),
    code("""
!nvidia-smi --query-gpu=name,memory.total --format=csv
from google.colab import drive
drive.mount("/content/drive")
DATA = f"/content/drive/MyDrive/omnix-voice/dataset/{LANG}"
OUT = f"/content/drive/MyDrive/omnix-voice/train/{LANG}"
import os
os.makedirs(OUT, exist_ok=True)
assert os.path.isfile(f"{DATA}/metadata.csv"), f"нет {DATA}/metadata.csv"
print(sum(1 for _ in open(f"{DATA}/metadata.csv", encoding="utf-8")), "фраз в наборе")
"""),
    code(f"""
%%bash
set -e
apt-get -qq install -y build-essential cmake ninja-build > /dev/null
if [ ! -d /content/piper1-gpl ]; then
  git clone -q --branch {PIPER_TAG} --depth 1 https://github.com/OHF-voice/piper1-gpl.git /content/piper1-gpl
fi
cd /content/piper1-gpl
pip install -q -e '.[train]'
./build_monotonic_align.sh > /dev/null
python setup.py build_ext --inplace > /dev/null
echo ok
"""),
    code(f"""
# Базовая модель: LibriTTS-R medium, обучена с нуля на LibriTTS-R (CC BY 4.0), 904 диктора.
# warmstart переносит все слои подходящей формы; таблица дикторов не нужна — у нас один голос.
BASE = "/content/libritts_r_medium.ckpt"
if not os.path.isfile(BASE):
    !wget -q -O {{BASE}} "{BASE_CKPT}"
print(round(os.path.getsize(BASE) / 1e6), "МБ")
"""),
    code("""
import glob
ckpts = sorted(glob.glob(f"{OUT}/**/*.ckpt", recursive=True), key=os.path.getmtime)
resume = ckpts[-1] if ckpts else None
start = f"--ckpt_path '{resume}'" if resume else f"--model.warmstart_ckpt {BASE}"
print("продолжаю с" if resume else "начинаю с базовой модели:", resume or BASE)

%cd /content/piper1-gpl
!python -m piper.train fit \\
  --data.voice_name "omnix_{LANG}" \\
  --data.csv_path "{DATA}/metadata.csv" \\
  --data.audio_dir "{DATA}/wavs" \\
  --model.sample_rate 22050 \\
  --data.espeak_voice "{ESPEAK}" \\
  --data.cache_dir /content/cache \\
  --data.config_path "{OUT}/{VOICE}.onnx.json" \\
  --data.batch_size 16 \\
  --trainer.max_epochs {EPOCHS} \\
  --trainer.default_root_dir "{OUT}" \\
  {start}
"""),
    code("""
ckpt = sorted(glob.glob(f"{OUT}/**/*.ckpt", recursive=True), key=os.path.getmtime)[-1]
print("экспорт из", ckpt)
!python -m piper.train.export_onnx --checkpoint "{ckpt}" --output-file "{OUT}/{VOICE}.onnx"
"""),
    code("""
SAMPLES = {
    "ru": "Добрый вечер. Все системы работают в штатном режиме. Чем могу помочь?",
    "tk": "Agşamyňyz haýyrly bolsun. Ähli ulgamlar kadaly işleýär. Size nähili kömek edip bilerin?",
}
!python -m piper -m "{OUT}/{VOICE}.onnx" -f "{OUT}/sample.wav" -- "{SAMPLES[LANG]}"
from IPython.display import Audio
Audio(f"{OUT}/sample.wav")
"""),
    md("""
Готово: `MyDrive/omnix-voice/train/<язык>/` содержит `*-omnix-medium.onnx` и `.onnx.json`.
Пришлите эти два файла — я подключу голос в приложение.
"""),
]

nb = {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                   "kernelspec": {"display_name": "Python 3", "name": "python3"}},
      "nbformat": 4, "nbformat_minor": 0}
out = Path(__file__).parent / "train_colab.ipynb"
out.write_text(json.dumps(nb, ensure_ascii=False, indent=1), encoding="utf-8")
print(out)
