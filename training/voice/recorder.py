"""Запись голоса OMNIX: локальная страница в браузере, файлы сразу в формате для обучения Piper.

    python recorder.py            -> открой http://localhost:8765 в Chrome

Микрофон в браузере разрешён только на localhost, поэтому страницу отдаёт этот
скрипт. Записи кладутся в recordings/<язык>/wavs/<id>.wav, тексты — в
recordings/<язык>/metadata.csv (`ru_0001.wav|текст`, как ждёт Piper). Папка recordings
не попадает в git: это личные данные диктора.
"""
import base64
import json
import threading
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).parent
REC = HERE / "recordings"
PORT = 8765
LANGS = {"ru": "Русский", "tk": "Türkmençe"}
LOCK = threading.Lock()


def phrases(lang: str) -> list[dict]:
    rows = []
    for line in (HERE / f"phrases_{lang}.txt").read_text(encoding="utf-8").splitlines():
        if "|" in line:
            pid, text = line.split("|", 1)
            rows.append({"id": pid, "text": text})
    return rows


def state_path(lang: str) -> Path:
    return REC / lang / "state.json"


def load_state(lang: str) -> dict:
    p = state_path(lang)
    if p.is_file():
        return json.loads(p.read_text(encoding="utf-8"))
    return {"texts": {}, "skipped": []}


def save_state(lang: str, state: dict) -> None:
    d = REC / lang
    d.mkdir(parents=True, exist_ok=True)
    state_path(lang).write_text(json.dumps(state, ensure_ascii=False, indent=1), encoding="utf-8")
    # metadata.csv — только записанные фразы, с текстом в том виде, как его прочитали.
    wavs = d / "wavs"
    lines = []
    for row in phrases(lang):
        if (wavs / f"{row['id']}.wav").is_file():
            lines.append(f"{row['id']}.wav|{state['texts'].get(row['id'], row['text'])}")
    (d / "metadata.csv").write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def send(self, code: int, body: bytes, ctype: str):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        q = parse_qs(url.query)
        if url.path == "/":
            self.send(200, PAGE.encode("utf-8"), "text/html; charset=utf-8")
        elif url.path == "/api/phrases":
            lang = q.get("lang", ["ru"])[0]
            if lang not in LANGS:
                return self.send(400, b"bad lang", "text/plain")
            with LOCK:
                st = load_state(lang)
            wavs = REC / lang / "wavs"
            out = [{
                "id": r["id"],
                "text": st["texts"].get(r["id"], r["text"]),
                "done": (wavs / f"{r['id']}.wav").is_file(),
                "skipped": r["id"] in st["skipped"],
            } for r in phrases(lang)]
            self.send(200, json.dumps(out, ensure_ascii=False).encode("utf-8"), "application/json")
        elif url.path == "/api/audio":
            lang, pid = q.get("lang", [""])[0], q.get("id", [""])[0]
            f = REC / lang / "wavs" / f"{pid}.wav"
            if lang in LANGS and pid.replace("_", "").isalnum() and f.is_file():
                self.send(200, f.read_bytes(), "audio/wav")
            else:
                self.send(404, b"", "text/plain")
        else:
            self.send(404, b"not found", "text/plain")

    def do_POST(self):
        url = urlparse(self.path)
        body = json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"{}")
        lang, pid = body.get("lang"), body.get("id", "")
        if lang not in LANGS or not pid.replace("_", "").isalnum():
            return self.send(400, b"bad request", "text/plain")
        with LOCK:
            st = load_state(lang)
            if url.path == "/api/save":
                wavs = REC / lang / "wavs"
                wavs.mkdir(parents=True, exist_ok=True)
                (wavs / f"{pid}.wav").write_bytes(base64.b64decode(body["wav"]))
                st["texts"][pid] = body["text"].strip()
                st["skipped"] = [s for s in st["skipped"] if s != pid]
            elif url.path == "/api/text":
                st["texts"][pid] = body["text"].strip()
            elif url.path == "/api/skip":
                if pid not in st["skipped"]:
                    st["skipped"].append(pid)
            else:
                return self.send(404, b"", "text/plain")
            save_state(lang, st)
        self.send(200, b"{}", "application/json")


