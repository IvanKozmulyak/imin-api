-- V134__audience_imports_provenance.sql
-- One row per real CSV import, and one provenance row per imported contact. A row is
-- only mailable as explicit consent when its own provenance row was accepted.
-- H2/PG-compatible: TEXT instead of jsonb, no native enum, no unnamed CHECK.

CREATE TABLE audience_imports (
    id              UUID         PRIMARY KEY,
    org_id          UUID         NOT NULL,
    created_by      UUID,
    -- SHA-256 of the file exactly as the organizer chose it, computed in their browser;
    -- matches their own copy. Null when the client could not compute it.
    original_file_sha256 VARCHAR(64),
    -- SHA-256 of the bytes the API received: the dashboard's rewrite (mapped columns only).
    uploaded_file_sha256 VARCHAR(64),
    -- Set only when every accepted row names the same platform / export date.
    source_platform VARCHAR(64),
    export_date     DATE,
    -- Import-level evidence; lifts the per-import cap on subscribed rows.
    proof_ref       VARCHAR(500),
    attestation_version VARCHAR(32),
    rows_total      INT          NOT NULL DEFAULT 0,
    rows_explicit   INT          NOT NULL DEFAULT 0,
    rows_no_basis   INT          NOT NULL DEFAULT 0,
    rows_rejected   INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_audience_imports_org_created ON audience_imports (org_id, created_at);

CREATE TABLE import_row_provenance (
    id                 UUID         PRIMARY KEY,
    import_id          UUID         NOT NULL REFERENCES audience_imports(id) ON DELETE CASCADE,
    membership_id      UUID         NOT NULL REFERENCES memberships(membership_id) ON DELETE CASCADE,
    row_number         INT          NOT NULL,
    source_platform    VARCHAR(64),
    export_date        DATE,
    -- Raw "events bought" cell, as the organizer exported it.
    events             TEXT,
    last_purchase_date DATE,
    -- opted_in | unsubscribed | none (anything else is stored as none)
    marketing_status   VARCHAR(16)  NOT NULL,
    proof_ref          VARCHAR(500),
    accepted           BOOLEAN      NOT NULL,
    -- not_opted_in | missing_proof | invalid_export_date | subscribed_cap | unsubscribed
    -- | suppressed | previously_unsubscribed; null when accepted
    reject_reason      VARCHAR(32),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

CREATE INDEX ix_import_row_provenance_membership ON import_row_provenance (membership_id);
CREATE INDEX ix_import_row_provenance_import ON import_row_provenance (import_id);
