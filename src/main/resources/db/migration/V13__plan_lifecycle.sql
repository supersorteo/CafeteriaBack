CREATE TABLE plan_notices (
    tenant_id UUID NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    kind VARCHAR(20) NOT NULL,
    period_expires_at TIMESTAMPTZ NOT NULL,
    sent_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, kind, period_expires_at)
);

CREATE INDEX tenants_plan_expires_idx ON tenants (plan_expires_at) WHERE plan_expires_at IS NOT NULL;

ALTER TABLE orders ADD COLUMN fulfilled_at TIMESTAMPTZ;
UPDATE orders SET fulfilled_at = coalesce(confirmed_at, paid_at) WHERE status = 'PAID' AND tenant_id IS NOT NULL;
