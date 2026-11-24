#!/usr/bin/env python3
"""Phase A v0.2: расширенный синтез raw-данных Piper TTS для wake-word «Омни».

Отличия от gen_raw.py (v0.1):
  * больше голосов и многоголосые модели (vctk):
    у них каждый speaker_id считается отдельным диктором, а test-дикторы
    выбираются по id (speaker-disjoint внутри одной модели);
  * языки: ru, en, kk (ближайший к
    туркменскому/узбекскому из доступных в rhasspy/piper-voices);
  * варианты фразы: «омни», «Омни?», «о-мни», «omni», «hey omni»,
    «окей омни», «эй омни», ... (см. POS_PHRASES);
  * негативы: трудные слова (умный, помни, тони, many, money, ...),
    «префиксы без omni» (hey / окей / okay), обычная речь, adversarial.
Голоса НЕ коммитятся: каталог моделей берётся из PIPER_VOICES
(официальный источник: huggingface.co/rhasspy/piper-voices).

Выход: 16 кГц mono int16 wav в <OUT>/{train,test}/{positive,negative}/ и meta.csv.
Запуск:  PIPER_VOICES=<папка> python training/gen_raw_v2.py --out <папка raw_v2>
         [--scale 0.1]   # быстрый прогон на 10% объёма
"""
import argparse
import csv
import json
import os
import random
import wave
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly

try:
    from piper import PiperVoice, SynthesisConfig
except ImportError:  # позволяет импортировать списки фраз без piper
    PiperVoice = SynthesisConfig = None

ROOT = Path(__file__).resolve().parent
VOICES = Path(os.environ.get("PIPER_VOICES", str(ROOT / "voices")))

# --- каталог голосов: имя -> (язык текстов, роль, n_pos, n_neg) ---------------
# роль: "train" | "test" | "split" (многоголосая: часть speaker_id в test)
# n_* - на всю модель (для split - суммарно по всем дикторам train-части);
# test-часть split-модели генерируется отдельно: TEST_PER_SPLIT_VOICE.
#
# ЛИЦЕНЗИИ (проверено по MODEL_CARD в rhasspy/piper-voices, 2026-11-23). В каталоге
# ТОЛЬКО голоса, чей корпус разрешает коммерческое использование (CC0 / CC BY):
# модель идёт в платное приложение. Исключены и НЕ должны возвращаться:
#   en_US-l2arctic (CC BY-NC 4.0), en_US-ryan (CC BY-NC-SA 4.0), tr_TR-dfki (CC BY-NC-SA 4.0),
#   ru_RU-ruslan (CC BY-NC-SA 4.0), en_US-lessac (исследовательская лицензия Blizzard 2013),
#   ru_RU-irina (лицензия "Unknown"), en_GB-jenny_dioco, en_GB-alan, en_US-amy (карточка
#   отсылает на URL с нестандартной/неясной лицензией), en_GB-northern_english_male (CC BY-SA),
#   а также все голоса, чья лицензия не проверена (joe, john, hfc_*, kristin, arctic,
#   issai, европейские de/pl/cs/fr/it/pt/es/uk). Подробности - NOTICE и docs/WAKEWORD_NEURAL.md.
CATALOG = {
    # русский: CC0 (OHF-Voice/voice-datasets); dmitri - тестовый (speaker-disjoint от denis)
    "ru_RU-denis-medium": ("ru", "train", 500, 300),
    "ru_RU-dmitri-medium": ("ru", "test", 120, 60),
    # английский: CC BY 4.0 (109 дикторов; alba и libritts_r тоже CC BY, но их файлы в локальном
    # каталоге оказались обрезаны, поэтому в обучении не участвовали)
    "en_GB-vctk-medium": ("en", "split", 400, 400),
    # казахский (ближайший тюркский к туркменскому из доступных): CC BY 4.0 (IS2AI)
    "kk_KZ-raya-x_low": ("kk", "train", 100, 100),
    "kk_KZ-iseke-x_low": ("kk", "test", 80, 40),
}

