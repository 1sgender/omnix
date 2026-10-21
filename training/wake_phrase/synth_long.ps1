param([string]$OutDir)
Add-Type -AssemblyName System.Speech
$fmt = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo(16000, [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)

$ru = @'
Вчера вечером он мне позвонил и сказал, что задержится на работе. Я приготовила омлет и села смотреть новости.
В новостях рассказывали про амнистию, про армию и про погоду на выходные. Помнишь, как мы в прошлом году ездили в Омск?
Там было много снега, и мы долго искали гостиницу. Сомнений не было, что поездка удастся. Обними меня, сказала она тихо.
Огни большого города отражались в окне. Мне нужно купить хлеба, молока и немного овощей. Он ни при чём, это всё она.
Давай встретимся в шесть возле метро. Вспомни, что ты обещал на прошлой неделе. Номер телефона я запишу в блокнот.
Мама просила позвонить ей после обеда. Сегодня отличная погода, можно погулять в парке. Они пошли домой пешком.
Омнибус проехал мимо остановки, не останавливаясь. Комната была пустой и холодной. Ну и что теперь делать?
Мнение эксперта оказалось неожиданным. Я не знаю, как ответить на этот вопрос. Включи музыку погромче, пожалуйста.
Он многое помнил о детстве, но не всё. Амнезия бывает разной. Мы смотрели фильм про космос и звёзды до полуночи.
Утром на кухне пахло кофе. Соседи опять шумели за стеной. Ребёнок нарисовал дом, солнце и огромную собаку.
'@

$en = @'
Yesterday evening he called me and said he would be late. I made an omelette and watched the news on television.
They talked about amnesty, about the army, and about the weather for the weekend. Do you remember our trip to Omaha?
There was a lot of snow, and we searched for a hotel for a long time. Only you know the answer. The omnibus passed the stop.
Common sense tells us to wait. On me, the drinks are on me tonight. Almighty winds were blowing over the hills.
The enemy is at the gates, and the commander is calm. Remember me when you are far away. Hold me close and do not let go.
'@

function Say($voice, $text, $rate, $path) {
  $s = New-Object System.Speech.Synthesis.SpeechSynthesizer
  $s.SelectVoice($voice)
  $s.Rate = $rate
  $s.SetOutputToWaveFile($path, $fmt)
  $s.Speak($text)
  $s.Dispose()
}

New-Item -ItemType Directory -Force (Join-Path $OutDir 'long') | Out-Null
foreach ($rate in @(-1, 1)) {
  Say 'Microsoft Irina Desktop' $ru $rate (Join-Path $OutDir "long/ru_$rate.wav")
  Say 'Microsoft Zira Desktop' $en $rate (Join-Path $OutDir "long/en_$rate.wav")
}
'done'
