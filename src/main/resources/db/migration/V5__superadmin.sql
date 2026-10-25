ALTER TABLE tenants ADD COLUMN suspended_at TIMESTAMPTZ;

CREATE TABLE audit_log (
    id UUID PRIMARY KEY,
    at TIMESTAMPTZ NOT NULL DEFAULT now(),
    action VARCHAR(40) NOT NULL,
    target_id UUID,
    target_label VARCHAR(200),
    actor VARCHAR(254) NOT NULL,
    ip VARCHAR(64),
    detail VARCHAR(500)
);
CREATE INDEX audit_log_at_idx ON audit_log (at DESC);
