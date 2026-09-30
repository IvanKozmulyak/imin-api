# M1-3 Durable predictor job runner
m1-3-predictor-job-runner · api · depends on M1-2 (V162) being on master

## Goal and scope
Durable queue on `predictor_job` so later tasks (M1-8 research, M1-9 re-check, M2 syncs) enqueue work that survives restarts. In scope: `PredictorJobHandler` (`String kind(); void run(PredictorJob job)`), `PredictorJobService.enqueue(String kind, Object payload) -> UUID` + state transitions, `PredictorJobRunner` (5 s poll), claim/requeue/finish queries, poll flag. Out: concrete handlers, migrating `predictorScoreExecutor`, dedicated executor (added with the first long handler), admin/API. Adapt JPQL to M1-2's actual `PredictorJob` field names if they differ.
Decisions (main session): an expired lease counts as an attempt (attempts incremented at claim); 5 s poll, up to 5 jobs per tick, sequential on the scheduler thread for now.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `imin-api/.claude/worktrees/m1-3-predictor-job-runner` | `./mvnw test` |

## Affected files (per repo)
Create `predictor/jobs/PredictorJobHandler.java`, `predictor/jobs/PredictorJobService.java`, `predictor/jobs/PredictorJobRunner.java`; modify `predictor/repository/PredictorJobRepository.java`, `predictor/config/PredictorProperties.java` (`jobsPollEnabled`, default true), `src/main/resources/application.yaml` (`jobs-poll-enabled: ${PREDICTOR_JOBS_POLL_ENABLED:true}` under `imin.predictor`), `src/test/resources/application.yaml` (`imin.predictor.jobs-poll-enabled: false`); tests `src/test/java/com/imin/iminapi/predictor/PredictorJobRunnerTest.java`, `PredictorJobRunnerPostgresTest.java`; this plan.

## Ordered steps
1. Baseline `./mvnw test`.
2. Repository (exported = false), JPQL `@Modifying(clearAutomatically = true, flushAutomatically = true)`, only non-null params (pattern `PublishInviteRepository.claim/reclaim`):
   - `List<UUID> findClaimable(Instant now, Pageable)`: status='queued' and runAfter <= now, order by runAfter.
   - `int claim(UUID id, Instant now, Instant lockedUntil)`: set status='running', lockedUntil, attempts=attempts+1, updatedAt where id and status='queued' and runAfter <= now.
   - `int requeueExpired(Instant now, int maxAttempts)`: running + lockedUntil < now + attempts < max → queued, lockedUntil null, runAfter now.
   - `int failExpired(Instant now, int maxAttempts, String error)`: same with attempts >= max → failed, lastError, lockedUntil null.
   - `int finish(UUID id, Instant lockedUntil, String status, Instant runAfter, String lastError, Instant now)`: where id, status='running', lockedUntil = :lockedUntil (lease fence). lastError "" on success.
