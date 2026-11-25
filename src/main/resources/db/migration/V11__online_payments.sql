ALTER TABLE orders
    ADD COLUMN provider_ref VARCHAR(80),
    ADD COLUMN provider_payment_id VARCHAR(80),
    ADD COLUMN payer_email VARCHAR(254),
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'USD',
    ADD COLUMN amount_minor INTEGER,
    ADD COLUMN fx_rate NUMERIC(14, 4),
    ADD COLUMN business_name VARCHAR(120),
    ADD COLUMN slug VARCHAR(63),
    ADD COLUMN password_hash VARCHAR(100),
    ADD COLUMN claim_hash VARCHAR(64),
    ADD COLUMN claim_used_at TIMESTAMPTZ,
    ADD COLUMN tenant_id UUID REFERENCES tenants (id) ON DELETE SET NULL,
    ADD COLUMN paid_at TIMESTAMPTZ,
    ADD COLUMN failure_reason VARCHAR(300);

ALTER TABLE orders DROP CONSTRAINT orders_method_allowed;
ALTER TABLE orders ADD CONSTRAINT orders_method_allowed CHECK (method IN ('WHATSAPP', 'MERCADOPAGO', 'PAYPAL'));
ALTER TABLE orders DROP CONSTRAINT orders_status_allowed;
ALTER TABLE orders ADD CONSTRAINT orders_status_allowed CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED', 'FAILED'));

CREATE UNIQUE INDEX orders_provider_ref_uk ON orders (method, provider_ref) WHERE provider_ref IS NOT NULL;
CREATE UNIQUE INDEX orders_slug_reserved_uk ON orders (slug) WHERE slug IS NOT NULL AND status = 'PENDING';
CREATE INDEX orders_unfulfilled_idx ON orders (paid_at) WHERE status = 'PAID' AND tenant_id IS NULL AND slug IS NOT NULL;

CREATE TABLE payment_events (
    id UUID PRIMARY KEY,
    provider VARCHAR(20) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    order_id UUID REFERENCES orders (id) ON DELETE SET NULL,
    kind VARCHAR(60),
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT payment_events_provider_event_uk UNIQUE (provider, event_id)
);

CREATE TABLE fx_rates (
    id UUID PRIMARY KEY,
    base VARCHAR(3) NOT NULL,
    quote VARCHAR(3) NOT NULL,
    rate NUMERIC(14, 4) NOT NULL,
    source VARCHAR(10) NOT NULL DEFAULT 'MANUAL',
    created_by VARCHAR(254),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fx_rates_rate_positive CHECK (rate > 0)
);
CREATE INDEX fx_rates_latest_idx ON fx_rates (base, quote, created_at DESC);
