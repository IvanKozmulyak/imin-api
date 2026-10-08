# Fix test tooling

## Goal
Close four test-tooling gaps: a private Postgres container, a guard blind spot, fixed seed addresses, an early-returning drain.

## Affected files
All under `src/test/java/com/imin/iminapi/`:
- `audience/ConsentExportPostgresTest.java` (moves to `SharedPostgres.migratedDatabase`, drops its database after)
- `support/SpringContextGuard.java`, `support/SpringContextGuardTest.java` (bare `@ExtendWith(SpringExtension.class)` is LEGACY)
- `marketing/MomentumTestSupport.java` (unique buyer addresses; no caller asserts the literals)
- `support/AsyncDrain.java`, new `support/AsyncDrainTest.java`

## Test impact
- New guard case `LegacyBareExtension`, red with the check removed.
- New `AsyncDrainTest` reproduces a worker that dequeued a task but has not locked it; red against the old condition.
- `FanFeatureTriggerEventsTest` poll kept: the settle re-check is a heuristic, not a proof.
- Programmatic contexts inside a test method cannot be found by annotation scanning; left alone.

## Risks
- Drain adds a 20 ms settle per call.
- `ConsentExportPostgresTest` now needs the shared container (Docker required, no silent skip).

## Definition of done
Touched classes and `SpringContextGuardTest` green, full `./mvnw test` green with no skipped container tests.

## Review rounds
round 1 → PASS (LOW fixed: AsyncDrainTest latch instead of sleep). Follow-up idea: TaskDecorator in-flight counter for an exact drain.
