CREATE TABLE billing_prices (
    plan_code VARCHAR(20) NOT NULL REFERENCES billing_plans (code),
    months INTEGER NOT NULL,
    usd_cents INTEGER NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (plan_code, months),
    CONSTRAINT billing_prices_months_allowed CHECK (months IN (1, 3, 6, 12)),
    CONSTRAINT billing_prices_amount_positive CHECK (usd_cents > 0)
);

INSERT INTO billing_prices (plan_code, months, usd_cents) VALUES ('EMPRENDEDOR', 1, 900), ('PROFESIONAL', 1, 1900);

CREATE TABLE billing_regions (
    code VARCHAR(2) PRIMARY KEY,
    name VARCHAR(60) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0
);

INSERT INTO billing_regions (code, name, sort_order) VALUES ('AR', 'Argentina', 0), ('UY', 'Uruguay', 1);

CREATE TABLE orders (
    id UUID PRIMARY KEY,
    reference VARCHAR(16) NOT NULL UNIQUE,
    plan_code VARCHAR(20) NOT NULL REFERENCES billing_plans (code),
    months INTEGER NOT NULL,
    region VARCHAR(2) NOT NULL REFERENCES billing_regions (code),
    method VARCHAR(20) NOT NULL,
    buyer_name VARCHAR(120),
    buyer_email VARCHAR(254) NOT NULL,
    usd_cents INTEGER NOT NULL,
    status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
    activation_code_id UUID REFERENCES activation_codes (id) ON DELETE SET NULL,
    confirmed_by VARCHAR(254),
    confirmed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT orders_status_allowed CHECK (status IN ('PENDING', 'PAID', 'CANCELLED', 'EXPIRED')),
    CONSTRAINT orders_method_allowed CHECK (method IN ('WHATSAPP'))
);

CREATE INDEX orders_status_created_idx ON orders (status, created_at DESC);
CREATE INDEX orders_region_idx ON orders (region);
CREATE INDEX orders_email_idx ON orders (lower(buyer_email));