# Голоса, чьи ДАТАСЕТЫ под некоммерческой лицензией (CC BY-NC / BY-NC-SA по
# MODEL_CARD в rhasspy/piper-voices) либо с неподтверждённой лицензией. Модель,
# обученная на их синтезе, не должна уходить в коммерческий релиз; подробности
# и ссылки: раздел omni_v0.2 в NOTICE.
NON_COMMERCIAL = {
    "en_US-l2arctic-medium", "en_US-ryan-medium", "en_US-hfc_female-medium",
    "en_US-hfc_male-medium", "ru_RU-ruslan-medium", "tr_TR-dfki-medium",
}
LICENSE_UNVERIFIED = {
    "en_US-lessac-medium", "en_US-amy-medium", "en_US-arctic-medium", "en_GB-alan-medium",
    "it_IT-riccardo-x_low", "ru_RU-irina-medium", "en_GB-northern_english_male-medium",
}
TEST_PER_SPLIT_VOICE = (80, 40)  # (pos, neg) test-диктора на split-модель
TEST_MOD = 7                     # speaker_id % 7 == 3 -> test-диктор

# --- фразы -----------------------------------------------------------------
# (текст, вес). Орфография подобрана, чтобы Piper читал разные варианты.
POS_PHRASES = {
    "ru": [("омни", 38), ("Омни?", 8), ("Омни!", 6), ("о-мни", 6), ("омни.", 4),
           ("окей омни", 10), ("эй омни", 8), ("привет омни", 5),
           ("омни ты здесь", 5), ("омни, слушай", 4), ("О-омни", 3), ("омнии", 3)],
    "en": [("omni", 36), ("Omni?", 8), ("Omni!", 6), ("hey omni", 12), ("okay omni", 10),
           ("hey, omni", 4), ("oh-mni", 3), ("ohm nee", 3), ("om-nee", 3),
           ("omni are you there", 4), ("omni, listen", 4), ("ok omni", 4), ("Omni.", 3)],
    "tr": [("omni", 40), ("Omni?", 10), ("Omni!", 6), ("hey omni", 10), ("tamam omni", 12),
           ("ey omni", 8), ("omni orada mısın", 4), ("om-ni", 6), ("omni.", 4)],
    "kk": [("омни", 40), ("Омни?", 10), ("Омни!", 6), ("эй омни", 12), ("жарайды омни", 10),
           ("о-мни", 6), ("сәлем омни", 6), ("омни.", 5), ("окей омни", 5)],
}

# трудные слова/фразы (близкие по звучанию) + префиксы БЕЗ omni
HARD_NEG = {
    "en": ["many", "money", "honey", "on me", "omen", "mommy", "tommy", "yoni", "homie",
           "oh manny", "oh money", "own me", "om nom nom", "monty", "connie", "bonnie",
           "johnnie", "tony", "sonny", "ronnie", "annie", "any", "money money", "so many",
           "on my way", "home alone", "oh no", "only", "army", "armani", "oh me", "omelet",
           "ohm", "emmy", "enemy", "ominous", "hey", "okay", "hey you", "okay then",
           "hey mommy", "okay honey", "okay money", "hey tony", "hey many", "ok boomer"],
    "ru": ["они", "мне", "он мне", "конни", "монни", "томми", "омлет", "омм", "монитор",
           "сомни", "тони", "сони", "бонни", "полночь", "только", "много", "маня", "моня",
           "они же", "на мониторе", "омлет с сыром", "умный", "помни", "не помни", "оми",
           "омск", "омон", "ноль", "мини", "комнат", "окей", "эй", "эй ты", "окей гугл",
           "эй тони", "окей тони", "привет тони", "помни меня", "умный дом", "окей умный",
           "омега", "оно мне", "оммм", "омар", "знамение", "имени"],
    "tr": ["oni", "omuz", "ömür", "hemen", "oyun", "onu", "ömrüm", "omlet", "Ömer", "mini",
           "çok", "bana", "tamam", "evet", "hayır", "ey", "hey", "tamam mı", "hey tony",
           "tamam onu", "ömer", "okay", "monte", "domino", "anemi", "hani", "onun"],
    "kk": ["оны", "мен", "омыз", "оңай", "ойын", "ол", "мына", "жаман", "жарайды", "эй",
           "окей", "оми", "мини", "омар", "ойнау", "онымен", "мені", "жарайды ма"],
}

