ALTER TABLE tenants
    ADD COLUMN business_title_color VARCHAR(7),
    ADD COLUMN background_color VARCHAR(7),
    ADD COLUMN footer_color VARCHAR(7),
    ADD COLUMN heading_color VARCHAR(7),
    ADD COLUMN paragraph_color VARCHAR(7),
    ADD COLUMN whatsapp_color VARCHAR(7),
    ADD COLUMN marquee_color VARCHAR(7),
    ADD COLUMN font_family VARCHAR(20),
    ADD COLUMN qr_dot_type VARCHAR(20),
    ADD COLUMN qr_corner_type VARCHAR(20),
    ADD COLUMN qr_dot_color VARCHAR(7),
    ADD COLUMN qr_background_mode VARCHAR(12),
    ADD COLUMN qr_background_color VARCHAR(7),
    ADD COLUMN qr_use_logo BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN plan_months INTEGER,
    ADD CONSTRAINT tenants_business_title_color_hex CHECK (business_title_color IS NULL OR business_title_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_background_color_hex CHECK (background_color IS NULL OR background_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_footer_color_hex CHECK (footer_color IS NULL OR footer_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_heading_color_hex CHECK (heading_color IS NULL OR heading_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_paragraph_color_hex CHECK (paragraph_color IS NULL OR paragraph_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_whatsapp_color_hex CHECK (whatsapp_color IS NULL OR whatsapp_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_marquee_color_hex CHECK (marquee_color IS NULL OR marquee_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_font_family_ck CHECK (font_family IS NULL OR font_family IN
        ('fraunces', 'inter', 'georgia', 'garamond', 'trebuchet', 'arial', 'verdana', 'courier')),
    ADD CONSTRAINT tenants_qr_dot_type_ck CHECK (qr_dot_type IS NULL OR qr_dot_type IN
        ('square', 'dots', 'rounded', 'classy', 'classy-rounded', 'extra-rounded')),
    ADD CONSTRAINT tenants_qr_corner_type_ck CHECK (qr_corner_type IS NULL OR qr_corner_type IN
        ('square', 'dot', 'extra-rounded')),
    ADD CONSTRAINT tenants_qr_dot_color_hex CHECK (qr_dot_color IS NULL OR qr_dot_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_qr_background_mode_ck CHECK (qr_background_mode IS NULL OR qr_background_mode IN
        ('solid', 'transparent', 'gradient')),
    ADD CONSTRAINT tenants_qr_background_color_hex CHECK (qr_background_color IS NULL OR qr_background_color ~ '^#[0-9a-fA-F]{6}$'),
    ADD CONSTRAINT tenants_plan_months_ck CHECK (plan_months IS NULL OR plan_months IN (1, 3, 6, 12));

UPDATE tenants t
SET plan_months = c.duration_months
FROM activation_codes c
WHERE c.tenant_id = t.id;
