CREATE TABLE payment_email_checks (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    code_hash VARCHAR(64) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    expires_at TIMESTAMPTZ NOT NULL,
    proof_hash VARCHAR(64),
    proof_expires_at TIMESTAMPTZ,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payment_email_checks_email_idx ON payment_email_checks (email, created_at DESC);

ALTER TABLE orders ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
