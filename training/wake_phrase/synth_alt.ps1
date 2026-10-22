param([string]$OutDir)
Add-Type -AssemblyName System.Speech
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$sets = @{
  'omnix_ru' = @('Омникс', 'Омникс, какая сегодня погода?', 'Омникс, позвони маме', 'Эй, Омникс', 'Омникс, сколько времени?')
  'okomni_ru' = @('Окей, Омни', 'Окей, Омни, какая сегодня погода?', 'Окей, Омни, позвони маме', 'Окей Омни, сколько времени?')
  'omnix_en' = @('Omnix', 'Omnix, what is the weather today?', 'Hey Omnix', 'Omnix, call mom')
  'neg2_ru' = @('Помнишь, как мы гуляли?', 'Помни об этом', 'Расскажи обо мне', 'Окей, давай', 'Окей, понял', 'Много мыслей', 'Оникс это камень', 'Он миксует музыку', 'Комикс про героев', 'Он мне никак не ответил')
}
$voices = @{ 'ru' = 'Microsoft Irina Desktop'; 'en' = 'Microsoft Zira Desktop' }
$i = 0
foreach ($set in $sets.Keys) {
  $dir = Join-Path $OutDir $set
  New-Item -ItemType Directory -Force $dir | Out-Null
  $lang = $set.Split('_')[1]
  foreach ($t in $sets[$set]) {
    foreach ($rate in @(-2, 0, 2)) {
      $i++
      $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
      $s.SelectVoice($voices[$lang]); $s.Rate = $rate
      $s.SetOutputToWaveFile((Join-Path $dir "$lang-$i.wav"), $fmt); $s.Speak($t); $s.Dispose()
      Add-Content -Encoding utf8 (Join-Path $dir 'index.tsv') "$lang-$i.wav`t$rate`t$t"
    }
  }
}
"done $i"
