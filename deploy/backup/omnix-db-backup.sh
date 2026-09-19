#!/usr/bin/env bash
# OMNIX: бэкап Postgres (pg_dump -Fc) с ретраями, sha256, валидацией
# архива и еженедельным restore-тестом. Установка и рунбук восстановления —
# deploy/backup/README.md.
#
# cron (root), время UTC:
#   17 3 * * *  /usr/local/bin/omnix-db-backup.sh           # ежедневно
#   33 4 * * 0  /usr/local/bin/omnix-db-backup.sh --verify  # воскресенье, restore-тест
#
# Exit codes: 0 — ок; 1 — дамп/verify не удался; 2 — локальная копия цела,
# но off-box не дошёл; 64 — неправильный вызов.
set -uo pipefail
umask 077

# ── конфиг; всё переопределяется в /etc/omnix/backup.conf ─────────────────
CONTAINER="${OMNIX_BACKUP_CONTAINER:-omnix-shared-postgres-1}"
DB_USER="${OMNIX_BACKUP_DB_USER:-omnix}"
DB_NAME="${OMNIX_BACKUP_DB_NAME:-omnix}"
BACKUP_DIR="${OMNIX_BACKUP_DIR:-/var/backups/omnix-db}"
LOG_FILE="${OMNIX_BACKUP_LOG:-/var/log/omnix-db-backup.log}"
KEEP_DAYS="${OMNIX_BACKUP_KEEP_DAYS:-14}"
ATTEMPTS="${OMNIX_BACKUP_ATTEMPTS:-3}"
RETRY_DELAY_SEC="${OMNIX_BACKUP_RETRY_DELAY:-60}"
# off-box: user@host:/path — rsync по ssh; пусто = только локальная копия
RSYNC_DEST="${OMNIX_BACKUP_RSYNC_DEST:-}"
# например: RSYNC_SSH="ssh -i /root/.ssh/omnix_backup"
RSYNC_SSH="${OMNIX_BACKUP_RSYNC_SSH:-}"
TEST_IMAGE="${OMNIX_BACKUP_TEST_IMAGE:-postgres:17.10-bookworm}"

[ -r /etc/omnix/backup.conf ] && . /etc/omnix/backup.conf

# Счётчики ключевых таблиц: пишутся рядом с дампом (.counts) и сверяются
# еженедельным restore-тестом. Ровно тот же запрос — для дампа и для restore.
COUNTS_SQL="SELECT 'licenses='||(SELECT count(*) FROM licenses)
       ||' admin_accounts='||(SELECT count(*) FROM admin_accounts)
       ||' accounts='||(SELECT count(*) FROM accounts)
       ||' clip_devices='||(SELECT count(*) FROM clip_devices)
       ||' billing_orders='||(SELECT count(*) FROM billing_orders)"

mkdir -p "$(dirname "$LOG_FILE")" "$BACKUP_DIR"
chmod 700 "$BACKUP_DIR"
log() { printf '%s %s\n' "$(date -u +%FT%TZ)" "$*" >>"$LOG_FILE"; }
fail() { log "FAIL: $*"; exit "${2:-1}"; }

# Второй запуск (ручной поверх cron) уходит молча, а не падает.
exec 9>/run/omnix-db-backup.lock
flock -n 9 || { log "SKIP: уже выполняется (lock)"; exit 0; }

prune() {
  find "$BACKUP_DIR" -maxdepth 1 -name 'omnix-*' -mtime +"$KEEP_DAYS" -print -delete >>"$LOG_FILE" 2>&1 \
    || log "WARN: prune не сработал (проверьте вручную)"
}

# Аргумент — базовое имя дампа (omnix-<ts>). Возврат: 0 — ок/не настроено, 2 — не дошло.
sync_offbox() {
  local base="$1"
  local cmd=(rsync -az --chmod=700)
  [ -n "$RSYNC_DEST" ] || return 0
  if ! command -v rsync >/dev/null 2>&1; then
    log "WARN off-box: rsync не установлен — копия только локальная"
    return 2
  fi
  [ -n "$RSYNC_SSH" ] && cmd+=(-e "$RSYNC_SSH")
  cmd+=("$BACKUP_DIR/$base.pgdump" "$BACKUP_DIR/$base.sha256" "$BACKUP_DIR/$base.counts" "$RSYNC_DEST/")
  if "${cmd[@]}" >>"$LOG_FILE" 2>&1; then
    log "off-box: $base выгружен в $RSYNC_DEST"
    return 0
  fi
  log "WARN off-box: rsync не дошёл (локальная копия цела)"
  return 2
}

