#!/usr/bin/env python3
"""Phase A: синтез raw-данных для wake-word «Omni» через Piper TTS.

Train-голоса (5): en_US-lessac, en_US-ryan, en_GB-alan, ru_RU-irina, ru_RU-dmitri
Test-голоса (2, speaker-disjoint): en_GB-jenny_dioco, ru_RU-ruslan

Позитивы: «omni» (en) / «омни» (ru), вариативные speed/noise синтеза.
Негативы: adversarial-тексты без omni/омни (трудные: many/money/honey/они/... + бытовые фразы).

Выход: 16 кГц mono int16 wav в training/raw/{split}/{kind}/{voice}_{i}.wav
"""
import os
import random
import wave
from pathlib import Path

import numpy as np
from scipy.signal import resample_poly
from piper import PiperVoice, SynthesisConfig

random.seed(42)
np.random.seed(42)

ROOT = Path(__file__).resolve().parent
RAW = ROOT / "raw"
VOICES = Path(os.environ.get("PIPER_VOICES", str(ROOT / "voices")))

TRAIN_VOICES = [
    ("en_US-lessac-medium", "en"),
    ("en_US-ryan-medium", "en"),
    ("en_US-amy-medium", "en"),
    ("en_GB-alan-medium", "en"),
    ("en_GB-northern_english_male-medium", "en"),
    ("ru_RU-irina-medium", "ru"),
    ("ru_RU-dmitri-medium", "ru"),
    ("de_DE-thorsten-medium", "de"),
    ("pl_PL-darkman-medium", "pl"),
    ("cs_CZ-jirka-medium", "cs"),
    ("fr_FR-siwis-medium", "fr"),
    ("it_IT-riccardo-x_low", "it"),
    ("pt_BR-faber-medium", "pt"),
    ("uk_UA-ukrainian_tts-medium", "uk"),
    ("es_ES-carlfm-x_low", "es"),
]
TEST_VOICES = [
    ("en_GB-jenny_dioco-medium", "en"),
    ("ru_RU-ruslan-medium", "ru"),
]

# Голоса ТОЛЬКО для негативов: учат модель "любой голос без omni = 0"
NEG_ONLY_VOICES = [
    ("en_US-kristin-medium", "en"),
    ("en_US-kusal-medium", "en"),
    ("fr_FR-tom-medium", "fr"),
    ("it_IT-paola-medium", "it"),
    ("sv_SE-nst-medium", "en"),
]
N_NEG_ONLY = 100

N_POS_TRAIN, N_NEG_TRAIN = 150, 150
N_POS_TEST, N_NEG_TEST = 90, 50

