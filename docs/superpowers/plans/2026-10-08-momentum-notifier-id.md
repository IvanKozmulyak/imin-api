# Momentum notifier: write the in-app notification, never throw

## Goal and scope

`MomentumNotifier.notifyOwner` is documented as "never throws" (`MomentumNotifier.java:36` on base), but on master it
never writes the organizer's "New campaign suggestion" notification and always throws:

- `MomentumNotifier.java:47` set an id on `Notification`, whose id is `@GeneratedValue(strategy = UUID)`
  (`model/Notification.java:17-19`). `save` saw an id, called `merge`, and Hibernate failed the stale row lookup. That
  marked the `REQUIRES_NEW` transaction rollback-only.
- The catch at `:54-58` swallowed the exception, but the commit in the `@Transactional` proxy then threw
  `UnexpectedRollbackException` out of `notifyOwner`. `MomentumEvaluator.evaluateOne` threw at `:206`, and `runOnce`
  logged ERROR for each firing event (`MomentumEvaluator.java:100-104`).

Removing `setId` alone is not enough. With `persist`, the INSERT runs at the commit flush, and that flush happens after
the method body returns. It is outside the try, so any write failure (FK, constraint, trigger) still escapes. The
scratch run below shows a `JpaSystemException` from `JpaTransactionManager.doCommit`. The fix moves the transaction
inside the try (`TransactionTemplate` with `PROPAGATION_REQUIRES_NEW`, same idiom as
`ConsentConfirmationMailer.java:62-63`), so both the rollback-only mark and a failed commit are caught.

Also in scope: migrate the legacy `MomentumEvaluatorTest` to `@IminIntegrationTest`. It hid the bug with a
`@MockitoBean NotificationRepository`. This is row 2d #9 of `docs/superpowers/plans/2026-10-07-p2-audience-marketing.md`
(workspace).

## Affected files

- `src/main/java/com/imin/iminapi/marketing/service/MomentumNotifier.java`: drop `setId`, replace the method-level
  `@Transactional(REQUIRES_NEW)` with a `TransactionTemplate` called inside the try.
- `src/test/java/com/imin/iminapi/marketing/MomentumEvaluatorTest.java`: deleted (moved).
- `src/test/java/com/imin/iminapi/marketing/service/MomentumEvaluatorTest.java`: migrated version. It goes from 16 to
  13 tests: the 4 layer duplicates are dropped per 2d #9 and `firedSuggestionNotifiesTheOrgOwnerInApp` is added.
- `src/test/resources/test-guard/legacy-spring-tests.txt`: one line removed.

## Test impact (red → green)

- `firedSuggestionNotifiesTheOrgOwnerInApp` (new): a fired suggestion leaves one unread notification for the owner,
  with a non-null id, kind `momentum_suggestion`, title `New campaign suggestion: launch push`, the draft's why line as
  the body and link `/marketing`. `evaluateOne` does not throw.
- `notificationFailureDoesNotRollBackSuggestion` (migrated): `PgFaults.failWrites(jdbc, "notifications", "user_id",
  ownerId)`. The evaluation does not throw, the suggestion stays and no notification row exists.

Red runs used scratch copies of `origin/master` with the new test class:

- Unfixed main: 8 of 13 errors, every firing row included, with
  `UnexpectedRollbackException: Transaction silently rolled back because it has been marked as rollback-only` (from
  `TransactionAspectSupport.commitTransactionAfterReturning`).
- `setId` removed, catch unchanged: 1 of 13 errors. `notificationFailureDoesNotRollBackSuggestion` fails with
  `JpaSystemException: could not execute statement [ERROR: injected test fault]` at `JpaTransactionManager.doCommit`.
- Fixed worktree: `MomentumEvaluatorTest` 13/13, `MomentumEvaluatorPlanTargetTest` 17/17, `SpringContextGuardTest`
  27/27. Full `./mvnw test`: 7175 run, 0 failures, 0 errors, 1 skipped (the `@Disabled` row in
  `SimulatorEvalTest`, not a Testcontainers test).

## Risks

- Prod starts writing one in-app notification per fired suggestion. That is the intended behaviour, and none have been
  written so far.
- `MomentumEvaluatorPlanTargetTest` mocks `MomentumNotifier` as a class, so the constructor change does not affect it.
- Firing rows call `evaluateOne` on the AOP target. That depends on `MomentumEvaluator` having no method-level advice
  there (P2 plan risk 8).

## Definition of done

- Full `./mvnw test` is green with no skipped Testcontainers tests.
- The allow-list only shrinks.
- `git diff --stat origin/master` lists only the files above.

Card: AudienceOrderProjector.onTicketsIssued (AudienceOrderProjector.java:68-91) has the same commit-flush-outside-catch weakness; the duplicate-insert race recovery (:174-181) cannot work on Postgres (aborted tx), so that order's membership is lost. Fix: TransactionTemplate inside try + separate REQUIRES_NEW/savepoint for the retry.
Live-test after deploy: first Momentum firing writes a notifications row kind=momentum_suggestion; runOnce stops logging rollback-only errors.
