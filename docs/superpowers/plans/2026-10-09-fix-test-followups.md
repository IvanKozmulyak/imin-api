# Test follow-ups (2026-10-09)

## Goal
1. `FanFeatureTriggerEventsTest.backfillCompleted_recomputesOnTheRecomputePool_notTheCallersThread` read `ThreadPoolExecutor.getTaskCount()`, which is approximate, and failed once under load. Hold the pool's single worker and assert the hand-off waits in the queue instead.
2. `GoogleWalletPassService.saveUrl` inside a Spring transaction: no test. The service is deliberately not `@Transactional` (it would hold a connection across outbound HTTPS calls); `GoogleWalletProvisionerTest` already owns the "never inside a transaction" rule.
3. Delete the test-only `ConceptStudioService.regenerate(AuthPrincipal, UUID, List)`; tests go through `ownedConcept` + the `prior` overload. The no-leak 404 stays owned by `ConceptControllerTest`.

## Affected files
- `src/test/java/com/imin/iminapi/audienceplan/service/FanFeatureTriggerEventsTest.java`
- `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java`
- `src/test/java/com/imin/iminapi/service/ai/ConceptStudioServiceTest.java`

## Test impact
Item 1 red-proven by removing `@Async(RECOMPUTE)` from `FanFeatureRecomputeJob.onBackfillCompleted` (queue-size assertion fails); 10 consecutive green runs.

## Risks
The pool test assumes the recompute pool has one core thread, so a second task queues rather than starting a thread.

## Definition of done
Full `./mvnw test` green, 0 skipped.

## Review rounds
round 1 → PASS (orchestrator review, test-only + dead overload removal).
