-- V009: атрибуция AI-usage по фиче для дневных квот тарифа.
--
-- feature: voice_ai (голос) / base_ai (чат). NULL = записи до V009;
-- гейт квот засчитывает их консервативно в каждую корзину (см.
-- PlanQuotaGate). Через сутки после деплоя наследие вымывается само.
ALTER TABLE ai_usage_records ADD COLUMN feature VARCHAR(32);

CREATE INDEX idx_ai_usage_client_feature_time
    ON ai_usage_records(client_id, feature, occurred_at DESC);
