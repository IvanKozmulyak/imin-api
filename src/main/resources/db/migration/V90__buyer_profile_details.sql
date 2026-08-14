-- V90__buyer_profile_details.sql
-- The post-signup profile step: first/last name and date of birth.
--
-- WHY SPLIT THE NAME. V83 shipped display_name as one column, and the account
-- screen deliberately rendered one field to match it. The door list reads the
-- ticket rather than the profile, so a split bought nothing then. It buys
-- something now: the onboarding step asks for a first and a last name, and
-- storing "Ada Lovelace" in one column loses which half is which the moment
-- anything needs to greet somebody by their first name.
--
-- display_name STAYS and stays authoritative for display. It is written as
-- "first last" when both are given, so every existing reader — the account
-- header, the initials disc, BuyerMeResponse — keeps working unchanged and no
-- backfill is needed. The two new columns are the structured form of the same
-- fact, not a second source of truth for it.
--
-- DATE OF BIRTH IS NULLABLE AND STAYS THAT WAY. Nothing in imin reads it
-- today: there is no age gate, no birthday mail, no analytics on it. It is
-- collected because the operator asked for it, it is optional on the screen,
-- and under data minimisation that combination is the most that can be
-- justified until something actually uses it. If nothing does, drop the column
-- rather than leaving it to age.
ALTER TABLE buyer_accounts ADD COLUMN first_name    VARCHAR(255) NULL;
ALTER TABLE buyer_accounts ADD COLUMN last_name     VARCHAR(255) NULL;
ALTER TABLE buyer_accounts ADD COLUMN date_of_birth DATE         NULL;