GENERAL_NEG = {
    "en": ["hey google", "alexa", "hey siri", "ok google", "computer", "hey jarvis",
           "hey mycroft", "hey cortana", "what time is it", "turn on the lights",
           "play some music", "call my mom", "set a timer for ten minutes",
           "what's the weather like", "open the door", "stop the music", "no thanks",
           "yes please", "hello there", "good morning", "good night", "tell me a joke",
           "remind me to call the doctor", "navigate home", "send a message", "volume up",
           "volume down", "mute the tv", "who is at the door", "how long until dinner",
           "answer the phone", "hang up", "later today", "maybe tomorrow", "monday morning",
           "so many things", "money in the bank", "honey i'm home", "the weather is nice",
           "can you help me", "i don't know", "let me think", "one moment please",
           "good afternoon", "good evening", "what's on the news today", "i had a great day",
           "let's grab lunch", "the meeting is at three", "can you hear me now",
           "are you still there", "hold on a second", "that sounds great", "i'll be right back",
           "where are the keys", "the store closes at nine", "traffic was terrible",
           "i need more coffee", "it's getting late", "see you tomorrow", "thanks for everything",
           "no problem at all", "have a nice day", "it might rain later", "the phone is ringing",
           "someone is coming over", "dinner is almost ready", "i'm going to bed",
           "did you take out the trash", "the kids are at school", "my back hurts",
           "turn it down a little", "come here for a second", "look at this picture",
           "i love this song", "change the channel", "we're out of milk", "order a pizza tonight",
           "the game starts soon", "okay google play music", "hey siri what time is it"],
    "ru": ["привет", "да", "нет", "спасибо", "сколько времени", "включи свет", "выключи музыку",
           "поставь будильник на семь утра", "позвони маме", "какая завтра погода", "открой дверь",
           "выключи телевизор", "сделай громче", "напомни позвонить врачу",
           "прокладывай маршрут домой", "отправь сообщение", "доброе утро", "доброй ночи",
           "расскажи анекдот", "останови музыку", "играй музыку", "кто у двери",
           "сколько ещё ждать", "много вещей", "деньги в банке", "погода хорошая",
           "ты можешь помочь", "я не знаю", "дай подумать", "одну минуту", "до завтра", "ладно",
           "добрый день", "добрый вечер", "что в новостях", "у меня был хороший день",
           "давай пообедаем", "встреча в три часа", "ты меня слышишь", "ты ещё здесь",
           "подожди секунду", "звучит отлично", "я сейчас вернусь", "где ключи",
           "магазин закрывается в девять", "пробки ужасные", "мне нужен кофе", "уже поздно",
           "хорошего дня", "какое красивое утро", "позже будет дождь", "телефон звонит",
           "ужин почти готов", "я иду спать", "вынеси мусор", "дети в школе",
           "у меня болит спина", "сделай потише", "подойди на секунду", "люблю эту песню",
           "включи другой канал", "закончилось молоко", "закажи пиццу вечером",
           "игра скоро начнётся", "умный мальчик", "алиса", "окей гугл", "привет алиса"],
    "tr": ["saat kaç", "ışığı aç", "günaydın", "iyi geceler", "müzik çal", "annemi ara",
           "hava nasıl", "kapıyı aç", "teşekkür ederim", "hayır teşekkürler", "evet lütfen",
           "bir dakika", "yarın görüşürüz", "ne yapıyorsun", "beni duyuyor musun", "alarm kur",
           "sesi aç", "sesi kapat", "çok güzel", "iyi günler", "eve gidelim", "telefon çalıyor"],
    "kk": ["сәлем", "рахмет", "жоқ", "иә", "қайырлы таң", "қайырлы түн", "уақыт қанша",
           "жарықты қос", "музыка қос", "анама қоңырау шал", "ауа райы қандай",
           "есікті аш", "бір минут", "ертең көрісеміз", "мені естисің бе", "дыбысты қос",
           "дыбысты өшір", "өте жақсы", "телефон шылдырлап тұр", "үйге барайық"],
}

