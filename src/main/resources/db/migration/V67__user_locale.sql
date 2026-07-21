-- V67: per-user UI language for the organizer dashboard.
-- Cross-device preference — the dashboard reads it on /auth/me and writes it via
-- PATCH /api/v1/me/profile {locale}. Nullable: NULL means "no server preference",
-- so the client falls back to its localStorage cache and then to English (the
-- default locale). Constrained to the four supported BCP-47 language subtags so a
-- bad write can't strand a user in an untranslated locale.
ALTER TABLE users ADD COLUMN locale VARCHAR(8);

ALTER TABLE users ADD CONSTRAINT users_locale_supported
    CHECK (locale IS NULL OR locale IN ('en', 'es', 'fr', 'uk'));
