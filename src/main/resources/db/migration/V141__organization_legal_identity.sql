-- V141__organization_legal_identity.sql
-- Organizer legal identity printed in every marketing email footer.
-- Nullable, no backfill: only the organizer can state its legal name and contact.
ALTER TABLE organizations ADD COLUMN legal_name VARCHAR(200);
ALTER TABLE organizations ADD COLUMN legal_contact VARCHAR(320);
