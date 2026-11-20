#!/usr/bin/env bash
# Запускает переданную команду до 3 раз, но повторяет ТОЛЬКО при сбое загрузки
# плагинов/зависимостей (сеть раннера). Настоящие ошибки компиляции и тестов
# не повторяются и падают сразу.
# Использование: bash scripts/ci-gradle-retry.sh <команда> [аргументы...]
set -uo pipefail

LOG_DIR=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LOG_FILE="$LOG_DIR/ci-gradle-retry.log"
mkdir -p "$LOG_DIR"

INFRA_RE='UnknownPluginException|Could not resolve|could not resolve plugin artifact|was not found in any of the following sources|Could not GET|Could not HEAD|Read timed out|Connection reset|Remote host terminated|TLS handshake'

attempt=1
while true; do
  "$@" 2>&1 | tee "$LOG_FILE"
  status=${PIPESTATUS[0]}
  if [[ $status -eq 0 || $attempt -ge 3 ]] || ! grep -qE "$INFRA_RE" "$LOG_FILE"; then
    exit "$status"
  fi
  echo "::warning::Сбой загрузки зависимостей, повтор $((attempt + 1)) из 3"
  attempt=$((attempt + 1))
  sleep 20
done
