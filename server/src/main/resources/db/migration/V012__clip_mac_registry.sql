-- V012: реестр готовых наушников OMNIX Clip по Bluetooth-адресу (режим MAC)
-- и каталог моделей/цветов, управляемый с сервера.
--
-- ВРЕМЕННАЯ схема (решение владельца 2026-11-13): заводская прошивка с ключом
-- (V008, identity_mode = 'KEY', challenge-response) пока невозможна, поэтому
-- пару опознаём по BT-адресу, который владелец регистрирует при упаковке.
-- MAC подделывается на root-телефоне — это осознанный компромисс. Когда
-- появится прошивка с ключом, записи переводятся в identity_mode = 'KEY'
-- (public_key заполняется), реестр и привязки к аккаунтам не меняются.

-- Каталог моделей: новая модель или цвет распознаются без обновления приложения.
CREATE TABLE clip_models (
    id VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(128) NOT NULL,
    color_name VARCHAR(64) NOT NULL,
    color_hex VARCHAR(7) NOT NULL CHECK (color_hex ~ '^#[0-9A-F]{6}$'),
    image_url VARCHAR(512),
    -- JSON-массив префиксов имени BT («OMNIX Clip», …): только подсказка
    -- «похоже на OMNIX», не доверие.
    bt_name_patterns JSONB NOT NULL DEFAULT '[]'::jsonb,
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE', 'HIDDEN')),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE clip_devices ALTER COLUMN public_key DROP NOT NULL;

-- Нормализованный адрес AA:BB:CC:DD:EE:FF (верхний регистр). NULL — запись
-- режима KEY без адреса.
ALTER TABLE clip_devices ADD COLUMN bt_address VARCHAR(17) UNIQUE;
ALTER TABLE clip_devices ADD COLUMN identity_mode VARCHAR(8) NOT NULL DEFAULT 'KEY';
ALTER TABLE clip_devices ADD COLUMN plan_id VARCHAR(64);
ALTER TABLE clip_devices ADD COLUMN model_id VARCHAR(64) REFERENCES clip_models(id);
ALTER TABLE clip_devices ADD COLUMN bt_name VARCHAR(64);
-- Хеш устройства (телефона), с которого прошла привязка: повтор привязки с
-- того же телефона без токена (переустановка) идемпотентен.
ALTER TABLE clip_devices ADD COLUMN bound_device_hash BYTEA;
ALTER TABLE clip_devices ADD COLUMN status_reason VARCHAR(256);
-- «7 дней Pro при первом подключении»: пробный период один раз на пару
-- (здесь) и один раз на аккаунт (licenses.metadata.trial = true).
ALTER TABLE clip_devices ADD COLUMN trial_granted_at TIMESTAMPTZ;

ALTER TABLE clip_devices ADD CONSTRAINT clip_devices_identity_mode_check
    CHECK (identity_mode IN ('MAC', 'KEY'));
ALTER TABLE clip_devices ADD CONSTRAINT clip_devices_identity_material_check
    CHECK ((identity_mode <> 'KEY' OR public_key IS NOT NULL)
       AND (identity_mode <> 'MAC' OR bt_address IS NOT NULL));
ALTER TABLE clip_devices ADD CONSTRAINT clip_devices_bt_address_format_check
    CHECK (bt_address IS NULL OR bt_address ~ '^([0-9A-F]{2}:){5}[0-9A-F]{2}$');

-- RELEASED: владелец сам отвязал пару (передача/продажа). Как и PROVISIONED,
-- допускает привязку к новому аккаунту; отличается историей (была в работе).
ALTER TABLE clip_devices DROP CONSTRAINT IF EXISTS clip_devices_status_check;
ALTER TABLE clip_devices ADD CONSTRAINT clip_devices_status_check
    CHECK (status IN ('PROVISIONED', 'ACTIVE', 'RELEASED', 'REVOKED'));

-- Токен, выданный привязкой пары: на AI-пути работает, только пока у
-- аккаунта есть хотя бы одна ACTIVE-пара (см. JdbcLicenseRepository).
ALTER TABLE api_tokens ADD COLUMN clip_device_id UUID REFERENCES clip_devices(id) ON DELETE SET NULL;