# собственный пул «фонетически близких» фраз (генератор openwakeword требует torch
# и pronouncing; если они есть — gen_raw.adversarial_pool добавит ещё)
ADV_POOL = {
    "en": ["oh meaning", "arm me", "on knee", "own knee", "oh need", "oh mean", "home need",
           "ohm needs", "bro knee", "common knee", "so many knee", "uh me", "ah mean",
           "oh my", "all me", "all knee", "I'm ni", "own eye", "no money", "owning",
           "homey", "mommy knee", "omni bus ticket", "alone me", "only me", "on the meal",
           "own the knee", "coma knee", "gnome knee", "oh man", "oh many", "oh money me",
           "romney", "Tommy knee", "armani suit", "honey knee", "mini", "meany", "moany",
           "oh no me", "you know me", "oh knee", "oh nee", "uh mini", "any money"],
    "ru": ["он мни", "ом ни", "омен", "они мне", "омнибус", "ам ни", "омо ни", "оно мне",
           "пом ни", "ком ни", "дом ни", "стони", "омине", "о мне", "а мне", "у мне",
           "он ни", "ольми", "оми но", "я мни", "ох мне", "омнит", "омёт", "анни", "ненни"],
    "tr": ["on ni", "omuz ni", "o mini", "bu ni", "oy mi", "önemli", "omni mi değil", "hemen ne"],
    "kk": ["он ни", "ом мен", "оны мен", "мен ни", "омыр", "ойни", "оң ни"],
}

POS_RATIO_NAMES = {"en": "en", "ru": "ru", "tr": "tr", "kk": "kk"}


def pick_weighted(options, rng):
    total = sum(w for _, w in options)
    r = rng.uniform(0, total)
    for text, w in options:
        r -= w
        if r <= 0:
            return text
    return options[-1][0]


def pos_text(lang, rng):
    return pick_weighted(POS_PHRASES[lang], rng)


def neg_texts(lang, n, rng):
    hard, general, adv = HARD_NEG[lang], GENERAL_NEG[lang], ADV_POOL[lang]
    out = []
    for _ in range(n):
        r = rng.random()
        if r < 0.30:
            out.append(rng.choice(hard))
        elif r < 0.50:
            out.append(rng.choice(adv))
        else:
            out.append(rng.choice(general))
    return out


def trim_silence(x, thresh_ratio=0.02, margin_ms=30):
    if len(x) == 0:
        return x
    mag = np.abs(x)
    idx = np.where(mag > thresh_ratio * mag.max())[0]
    if len(idx) == 0:
        return x
    margin = int(margin_ms / 1000 * 16000)
    return x[max(0, idx[0] - margin):min(len(x), idx[-1] + margin)]


