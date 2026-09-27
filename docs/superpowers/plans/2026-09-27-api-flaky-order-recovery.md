# Make OrderRecoveryTransactionBoundaryTest deterministic

## Goal and scope

`OrderRecoveryTransactionBoundaryTest.the_resend_call_does_not_hold_a_pooled_connection` fails
intermittently in full-suite runs ("the outbound Resend send must happen outside any transaction",
`Expecting AtomicBoolean(true)`), and passes alone. Make it deterministic without weakening what it
asserts. Test-only change; production code is correct.

## Affected files

- `src/test/java/com/imin/iminapi/service/ticket/OrderRecoveryTransactionBoundaryTest.java`

## Root cause

The failure is not a transaction leak. `Expecting AtomicBoolean(true)` is the initial value, so
`email.send` was never called: `findRecentForRecovery` returned the mock default (empty list), and
the service returned at "no orders found".

Why the stub went missing: the test stubbed the `@MockitoBean OrderRepository` inside the test
method, while a background thread was calling the same mock. On context startup
`AudienceBackfillJob.onStartup` (ApplicationReadyEvent) publishes `AudienceBackfillCompleted`, and
`FanFeatureRecomputeJob.onBackfillCompleted` (`@Async`, fan-feature-recompute pool) walks every
membership in the DB and calls `orders.findByOrgIdAndNormalizedEmailIn(...)` once per org. Mockito
stubbing is not thread-safe: `when(mock.x()).thenReturn(v)` binds `v` to the mock's most recent
invocation, so a concurrent `findByOrgIdAndNormalizedEmailIn` call landing between
`orders.findRecentForRecovery(...)` and `.thenReturn(...)` steals the stub (both return `List`, so
no WrongTypeOfReturnValue is raised).

Why only in the full suite: all contexts share the one in-memory H2 database `imin`.
- Alone, there are no memberships, so the recompute makes no `OrderRepository` calls.
- In the full suite, earlier classes leave memberships behind. The recompute only runs when its
  ShedLock rows (`audience_backfill` and `fan_feature_recompute`, `lockAtLeastFor = PT1M`) have
  expired, i.e. when more than a minute has passed since another context started. On a loaded
  machine that is common.

Evidence (temporary diagnostics, since reverted): with 3000 seeded memberships and expired locks,
the mock received about 900 `findByOrgIdAndNormalizedEmailIn` calls during the test. In a
300-iteration stub-then-recover loop, 11 iterations never sent. That is the reported failure.

Fix: stub before any background thread can see the mock. `@TestBean` factories build and stub the
`OrderRepository` and `EmailService` mocks during context refresh, before
ApplicationReadyEvent. The email answer records every call with its recipient. The test asserts
that exactly one send reached the buyer (so "never sent" gets its own failure message) and that
this send ran outside a transaction (the original assertion, unchanged).

## Verification commands

- `./mvnw -q test -Dtest=OrderRecoveryTransactionBoundaryTest` ×5
- `./mvnw -q test -Dtest='OrderRecoveryTransactionBoundaryTest,OrderRecoveryServiceTest,PaidCheckoutDuplicateKeyTest,AudiencePersistenceTest,AudienceDsarTest,DoorOptInServiceTest'`
  (same package, the other `@MockitoBean OrderRepository` class, and membership writers)
- `./mvnw test` once at the end

## Test impact

The test keeps its original assertion and adds one precondition: exactly one send to the buyer.
The race is removed by construction, not by timing.

## Risks

- `@TestBean` mocks are not reset between methods. The class has one method. Any new method must
  clear `SENDS` first.
- Other `@MockitoBean OrderRepository` tests that stub in-method (`PaidCheckoutDuplicateKeyTest`)
  are open to the same race. Reported, not changed.

## Review rounds

- none yet
