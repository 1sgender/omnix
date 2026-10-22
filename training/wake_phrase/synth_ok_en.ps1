param([string]$OutDir)
Add-Type -AssemblyName System.Speech
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
$dir = Join-Path $OutDir 'okomni_en'
New-Item -ItemType Directory -Force $dir | Out-Null
$i = 0
foreach ($t in @('Okay Omni', 'Okay Omni, what is the weather today?', 'Okay Omni, call mom', 'OK Omni, set an alarm')) {
  foreach ($rate in @(-2, 0, 2)) {
    $i++
    $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
    $s.SelectVoice('Microsoft Zira Desktop'); $s.Rate = $rate
    $s.SetOutputToWaveFile((Join-Path $dir "en-$i.wav"), $fmt); $s.Speak($t); $s.Dispose()
    Add-Content -Encoding utf8 (Join-Path $dir 'index.tsv') "en-$i.wav`t$rate`t$t"
  }
}
"done $i"
