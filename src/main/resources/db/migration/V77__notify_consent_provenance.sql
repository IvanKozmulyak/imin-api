-- V77: consent provenance for notify-me subscriptions.
--
-- The notify-me form makes a promise ("We'll email you if tickets release.") and
-- NotifyReleaseSender (V76/W0.5) now keeps it. Storing the row alone is not proof of
-- consent: if a buyer disputes the email we need to be able to say WHO asked, FROM
-- WHERE, WITH WHICH CLIENT, and — crucially — WHAT EXACT TEXT they were shown when
-- they asked. These four columns are that record, captured at subscribe time.
--
-- All nullable: rows written before this migration have no provenance and are not
-- back-fillable, and a request can legitimately arrive with no User-Agent header.
--   source_ip    — client IP (X-Forwarded-For first hop when present). 45 chars fits
--                  a full IPv6 address including an IPv4-mapped suffix.
--   user_agent   — raw User-Agent header, truncated to the column width.
--   consent_text — the verbatim UI promise shown at collection. Stored per row rather
--                  than assumed from the current build, so changing the copy later
--                  cannot rewrite what an existing subscriber actually agreed to.
--   locale       — the buyer's UI language, used to pick the notify-release email
--                  variant (en/es/fr/uk). NULL means "no preference" ⇒ English.
ALTER TABLE notify_subscriptions ADD COLUMN source_ip VARCHAR(45) NULL;
ALTER TABLE notify_subscriptions ADD COLUMN user_agent VARCHAR(255) NULL;
ALTER TABLE notify_subscriptions ADD COLUMN consent_text VARCHAR(255) NULL;
ALTER TABLE notify_subscriptions ADD COLUMN locale VARCHAR(5) NULL;