do_backup() {
  local ts file tmp attempt=1
  ts=$(date -u +%Y%m%dT%H%M%SZ)
  file="$BACKUP_DIR/omnix-$ts.pgdump"
  tmp="$file.part"
  while [ "$attempt" -le "$ATTEMPTS" ]; do
    if docker exec "$CONTAINER" pg_dump -U "$DB_USER" -Fc "$DB_NAME" >"$tmp" 2>>"$LOG_FILE" \
       && [ -s "$tmp" ]; then
      mv "$tmp" "$file"
      ( cd "$BACKUP_DIR" && sha256sum "omnix-$ts.pgdump" >"omnix-$ts.sha256" )
      if docker exec -i "$CONTAINER" pg_restore --list <"$file" >/dev/null 2>>"$LOG_FILE"; then
        local counts
        counts=$(docker exec "$CONTAINER" psql -U "$DB_USER" -d "$DB_NAME" -tAc "$COUNTS_SQL" 2>>"$LOG_FILE" | tr -d ' ')
        if [ -n "$counts" ]; then
          printf '%s\n' "$counts" >"$BACKUP_DIR/omnix-$ts.counts"
          printf '%s\n' "$file" >"$BACKUP_DIR/latest"
          log "OK: omnix-$ts.pgdump $(stat -c%s "$file") B; sha256+counts записаны"
          prune
          sync_offbox "omnix-$ts"
          exit $?
        fi
        log "попытка $attempt/$ATTEMPTS: counts пуст — считаем дамп не зачтённым"
      else
        log "попытка $attempt/$ATTEMPTS: архив не читается pg_restore --list"
      fi
      rm -f "$file" "$BACKUP_DIR/omnix-$ts.sha256"
    else
      log "попытка $attempt/$ATTEMPTS: pg_dump упал или пуст"
      rm -f "$tmp"
    fi
    attempt=$((attempt + 1))
    [ "$attempt" -le "$ATTEMPTS" ] && sleep "$RETRY_DELAY_SEC"
  done
  fail "pg_dump не удался за $ATTEMPTS попыток" 1
}

do_verify() {
  local file base counts_restored counts_expected cname i
  file=$(cat "$BACKUP_DIR/latest" 2>/dev/null) || fail "verify: нет latest-маркера" 1
  [ -f "$file" ] || fail "verify: latest указывает на отсутствующий $file" 1
  base=$(basename "$file" .pgdump)

  ( cd "$BACKUP_DIR" && sha256sum -c "$base.sha256" ) >>"$LOG_FILE" 2>&1 \
    || fail "verify: sha256 не сошёлся для $base" 1

  # Throwaway-postgres: тот же образ, что и продовый контейнер.
  cname=omnix-backup-verify
  docker rm -f "$cname" >/dev/null 2>&1
  docker run --rm -d --name "$cname" -e POSTGRES_PASSWORD=postgres "$TEST_IMAGE" >/dev/null 2>&1 \
    || fail "verify: не поднялся тестовый postgres ($TEST_IMAGE)" 1
  i=0
  until docker exec "$cname" pg_isready -U postgres >/dev/null 2>&1; do
    i=$((i + 1))
    if [ "$i" -gt 30 ]; then
      docker stop "$cname" >/dev/null 2>&1
      fail "verify: тестовый postgres не готов за 30 с" 1
    fi
    sleep 1
  done
  docker exec "$cname" createdb -U postgres -T template0 "$DB_NAME" >>"$LOG_FILE" 2>&1 \
    || { docker stop "$cname" >/dev/null 2>&1; fail "verify: createdb" 1; }
  docker exec -i "$cname" pg_restore -U postgres --no-owner --no-privileges -d "$DB_NAME" <"$file" >>"$LOG_FILE" 2>&1 \
    || { docker stop "$cname" >/dev/null 2>&1; fail "verify: pg_restore упал" 1; }
  counts_restored=$(docker exec "$cname" psql -U postgres -d "$DB_NAME" -tAc "$COUNTS_SQL" 2>>"$LOG_FILE" | tr -d ' ')
  docker stop "$cname" >/dev/null 2>&1
  counts_expected=$(tr -d ' ' <"$BACKUP_DIR/$base.counts" 2>/dev/null)
  [ -n "$counts_restored" ] && [ "$counts_restored" = "$counts_expected" ] \
    || fail "verify: счётчики расходятся (expected=[$counts_expected] restored=[$counts_restored])" 1
  log "VERIFY OK: $base — sha256 сходится, restore прошёл, счётчики совпадают: $counts_restored"
}

case "${1:-}" in
  --verify) do_verify ;;
  "")       do_backup ;;
  *)        echo "usage: $0 [--verify]" >&2; exit 64 ;;
esac
