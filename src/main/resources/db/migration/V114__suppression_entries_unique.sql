-- V50 declared suppression uniqueness ("one row per (org_id, membership_id)", "one row per
-- normalized_email") and enforced it nowhere: both writers in SuppressionService are
-- unguarded read-then-insert, and addDeliverability runs from the bounce webhook where two
-- concurrent deliveries for one address are ordinary. A single duplicate row turned the
-- Optional finders into an IncorrectResultSizeDataAccessException, i.e. a permanent 500 on
-- GET /audience/members/{id} and POST /audience/import until somebody deleted a row by hand.
--
-- No WHERE clause is needed to make these partial, which is what V50 said H2 could not do:
-- scope is part of each key, and the other key columns are NULL on the rows the index is not
-- meant to cover (a marketing row has no normalized_email, a deliverability row has no
-- org_id/membership_id). Both Postgres and H2 treat NULLs as distinct in a unique index, so
-- each index constrains exactly its own scope.

-- Collapse whatever duplicates already exist, oldest row wins.
DELETE FROM suppression_entries
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (PARTITION BY org_id, membership_id ORDER BY since ASC, id ASC) AS rn
           FROM suppression_entries
          WHERE scope = 'marketing'
     ) dup
      WHERE dup.rn > 1
 );

DELETE FROM suppression_entries
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (PARTITION BY normalized_email ORDER BY since ASC, id ASC) AS rn
           FROM suppression_entries
          WHERE scope = 'deliverability'
     ) dup
      WHERE dup.rn > 1
 );

DROP INDEX ix_supp_marketing_org_mem;
DROP INDEX ix_supp_deliverability_email;

CREATE UNIQUE INDEX ix_supp_marketing_org_mem ON suppression_entries (scope, org_id, membership_id);
CREATE UNIQUE INDEX ix_supp_deliverability_email ON suppression_entries (scope, normalized_email);
