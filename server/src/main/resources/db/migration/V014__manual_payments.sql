-- Ручная оплата тарифов (пока нет договора с банком): заявка = billing_order
-- с провайдером MANUAL. Номер заявки человекочитаемый (OMX-4821), срок жизни
-- ограничен, подтверждение/отмена оператором фиксируют сумму, способ и причину.
ALTER TABLE billing_orders DROP CONSTRAINT IF EXISTS billing_orders_provider_check;
ALTER TABLE billing_orders ADD CONSTRAINT billing_orders_provider_check
    CHECK (provider IN ('PADDLE', 'HELEKET', 'LOCAL_TMT', 'MANUAL'));

ALTER TABLE billing_events DROP CONSTRAINT IF EXISTS billing_events_provider_check;
ALTER TABLE billing_events ADD CONSTRAINT billing_events_provider_check
    CHECK (provider IN ('PADDLE', 'HELEKET', 'LOCAL_TMT', 'MANUAL'));

ALTER TABLE billing_orders
    ADD COLUMN order_number VARCHAR(16),
    ADD COLUMN expires_at TIMESTAMPTZ,
    ADD COLUMN paid_amount_minor BIGINT CHECK (paid_amount_minor IS NULL OR paid_amount_minor >= 0),
    ADD COLUMN payment_method VARCHAR(32),
    ADD COLUMN resolution_note VARCHAR(512),
    ADD COLUMN resolved_by VARCHAR(128),
    ADD COLUMN resolved_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_billing_order_number ON billing_orders(order_number)
    WHERE order_number IS NOT NULL;
CREATE INDEX idx_billing_orders_manual ON billing_orders(status, created_at DESC)
    WHERE provider = 'MANUAL';
