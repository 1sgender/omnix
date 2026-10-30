"""Собирает train_colab.ipynb — обучение голоса OMNIX в Google Colab (GPU T4).

Набор (из prepare_dataset.py, при нужде — в тембре выбранного голоса) кладётся в среду Colab
панелью «Файлы» zip-ом или его частями `.part1…` (расширению браузера удобно до 10 МБ).
Google Диск не подключается: для этого нужно ручное подтверждение в окне Google.
Результат (`.onnx` и `.onnx.json`) ноутбук скачивает в «Загрузки».
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
2. Набор — `omnix-voice-dataset-<язык>.zip` (части `.part1…` можно положить в панель «Файлы» слева; иначе ячейка 2 сама попросит выбрать файл).
3. Выберите язык в первой ячейке и запустите все ячейки по порядку.

Обучение долгое (часы). Если среда Colab закрылась, чекпоинты пропадают (Диск не используется) — обучение начнётся заново.
"""),
    code("""
LANG = "ru"   # "ru" или "tk"
EPOCHS = 800  # для ~600 фраз; для ~230 фраз (12 минут) хватает 500 — больше переобучает

ESPEAK = {"ru": "ru", "tk": "tk"}[LANG]
LOCALE = {"ru": "ru_RU", "tk": "tk_TM"}[LANG]
VOICE = f"{LOCALE}-omnix-medium"
"""),
    code("""
!nvidia-smi --query-gpu=name,memory.total --format=csv
# Без Google Диска: набор и результат лежат в самой среде Colab
# (подтверждение доступа к Диску требует ручного окна Google).
DATA = f"/content/dataset/{LANG}"
OUT = f"/content/train/{LANG}"
import os
os.makedirs(OUT, exist_ok=True)
if not os.path.isfile(f"{DATA}/metadata.csv"):
    # Набора ещё нет: выберите omnix-voice-dataset-tk.zip.
    import glob, zipfile
    parts = sorted(glob.glob("/content/omnix-voice-dataset-tk.zip.part*"))
    if parts:  # набор загружен кусками через панель «Файлы»
        with open("/content/omnix-voice-dataset-tk.zip", "wb") as whole:
            for part in parts:
                whole.write(open(part, "rb").read())
        name = "/content/omnix-voice-dataset-tk.zip"
    else:
        from google.colab import files
        up = files.upload()
        name = next(iter(up))
    with zipfile.ZipFile(name) as z:
        z.extractall("/content/dataset")
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
# espeakbridge (озвучка espeak для обучения) собирает setup.py, ему нужен scikit-build
pip install -q scikit-build cmake ninja
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
    code("""
# Результат — в «Загрузки» на компьютере (браузер может спросить разрешение на несколько файлов).
from google.colab import files
files.download(f"{OUT}/{VOICE}.onnx")
files.download(f"{OUT}/{VOICE}.onnx.json")
"""),
    md("""
Готово: `*-omnix-medium.onnx` и `.onnx.json` скачаны в «Загрузки». Их достаточно, чтобы подключить голос к приложению.
"""),
]

nb = {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                   "kernelspec": {"display_name": "Python 3", "name": "python3"}},
      "nbformat": 4, "nbformat_minor": 0}
out = Path(__file__).parent / "train_colab.ipynb"
out.write_text(json.dumps(nb, ensure_ascii=False, indent=1), encoding="utf-8")
print(out)
