# Test-suite wall-time hotspots

## Goal

Cut the wall time of `./mvnw test` well below the 549 s pre-migration baseline, test code only. Every assertion,
row and constraint name stays; no new Spring context; `src/main` and `pom.xml` untouched.

## Diagnosis (measured, not guessed)

- Profiled a full idle run with JFR on the fork (wait events on `main`) and Postgres `log_min_duration_statement`
  plus `log_lock_waits`. Postgres itself is not slow: 39 statements over 150 ms in the whole run, all of them
  deliberate lock waits in race tests.
- The 850 s reference run was taken while other sessions ran their own suites (their Postgres containers were
  visible in `docker ps` during my runs too). Run-to-run variance is large: the same class took 3 s idle and
  74–129 s while another suite ran. Classes that do thousands of sequential round trips suffer most.
- Hotspots by cause:
  - Migration tests: one `CREATE DATABASE` plus a full Flyway migrate per test row (73 in `DateCheckMigrationTest`),
    and a new physical JDBC login (SCRAM) per statement, because `DriverManagerDataSource` never pools.
  - Audience-plan tests: `setUp` saved 345 consumers and 345 memberships one JPA `save` (one commit) each;
    `tearDown` deleted consumers one statement at a time.
  - Race tests: fixed `Thread.sleep(500)` / `sleep(300)` before asserting a writer is still blocked.
  - `FanFeatureTriggerEventsTest`: a 1 s negative watch after a drain that already proves completion.
  - `ComplaintRateBreakerTest`: 7,005 provider events, each `save` a merge (select + insert) and a commit.
  - `SeedDemoEventsSqlPostgresTest` is not slow: it is the first Spring class, so the one context boot is billed to it.
  - `MetaGraphConfigTest` (10 s) waits out the client's real read timeout, a constant in `src/main` — out of scope.

## Affected files

- `support/SharedPostgres.java` — `migratedDatabase(prefix, target)` copies a per-version template
  (`CREATE DATABASE … TEMPLATE`), each template migrated once per JVM from the nearest lower one; `buildTemplates`
  builds them ascending; `copyOf`; `drop` (`WITH (FORCE)`). Databases hold one autocommit connection.
- `support/PgLocks.java` (new) — `awaitLockWait(jdbc, table)` / `awaitLockWaits(jdbc, regex, n)` poll
  `pg_stat_activity` for `wait_event_type = 'Lock'`; fail after 30 s.
- `migration/DateCheckMigrationTest`, `ExperimentForeignKeysMigrationTest`, `OrderSettlementMigrationTest`,
  `V83BuyerIdentityMigrationTest`, `VenueCoordsConstraintTest` — template databases, dropped after use.
- `audienceplan/controller/AudiencePlanControllerIntegrationTest`, `AudiencePlanInvitationIntegrationTest`,
  `AudiencePlanInviteOnPublishIntegrationTest`, `AudiencePlanListIntegrationTest`, `TimingArmSchedulingTest`,
  `audienceplan/service/SummarizerFlowTest` — consumer deletes batched; member seeding in one transaction
  (Invitation, InviteOnPublish, TimingArm); plan-lock races wait on `pg_stat_activity` (Controller, List).
- `audienceplan/service/FanFeatureTriggerEventsTest` — rollback watch 1 s → 100 ms after the drain.
- `marketing/ComplaintRateBreakerTest` — bulk events committed in one transaction.
- `service/event/EventStatusRevertPostgresTest`, `TierInventoryRacePostgresTest`, `UnpublishCheckoutRacePostgresTest`,
  `EventSoftDeleteRaceIntegrationTest` — `sleep(500)` → `PgLocks.awaitLockWait`.

## Why each change keeps the assertion

