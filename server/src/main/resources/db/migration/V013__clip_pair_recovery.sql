-- V013: перенос пары OMNIX Clip между телефонами и «пробный раз на телефон».
--
-- Восстановление: ACTIVE-пара, подключённая к другому телефону (очистка
-- данных, новый телефон), входит в тот же аккаунт-владелец и переезжает на
-- этот телефон. Чтобы пару не перебрасывали между телефонами, переносы
-- ограничены за сутки (CLIP_MAX_RECOVERIES_PER_DAY).
ALTER TABLE clip_devices ADD COLUMN recovery_window_start TIMESTAMPTZ;
ALTER TABLE clip_devices ADD COLUMN recovery_count INTEGER NOT NULL DEFAULT 0;

-- Телефоны, уже получившие пробный Pro: ключевой хеш (HMAC сервера) от
-- солёного SHA-256 ANDROID_ID. Сам ANDROID_ID на сервер не приходит.
CREATE TABLE clip_trial_phones (
    phone_hash BYTEA PRIMARY KEY,
    account_id UUID REFERENCES accounts(id) ON DELETE SET NULL,
    clip_device_id UUID REFERENCES clip_devices(id) ON DELETE SET NULL,
    granted_at TIMESTAMPTZ NOT NULL
);
