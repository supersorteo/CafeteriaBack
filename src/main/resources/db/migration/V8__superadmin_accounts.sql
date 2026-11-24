CREATE TABLE superadmin_accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(10) NOT NULL DEFAULT 'OPERATOR',
    regions VARCHAR(40) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    must_change_password BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_login_at TIMESTAMPTZ,
    CONSTRAINT superadmin_accounts_email_uk UNIQUE (email),
    CONSTRAINT superadmin_accounts_email_lower CHECK (email = lower(email)),
    CONSTRAINT superadmin_accounts_role_allowed CHECK (role IN ('ADMIN', 'OPERATOR'))
);
