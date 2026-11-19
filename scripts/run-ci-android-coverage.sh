#!/usr/bin/env bash
set -euo pipefail

LOG_DIR=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LOG_FILE="$LOG_DIR/instrumentation-gradle.log"
mkdir -p "$LOG_DIR"

# Сбои загрузки плагинов/зависимостей (инфраструктура сети) не должны валить
# сборку main: до 3 попыток, только если в логе именно такая ошибка.
INFRA_RE='UnknownPluginException|Could not resolve|Could not GET|Could not HEAD|Read timed out|Connection reset|Remote host terminated'
attempt=1
while true; do
  set +e
  bash ./gradlew :app:createDevDebugCoverageReport \
    --stacktrace --no-daemon --no-parallel --max-workers=2 \
    2>&1 | tee "$LOG_FILE"
  status=${PIPESTATUS[0]}
  set -e
  if [[ $status -eq 0 || $attempt -ge 3 ]] || ! grep -qE "$INFRA_RE" "$LOG_FILE"; then
    break
  fi
  echo "::warning::Сбой загрузки зависимостей, повтор $((attempt + 1)) из 3"
  attempt=$((attempt + 1))
  sleep 20
done

if [[ $status -eq 0 ]]; then
  exit 0
fi

summary=$(grep -E \
  'FAILURE:|What went wrong|Execution failed|FAILED|failing tests|error:' \
  "$LOG_FILE" | tail -n 20 || true)
if [[ -z "$summary" ]]; then
  summary=$(tail -n 20 "$LOG_FILE")
fi
summary=${summary//'%'/'%25'}
summary=${summary//$'\r'/'%0D'}
summary=${summary//$'\n'/'%0A'}
printf '::error title=Android instrumentation failed::%s\n' "$summary"
exit "$status"
