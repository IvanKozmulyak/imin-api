# Drop trivial unit tests and dead code

## Goal
Delete three unit tests that guard nothing a user would notice, and remove code with no caller in `src/main`
or `src/test`, without changing behaviour.

## Affected files
- delete `src/test/java/com/imin/iminapi/security/{PasswordHasherTest,RateLimiterTest,TokenServiceTest}.java`
- delete `src/main/java/com/imin/iminapi/predictor/model/OrgConnector.java`,
  `src/main/java/com/imin/iminapi/predictor/repository/OrgConnectorRepository.java` (table `org_connector` stays;
  no migration; Hibernate `ddl-auto` is unset in main yaml, which is `none` for Postgres, and `none` in test yaml)
- `service/event/EventService.java`: drop the unused 9-arg and 10-arg constructors
- `stripe/StripeProperties.java`: drop `getSecretKey()` (binding uses the setter; the client reads `trimmedSecretKey()`)
- `service/ticket/google/GoogleWalletProperties.java`: drop `isEnabled()` (gate reads the field via `fullyConfigured()`)
- `marketing/dto/CampaignSummary.java`: drop the 1- and 2-arg `from` overloads
- `audienceplan/service/PlanService.java`: `lockFirstPlan` always takes the advisory lock; drop the `postgres()`
  probe and the `DataSource` dependency
- `audienceplan/repository/AudiencePlanRepository.java`: drop `lockEventRow` (only the H2 branch called it)
- `audienceplan/service/ConsentGate.java`: drop the 16-raw-bytes UUID branch (H2 only); `CandidateLoader.java`: drop
  the Clob branch (H2 only). The Postgres path (UUID / String) is unchanged.
- Javadoc and comments under `src/main/java` that gave H2 as a reason: reworded to the Postgres reason or the H2
  clause dropped. Comments only, no query changes. Migration SQL is never edited.
- `src/test/.../service/auth/PasswordResetServiceTest.java`: one assertion in the existing issue-token test pins the
  reset token's entropy (`[A-Za-z0-9_-]{43,}`) and the stored 64-hex hash, the cover lost with `TokenServiceTest`.

Kept: `PredictionLedgerService` null-JSON defaults. Both columns are `NOT NULL` (V70:28-29) and the javadoc
promises null means `{}`; removing them turns a future null into a 500.

## Test impact
No new test methods; one extended assertion in `PasswordResetServiceTest`, red-proven in a scratch copy with
`TokenService` issuing 8 random bytes. `AudiencePlanListIntegrationTest` first-plan race tests keep covering the lock; red proof: in a
scratch copy, `lockFirstPlan` as a no-op turns `firstPlan_aRefreshAndAGetRacing_...` red.

## Risks
- Setter-only `@ConfigurationProperties` binding: verified by binding both classes through Spring's `Binder`
  in a scratch check.
- Constructor removal could break a reflective or Spring caller: grep over `src/main` and `src/test` shows none.

## Definition of done
`./mvnw -q test-compile` clean; targeted tests green; full `./mvnw test` green with 0 skipped.

## Review rounds
round 1 → PASS (LOW fixed by orchestrator: BuyerAccountEmail marker reason, "No ON CONFLICT" clauses dropped, blank line).
