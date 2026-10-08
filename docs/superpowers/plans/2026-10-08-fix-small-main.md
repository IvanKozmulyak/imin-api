# Four small src/main fixes

## Goal

1. A cross-org `POST /api/v1/ai/events/concept/regenerate` is a 404 that does not consume the caller's image quota.
   Today `ConceptController.java:54` meters before `ConceptStudioService.java:103` checks the org. The order becomes
   ownership check, then metering, then the paid pipeline. The 429 (quota) and 502 (upstream, still counted)
   behaviour stays the same.
   The same card also covers two sibling defects that metered before refusing.
   - `create` with another org's `eventId` spent quota before the 404 (`ConceptController.java:38`, then
     `ConceptStudioService.resolveDjPhotoFromEvent`).
   - An unknown `vibeId` spent quota before its 400, which was thrown inside `run()`, on both `create` and
     `regenerate`.
   Both checks now run in the controller before metering: `requireOwnedEvent` and `requireKnownVibe` on create,
   `requireKnownVibe(prior.getRequestVibeId())` on regenerate. `run()` keeps its own vibe check for direct service
   callers.
2. Drop `status IN (ready, empty)` from `PortraitResearchStore.dueForRefresh` (`PortraitResearchStore.java:122`).
   It duplicates `generated_at < ?`: the only writer that sets `pending` is the `touch` INSERT
   (`PortraitResearchStore.java:70-72`), which leaves `generated_at` NULL. `saveReady` (`:80`) and `saveEmpty`
   (`:94`) set ready/empty with a non-null `generated_at`. `touch` UPDATE (`:65`) and `markRefreshAttempted` (`:110`)
   never touch status. No other code in `src/main` writes `audience_portraits`, and no migration after V160 does.
   `NULL < ?` is never true, so pending rows stay excluded.
3. `GoogleWalletPassService` javadoc (`GoogleWalletPassService.java:53-57` in the file header) cites a deleted test,
   `GoogleWalletEndpointTest#theSaveLinkPathRunsWithNoDatabaseTransactionOpen`. Reword it so it cites only
   `GoogleWalletProvisionerTest`.
4. `MetaGraphConfig` hard-codes its 5 s connect / 10 s read timeouts (`MetaGraphConfig.java:27-28,39`), so
   `MetaGraphConfigTest` waits out a real 10 s. Make both injectable as `imin.meta.connect-timeout-millis` /
   `imin.meta.read-timeout-millis`, with defaults of `5000` / `10000` (millis, because `@Value` Duration binding needs a conversion service the runner lacks), so prod behaviour stays identical (neither key is set in
   `application.yaml`). The test sets a short read timeout through `ApplicationContextRunner.withPropertyValues`.

## Affected files

- `src/main/java/com/imin/iminapi/controller/ai/ConceptController.java`
- `src/main/java/com/imin/iminapi/service/ai/ConceptStudioService.java`: a public `ownedConcept(p, id)` plus a
  `regenerate(p, GeneratedEvent prior, lock)` overload; the UUID overload delegates to them.
- `src/main/java/com/imin/iminapi/audienceplan/service/PortraitResearchStore.java`
- `src/main/java/com/imin/iminapi/service/ticket/google/GoogleWalletPassService.java` (javadoc only)
- `src/main/java/com/imin/iminapi/config/MetaGraphConfig.java`
- `src/test/java/com/imin/iminapi/controller/ai/ConceptControllerTest.java`
- `src/test/java/com/imin/iminapi/config/MetaGraphConfigTest.java`

## Test impact

- Card 1, integration (org scoping + money/quota): extend the existing
  `regenerating_another_orgs_concept_is_a_404_without_a_paid_call` to also assert that the caller's `image` usage is
  0 after the 404. One branch gets one test. Two siblings are added:
  `creating_against_another_orgs_event_is_a_404_without_a_paid_call_or_spent_quota`, and a parameterized
  `an_unknown_vibeId_is_FIELD_INVALID_without_a_paid_call_or_spent_quota` covering create and regenerate. Each must be
  red on the code before its fix. It must be red on the unfixed code. The 502-still-counts test stays
  as it is.
- Card 2: no new test. A refactor with no behaviour change; `PortraitResearchStoreIntegrationTest` must stay green.
- Card 3: comment only.
- Card 4, config check: `ApplicationContextRunner` with `MetaGraphConfig` and `imin.meta.read-timeout-millis=300`. It
  asserts that a socket which never answers fails within a 3 s preemptive bound. It must be red on the unfixed code,
  because the property is ignored and 10 s applies. Class runtime is measured before and after.
- No new Spring context, no new fakes.

## Risks

- Card 1: a second concept read per regenerate is avoided because the controller passes the loaded entity on.
  Metering still happens before any paid call. A concept deleted between the check and the run has no new failure
  mode: the row is already loaded. On `create` with an `eventId`, the event is now read twice, once by the pre-check
  and once by the DJ-photo resolve. Both are cheap indexed reads.
- Card 4: the properties are plain millis longs, so they bind with or without a conversion service. A typo in a
  default would change the prod timeouts, so the defaults are exactly the old constants (5000 / 10000).

## Definition of done

- The four cards are applied as above. The card 1 and card 4 tests are red on base and green after the fix.
- `ConceptControllerTest`, `ConceptStudioServiceTest`, `PortraitResearchStoreIntegrationTest`, `MetaGraphConfigTest`
  and `SpringContextGuardTest` are green.
- The full `./mvnw test` is green with no skipped Testcontainers tests.

## Review rounds
round 1 → PASS (LOW fixed by orchestrator: org check inside the public regenerate(prior) overload as defence in depth).