- Template copy = byte copy of a database Flyway left at exactly that version, so a test sees the same schema and
  `flyway_schema_history`; upgrade tests still seed at version N and run the real `migrate()` to latest. Each row
  still gets its own database. One connection per database with autocommit on: every statement commits as before,
  and a rejected insert does not poison the next one (checked: autocommit is still on after Flyway's migrate).
- One transaction around the JPA saves writes the same entities with the same Java-side defaults and `@PrePersist`;
  no listener reacts to these saves. Batched `delete … where consumer_id = ?` sends the same statements in one trip.
- Lock waits: "still not done after 500 ms" becomes "Postgres reports a statement on that table queued on a lock",
  then the same `isDone()` assertion. Stronger: a writer that is merely slow no longer passes.
- Fan-feature rollback watch: `FanFeatureProjector.dispatch` submits to the live executor on the committing thread,
  so `AsyncDrain` already waited for any recompute the transaction triggered; the watch was margin only.

## Test impact (seconds, surefire "Time elapsed", same machine)

Full runs: the reference log (another suite running), then base `ab2560f2` and this change, each a full
`./mvnw test` on an otherwise idle Docker (container count logged every 5 s).

| class | reference | base idle | after |
|---|---|---|---|
| migration.DateCheckMigrationTest | 228.8 | 47.4 | 3.9 |
| audienceplan…AudiencePlanControllerIntegrationTest | 89.9 | 3.3 | 2.1 |
| audienceplan…AudiencePlanInvitationIntegrationTest | 68.0 | 82.4 | 6.6 |
| audienceplan…AudiencePlanInviteOnPublishIntegrationTest | 68.2 | 10.7 | 7.3 |
| audienceplan…AudiencePlanListIntegrationTest | 11.8 | 47.2 | 2.2 |
| audienceplan…SummarizerFlowTest | 29.9 | 2.3 | 2.1 |
| audienceplan…TimingArmSchedulingTest | 17.9 | 2.1 | 1.6 |
| audienceplan…FanFeatureTriggerEventsTest | 3.6 | 3.3 | 0.5 |
| repository.SeedDemoEventsSqlPostgresTest (context boot) | 24.1 | 8.7 | 8.9 |
| migration.VenueCoordsConstraintTest | 22.6 | 4.5 | 0.3 |
| migration.OrderSettlementMigrationTest | 21.0 | 4.2 | 0.5 |
| migration.ExperimentForeignKeysMigrationTest | 6.8 | 1.4 | 0.7 |
| migration.V83BuyerIdentityMigrationTest | 3.4 | 0.7 | 0.0 |
| marketing.ComplaintRateBreakerTest | 13.9 | 2.4 | 1.6 |
| service.event.EventStatusRevertPostgresTest | 9.8 | 9.7 | 0.3 |
| service.event.TierInventoryRacePostgresTest | 5.8 | 5.4 | 0.2 |
| service.event.UnpublishCheckoutRacePostgresTest | 1.7 | 1.6 | 0.1 |
| service.event.EventSoftDeleteRaceIntegrationTest | 1.1 | 1.1 | 0.0 |
| **full `./mvnw test` (real / user CPU)** | **850 / 278** | **321 / 160** | **134 / 172** |

Red-proofs (scratch copy, `src/main` mutated, worktree untouched):
- `lockActiveForWrite` made a no-op → EventStatusRevert 16/20 red, EventSoftDeleteRace 1/3 red (every changed site
  that relies on that lock).
- tier `FOR UPDATE` removed → TierInventoryRace 10/13, UnpublishCheckoutRace 3/3 red.
- `lockCurrent` without `FOR UPDATE` → Controller recompute/post race red; `lockFirstPlan` no-op → List race red.
- fan-feature listeners `AFTER_COMMIT` → `AFTER_COMPLETION` → the three rollback rows red with the 100 ms watch.

## Risks

- `PgLocks` matches any lock wait whose statement names the table, in the test database. A stray background writer
  queued on the same table at that instant could satisfy the poll early; the `isDone()` assertion still follows,
  and the mutations above show the poll does not pass without the guarded lock.
- Templates are cached per JVM; a test must never connect to a template (only `SharedPostgres` does, while building).
- Wall time still swings 2–5× when another suite shares the machine; numbers above are only comparable pairwise.

## Definition of done

- `./mvnw test` green, 6,960 tests, 0 skipped, `SpringContextGuardTest` green.
- Every touched class measured before/after; each changed wait red-proofed by a mutation.
- `src/main`, `pom.xml` untouched; no new Spring context or context-changing annotation.

## Review rounds
round 1 → PASS (LOW fixed: PgLocks regex per guarded statement + description in failure, dead copyOf removed, template rebuild drops a half-built db first).
