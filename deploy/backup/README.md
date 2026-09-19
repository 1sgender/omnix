# OMNIX: бэкап Postgres (VPS)

Ежедневный логический бэкап БД `omnix` из контейнера
`omnix-shared-postgres-1` + еженедельный автоматический restore-тест.
Реализация — `omnix-db-backup.sh` (одна точка правды, ставится на VPS как есть).

## Что делает

| Параметр | Значение |
|---|---|
| Метод | `pg_dump -Fc` (custom, сжатый) через `docker exec` |
| Расписание | ежедневно **03:17 UTC** (cron root) |
| Каталог | `/var/backups/omnix-db/` (0700, root) |
| Ретраи | 3 попытки с паузой 60 c |
| Валидация при каждом прогоне | `sha256sum` + `pg_restore --list` (архив читается) |
| `.counts` | счётчики ключевых таблиц, снятые сразу после дампа |
| Retention | 14 дней (`find -mtime +14 -delete`, включая off-box-зеркалирование) |
| Restore-тест | **воскресенье 04:33 UTC** (`--verify`): разворот последнего дампа в throwaway-контейнер того же образа + сверка счётчиков с `.counts` |
| Off-box | `RSYNC_DEST` в `/etc/omnix/backup.conf` (rsync по ssh) |

Файлы одного бэкапа: `omnix-<ts>.pgdump` + `.sha256` + `.counts`, плюс
маркер `latest` (путь к самому свежему дампу).

## Установка (VPS)

```bash
cd /opt/omnix && git pull
install -m 700 deploy/backup/omnix-db-backup.sh /usr/local/bin/omnix-db-backup.sh
mkdir -p /etc/omnix && install -m 600 /dev/null /etc/omnix/backup.conf
# по желанию — off-box (см. ниже):
#   echo 'RSYNC_DEST="backups@storage.example:/srv/omnix-db"' >> /etc/omnix/backup.conf
cp deploy/backup/logrotate-omnix-backup /etc/logrotate.d/omnix-backup
crontab -l 2>/dev/null | { cat; echo '17 3 * * * /usr/local/bin/omnix-db-backup.sh'; echo '33 4 * * 0 /usr/local/bin/omnix-db-backup.sh --verify'; } | crontab -

# приёмочный прогон:
omnix-db-backup.sh && omnix-db-backup.sh --verify
tail /var/log/omnix-db-backup.log
```

Все параметры (контейнер, БД, каталог, retention, попытки) переопределяются
в `/etc/omnix/backup.conf` переменными `OMNIX_BACKUP_*` — см. шапку скрипта.

## Off-box копия

1. На VPS: `apt-get install -y rsync`, `ssh-keygen -t ed25519 -f /root/.ssh/omnix_backup -N ''`.
2. Публичный ключ — в `~backups/.ssh/authorized_keys` на хранилище
   (рекомендуется ограничить `command="rsync --server -az . /srv/omnix-db"`).
3. В `/etc/omnix/backup.conf`: `RSYNC_DEST="backups@storage.example:/srv/omnix-db"`.
4. Пронировать ssh-хост: `ssh -i /root/.ssh/omnix_backup backups@… true`,
   затем добавить `RSYNC_SSH="ssh -i /root/.ssh/omnix_backup"` — и скрипт
   передаст его как `rsync -e "$RSYNC_SSH"`.

Скрипт выгружает только файлы текущего дампа; зеркало каталога
(`rsync -az --delete /var/backups/omnix-db/ dest:`) держит retention и там.

## Восстановление (рунбук)

```bash
# 1. Выбрать дамп и проверить целостность
cd /var/backups/omnix-db && cat latest
sha256sum -c omnix-<ts>.sha256

# 2. Остановить сервер (чтобы никто не писал во время restore)
cd /opt/omnix/deploy && docker compose -f docker-compose.vps-shared.yml --env-file .env.production stop omnix-server

# 3. Развернуть (текущий кластер, роль omnix существует)
docker exec -i omnix-shared-postgres-1 pg_restore -U omnix --clean --if-exists -d omnix < /var/backups/omnix-db/omnix-<ts>.pgdump

# 4. Поднять сервер и проверить
docker compose -f docker-compose.vps-shared.yml --env-file .env.production up -d
docker logs -f omnix-shared-omnix-server-1   # старт без ошибок
# браузер: https://omnix.144.31.14.236.sslip.io/admin — логин и данные на месте
```

## Мониторинг

- Лог: `/var/log/omnix-db-backup.log` (каждый прогон — одна строка OK/FAIL/WARN).
- `grep -c FAIL /var/log/omnix-db-backup.log` — быстрый аудит здоровья.
- Exit codes: `0` ок · `1` дамп/verify не удался · `2` локально ок, off-box
  не дошёл · `64` неправильный вызов. Cron без MTA — статус виден в логе.
