"""Собирает тексты для записи голоса OMNIX: базовые фразы + типовые ответы ассистента.

Числа пишутся словами с согласованием — диктор читает ровно то, что написано,
и фонемизатор при обучении видит те же слова. Результат детерминирован (seed).

    python make_phrases.py            -> phrases_ru.txt, phrases_tk.txt (id|текст)
"""
import random
from pathlib import Path

HERE = Path(__file__).parent
TARGET = 600

# ---------------------------------------------------------------- русский
RU_UNITS = ["ноль", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"]
RU_TENS = ["", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят", "семьдесят", "восемьдесят",
           "девяносто"]
RU_HUNDREDS = ["", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот", "семьсот", "восемьсот",
               "девятьсот"]


def ru_num(n: int, feminine: bool = False) -> str:
    assert 0 <= n < 1000
    if n == 0:
        return "ноль"
    words = []
    if n >= 100:
        words.append(RU_HUNDREDS[n // 100])
        n %= 100
    if n >= 20:
        words.append(RU_TENS[n // 10])
        n %= 10
    if n:
        w = RU_UNITS[n]
        if feminine and n == 1:
            w = "одна"
        elif feminine and n == 2:
            w = "две"
        words.append(w)
    return " ".join(words)


def ru_plural(n: int, one: str, few: str, many: str) -> str:
    if 11 <= n % 100 <= 14:
        return many
    return {1: one, 2: few, 3: few, 4: few}.get(n % 10, many)


RU_ORD_NEUT = ["", "первое", "второе", "третье", "четвёртое", "пятое", "шестое", "седьмое", "восьмое",
               "девятое", "десятое", "одиннадцатое", "двенадцатое", "тринадцатое", "четырнадцатое",
               "пятнадцатое", "шестнадцатое", "семнадцатое", "восемнадцатое", "девятнадцатое", "двадцатое"]


def ru_ord_neut(d: int) -> str:
    if d <= 20:
        return RU_ORD_NEUT[d]
    if d == 30:
        return "тридцатое"
    tens = "двадцать" if d < 30 else "тридцать"
    return f"{tens} {RU_ORD_NEUT[d % 10]}"


RU_MONTHS_GEN = ["января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября",
                 "октября", "ноября", "декабря"]
RU_WEEKDAYS = ["понедельник", "вторник", "среда", "четверг", "пятница", "суббота", "воскресенье"]
RU_CITIES_PREP = ["Ашхабаде", "Мары", "Туркменабаде", "Дашогузе", "Балканабаде", "Аваза", "Москве",
                  "Стамбуле", "Дубае", "Ташкенте", "Алматы", "Тегеране"]
RU_WEATHER = ["солнечно", "ясно", "облачно", "пасмурно", "переменная облачность", "небольшой дождь",
              "ливень", "ветрено", "туман", "снег", "гроза", "жарко и сухо"]
# (именительный, дательный, творительный)
RU_NAMES = [("Сердар", "Сердару", "Сердаром"), ("Айна", "Айне", "Айной"), ("Мердан", "Мердану", "Мерданом"),
            ("Джерен", "Джерен", "Джерен"), ("Майса", "Майсе", "Майсой"), ("Батыр", "Батыру", "Батыром"),
            ("Мурад", "Мураду", "Мурадом"), ("Довлет", "Довлету", "Довлетом"), ("Айгуль", "Айгуль", "Айгуль"),
            ("Лейли", "Лейли", "Лейли"), ("Кемал", "Кемалу", "Кемалом"), ("Огулджан", "Огулджан", "Огулджан"),
            ("мама", "маме", "мамой"), ("папа", "папе", "папой"), ("брат", "брату", "братом"),
            ("сестра", "сестре", "сестрой"), ("начальник", "начальнику", "начальником"),
            ("Анна", "Анне", "Анной"), ("Дмитрий", "Дмитрию", "Дмитрием"), ("Ирина", "Ирине", "Ириной")]
RU_TASKS = ["купить хлеб", "позвонить врачу", "оплатить интернет", "забрать посылку", "полить цветы",
            "отправить отчёт", "записаться в парикмахерскую", "поздравить бабушку", "заправить машину",
            "выпить лекарство", "забрать детей из школы", "сделать зарядку", "проверить почту",
            "купить подарок", "вынести мусор"]
RU_SONGS = ["спокойную музыку", "что-нибудь весёлое", "туркменские песни", "классическую музыку",
            "последний альбом", "радио", "джаз", "музыку для бега", "колыбельную", "новости"]


def ru_time(h: int, m: int) -> str:
    hours = f"{ru_num(h)} {ru_plural(h, 'час', 'часа', 'часов')}"
    if m == 0:
        return f"{hours} ровно"
    return f"{hours} {ru_num(m, feminine=True)} {ru_plural(m, 'минута', 'минуты', 'минут')}"


def ru_templates(rng: random.Random):
    h, m = rng.randint(1, 12), rng.choice([0, 5, 10, 15, 20, 25, 30, 40, 45, 50, 55, rng.randint(1, 59)])
    n = rng.randint(2, 59)
    t = rng.randint(1, 45)
    name, dat, ins = rng.choice(RU_NAMES)
    city = rng.choice(RU_CITIES_PREP)
    wd = rng.choice(RU_WEEKDAYS)
    d, mon = rng.randint(1, 31), rng.choice(RU_MONTHS_GEN)
    p = rng.randint(1, 100)
    price, kop = rng.randint(2, 999), rng.choice([0, 10, 20, 50, 75])
    return rng.choice([
        f"Будильник поставлен на {ru_time(h, m)}.",
        f"Сейчас {ru_time(h, m)}.",
        f"Напомню через {ru_num(n, True)} {ru_plural(n, 'минуту', 'минуты', 'минут')}.",
        f"Таймер на {ru_num(n, True)} {ru_plural(n, 'минуту', 'минуты', 'минут')} запущен.",
        f"На улице плюс {ru_num(t)} {ru_plural(t, 'градус', 'градуса', 'градусов')}, {rng.choice(RU_WEATHER)}.",
        f"Ночью минус {ru_num(min(t, 20))} {ru_plural(min(t, 20), 'градус', 'градуса', 'градусов')}.",
        f"Завтра в {city} {rng.choice(RU_WEATHER)}, температура {ru_num(t)} {ru_plural(t, 'градус', 'градуса', 'градусов')}.",
        f"Сейчас в {city} {rng.choice(RU_WEATHER)}.",
        f"Звоню {dat}.",
        f"Отправляю сообщение {dat}.",
        f"Вам пишет {name}.",
        f"Встреча с {ins} в {ru_num(h)} {ru_plural(h, 'час', 'часа', 'часов')}.",
        f"Напомнить {dat} про встречу?",
        f"Сегодня {wd}, {ru_ord_neut(d)} {mon}.",
        f"Ваша встреча назначена на {ru_ord_neut(d)} {mon}.",
        f"Батарея заряжена на {ru_num(p)} {ru_plural(p, 'процент', 'процента', 'процентов')}.",
        f"Осталось {ru_num(p)} {ru_plural(p, 'процент', 'процента', 'процентов')} заряда.",
        f"Сумма к оплате: {ru_num(price)} {ru_plural(price, 'манат', 'маната', 'манатов')}"
        + (f" {ru_num(kop)} тенге." if kop else "."),
        f"Напоминаю: нужно {rng.choice(RU_TASKS)}.",
        f"Хорошо, напомню {rng.choice(RU_TASKS)} в {ru_num(h)} {ru_plural(h, 'час', 'часа', 'часов')}.",
        f"Включаю {rng.choice(RU_SONGS)}.",
        f"Дорога до работы займёт {ru_num(n, True)} {ru_plural(n, 'минуту', 'минуты', 'минут')}.",
        f"Через {ru_num(n % 9 + 2)} {ru_plural(n % 9 + 2, 'километр', 'километра', 'километров')} поверните налево.",
        f"Громкость {ru_num(p)} {ru_plural(p, 'процент', 'процента', 'процентов')}.",
        f"Вы прошли {ru_num(p)} {ru_plural(p, 'километр', 'километра', 'километров')} за неделю.",
        f"Курс доллара: {ru_num(n % 38 + 3)} {ru_plural(n % 38 + 3, 'манат', 'маната', 'манатов')}.",
    ])


# --------------------------------------------------------------- туркменский
TK_UNITS = ["nol", "bir", "iki", "üç", "dört", "bäş", "alty", "ýedi", "sekiz", "dokuz"]
TK_TENS = ["", "on", "ýigrimi", "otuz", "kyrk", "elli", "altmyş", "ýetmiş", "segsen", "togsan"]


def tk_num(n: int) -> str:
    assert 0 <= n < 1000
    if n == 0:
        return "nol"
    words = []
    if n >= 100:
        words += ([TK_UNITS[n // 100]] if n // 100 > 1 else []) + ["ýüz"]
        n %= 100
    if n >= 10:
        words.append(TK_TENS[n // 10])
        n %= 10
    if n:
        words.append(TK_UNITS[n])
    return " ".join(words)


# Притяжательная форма последнего слова: «ýigrimi iki» → «ýigrimi ikisi» (дата: «oktýabryň ýigrimi ikisi»).
TK_POSS = {"bir": "biri", "iki": "ikisi", "üç": "üçi", "dört": "dördi", "bäş": "bäşi", "alty": "altysy",
           "ýedi": "ýedisi", "sekiz": "sekizi", "dokuz": "dokuzy", "on": "ony", "ýigrimi": "ýigrimisi",
           "otuz": "otuzy"}
# Дательный и местный падеж часа: «sagat ýedä», «sagat ýedide».
TK_HOUR_DAT = ["", "bire", "ikä", "üçe", "dörde", "bäşe", "alta", "ýedä", "sekize", "dokuza", "ona", "on bire",
               "on ikä"]
TK_HOUR_LOC = ["", "birde", "ikide", "üçde", "dörtde", "bäşde", "altyda", "ýedide", "sekizde", "dokuzda", "onda",
               "on birde", "on ikide"]
TK_MONTHS_GEN = ["ýanwaryň", "fewralyň", "martyň", "apreliň", "maýyň", "iýunyň", "iýulyň", "awgustyň",
                 "sentýabryň", "oktýabryň", "noýabryň", "dekabryň"]
TK_WEEKDAYS = ["duşenbe", "sişenbe", "çarşenbe", "penşenbe", "anna", "şenbe", "ýekşenbe"]
TK_CITIES_LOC = ["Aşgabatda", "Maryda", "Türkmenabatda", "Daşoguzda", "Balkanabatda", "Awazada", "Moskwada",
                 "Stambulda", "Dubaýda", "Daşkentde", "Almatyda", "Tährande"]
TK_WEATHER = ["gün şöhleli", "açyk", "bulutly", "tutuk", "ýagyşly", "gyrmaýagyş", "şemally", "dumanly",
              "garly", "ýyldyrymly", "yssy we gurak"]
# (именительный, дательный)
TK_NAMES = [("Serdar", "Serdara"), ("Aýna", "Aýna"), ("Merdan", "Merdana"), ("Jeren", "Jerene"),
            ("Maýsa", "Maýsa"), ("Batyr", "Batyra"), ("Myrat", "Myrada"), ("Döwlet", "Döwlede"),
            ("Aýgül", "Aýgüle"), ("Leýli", "Leýlä"), ("Kemal", "Kemala"), ("Ogulja", "Ogulja"),
            ("Ejem", "Ejeme"), ("Kakam", "Kakama"), ("Doganym", "Doganyma"), ("Uýam", "Uýama"),
            ("Başlyk", "Başlyga"), ("Mähri", "Mährä"), ("Arslan", "Arslana"), ("Gülşat", "Gülşada")]
TK_TASKS = ["çörek satyn almagy", "lukmana jaň etmegi", "interneti tölemegi", "bukjany almagy",
            "güllere suw bermegi", "hasabaty ibermegi", "enemi gutlamagy", "maşyna ýangyç guýmagy",
            "derman içmegi", "çagalary mekdepden almagy", "poçtany barlamagy", "sowgat almagy",
            "zibili çykarmagy", "sport bilen meşgullanmagy"]
TK_SONGS = ["asuda aýdym", "şadyýan aýdym", "türkmen aýdymlaryny", "nusgawy sazy", "radiony",
            "täzelikleri", "ylgamak üçin sazy", "hüwdi aýdymyny", "jaz sazyny"]


def tk_date(d: int) -> str:
    words = tk_num(d).split()
    words[-1] = TK_POSS[words[-1]]
    return " ".join(words)


def tk_templates(rng: random.Random):
    h, m = rng.randint(1, 12), rng.choice([5, 10, 15, 20, 25, 30, 40, 45, 50, 55, rng.randint(1, 59)])
    n = rng.randint(2, 59)
    t = rng.randint(1, 45)
    name, dat = rng.choice(TK_NAMES)
    city = rng.choice(TK_CITIES_LOC)
    d, mon = rng.randint(1, 31), rng.choice(TK_MONTHS_GEN)
    p = rng.randint(1, 100)
    price, teňňe = rng.randint(2, 999), rng.choice([0, 10, 20, 50, 75])
    return rng.choice([
        f"Oýardyjy sagat {TK_HOUR_DAT[h]} goýuldy.",
        f"Häzir sagat {tk_num(h)}, {tk_num(m)} minut.",
        f"{tk_num(n).capitalize()} minutdan ýatladaryn.",
        f"{tk_num(n).capitalize()} minutlyk taýmer başlandy.",
        f"Daşarda {tk_num(t)} gradus ýylylyk, {rng.choice(TK_WEATHER)}.",
        f"Gijesine {tk_num(min(t, 20))} gradus aýaz.",
        f"Ertir {city} {rng.choice(TK_WEATHER)}, {tk_num(t)} gradus.",
        f"Häzir {city} howa {rng.choice(TK_WEATHER)}.",
        f"{dat} jaň edýärin.",
        f"{dat} habar iberýärin.",
        f"{name} täze habar iberdi.",
        f"{name} bilen duşuşyk sagat {TK_HOUR_LOC[h]}.",
        f"{dat} duşuşyk hakda ýatlatmalymy?",
        f"Şu gün {rng.choice(TK_WEEKDAYS)}, {mon} {tk_date(d)}.",
        f"Duşuşygyňyz {mon} {tk_date(d)} bellenildi.",
        f"Batareýa {tk_num(p)} göterim zarýadly.",
        f"Zarýadyň {tk_num(p)} göterimi galdy.",
        f"Tölenmeli mukdar: {tk_num(price)} manat" + (f" {tk_num(teňňe)} teňňe." if teňňe else "."),
        f"Ýatladýaryn: {rng.choice(TK_TASKS)} unutmaň.",
        f"Bolýar, sagat {TK_HOUR_LOC[h]} {rng.choice(TK_TASKS)} ýatladaryn.",
        f"{rng.choice(TK_SONGS).capitalize()} goýýaryn.",
        f"Işe barýan ýol {tk_num(n)} minut alar.",
        f"{tk_num(n % 9 + 2).capitalize()} kilometrden çepe öwrüliň.",
        f"Sesiň güýji {tk_num(p)} göterim.",
        f"Siz bir hepdede {tk_num(p)} kilometr ýöräpsiňiz.",
    ])


def build(lang: str, base_file: str, templates):
    rng = random.Random(f"omnix-voice-{lang}")
    lines = [l.strip() for l in (HERE / base_file).read_text(encoding="utf-8").splitlines() if l.strip()]
    seen = set(lines)
    attempts = 0
    while len(lines) < TARGET and attempts < 50_000:
        attempts += 1
        s = templates(rng)
        s = s[0].upper() + s[1:]
        if s not in seen:
            seen.add(s)
            lines.append(s)
    out = HERE / f"phrases_{lang}.txt"
    out.write_text("".join(f"{lang}_{i:04d}|{s}\n" for i, s in enumerate(lines, 1)), encoding="utf-8")
    print(out.name, len(lines))


if __name__ == "__main__":
    build("ru", "phrases_ru_base.txt", ru_templates)
    build("tk", "phrases_tk_base.txt", tk_templates)
