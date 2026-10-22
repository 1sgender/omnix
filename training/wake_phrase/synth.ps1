param([string]$OutDir)
Add-Type -AssemblyName System.Speech
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

$positive = @{
  'ru' = @('Омни', 'Омни, какая сегодня погода?', 'Омни, поставь будильник на семь', 'Эй, Омни', 'Омни, позвони маме', 'Омни, сколько времени?')
  'en' = @('Omni', 'Omni, what is the weather today?', 'Hey Omni', 'Omni, call mom', 'Omni, set an alarm')
}
$negative = @{
  'ru' = @('Он мне сказал, что придёт позже', 'Омлет с сыром на завтрак', 'Армия и флот', 'Мне нужно купить хлеба', 'Амнистия была объявлена вчера',
           'Вспомни, что было вчера', 'Помню всё', 'Они пошли домой', 'Сегодня отличная погода', 'Давай встретимся в шесть',
           'Он ни при чём', 'Комната была пустой', 'Омск большой город', 'Мнение эксперта важно', 'Позвони мне завтра утром',
           'Ну и что теперь делать', 'Я не знаю', 'Включи музыку погромче', 'Обними меня', 'Огни большого города')
  'en' = @('On me', 'Only you', 'Amnesty international', 'Omelette for breakfast', 'Hello world', 'I am going home',
           'Remember me', 'Common knowledge', 'Army and navy', 'Enemy at the gates', 'Almighty', 'Omnibus')
}

function Say($voice, $text, $rate, $path) {
  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  $s.SelectVoice($voice)
  $s.Rate = $rate
  $s.SetOutputToWaveFile($path, $fmt)
  $s.Speak($text)
  $s.Dispose()
}

New-Item -ItemType Directory -Force (Join-Path $OutDir 'pos') | Out-Null
New-Item -ItemType Directory -Force (Join-Path $OutDir 'neg') | Out-Null
$voices = @{ 'ru' = 'Microsoft Irina Desktop'; 'en' = 'Microsoft Zira Desktop' }
$i = 0
foreach ($kind in @('pos', 'neg')) {
  $set = if ($kind -eq 'pos') { $positive } else { $negative }
  foreach ($lang in $set.Keys) {
    foreach ($t in $set[$lang]) {
      foreach ($rate in @(-2, 0, 2)) {
        $i++
        Say $voices[$lang] $t $rate (Join-Path $OutDir "$kind/$lang-$i.wav")
        Add-Content -Encoding utf8 (Join-Path $OutDir "$kind/index.tsv") "$lang-$i.wav`t$rate`t$t"
      }
    }
  }
}
"done $i"