3. `PredictorJobService` (repo, `Clock`, `TransactionTemplate`): `@Transactional enqueue` joins caller tx; blank kind → IAE; payload via `PredictorJson.MAPPER` (null → "{}", JsonProcessingException → IAE); saves queued/0/runAfter=now. Constants MAX_ATTEMPTS=3, LOCK=10 min, MAX_ERROR=2000; `static Duration backoff(int attempts) = Duration.ofMinutes(1L << attempts)`; `static String truncate(String)`; `requeueExpired()` (requeue + failExpired with "lock expired"), `claim(id)` → `Optional<Instant>` lease, `markDone`, `markRetryOrFailed` (attempts < 3 → queued with runAfter now+backoff(attempts), else failed), `markReleasedOrFailed` (attempts < 3 → queued with runAfter now+RELEASE_DELAY (5 min), else failed; a release consumes an attempt, accepted because rolling deploys are short); each in its own tx; `clock.instant().truncatedTo(MICROS)`.
4. `PredictorJobRunner` (`@Component`): `@Autowired` production ctor `(PredictorJobService, PredictorJobRepository, ObjectProvider<PredictorJobHandler>, PredictorProperties)` using `orderedStream().toList()`; package-private ctor with a List for tests; map kind→handler, duplicate kind → `IllegalStateException`. `@Scheduled(fixedDelay = 5_000, initialDelay = 30_000) poll()`: return if disabled; `try { tick(); } catch (Exception e) { log.error(... LogSafe.redact) }`. No ShedLock (CAS is the guard). `tick()`: requeueExpired; findClaimable(now, 5); per id claim (lost → skip); load; no handler → markReleasedOrFailed("unknown kind: " + kind) (an older instance in a rolling deploy may claim a kind only the new build knows), WARN "released" only when the fenced finish updated the row; else run then markDone; on exception WARN with the throwable (stack trace, redacted message) then `try { markRetryOrFailed(...) } catch (Exception inner) { log both }`; lastError = truncate(simpleName + ": " + LogSafe.redact(msg)); ERROR "failed after N attempts" on terminal failure (retry or unknown kind); fenced finish of 0 rows → WARN "lease lost". Claim commits before the handler runs.
5. 1–2 line javadoc on the handler: must be idempotent (at-least-once after a lease expires).
6. Tests; `./mvnw test`; read the diff.

## Verification commands
`./mvnw test` (Postgres test needs Docker; say if skipped)

## Test impact
Reproduction test: n/a. `PredictorJobRunnerTest` (H2, `@SpringBootTest @Import(TestRateLimitConfig.class)`, real repo/service, a mutable clock, not @Transactional, deleteAll in @BeforeEach, runner via List ctor): `claimIsExclusiveAcrossTwoRunners` (A's handler calls B.tick(); handler once; B claims 0; row done) + `claimTwiceSecondIsEmpty`; `expiredLockIsRequeued` (+ attempts=3 row → failed "lock expired"); `thirdFailureMarksFailed` (incl. 5000-char message cut to 2000); `successMarksDone` (payload round-trips); `unknownKindIsReleasedThenFailsAfterThreeClaims` (first tick: queued, runAfter +5 min, one WARN "released", no ERROR; third claim: failed with one ERROR); `thirdFailureMarksFailed` also asserts the WARN carries the exception and one ERROR on the terminal failure; `leaseLostIsWarned`; `retryBookkeepingFailureKeepsOriginalError`; `backoffDoublesPerAttempt` (+2 min then +4 min; not claimable at +1:59); `duplicateKindFailsFast`; `pollDisabledDoesNothing` and `tickFailureIsLoggedNotThrown` (unit, service mocked); `enqueueNullPayloadStoresEmptyObject`; `enqueueRejectsBlankKind`. `PredictorJobRunnerPostgresTest` (`@Testcontainers(disabledWithoutDocker = true)`, like `AudiencePostgresTest`) repeats the exclusive-claim and expired-lock tests on PG 17, plus `concurrentClaimIsExclusive` (two threads race the claim UPDATE on one row; exactly one wins).

## Live-test
Prod boots; api-docs unchanged; no `PredictorJobRunner` ERROR in logs.

## Contract impact
none

## i18n impact
none

## Blast radius
Shared scheduler pool of 4: one cheap indexed query every 5 s; no handlers yet. No money/auth/contract.

## Risks
Long future handlers would hold a scheduler thread (dedicated executor when M2-4 lands); lease (10 min) must exceed handler runtime. H2 vs PG concurrent UPDATE semantics → Postgres test.

## Definition of done
Classes and queries as specified; tests pass; `./mvnw test` green; poll off in test yaml; key with env var in main yaml; nothing committed by the worker.

## Live-test evidence

## Review rounds
- round 1 → PASS (2 MEDIUM fixed: unknown kind released not failed; stack trace + ERROR on terminal failure; LOW concurrent PG test)
- round 2 → PASS (3 LOW fixed)
