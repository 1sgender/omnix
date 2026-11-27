#!/usr/bin/env bash
set -euo pipefail

LOG_DIR=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LOG_FILE="$LOG_DIR/instrumentation-gradle.log"
mkdir -p "$LOG_DIR"

# Сбои загрузки плагинов/зависимостей (инфраструктура сети) не должны валить
# сборку main: до CI_RETRY_ATTEMPTS попыток (по умолчанию 6) с паузами 30, 60, 90… с,
# только если в логе именно такая ошибка.
INFRA_RE='UnknownPluginException|Could not resolve|could not resolve plugin artifact|was not found in any of the following sources|Could not GET|Could not HEAD|Could not download|Read timed out|Connect timed out|Connection reset|Remote host terminated|TLS handshake|Temporary failure in name resolution'
ATTEMPTS=${CI_RETRY_ATTEMPTS:-6}
attempt=1
while true; do
  set +e
  bash ./gradlew :app:createDevDebugCoverageReport \
    --stacktrace --no-daemon --no-parallel --max-workers=2 \
    2>&1 | tee "$LOG_FILE"
  status=${PIPESTATUS[0]}
  set -e
  if [[ $status -eq 0 || $attempt -ge $ATTEMPTS ]] || ! grep -qE "$INFRA_RE" "$LOG_FILE"; then
    break
  fi
  pause=$((attempt * 30))
  echo "::warning::Сбой загрузки зависимостей (сеть раннера), повтор $((attempt + 1)) из $ATTEMPTS через ${pause} с"
  attempt=$((attempt + 1))
  sleep "$pause"
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
