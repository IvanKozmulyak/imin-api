-- Consent evidence the 2026-09 legal audit found missing.
--
-- 1. GUEST-CHECKOUT TERMS. A buyer accepts the terms of sale on the buy page and
--    nothing recorded it: the buyer surface had buyer_accounts.terms_accepted_at
--    (V91) but a guest has no account, and guest checkout is most of the volume.
--    "They accepted" is unfalsifiable without a timestamp.
--
-- 2. MARKETING PROOF TEXT. The email opt-in at checkout is recorded with a proof
--    sentence hardcoded in AudienceOrderProjector — in English, for everyone,
--    describing a checkbox label that the buyer site owns and can change. Art.
--    7(1) proof has to be of the sentence the data subject actually read, so the
--    buyer site now sends it verbatim and it is stored beside the flag it
--    justifies. Nullable: an absent value falls back to the server sentence, so
--    nothing breaks before imin-public ships its half.
--
-- 3. BUYER TERMS WORDING. Same argument on the account surface, where the
--    version was already stored but the wording it names was not.
ALTER TABLE orders ADD COLUMN terms_accepted_at        TIMESTAMP WITH TIME ZONE NULL;
ALTER TABLE orders ADD COLUMN marketing_opt_in_proof   VARCHAR(500)             NULL;

ALTER TABLE buyer_accounts ADD COLUMN terms_proof TEXT NULL;
