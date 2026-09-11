-- One connected account per organization. Plain (not partial) unique index: NULLs are
-- distinct in both PostgreSQL and H2, so unconnected orgs are unaffected.
CREATE UNIQUE INDEX uq_organizations_stripe_account ON organizations (stripe_account_id);
