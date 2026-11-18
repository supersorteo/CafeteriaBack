ALTER TABLE tenants ADD COLUMN plan_expires_at TIMESTAMPTZ;

CREATE TABLE code_batches (
    id UUID PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    duration_months INTEGER NOT NULL,
    quantity INTEGER NOT NULL,
    note VARCHAR(300),
    created_by VARCHAR(254) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT code_batches_duration_allowed CHECK (duration_months IN (1, 3, 6, 12)),
    CONSTRAINT code_batches_quantity_range CHECK (quantity BETWEEN 1 AND 500)
);

CREATE TABLE activation_codes (
    id UUID PRIMARY KEY,
    code VARCHAR(20) NOT NULL,
    batch_id UUID NOT NULL REFERENCES code_batches (id),
    duration_months INTEGER NOT NULL,
    status VARCHAR(10) NOT NULL DEFAULT 'AVAILABLE',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    redeemed_at TIMESTAMPTZ,
    redeemed_by_email VARCHAR(254),
    tenant_id UUID REFERENCES tenants (id),
    CONSTRAINT activation_codes_code_uk UNIQUE (code),
    CONSTRAINT activation_codes_duration_allowed CHECK (duration_months IN (1, 3, 6, 12)),
    CONSTRAINT activation_codes_status_allowed CHECK (status IN ('AVAILABLE', 'REDEEMED', 'DISABLED')),
    CONSTRAINT activation_codes_redeemed_has_tenant CHECK (status <> 'REDEEMED' OR tenant_id IS NOT NULL)
);
CREATE INDEX activation_codes_status_idx ON activation_codes (status);
CREATE INDEX activation_codes_batch_idx ON activation_codes (batch_id);
CREATE UNIQUE INDEX activation_codes_one_per_tenant_uk ON activation_codes (tenant_id) WHERE tenant_id IS NOT NULL;
