CREATE TABLE site_spaces (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    position INTEGER NOT NULL,
    name VARCHAR(60) NOT NULL,
    description VARCHAR(200),
    media_id UUID,
    bookable BOOLEAN NOT NULL DEFAULT TRUE,
    CONSTRAINT site_spaces_position_range CHECK (position BETWEEN 0 AND 9),
    CONSTRAINT site_spaces_name_nonblank CHECK (char_length(btrim(name)) > 0),
    CONSTRAINT site_spaces_position_uk UNIQUE (tenant_id, position),
    CONSTRAINT site_spaces_media_fk FOREIGN KEY (tenant_id, media_id)
        REFERENCES media_assets (tenant_id, id) ON DELETE RESTRICT
);
CREATE UNIQUE INDEX site_spaces_name_uk ON site_spaces (tenant_id, lower(btrim(name)));

INSERT INTO site_spaces (id, tenant_id, position, name, description, bookable)
SELECT gen_random_uuid(), h.tenant_id,
       (row_number() OVER (PARTITION BY h.tenant_id ORDER BY h.position) - 1)::int,
       btrim(h.title), h.description, FALSE
FROM (
    SELECT DISTINCT ON (tenant_id, lower(btrim(title))) tenant_id, position, title, description
    FROM site_highlights
    ORDER BY tenant_id, lower(btrim(title)), position
) h;

CREATE TEMP TABLE v4_areas AS
SELECT DISTINCT ON (t.id, lower(btrim(a.name))) t.id AS tenant_id, btrim(a.name) AS name, a.ord
FROM tenants t
CROSS JOIN LATERAL unnest(t.reservation_areas) WITH ORDINALITY AS a(name, ord)
WHERE btrim(a.name) <> ''
ORDER BY t.id, lower(btrim(a.name)), a.ord;

UPDATE site_spaces s SET bookable = TRUE
FROM v4_areas d
WHERE s.tenant_id = d.tenant_id AND lower(btrim(s.name)) = lower(d.name);

INSERT INTO site_spaces (id, tenant_id, position, name, bookable)
SELECT gen_random_uuid(), x.tenant_id, x.pos::int, left(x.name, 60), TRUE
FROM (
    SELECT d.tenant_id, d.name,
           (SELECT count(*) FROM site_spaces s WHERE s.tenant_id = d.tenant_id)
               + row_number() OVER (PARTITION BY d.tenant_id ORDER BY d.ord) - 1 AS pos
    FROM v4_areas d
    WHERE NOT EXISTS (SELECT 1 FROM site_spaces s
                      WHERE s.tenant_id = d.tenant_id AND lower(btrim(s.name)) = lower(d.name))
) x
WHERE x.pos < 10;

DROP TABLE v4_areas;
