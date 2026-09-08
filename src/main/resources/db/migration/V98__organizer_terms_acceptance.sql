-- Organizer terms acceptance at signup.
--
-- The buyer surface has recorded this since V91 (buyer_accounts.terms_accepted_at
-- + terms_version) and the guest-checkout half landed in V97. The organizer — the
-- party that signs the contract imin actually relies on, and the data controller
-- for every attendee they collect — had no such record at all: `users` carried no
-- terms columns, and SignupRequest had no field to carry an acceptance.
--
-- Nullable and NOT enforced. The dashboard has no legal pages to link yet, so a
-- required checkbox would gate signup on a link that 404s; the column exists so
-- that the acceptance can be recorded the moment the dashboard sends it. Absence
-- means "not recorded", never "declined" — the two must not be conflated when
-- this is read back as evidence. Enforcement (and re-acceptance on a version
-- bump) is the follow-up.
ALTER TABLE users ADD COLUMN terms_accepted_at TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE users ADD COLUMN terms_version     VARCHAR(32)              NULL;
