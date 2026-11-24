"""Доп. hard-негативы Piper для v0.2 fp-fix (только train-голоса и train-дикторы)."""
import os, sys, random, csv
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import gen_raw_v2 as g
from piper import PiperVoice
# Запуск: PIPER_VOICES=<папка голосов> python gen_hardneg_val.py <out raw_hn> <N на голос>
OUT = Path(sys.argv[1]); N = int(sys.argv[2])
g.VOICES = Path(os.environ["PIPER_VOICES"])

RU_SIM = ["помни", "помни меня", "не помни", "вспомни", "напомни", "обо мне", "о мне", "расскажи о мне",
 "омлет", "омлет на завтрак", "умный", "умный дом", "умная колонка", "тони", "тони пришёл", "омар", "омар хайям",
 "оммм", "ом мани", "они", "они пришли", "он мне", "он мне сказал", "оно мне надо", "а мне", "мне нужно", "мне тоже",
 "помню", "я помню тебя", "не помню", "ни о чём", "ни за что", "ну ладно", "омск", "в омске", "омега", "омут",
 "оптимист", "полно", "комната", "в комнате", "компания", "монитор", "много денег", "омни ли", "огни", "огни города",
 "ономастика", "онемел", "ноль", "ничего", "о нём", "он ни при чём", "нами", "на мне", "на мне платье", "обними",
 "обними меня", "обнимать", "обман", "объём", "любимый", "любимая", "оми", "ами", "эми", "эмми", "томми", "тоня",
 "тоня пришла", "моня", "лёня", "оля", "оля привет", "оми-оми", "ом-ни-бус", "омнибус", "автобус"]
RU_SENT = ["сегодня мы идём в гости к родителям", "что у нас на ужин", "я не успеваю на работу", "позвони мне вечером",
 "положи телефон на стол", "мне кажется, будет дождь", "давай посмотрим фильм", "он мне ничего не сказал",
 "помни, что завтра встреча", "напомни мне про лекарства", "мама, ты где", "включи, пожалуйста, чайник",
 "у меня сегодня много дел", "поставь чайник на плиту", "это очень умный человек", "расскажи мне сказку",
 "слушай, а как ты думаешь", "нам нужно поговорить", "привет, как дела", "я только что пришёл", "сколько это стоит",
 "обо мне не беспокойся", "мне нужно в магазин", "мы едем в омск", "омлет получился вкусный", "тони, иди сюда",
 "отнеси это в комнату", "они уже ушли", "на этой неделе много работы", "дай мне минутку", "что ты сказал",
 "ты не помнишь где ключи", "картошка уже готова", "не забудь выключить свет", "почему так поздно",
 "в комнате очень тихо", "как приятно тебя слышать", "сегодня хорошая погода", "нужно купить молоко и хлеб"]
EN_SIM = ["on me", "oh me", "own me", "hold me", "owe me", "oh mean", "om", "ohm", "omen", "omelet", "ominous",
 "many", "money", "honey", "mommy", "tommy", "tony", "johnny", "only me", "only", "lonely", "homey", "mini", "meany",
 "oh knee", "oh no", "oh my", "oh man", "oh nice", "armani", "romney", "bonnie", "connie", "sunny", "ronnie",
 "I'm nice", "I'm needy", "all knee", "all me", "on my knee", "own the knee", "oh, many", "omni bus", "omnibus",
 "omnivore", "omniscient", "emmy", "amy", "anni", "ani", "oni", "yoni", "keep me", "remember me", "remind me",
 "show me", "tell me", "call me", "text me", "hey tony", "hey honey", "hey mommy", "okay tony", "okay honey", "okay, many",
 "okay then", "hey you", "hey there", "okay google", "hey google", "hey siri", "alexa", "hey amy", "okay amy", "ok mommy"]
EN_SENT = ["what are you doing today", "I need to go to the store", "can you call me later", "it is a lovely evening",
 "tell me what you think", "remind me to buy milk", "he gave me a lot of money", "many people came to the party",
 "oh my god that was close", "I only have a minute", "honey, dinner is ready", "come on over to my place",
 "I'm going home now", "is anybody home", "that's an omen", "make me an omelet please", "tommy is at school",
 "let me know when you're ready", "turn on the lights in the kitchen", "play my favorite song",
 "what's the weather going to be like", "how much money do we have", "I don't want to be alone",
 "he owns many homes", "the meeting is over", "oh nice to meet you", "see you on Monday", "hold on a moment"]
TR_SIM = ["omuz", "ömür", "oni", "onu", "mini", "hemen", "ömer", "omlet", "tamam", "evet", "ona", "bana", "bunu",
 "hey tony", "tamam tony", "eyvah", "oh", "önemli", "omni mi", "ne yapıyorsun", "bana bak", "bizim ev"]
KK_SIM = ["оны", "мен", "омыз", "оңай", "ойын", "оми", "омар", "мені", "мына", "өмір", "оның", "онда", "ол келді", "жаңа үй"]
SIM = {"ru": RU_SIM + RU_SENT, "en": EN_SIM + EN_SENT, "tr": TR_SIM, "kk": KK_SIM}

def main():
    meta = g.MetaTable(OUT / "meta.csv"); rng0 = random.Random(2025)
    for name, (lang, role, _, _) in g.CATALOG.items():
        onnx = g.VOICES / f"{name}.onnx"
        if role == "test" or not onnx.exists(): continue
        try: voice = PiperVoice.load(str(onnx))
        except Exception as e: print('bad voice', name, str(e)[:60], flush=True); continue
        tr_ids, _ = g.speakers_of(voice, role)
        rng = random.Random(f"hn-{name}")
        n = N if lang in ("ru", "en") else max(2, N // 4)
        if lang == "en" and "vctk" not in name and "l2arctic" not in name and "libritts" not in name and "arctic" not in name and "-" in name and name.split("-")[0] in ("de_DE","pl_PL","cs_CZ","fr_FR","it_IT","pt_BR","es_ES"):
            n = N // 3
        for i in range(n):
            spk = tr_ids[i % len(tr_ids)]
            text = rng.choice(SIM[lang])
            tag = name + (f"_s{spk}" if spk is not None else "")
            path = OUT / "train" / "negative" / f"{tag}_h{i:04d}.wav"
            if path.exists(): continue
            try: x = g.synth_to_16k(voice, text, rng, spk)
            except Exception as e: print("skip", tag, text, e); continue
            if len(x) < 1600: continue
            g.save_wav(path, x)
            meta.writerow([str(path.relative_to(OUT)).replace("\\", "/"), name, tag, lang, "train", "negative", text])
        meta.flush(); print(name, n, flush=True); del voice
if __name__ == "__main__": main()
