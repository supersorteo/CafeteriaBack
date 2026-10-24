CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    email_verified_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT users_email_lower CHECK (email = lower(email))
);
CREATE UNIQUE INDEX users_email_uk ON users (email);

CREATE TABLE tenants (
    id UUID PRIMARY KEY,
    slug VARCHAR(63) NOT NULL,
    name VARCHAR(120) NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    whatsapp_number VARCHAR(15),
    phone VARCHAR(30),
    email VARCHAR(254),
    address VARCHAR(255),
    schedule VARCHAR(500),
    currency VARCHAR(3) NOT NULL DEFAULT 'UYU',
    locale VARCHAR(10) NOT NULL DEFAULT 'es-UY',
    timezone VARCHAR(64) NOT NULL DEFAULT 'America/Montevideo',
    primary_color VARCHAR(7) NOT NULL DEFAULT '#e4572e',
    accent_color VARCHAR(7) NOT NULL DEFAULT '#17bebb',
    hero_title VARCHAR(160),
    hero_description VARCHAR(600),
    about_text VARCHAR(2000),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenants_slug_format CHECK (
        slug ~ '^[a-z0-9]([a-z0-9]|-[a-z0-9])*$' AND char_length(slug) BETWEEN 3 AND 63
    ),
    CONSTRAINT tenants_slug_reserved CHECK (
        slug NOT IN ('admin','api','media','assets','images','auth','www','support','status')
    ),
    CONSTRAINT tenants_name_nonblank CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT tenants_whatsapp_format CHECK (whatsapp_number IS NULL OR whatsapp_number ~ '^[0-9]{8,15}$'),
    CONSTRAINT tenants_currency_allowed CHECK (currency IN ('UYU','USD','MXN','EUR')),
    CONSTRAINT tenants_locale_allowed CHECK (locale IN ('es-UY','es-MX','es-ES')),
    CONSTRAINT tenants_primary_color_hex CHECK (primary_color ~ '^#[0-9a-fA-F]{6}$'),
    CONSTRAINT tenants_accent_color_hex CHECK (accent_color ~ '^#[0-9a-fA-F]{6}$')
);
CREATE UNIQUE INDEX tenants_slug_uk ON tenants (slug);

CREATE TABLE tenant_users (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    role VARCHAR(10) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT tenant_users_role_allowed CHECK (role IN ('OWNER','EDITOR')),
    CONSTRAINT tenant_users_user_uk UNIQUE (user_id)
);
CREATE UNIQUE INDEX tenant_users_one_owner_uk ON tenant_users (tenant_id) WHERE role = 'OWNER';
CREATE INDEX tenant_users_tenant_idx ON tenant_users (tenant_id);

CREATE TABLE media_assets (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    storage_key VARCHAR(255) NOT NULL,
    public_url VARCHAR(500),
    mime_type VARCHAR(50) NOT NULL,
    byte_size BIGINT NOT NULL,
    width INTEGER,
    height INTEGER,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT media_assets_storage_key_uk UNIQUE (storage_key),
    CONSTRAINT media_assets_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT media_assets_status_allowed CHECK (status IN ('PENDING','READY','DELETED')),
    CONSTRAINT media_assets_mime_allowed CHECK (mime_type IN ('image/jpeg','image/png','image/webp')),
    CONSTRAINT media_assets_size_positive CHECK (byte_size > 0)
);

CREATE TABLE categories (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    name VARCHAR(120) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT categories_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT categories_name_nonblank CHECK (char_length(btrim(name)) > 0)
);
CREATE INDEX categories_tenant_order_idx ON categories (tenant_id, sort_order, id);

CREATE TABLE menu_items (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    category_id UUID NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(2000),
    price NUMERIC(10,2) NOT NULL,
    tag VARCHAR(40),
    image_id UUID,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT menu_items_category_fk FOREIGN KEY (tenant_id, category_id)
        REFERENCES categories (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT menu_items_image_fk FOREIGN KEY (tenant_id, image_id)
        REFERENCES media_assets (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT menu_items_price_nonneg CHECK (price >= 0),
    CONSTRAINT menu_items_name_nonblank CHECK (char_length(btrim(name)) > 0)
);
CREATE INDEX menu_items_category_order_idx ON menu_items (tenant_id, category_id, sort_order, id);

CREATE TABLE site_images (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    slot VARCHAR(20) NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    media_id UUID NOT NULL,
    alt VARCHAR(200),
    CONSTRAINT site_images_media_fk FOREIGN KEY (tenant_id, media_id)
        REFERENCES media_assets (tenant_id, id) ON DELETE RESTRICT,
    CONSTRAINT site_images_slot_allowed CHECK (slot IN ('HERO','ABOUT','GALLERY')),
    CONSTRAINT site_images_slot_position_uk UNIQUE (tenant_id, slot, position)
);

CREATE TABLE account_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id),
    purpose VARCHAR(20) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT account_tokens_purpose_allowed CHECK (purpose IN ('VERIFY_EMAIL','RESET_PASSWORD')),
    CONSTRAINT account_tokens_hash_uk UNIQUE (token_hash)
);
CREATE INDEX account_tokens_user_idx ON account_tokens (user_id, purpose);

CREATE TABLE email_outbox (
    id UUID PRIMARY KEY,
    type VARCHAR(30) NOT NULL,
    recipient VARCHAR(254) NOT NULL,
    payload TEXT,
    status VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    CONSTRAINT email_outbox_status_allowed CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX email_outbox_pending_idx ON email_outbox (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE SPRING_SESSION (
    PRIMARY_ID CHAR(36) NOT NULL,
    SESSION_ID CHAR(36) NOT NULL,
    CREATION_TIME BIGINT NOT NULL,
    LAST_ACCESS_TIME BIGINT NOT NULL,
    MAX_INACTIVE_INTERVAL INT NOT NULL,
    EXPIRY_TIME BIGINT NOT NULL,
    PRINCIPAL_NAME VARCHAR(100),
    CONSTRAINT SPRING_SESSION_PK PRIMARY KEY (PRIMARY_ID)
);
CREATE UNIQUE INDEX SPRING_SESSION_IX1 ON SPRING_SESSION (SESSION_ID);
CREATE INDEX SPRING_SESSION_IX2 ON SPRING_SESSION (EXPIRY_TIME);
CREATE INDEX SPRING_SESSION_IX3 ON SPRING_SESSION (PRINCIPAL_NAME);

CREATE TABLE SPRING_SESSION_ATTRIBUTES (
    SESSION_PRIMARY_ID CHAR(36) NOT NULL,
    ATTRIBUTE_NAME VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES BYTEA NOT NULL,
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_PK PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT SPRING_SESSION_ATTRIBUTES_FK FOREIGN KEY (SESSION_PRIMARY_ID)
        REFERENCES SPRING_SESSION (PRIMARY_ID) ON DELETE CASCADE
);
