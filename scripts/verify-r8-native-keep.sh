#!/usr/bin/env bash
# verify-r8-native-keep.sh — guard for Java classes that native code finds by
# name (JNI FindClass/GetMethodID). R8 must neither rename nor drop them:
# otherwise the release build dies in native code while debug builds and the
# emulator tests (unminified) pass.
#
# Field report 2026-10-21 (v300, SM-S928B, Android 16):
#   "JNI DETECTED ERROR IN APPLICATION: java_class == null
#    from boolean[] ai.onnxruntime.OrtSession.run(...)"   — wake word engine
#   "JNI DETECTED ERROR IN APPLICATION: mid == null"
#    in libllm_inference_engine_jni.so                     — local LLM (v292)
#
# Run after a minified build:
#   bash scripts/verify-r8-native-keep.sh [path/to/mapping.txt]
# Without an argument it uses the stagingRelease mapping.
set -euo pipefail
cd "$(dirname "$0")/.."

MAPPING="${1:-app/build/outputs/mapping/stagingRelease/mapping.txt}"
[ -f "$MAPPING" ] || { echo "FAIL: R8 mapping not found: $MAPPING"; exit 1; }

# Packages whose classes are reached from native code by name.
WATCHED='^(ai[.]onnxruntime[.]|com[.]google[.]mediapipe[.]|com[.]k2fsa[.]sherpa[.]onnx[.])'

# Classes the native code of the shipped features needs; each must be present
# under its own name.
REQUIRED="
ai.onnxruntime.OnnxTensor
ai.onnxruntime.OnnxValue
ai.onnxruntime.OrtException
ai.onnxruntime.OrtSession
ai.onnxruntime.OrtSession\$Result
ai.onnxruntime.OrtEnvironment
com.google.mediapipe.tasks.genai.llminference.LlmInference
com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
com.k2fsa.sherpa.onnx.KeywordSpotter
com.k2fsa.sherpa.onnx.KeywordSpotterConfig
com.k2fsa.sherpa.onnx.KeywordSpotterResult
com.k2fsa.sherpa.onnx.OnlineModelConfig
com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
com.k2fsa.sherpa.onnx.FeatureConfig
com.k2fsa.sherpa.onnx.OnlineStream
"

fail=0

for cls in $REQUIRED; do
  if grep -qxF "$cls -> $cls:" "$MAPPING"; then
    echo "ok: $cls kept"
  else
    echo "FAIL: $cls is renamed or removed by R8"
    grep -F "$cls -> " "$MAPPING" | head -1 || true
    fail=1
  fi
done

# Every class of the watched packages keeps its name, and every member it
# declares keeps its name. Not checked, because native code cannot look them
# up on this class: methods R8 synthesized (marked r8.synthesized) and methods
# of other classes that R8 moved or inlined here (their original name is
# qualified with the other class). Inlined frames of the same class map an
# original method to the name of the kept method it was inlined into, so a
# member's new name must be one of the original member names of the class; a
# rename gives a new short name.
report=$(awk -v watched="$WATCHED" '
  function commit() {
    if (pending_line == "") return
    orignames[pending_orig] = 1
    if (!(pending_new in cand)) cand[pending_new] = pending_line
    pending_line = ""
  }
  function flush() {
    commit()
    if (cls != "")
      for (n in cand) if (!(n in orignames)) print "member renamed in " cls ":" cand[n]
    delete cand; delete orignames
  }
  /^#/ { next }
  /^ +#/ {
    if ($0 ~ /r8[.]synthesized/) pending_line = ""
    next
  }
  /^[^ ]/ {
    flush()
    cls = ""
    split($0, parts, " -> ")
    from = parts[1]; to = parts[2]; sub(/:$/, "", to)
    if (from !~ watched) next
    # R8-synthesized helper classes (lambdas, outlines) are not looked up by name.
    if (from ~ /\$\$ExternalSynthetic|\$r8\$|\$-CC$/) next
    if (from != to) { print "class renamed: " from " -> " to; next }
    cls = from
    next
  }
  /^    / {
    if (cls == "") next
    commit()
    line = $0
    p = index(line, " -> ")
    if (p == 0) next
    left = substr(line, 1, p - 1)
    newname = substr(line, p + 4)
    sub(/^ +/, "", left)
    n = split(left, tok, " ")
    orig = tok[n]
    sub(/\(.*/, "", orig)
    sub(/:.*/, "", orig)
    # Moved or inlined from another class: not a member of this class.
    if (index(orig, ".") > 0) next
    pending_orig = orig
    pending_new = newname
    pending_line = line
  }
  END { flush() }
' "$MAPPING")

if [ -n "$report" ]; then
  echo "FAIL: R8 changed names that native code looks up:"
  echo "$report" | head -40
  fail=1
else
  echo "ok: ai.onnxruntime and com.google.mediapipe keep their class and member names"
fi

exit "$fail"
