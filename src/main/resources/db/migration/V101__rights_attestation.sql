-- V101__rights_attestation.sql
-- Server-side record of who asserted the rights to a third party's likeness.
--
-- A DJ photo goes into an AI pipeline: Ideogram receives it as
-- character_reference_images, and the OpenRouter vision gate receives the
-- finished poster containing that likeness. Nothing anywhere captured a claim
-- that the uploader had the right to do that — droit à l'image (C. civ. art. 9)
-- and CPI L122-4 both make it the uploader's claim to make, and ours to be able
-- to produce later. The audience CSV import has had exactly this gate since
-- Tier C (IMPORT_ATTESTATION_REQUIRED); this is the same pattern for likeness.
--
-- The version column is what makes the timestamp mean something. "Attested on
-- 2026-09-08" is worthless without the wording that was attested to, and that
-- wording will change; the constant (RightsAttestation.CURRENT_VERSION) pins
-- which text this row agreed to.
--
-- Nullable, no backfill: every photo already uploaded was uploaded without an
-- attestation, and stamping today's date on those rows would manufacture a
-- consent record that nobody gave.

ALTER TABLE events ADD COLUMN dj_photo_rights_attested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE events ADD COLUMN dj_photo_rights_attestation_version VARCHAR(32);

-- Organizer logo. Optional rather than required: a logo is normally the
-- organizer's own mark, so a hard gate would block a legitimate upload to
-- capture a claim that is usually trivially true. When the dashboard does send
-- it, we record it.
ALTER TABLE organizations ADD COLUMN logo_rights_attested_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE organizations ADD COLUMN logo_rights_attestation_version VARCHAR(32);