EN_NEG_HARD = [
    "many", "money", "honey", "on me", "omen", "mommy", "tommy", "yoni",
    "homie", "oh manny", "oh money", "own me", "om nom nom", "monty",
    "connie", "bonnie", "johnnie", "tony", "sonny", "ronnie", "annie",
    "any", "money money", "so many", "on my way", "home alone", "oh no",
]
EN_NEG_GENERAL = [
    "hey google", "alexa", "hey siri", "ok google", "computer",
    "hey jarvis", "hey mycroft", "hey cortana", "what time is it",
    "turn on the lights", "play some music", "call my mom",
    "set a timer for ten minutes", "what's the weather like",
    "open the door", "stop the music", "no thanks", "yes please",
    "hello there", "good morning", "good night", "tell me a joke",
    "remind me to call the doctor", "navigate home", "send a message",
    "volume up", "volume down", "mute the tv", "who is at the door",
    "how long until dinner", "make a call", "answer the phone", "hang up",
    "later today", "maybe tomorrow", "monday morning", "so many things",
    "money in the bank", "honey i'm home", "the weather is nice",
    "can you help me", "i don't know", "let me think", "one moment please",
    "good morning everyone", "good afternoon", "good evening",
    "what's on the news today", "i had a great day", "let's grab lunch",
    "the meeting is at three", "can you hear me now", "are you still there",
    "hold on a second", "that sounds great", "i'll be right back",
    "where are the keys", "the store closes at nine", "traffic was terrible",
    "i need more coffee", "it's getting late", "see you tomorrow",
    "thanks for everything", "no problem at all", "have a nice day",
    "what a beautiful morning", "it might rain later", "the phone is ringing",
    "someone is coming over", "dinner is almost ready", "i'm going to bed",
    "did you take out the trash", "the kids are at school", "my back hurts",
    "turn it down a little", "put that away please", "come here for a second",
    "look at this picture", "i love this song", "change the channel",
    "we're out of milk", "order a pizza tonight", "the game starts soon",
]
RU_NEG_HARD = [
    "они", "мне", "он мне", "конни", "монни", "томми", "омлет", "омм",
    "монитор", "сомни", "тони", "сони", "бонни", "полночь", "только",
    "много", "маня", "моня", "они же", "на мониторе", "омлет с сыром",
]
RU_NEG_GENERAL = [
    "привет", "да", "нет", "спасибо", "сколько времени", "включи свет",
    "выключи музыку", "поставь будильник на семь утра", "позвони маме",
    "какая завтра погода", "открой дверь", "выключи телевизор",
    "сделай громче", "напомни позвонить врачу", "прокладывай маршрут домой",
    "отправь сообщение", "доброе утро", "доброй ночи", "расскажи анекдот",
    "останови музыку", "играй музыку", "кто у двери", "сколько ещё ждать",
    "много вещей", "деньги в банке", "погода хорошая", "ты можешь помочь",
    "я не знаю", "дай подумать", "одну минуту", "до завтра", "ладно",
    "доброе утро всем", "добрый день", "добрый вечер", "что в новостях",
    "у меня был хороший день", "давай пообедаем", "встреча в три часа",
    "ты меня слышишь", "ты ещё здесь", "подожди секунду", "звучит отлично",
    "я сейчас вернусь", "где ключи", "магазин закрывается в девять",
    "пробки ужасные", "мне нужен кофе", "уже поздно", "до завтра",
    "спасибо за всё", "без проблем", "хорошего дня", "какое красивое утро",
    "позже будет дождь", "телефон звонит", "кто-то приходит", "ужин почти готов",
    "я иду спать", "вынеси мусор", "дети в школе", "у меня болит спина",
    "сделай потише", "убери это", "подойди на секунду", "посмотри на фото",
    "люблю эту песню", "включи другой канал", "закончилось молоко",
    "закажи пиццу вечером", "игра скоро начнётся", "умный дом", "умный мальчик",
]

NATIVE_NEG = {
    "de": ["wie spät ist es", "mach das licht an", "guten morgen", "hallo", "nein danke",
           "spiel etwas musik", "wecke mich um sieben", "wo ist mein handy"],
    "pl": ["która godzina", "włącz światło", "dzien dobry", "nie", "grać muzykę",
           "obudź mnie o siódmej", "gdzie jest mój telefon"],
    "cs": ["kolik je hodin", "zapni světlo", "dobré ráno", "ne díky", "přehraj hudbu",
           "vzbud mě v sedm", "kde je můj telefon"],
    "fr": ["quelle heure est-il", "allume la lumière", "bonjour", "non merci",
           "joue de la musique", "réveille-moi à sept heures", "où est mon téléphone"],
    "it": ["che ore sono", "accendi la luce", "buongiorno", "no grazie", "suona musica",
           "svegliami alle sette", "dov'è il mio telefono"],
    "pt": ["que horas são", "acenda a luz", "bom dia", "não obrigado", "toca música",
           "me acorde às sete", "onde está meu telefone"],
    "uk": ["котра година", "увімкни світло", "доброго ранку", "ні дякую", "включи музику",
           "розбуди мене о сьомій", "де мій телефон"],
    "es": ["qué hora es", "enciende la luz", "buenos días", "no gracias", "pon música",
           "despiértame a las siete", "dónde está mi teléfono"],
}

def adversarial_pool(n=300):
    """Официальный генератор adversarial-фраз по фонемному пересечению."""
    try:
        from openwakeword.data import generate_adversarial_texts
        out = [t.strip().lower() for t in generate_adversarial_texts("omni", n)]
        return sorted({t for t in out if t and "omni" not in t and len(t) < 60})
    except Exception as e:
        print(f"adversarial_pool fallback: {e}")
        return []