PAGE = r"""<!doctype html>
<html lang="ru"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Запись голоса OMNIX</title>
<style>
:root{--bg:#101312;--panel:#181c1b;--line:#2a302e;--fg:#e9eeec;--dim:#8f9a96;--lime:#c6f432;--rec:#ff5a4f;--warn:#ffb648;
--display:"Segoe UI",system-ui,sans-serif;--mono:"Cascadia Mono",Consolas,monospace}
*{box-sizing:border-box}html,body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.5 var(--display)}
main{max-width:860px;margin:0 auto;padding:24px 16px 48px;display:grid;gap:18px}
header{display:flex;flex-wrap:wrap;gap:12px;align-items:center;justify-content:space-between}
h1{font-size:18px;font-weight:600;margin:0;letter-spacing:.02em}
select,button,textarea{font:inherit;color:var(--fg);background:var(--panel);border:1px solid var(--line);border-radius:10px}
select,button{padding:8px 14px;cursor:pointer}button:focus-visible,select:focus-visible,textarea:focus-visible{outline:2px solid var(--lime);outline-offset:2px}
.progress{font-family:var(--mono);color:var(--dim);font-size:14px}
.bar{height:6px;background:var(--line);border-radius:3px;overflow:hidden}.bar>i{display:block;height:100%;background:var(--lime);width:0}
.card{background:var(--panel);border:1px solid var(--line);border-radius:18px;padding:28px 24px;display:grid;gap:16px}
.pid{font-family:var(--mono);font-size:13px;color:var(--dim);display:flex;gap:10px;flex-wrap:wrap}
.phrase{font-size:30px;line-height:1.35;font-weight:500;text-wrap:balance;min-height:2.7em}
textarea{width:100%;font-size:22px;padding:12px;min-height:110px}
.meter{height:14px;background:#0b0d0c;border-radius:7px;overflow:hidden;border:1px solid var(--line)}.meter>i{display:block;height:100%;width:0;background:var(--lime);transition:width 60ms}
.status{min-height:24px;font-size:15px}.status.rec{color:var(--rec)}.status.warn{color:var(--warn)}.status.ok{color:var(--lime)}
.actions{display:flex;flex-wrap:wrap;gap:10px}.actions .big{background:var(--lime);color:#111;border-color:var(--lime);font-weight:600;padding:12px 22px}
.actions .big.recording{background:var(--rec);border-color:var(--rec);color:#fff}
.keys{color:var(--dim);font-size:14px}kbd{font-family:var(--mono);background:var(--panel);border:1px solid var(--line);border-radius:5px;padding:1px 6px;font-size:12px}
details{color:var(--dim);font-size:14px}details li{margin:4px 0}
</style></head><body><main>
<header><h1>Запись голоса OMNIX</h1>
<div style="display:flex;gap:10px;align-items:center"><label for="lang">Язык</label>
<select id="lang"><option value="ru">Русский</option><option value="tk">Türkmençe</option></select></div></header>
<div><div class="progress" id="progress">—</div><div class="bar"><i id="bar"></i></div></div>
<section class="card">
<div class="pid"><span id="pid">—</span><span id="flag"></span></div>
<div class="phrase" id="phrase">Загрузка…</div>
<textarea id="editor" hidden></textarea>
<div class="meter" aria-hidden="true"><i id="meter"></i></div>
<div class="status" id="status">Нажмите «Запись» или пробел. Первый раз браузер спросит разрешение на микрофон.</div>
<div class="actions">
<button class="big" id="recBtn">● Запись</button>
<button id="playBtn" disabled>▶ Прослушать</button>
<button id="prevBtn">← Назад</button>
<button id="nextBtn">Дальше →</button>
<button id="editBtn">✎ Исправить текст</button>
<button id="skipBtn">Пропустить</button>
<button id="todoBtn">К первой незаписанной</button>
</div>
<div class="keys"><kbd>Пробел</kbd> запись / стоп · <kbd>P</kbd> прослушать · <kbd>→</kbd> дальше · <kbd>←</kbd> назад · <kbd>E</kbd> исправить текст</div>
</section>
<details><summary>Как записывать</summary><ul>
<li>Тихая комната без эха (ковёр, шторы, мебель). Выключите кондиционер и вентиляторы.</li>
<li>Микрофон в 15–20 см ото рта, всегда на одном месте. Один и тот же микрофон для всех записей.</li>
<li>Читайте спокойно и ровно, тем голосом, каким должен говорить OMNIX. Не спешите.</li>
<li>Перед словами и после — полсекунды тишины; лишнее программа обрежет сама.</li>
<li>Ошиблись — просто нажмите пробел ещё раз и перезапишите.</li>
<li>Если фраза звучит неестественно — «Исправить текст», затем читайте исправленное.</li>
<li>Делайте перерыв каждые 20–30 минут, пейте воду.</li></ul></details>
</main>
<script>
const $=id=>document.getElementById(id);
let lang=localStorage.getItem('omnix-lang')||'ru', items=[], idx=0, ctx=null, stream=null, node=null, chunks=[], recording=false, lastUrl=null, rate=48000;
$('lang').value=lang;
async function load(){
  items=await (await fetch('/api/phrases?lang='+lang)).json();
  const saved=+localStorage.getItem('omnix-idx-'+lang);
  idx=Number.isFinite(saved)&&saved<items.length?saved:firstTodo();
  render();
}
function firstTodo(){const i=items.findIndex(x=>!x.done&&!x.skipped);return i<0?0:i}
function render(){
  const it=items[idx]; if(!it) return;
  const done=items.filter(x=>x.done).length;
  $('progress').textContent=`Записано ${done} из ${items.length} · фраза ${idx+1}`;
  $('bar').style.width=(100*done/items.length)+'%';
  $('pid').textContent=it.id;
  $('flag').textContent=it.done?'✓ записана':it.skipped?'пропущена':'';
  $('phrase').textContent=it.text; $('phrase').hidden=false; $('editor').hidden=true;
  $('playBtn').disabled=!it.done; lastUrl=it.done?`/api/audio?lang=${lang}&id=${it.id}&t=${Date.now()}`:null;
  try{localStorage.setItem('omnix-idx-'+lang,idx)}catch(e){}
}
function status(t,c=''){$('status').textContent=t;$('status').className='status '+c}
async function ensureMic(){
  if(ctx) return;
  stream=await navigator.mediaDevices.getUserMedia({audio:{channelCount:1,echoCancellation:false,noiseSuppression:false,autoGainControl:false}});
  ctx=new AudioContext(); rate=ctx.sampleRate;
  const src=ctx.createMediaStreamSource(stream);
  node=ctx.createScriptProcessor(4096,1,1);
  node.onaudioprocess=e=>{const d=e.inputBuffer.getChannelData(0);let peak=0;for(const v of d)peak=Math.max(peak,Math.abs(v));
    $('meter').style.width=Math.min(100,peak*100)+'%'; $('meter').style.background=peak>.97?'var(--rec)':'var(--lime)';
    if(recording) chunks.push(new Float32Array(d));};
  src.connect(node); node.connect(ctx.destination);
}
async function toggleRec(){
  if(!recording){
    try{await ensureMic()}catch(e){status('Нет доступа к микрофону: разрешите его в браузере и обновите страницу.','warn');return}
    if(ctx.state==='suspended') await ctx.resume();
    chunks=[]; recording=true; $('recBtn').textContent='■ Стоп'; $('recBtn').classList.add('recording'); status('Идёт запись… читайте фразу','rec');
  } else {
    recording=false; $('recBtn').textContent='● Запись'; $('recBtn').classList.remove('recording');
    await finish();
  }
}
async function finish(){
  let n=0; for(const c of chunks) n+=c.length; const all=new Float32Array(n); let o=0; for(const c of chunks){all.set(c,o);o+=c.length}
  let peak=0; for(const v of all) peak=Math.max(peak,Math.abs(v));
  const trimmed=trim(all,rate);
  if(trimmed.length<rate*0.4){status('Слишком коротко или тихо. Запишите ещё раз.','warn');return}
  const wav=toWav(trimmed,rate);
  const it=items[idx];
  const b64=await new Promise(r=>{const fr=new FileReader();fr.onload=()=>r(fr.result.split(',')[1]);fr.readAsDataURL(wav)});
  const res=await fetch('/api/save',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({lang,id:it.id,text:it.text,wav:b64})});
  if(!res.ok){status('Не удалось сохранить. Проверьте, что программа записи запущена.','warn');return}
  it.done=true; it.skipped=false; render();
  const secs=(trimmed.length/rate).toFixed(1);
  if(peak>.97) status(`Сохранено (${secs} с), но звук перегружен — отодвиньтесь или уменьшите громкость микрофона и перезапишите.`,'warn');
  else if(peak<.12) status(`Сохранено (${secs} с), но очень тихо — говорите громче или ближе к микрофону.`,'warn');
  else status(`Сохранено, ${secs} с. Пробел — перезаписать, → — дальше.`,'ok');
}
function trim(x,sr){
  const win=Math.round(sr*0.02); let floor=Infinity, energies=[];
  for(let i=0;i+win<=x.length;i+=win){let s=0;for(let j=i;j<i+win;j++)s+=x[j]*x[j];const e=Math.sqrt(s/win);energies.push(e);floor=Math.min(floor,e)}
  const thr=Math.max(floor*4,0.01); let a=energies.findIndex(e=>e>thr); let b=energies.length-1-[...energies].reverse().findIndex(e=>e>thr);
  if(a<0) return new Float32Array(0);
  const pad=Math.round(sr*0.15); return x.slice(Math.max(0,a*win-pad),Math.min(x.length,(b+1)*win+pad));
}
function toWav(x,sr){
  const buf=new ArrayBuffer(44+x.length*2), v=new DataView(buf);
  const w=(o,s)=>{for(let i=0;i<s.length;i++)v.setUint8(o+i,s.charCodeAt(i))};
  w(0,'RIFF');v.setUint32(4,36+x.length*2,true);w(8,'WAVE');w(12,'fmt ');v.setUint32(16,16,true);v.setUint16(20,1,true);v.setUint16(22,1,true);
  v.setUint32(24,sr,true);v.setUint32(28,sr*2,true);v.setUint16(32,2,true);v.setUint16(34,16,true);w(36,'data');v.setUint32(40,x.length*2,true);
  for(let i=0;i<x.length;i++){const s=Math.max(-1,Math.min(1,x[i]));v.setInt16(44+i*2,s<0?s*32768:s*32767,true)}
  return new Blob([buf],{type:'audio/wav'});
}
function play(){if(lastUrl) new Audio(lastUrl).play()}
function go(d){if(recording)return; idx=Math.max(0,Math.min(items.length-1,idx+d)); render(); status('Пробел — запись.')}
async function toggleEdit(){
  const it=items[idx];
  if($('editor').hidden){$('editor').value=it.text;$('editor').hidden=false;$('phrase').hidden=true;$('editor').focus();$('editBtn').textContent='✓ Сохранить текст';return}
  it.text=$('editor').value.trim()||it.text; $('editBtn').textContent='✎ Исправить текст';
  await fetch('/api/text',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({lang,id:it.id,text:it.text})});
  render(); status(it.done?'Текст исправлен. Если читали по-другому — перезапишите.':'Текст исправлен. Пробел — запись.','ok');
}
async function skip(){const it=items[idx];await fetch('/api/skip',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({lang,id:it.id})});it.skipped=true;go(1)}
$('recBtn').onclick=toggleRec;$('playBtn').onclick=play;$('prevBtn').onclick=()=>go(-1);$('nextBtn').onclick=()=>go(1);
$('editBtn').onclick=toggleEdit;$('skipBtn').onclick=skip;$('todoBtn').onclick=()=>{idx=firstTodo();render()};
$('lang').onchange=()=>{lang=$('lang').value;try{localStorage.setItem('omnix-lang',lang)}catch(e){}load()};
document.addEventListener('keydown',e=>{
  if(!$('editor').hidden){if(e.key==='Enter'&&(e.ctrlKey||e.metaKey))toggleEdit();return}
  if(e.target.tagName==='SELECT')return;
  if(e.code==='Space'){e.preventDefault();toggleRec()}
  else if(e.key==='ArrowRight')go(1); else if(e.key==='ArrowLeft')go(-1);
  else if(e.key==='p'||e.key==='P'||e.key==='з'||e.key==='З')play();
  else if(e.key==='e'||e.key==='E'||e.key==='у'||e.key==='У'){e.preventDefault();toggleEdit()}
});
load();
</script></body></html>"""


if __name__ == "__main__":
    REC.mkdir(exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    url = f"http://localhost:{PORT}"
    print(f"Запись голоса OMNIX: {url}  (остановить — Ctrl+C)")
    print(f"Записи сохраняются в {REC}")
    try:
        webbrowser.open(url)
    except Exception:
        pass
    server.serve_forever()
