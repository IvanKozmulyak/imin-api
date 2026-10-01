# Venue coordinates are set as a pair or not at all (api)
venue-coords-pair · main session

## Goal and scope
`ck_events_venue_coords_valid` (V80__event_venue_coordinates.sql:25-28) is
`(lat IS NULL AND lon IS NULL) OR (lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180)`.
For `(NULL, 13.44)` the second disjunct is NULL, so the whole CHECK is NULL and passes.
The migration's own comment says a lone coordinate must never persist. Fix the constraint.
Prod has 0 half-null rows (33 rows with coordinates, checked 2026-10-02), so the new
constraint validates without a data fix.

## Repos in ship order
1. `api` (imin-api, base master). Worktree `.claude/worktrees/venue-coords-pair`.

## Affected files
- New `src/main/resources/db/migration/V170__event_venue_coords_pair.sql`:
  `ALTER TABLE events DROP CONSTRAINT ck_events_venue_coords_valid;` then
  `ALTER TABLE events ADD CONSTRAINT ck_events_venue_coords_valid CHECK (
     (venue_latitude IS NULL AND venue_longitude IS NULL)
     OR (venue_latitude IS NOT NULL AND venue_longitude IS NOT NULL
         AND venue_latitude BETWEEN -90 AND 90 AND venue_longitude BETWEEN -180 AND 180));`
  1–2 line header comment saying why (a NULL CHECK passes). Must run on H2 PG-compat and Postgres.
- Tests: extend the existing coordinate test class that runs on Postgres
  (VenueGeocodingLostUpdateTest or a Postgres twin; pick whichever runs on Testcontainers
  Postgres, else add a small `VenueCoordsConstraintPostgresTest`), plus H2 if the default suite is H2:
  1. `(NULL, 13.44)` via JDBC update/insert → DataIntegrityViolation.
  2. `(52.5, NULL)` → violation.
  3. both NULL → ok; both in bounds → ok; lat 91 → violation (bounds kept).
  Prove 1 and 2 go red against the old constraint (temporarily revert V170 body).

## Verification commands
docker info; /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test (full). Skipped Testcontainers = red.

## Contract impact
none. ## i18n impact
none.

## Risks
Flyway out-of-order is on in prod; V170 must be the highest number at ship (check origin at rebase).
`updateVenueCoordinates(id, null, null)` and paired writes are the only writers, so no prod path writes a half pair.

## Live-test evidence

## Review rounds
- 2026-10-02 implement. Tests: `migration/VenueCoordsConstraint{Scenarios,H2Test,PostgresTest}` (Flyway to latest on a
  fresh DB, JDBC writes). Red proof: the tests were written and run before V170 existed (latest = V169, the old
  constraint). Result: H2 7 run / 3 failed, Postgres 7 run / 3 failed, all "Expecting code to raise a throwable" in
  `aLoneLongitude_isRejected`, `aLoneLatitude_isRejected` and `aLoneLongitude_isRejectedOnInsertToo`. The bounds and
  both-null cases passed. With V170: 14/14 green. Full gate baseline 6970/0F/3S, after 6984/0F/3S. The skips are the same
  3 as the baseline (2 H2-only assumptions in AudiencePlanInvitationWebTest, 1 @Disabled in SimulatorEvalTest), and no
  Testcontainers class was skipped.
