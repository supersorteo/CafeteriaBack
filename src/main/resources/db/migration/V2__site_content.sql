ALTER TABLE tenants
    ADD COLUMN descriptor VARCHAR(60),
    ADD COLUMN tagline VARCHAR(160),
    ADD COLUMN about_title VARCHAR(120),
    ADD COLUMN footer_text VARCHAR(200),
    ADD COLUMN hero_caption VARCHAR(80),
    ADD COLUMN marquee_items TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN reservation_areas TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN party_sizes TEXT[] NOT NULL DEFAULT '{}',
    ADD CONSTRAINT tenants_marquee_max CHECK (cardinality(marquee_items) <= 8),
    ADD CONSTRAINT tenants_areas_max CHECK (cardinality(reservation_areas) <= 8),
    ADD CONSTRAINT tenants_party_sizes_max CHECK (cardinality(party_sizes) <= 10);

CREATE TABLE site_stats (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    position INTEGER NOT NULL,
    label VARCHAR(40) NOT NULL,
    value INTEGER NOT NULL,
    CONSTRAINT site_stats_position_range CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT site_stats_value_range CHECK (value BETWEEN 0 AND 999999),
    CONSTRAINT site_stats_label_nonblank CHECK (char_length(btrim(label)) > 0),
    CONSTRAINT site_stats_position_uk UNIQUE (tenant_id, position)
);

CREATE TABLE site_highlights (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    position INTEGER NOT NULL,
    title VARCHAR(60) NOT NULL,
    description VARCHAR(200),
    CONSTRAINT site_highlights_position_range CHECK (position BETWEEN 0 AND 2),
    CONSTRAINT site_highlights_title_nonblank CHECK (char_length(btrim(title)) > 0),
    CONSTRAINT site_highlights_position_uk UNIQUE (tenant_id, position)
);
