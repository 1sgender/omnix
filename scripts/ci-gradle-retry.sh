#!/usr/bin/env bash
# Запускает переданную команду повторно, но ТОЛЬКО при сбое загрузки плагинов/зависимостей
# (сеть раннера GitHub). Настоящие ошибки компиляции и тестов не повторяются и падают сразу.
#
# Сеть раннера пропадает на несколько минут, поэтому попыток до CI_RETRY_ATTEMPTS (по умолчанию 6)
# с растущими паузами 30, 60, 90, 120, 150 с — около 7,5 мин ожидания в худшем случае вместо
# ручного перезапуска всего прогона.
# Использование: bash scripts/ci-gradle-retry.sh <команда> [аргументы...]
set -uo pipefail

LOG_DIR=${RUNNER_TEMP:-${TMPDIR:-/tmp}}
LOG_FILE="$LOG_DIR/ci-gradle-retry-$$.log"
mkdir -p "$LOG_DIR"

ATTEMPTS=${CI_RETRY_ATTEMPTS:-6}
INFRA_RE='UnknownPluginException|Could not resolve|could not resolve plugin artifact|was not found in any of the following sources|Could not GET|Could not HEAD|Could not download|Read timed out|Connect timed out|Connection reset|Remote host terminated|TLS handshake|Temporary failure in name resolution'

attempt=1
while true; do
  "$@" 2>&1 | tee "$LOG_FILE"
  status=${PIPESTATUS[0]}
  if [[ $status -eq 0 || $attempt -ge $ATTEMPTS ]] || ! grep -qE "$INFRA_RE" "$LOG_FILE"; then
    exit "$status"
  fi
  pause=$((attempt * ${CI_RETRY_PAUSE:-30}))
  echo "::warning::Сбой загрузки зависимостей (сеть раннера), повтор $((attempt + 1)) из $ATTEMPTS через ${pause} с"
  attempt=$((attempt + 1))
  sleep "$pause"
done