def synth_to_16k(voice, text, rng, speaker_id=None):
    cfg = SynthesisConfig(
        speaker_id=speaker_id,
        length_scale=rng.uniform(0.65, 1.25),
        noise_scale=rng.uniform(0.2, 0.8),
        noise_w_scale=rng.uniform(0.2, 0.8),
        volume=rng.uniform(0.5, 1.0),
    )
    chunks = [c.audio_float_array for c in voice.synthesize(text, syn_config=cfg)]
    raw = np.concatenate(chunks).astype(np.float32)
    sr = voice.config.sample_rate
    if sr != 16000:
        g = int(np.gcd(sr, 16000))
        raw = resample_poly(raw, 16000 // g, sr // g)
    return trim_silence(raw)


def save_wav(path, x):
    xi = (np.clip(x, -1.0, 1.0) * 32767).astype(np.int16)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(xi.tobytes())


def speakers_of(voice, role):
    """-> (train_ids, test_ids) для модели."""
    n = int(getattr(voice.config, "num_speakers", 1) or 1)
    if n <= 1:
        return ([None], []) if role != "test" else ([], [None])
    ids = list(range(n))
    if role == "test":
        return [], ids
    if role == "split":
        test = [i for i in ids if i % TEST_MOD == 3]
        return [i for i in ids if i % TEST_MOD != 3], test
    return ids, []


def run_job(voice, name, lang, split, speakers, n_pos, n_neg, out, writer, rng, tag):
    """Сгенерировать n_pos позитивов и n_neg негативов, ротируя дикторов."""
    for kind, n, texts in (("positive", n_pos, None), ("negative", n_neg, neg_texts(lang, n_neg, rng))):
        for i in range(n):
            spk = speakers[i % len(speakers)]
            text = pos_text(lang, rng) if kind == "positive" else texts[i]
            spk_tag = f"{name}" + (f"_s{spk}" if spk is not None else "")
            path = out / split / kind / f"{spk_tag}_{tag}{i:04d}.wav"
            if not path.exists():
                try:
                    x = synth_to_16k(voice, text, rng, spk)
                except Exception as e:  # редкие падения phonemizer на странных строках
                    print(f"  skip {spk_tag} {text!r}: {e}")
                    continue
                if len(x) < 1600:
                    continue
                save_wav(path, x)
            writer.writerow([str(path.relative_to(out)).replace("\\", "/"), name, spk_tag, lang,
                             split, kind, text])


class MetaTable:
    """meta.csv: file -> строка; дописывается между запусками без дублей."""
    HEADER = ["file", "voice", "speaker", "lang", "split", "kind", "text"]

    def __init__(self, path):
        self.path, self.rows = path, {}
        if path.exists():
            for r in csv.reader(open(path, encoding="utf-8", newline="")):
                if r and r[0] != "file":
                    self.rows[r[0]] = r

    def writerow(self, row):
        self.rows[row[0]] = list(row)

    def flush(self):
        with open(self.path, "w", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            w.writerow(self.HEADER)
            w.writerows(self.rows.values())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", required=True, help="папка raw_v2 (train/test/...)")
    ap.add_argument("--scale", type=float, default=1.0, help="множитель объёма (0.1 = быстрый прогон)")
    ap.add_argument("--only", help="подстрока имени голоса (отладка)")
    ap.add_argument("--exclude-nc", action="store_true",
                    help="пропустить голоса с некоммерческой лицензией датасета (для коммерческого релиза)")
    ap.add_argument("--exclude-unverified", action="store_true",
                    help="пропустить голоса с неподтверждённой лицензией")
    args = ap.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    meta = MetaTable(out / "meta.csv")  # повторный запуск дополняет таблицу (--only, докачка голосов)
    writer = meta
    vu = out / "voices_used.json"
    missing, done = [], (json.loads(vu.read_text(encoding="utf-8")) if vu.exists() else {})
    sc = lambda n: max(2, int(round(n * args.scale)))
    for name, (lang, role, n_pos, n_neg) in CATALOG.items():
        if args.only and args.only not in name:
            continue
        if name in NON_COMMERCIAL:
            if args.exclude_nc:
                print(f"[{name}] пропущен: некоммерческая лицензия датасета", flush=True)
                continue
            print(f"[{name}] ВНИМАНИЕ: датасет NC, модель нельзя в коммерческий релиз", flush=True)
        if name in LICENSE_UNVERIFIED:
            if args.exclude_unverified:
                print(f"[{name}] пропущен: лицензия не подтверждена", flush=True)
                continue
            print(f"[{name}] внимание: лицензия датасета не подтверждена", flush=True)
        onnx = VOICES / f"{name}.onnx"
        if not onnx.exists():
            missing.append(name)
            continue
        print(f"[{name}] role={role} ...", flush=True)
        voice = PiperVoice.load(str(onnx))
        tr_ids, te_ids = speakers_of(voice, role)
        rng = random.Random(f"v02-{name}")
        if tr_ids:
            run_job(voice, name, lang, "train", tr_ids, sc(n_pos), sc(n_neg), out, writer, rng, "t")
        if role == "test":
            run_job(voice, name, lang, "test", te_ids, sc(n_pos), sc(n_neg), out, writer, rng, "e")
        elif te_ids:
            run_job(voice, name, lang, "test", te_ids, sc(TEST_PER_SPLIT_VOICE[0]),
                    sc(TEST_PER_SPLIT_VOICE[1]), out, writer, rng, "e")
        meta.flush()
        done[name] = (lang, role)
        del voice
    meta.flush()
    (out / "voices_used.json").write_text(json.dumps(done, indent=1, ensure_ascii=False), encoding="utf-8")
    print("готово; нет моделей:", missing)
    for split in ("train", "test"):
        for kind in ("positive", "negative"):
            print(f"  {split}/{kind}: {len(list((out / split / kind).glob('*.wav')))}")


if __name__ == "__main__":
    main()
