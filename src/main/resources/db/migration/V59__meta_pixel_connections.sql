-- Meta Pixel + Conversions API connection (spec §5, Stage C).
-- One connection per (org, event); event_id NULL = org-wide default pixel.
-- capi_access_token_enc holds the AES-256-GCM ciphertext of the organizer-minted
-- CAPI token (write-only — never returned by any API).
CREATE TABLE meta_pixel_connections (
    id                    UUID         PRIMARY KEY,
    org_id                UUID         NOT NULL,
    -- NULL = org-wide default; a specific event overrides the org default.
    event_id              UUID         NULL REFERENCES events (id) ON DELETE CASCADE,
    pixel_id              VARCHAR(32)  NOT NULL,
    capi_access_token_enc TEXT         NOT NULL,
    test_event_code       VARCHAR(64)  NULL,
    -- status: active | disabled
    status                VARCHAR(16)  NOT NULL DEFAULT 'active',
    created_by            UUID         NULL,
    created_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

-- One connection per (org, event). Postgres and H2 both treat two NULL event_ids
-- as distinct under a UNIQUE constraint, so the app layer enforces the single
-- org-wide (event_id IS NULL) row (see MetaConnectionService upsert).
ALTER TABLE meta_pixel_connections
    ADD CONSTRAINT uq_meta_pixel_org_event UNIQUE (org_id, event_id);

CREATE INDEX ix_meta_pixel_org ON meta_pixel_connections (org_id);
