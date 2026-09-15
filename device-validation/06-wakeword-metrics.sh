#!/usr/bin/env bash
# 06-wakeword-metrics.sh — протокол замеров neural wake word на устройстве.
# FAR/FRR, задержки инференса, батарея в standby. См. docs/WAKEWORD_NEURAL.md.
#
# Использование:
#   bash device-validation/06-wakeword-metrics.sh [длительность_фона_сек]
# Предусловия: adb, собранный APK с движком, Clip/phone рядом,
# включённый wakeword.debug_logging (adb shell setprop / настройки).
set -u
DUR="${1:-300}"
OUT="device-validation/results-wakeword-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"

adb shell getprop ro.build.model > "$OUT/device.txt" 2>&1 || { echo "FAIL: нет adb-устройства"; exit 1; }
echo "device: $(cat "$OUT/device.txt")"
echo "out: $OUT"

echo "== 1. батарея baseline (standby БЕЗ движка): выключите wakeword.enabled, подождите 10 мин, введите уровень =="
adb shell dumpsys battery | grep -E "level|temperature" | tee "$OUT/battery-baseline.txt"

echo "== 2. включите wakeword.enabled + debug_logging, скажите 'Hey Jarvis' 10 раз с паузами 5с =="
echo "нажмите Enter когда готовы (пишем logcat 120с)"; read -r
adb logcat -c
adb logcat -v threadtime "*:S" NeuralWakeWord:V VoiceOrchestrator:V > "$OUT/logcat-wake.log" 2>&1 &
LOGCAT_PID=$!
sleep 120
kill $LOGCAT_PID 2>/dev/null
echo "детекций: $(grep -c "wakeword '" "$OUT/logcat-wake.log" || true)"
grep "wakeword '" "$OUT/logcat-wake.log" | tail -15
grep -o "infer=[0-9]*ms" "$OUT/logcat-wake.log" | sort -t= -k2 -n | tail -5 > "$OUT/infer-top5.txt" || true
echo "топ-5 инференсов:"; cat "$OUT/infer-top5.txt" 2>/dev/null || echo "(нет данных)"

echo "== 3. фон ${DUR}с: ТВ/разговоры БЕЗ фразы (считаем ложные) =="
echo "нажмите Enter когда готовы"; read -r
adb logcat -c
adb logcat -v threadtime "*:S" NeuralWakeWord:V VoiceOrchestrator:V > "$OUT/logcat-bg.log" 2>&1 &
LOGCAT_PID=$!
sleep "$DUR"
kill $LOGCAT_PID 2>/dev/null
FA=$(grep -c "wakeword '" "$OUT/logcat-bg.log" || true)
echo "ложных срабатываний за ${DUR}с: $FA"

echo "== 4. батарея после =="
adb shell dumpsys battery | grep -E "level|temperature" | tee "$OUT/battery-after.txt"

cat > "$OUT/SUMMARY.md" <<EOF
# Wake word metrics $(date +%Y-%m-%d)
- device: $(cat "$OUT/device.txt")
- детекции/10 попыток: см. logcat-wake.log
- топ-5 инференсов: $(tr '\n' ' ' < "$OUT/infer-top5.txt" 2>/dev/null)
- FA за ${DUR}с фона: $FA
- батарея: baseline vs after — см. battery-*.txt
Критерии: инференс чанка <80 мс; FA <1/час; деградация батареи <2%/ч vs baseline.
EOF
echo "готово: $OUT/SUMMARY.md"
