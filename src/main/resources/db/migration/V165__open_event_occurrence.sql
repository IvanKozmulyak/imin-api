-- V165: open-data event nights (OpenAgenda, Que Faire à Paris; DATAtourisme later) that matched a genre or local-event keyword.
-- Titles and links only, pruned after 200 days; licence kept per row so ODbL rows stay separable.
CREATE TABLE open_event_occurrence (
    id UUID PRIMARY KEY,
    source VARCHAR(32) NOT NULL,
    source_event_id VARCHAR(64) NOT NULL,
    city_key VARCHAR(100) NOT NULL,
    night_date DATE NOT NULL,
    title VARCHAR(255) NOT NULL,
    title_key VARCHAR(255) NOT NULL,          -- normalised title, cross-source dedup and recurrence grouping
    url VARCHAR(512) NOT NULL,
    genre_keys TEXT NOT NULL DEFAULT '[]',    -- JSON array of bucket names
    community BOOLEAN NOT NULL DEFAULT FALSE,
    licence VARCHAR(32) NOT NULL,
    credit VARCHAR(255),                      -- per-row author credit (DATAtourisme hasBeenCreatedBy); null today
    synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_open_event_occurrence UNIQUE (source, source_event_id, night_date),
    CONSTRAINT ck_open_event_occurrence_source CHECK (source IN ('openagenda', 'quefaireaparis', 'datatourisme')),
    CONSTRAINT ck_open_event_occurrence_licence CHECK (licence IN ('Licence Ouverte 2.0', 'ODbL 1.0'))
);
CREATE INDEX ix_open_event_occurrence_city_night ON open_event_occurrence (city_key, night_date);