ADV_POOL = adversarial_pool()

def neg_texts(lang, n, rng):
    hard = EN_NEG_HARD if lang == "en" else RU_NEG_HARD
    general = EN_NEG_GENERAL if lang == "en" else RU_NEG_GENERAL
    native = NATIVE_NEG.get(lang, [])
    out = []
    for _ in range(n):
        r = rng.random()
        if r < 0.25:
            out.append(rng.choice(hard))
        elif r < 0.55 and ADV_POOL:
            out.append(rng.choice(ADV_POOL))
        elif r < 0.85 or not native:
            out.append(rng.choice(general))
        else:
            out.append(rng.choice(native))
    return out

def pos_text(lang, rng):
    base = "omni" if lang == "en" else "омни"
    r = rng.random()
    if r < 0.80:
        return base
    if r < 0.90:
        return base.capitalize() + "!"
    return base.capitalize() + "?"

def synth_to_16k(voice, text, rng):
    """Синтез с рандомными параметрами -> float32 16 кГц mono."""
    cfg = SynthesisConfig(
        length_scale=rng.uniform(0.65, 1.2),
        noise_scale=rng.uniform(0.2, 0.75),
        noise_w_scale=rng.uniform(0.2, 0.75),
    )
    chunks = [c.audio_float_array for c in voice.synthesize(text, syn_config=cfg)]
    raw = np.concatenate(chunks).astype(np.float32)
    # 22050 -> 16000: up=320, down=441 (gcd=50)
    if voice.config.sample_rate != 16000:
        up = 16000 // 50
        down = voice.config.sample_rate // 50
        raw = resample_poly(raw, up, down)
    return trim_silence(raw)

def trim_silence(x, thresh_ratio=0.02, margin_ms=30):
    """Обрезать тишину по краям, оставить margin_ms."""
    if len(x) == 0:
        return x
    mag = np.abs(x)
    thresh = thresh_ratio * mag.max()
    idx = np.where(mag > thresh)[0]
    if len(idx) == 0:
        return x
    margin = int(0.030 * 16000)
    a = max(0, idx[0] - margin)
    b = min(len(x), idx[-1] + margin)
    return x[a:b]

def save_wav(path, x_float):
    x = np.clip(x_float, -1.0, 1.0)
    xi = (x * 32767).astype(np.int16)
    path.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(16000)
        w.writeframes(xi.tobytes())

def main():
    print("=== Phase A: синтез raw-данных Piper TTS ===")
    plan = []
    for voice_name, lang in TRAIN_VOICES:
        plan.append((voice_name, lang, "train", N_POS_TRAIN, N_NEG_TRAIN))
    for voice_name, lang in TEST_VOICES:
        plan.append((voice_name, lang, "test", N_POS_TEST, N_NEG_TEST))
    for voice_name, lang in NEG_ONLY_VOICES:
        plan.append((voice_name, lang, "train_neg_only", 0, N_NEG_ONLY))

    total = 0
    for voice_name, lang, split, n_pos, n_neg in plan:
        print(f"[{voice_name}] split={split} pos={n_pos} neg={n_neg} ...", flush=True)
        voice = PiperVoice.load(str(VOICES / f"{voice_name}.onnx"))
        rng = random.Random(f"{voice_name}-{split}")
        neg_split = "train" if split == "train_neg_only" else split
        for i in range(n_pos):
            x = synth_to_16k(voice, pos_text(lang, rng), rng)
            save_wav(RAW / split / "positive" / f"{voice_name}_{i:04d}.wav", x)
            total += 1
        for i, text in enumerate(neg_texts(lang, n_neg, rng)):
            x = synth_to_16k(voice, text, rng)
            save_wav(RAW / neg_split / "negative" / f"{voice_name}_{i:04d}.wav", x)
            total += 1
        print(f"[{voice_name}] done, total={total}", flush=True)
        del voice
    print(f"ИТОГО синтезировано клипов: {total}")
    for split in ("train", "test"):
        for kind in ("positive", "negative"):
            n = len(list((RAW / split / kind).glob("*.wav")))
            print(f"  {split}/{kind}: {n}")

if __name__ == "__main__":
    main()
