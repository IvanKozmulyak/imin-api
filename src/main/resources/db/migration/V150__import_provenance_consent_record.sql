-- V150: key each accepted import provenance row to the consent record it proves.
-- Both rows go on DSAR erase (membership cascade), so the FK cascades too.
-- H2/PG-compatible.

ALTER TABLE import_row_provenance ADD COLUMN consent_record_id UUID;

ALTER TABLE import_row_provenance
    ADD CONSTRAINT fk_import_row_provenance_consent_record
    FOREIGN KEY (consent_record_id) REFERENCES consent_records(id) ON DELETE CASCADE;

CREATE INDEX ix_import_row_provenance_consent_record ON import_row_provenance (consent_record_id);

-- Backfill: until now the pair was only linked by the import id and row number that
-- AudienceImportService.proofText writes at the start of the record's proof text.
UPDATE import_row_provenance p
   SET consent_record_id = (
        SELECT c.id
          FROM consent_records c
         WHERE c.membership_id = p.membership_id
           AND c.source = 'organizer_import_row'
           AND c.proof_text LIKE CONCAT('Per-row consent proof from CSV import ',
                   CAST(p.import_id AS VARCHAR), ' row ', CAST(p.row_number AS VARCHAR), ':%')
         ORDER BY c.occurred_at, c.id
         LIMIT 1)
 WHERE p.accepted = TRUE
   AND p.consent_record_id IS NULL;
