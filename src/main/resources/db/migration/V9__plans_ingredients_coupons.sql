CREATE TABLE billing_plans (
    code VARCHAR(20) PRIMARY KEY,
    name VARCHAR(40) NOT NULL,
    sort_order INTEGER NOT NULL DEFAULT 0,
    max_menu_items INTEGER NOT NULL,
    max_active_coupons INTEGER NOT NULL,
    max_gallery INTEGER NOT NULL,
    max_table_qr INTEGER NOT NULL DEFAULT 0,
    full_branding BOOLEAN NOT NULL DEFAULT FALSE,
    advanced_coupons BOOLEAN NOT NULL DEFAULT FALSE,
    table_qr BOOLEAN NOT NULL DEFAULT FALSE,
    priority_support BOOLEAN NOT NULL DEFAULT FALSE,
    stats BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT billing_plans_limits_positive CHECK (max_menu_items >= 0 AND max_active_coupons >= 0
        AND max_gallery BETWEEN 0 AND 15 AND max_table_qr >= 0)
);

INSERT INTO billing_plans (code, name, sort_order, max_menu_items, max_active_coupons, max_gallery, max_table_qr,
        full_branding, advanced_coupons, table_qr, priority_support, stats) VALUES
    ('EMPRENDEDOR', 'Emprendedor', 1, 50, 15, 6, 0, FALSE, FALSE, FALSE, FALSE, FALSE),
    ('PROFESIONAL', 'Profesional', 2, 100, 35, 15, 30, TRUE, TRUE, TRUE, TRUE, FALSE);

ALTER TABLE tenants ADD COLUMN plan_code VARCHAR(20) REFERENCES billing_plans (code);
ALTER TABLE code_batches ADD COLUMN plan_code VARCHAR(20) REFERENCES billing_plans (code);
ALTER TABLE activation_codes ADD COLUMN plan_code VARCHAR(20) REFERENCES billing_plans (code);

UPDATE tenants SET plan_code = 'PROFESIONAL';
UPDATE code_batches SET plan_code = 'PROFESIONAL';
UPDATE activation_codes SET plan_code = 'PROFESIONAL';

ALTER TABLE menu_items ADD CONSTRAINT menu_items_tenant_id_uk UNIQUE (tenant_id, id);

CREATE TABLE ingredients (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    name VARCHAR(80) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ingredients_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT ingredients_name_not_blank CHECK (btrim(name) <> '')
);
CREATE UNIQUE INDEX ingredients_tenant_name_uk ON ingredients (tenant_id, lower(btrim(name)));

CREATE TABLE menu_item_ingredients (
    tenant_id UUID NOT NULL,
    menu_item_id UUID NOT NULL,
    ingredient_id UUID NOT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (menu_item_id, ingredient_id),
    CONSTRAINT menu_item_ingredients_item_fk FOREIGN KEY (tenant_id, menu_item_id)
        REFERENCES menu_items (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT menu_item_ingredients_ingredient_fk FOREIGN KEY (tenant_id, ingredient_id)
        REFERENCES ingredients (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX menu_item_ingredients_tenant_idx ON menu_item_ingredients (tenant_id);

CREATE TABLE coupons (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    code VARCHAR(24) NOT NULL,
    type VARCHAR(12) NOT NULL,
    title VARCHAR(80) NOT NULL,
    benefit VARCHAR(180) NOT NULL,
    validity_text VARCHAR(80),
    starts_on DATE,
    ends_on DATE,
    weekdays VARCHAR(7),
    time_from TIME,
    time_to TIME,
    max_redemptions INTEGER,
    redemptions INTEGER NOT NULL DEFAULT 0,
    featured BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    sort_order INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT coupons_code_uk UNIQUE (tenant_id, code),
    CONSTRAINT coupons_type_allowed CHECK (type IN ('DESCUENTO', 'VIGENCIA', 'CAMBIO', 'REGALO', 'OTRO')),
    CONSTRAINT coupons_text_not_blank CHECK (btrim(code) <> '' AND btrim(title) <> '' AND btrim(benefit) <> ''),
    CONSTRAINT coupons_dates_ordered CHECK (starts_on IS NULL OR ends_on IS NULL OR ends_on >= starts_on),
    CONSTRAINT coupons_weekdays_format CHECK (weekdays IS NULL OR weekdays ~ '^[1-7]{1,7}$'),
    CONSTRAINT coupons_redemptions_valid CHECK (redemptions >= 0 AND (max_redemptions IS NULL OR max_redemptions >= 1))
);
CREATE INDEX coupons_tenant_idx ON coupons (tenant_id, sort_order, created_at);
