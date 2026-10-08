# Audience projection: race-safe consumer and membership get-or-create, failures caught inside the transaction

## Goal

`AudienceOrderProjector.onTicketsIssued` (`AudienceOrderProjector.java:71-91` on base) is
`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + method-level `@Transactional(REQUIRES_NEW)`, with its try/catch
inside the body. It has these defects:

- **Lost membership on the consumer race.** When two orders for the same new email are projected at once, both miss
  at `:169`, both `saveAndFlush` at `:177`, and the loser gets a `DataIntegrityViolationException` on
  `ux_consumers_normalized_email` (`V47__audience_consumers.sql:10`). The catch at `:178-181` re-reads in the same
  transaction, which Postgres has aborted ("current transaction is aborted"). The loser's membership is never written.
  - `SmsConsentService.upsertMembership` (`SmsConsentService.java:106-120` on base) has the same re-read. There the
    loser's SMS opt-in request fails, and its exception message carries the email.
- **Lost membership update on the same-org race.** When two orders from one buyer to one org are projected at once,
  both miss the membership at `:185` and both create one. The loser hits `ux_memberships_org_consumer`
  (`V48__audience_memberships.sql:63`) at commit, and its transaction rolls back. What it loses:
  - its totals;
  - its checkout email consent (`consentService.capture` at `:221`). The nightly backfill does not restore that
    consent: it passes `emailOptIn=false` (`AudienceBackfillJob.java:109`).
- **Lost update on an existing member.** `Membership` has no `@Version` or `@DynamicUpdate`, so each projection's
  commit writes the whole row. Two concurrent projections of one existing member both load it, and the later commit
  writes back the other's consent, phone and SMS state as they were when it loaded the row.
- **Failures escape the catch.** The membership is written at the commit flush (`membershipRepo.save(m)` at `:209`
  issues no statement, because ids are assigned in memory). That commit, or an `UnexpectedRollbackException`, happens
  in the proxy after the body returns, outside the try at `:75-90`. The error log at `:89` also passes the raw message
  and throwable, and those can carry the buyer's email.

Fix:

- **Consumer** get-or-create becomes `insert ... on conflict (normalized_email) do nothing`, then a re-read.
  - `AudienceOrderProjector.getOrCreateConsumer` is shared by the projector and `SmsConsentService`.
  - Postgres waits on a concurrent uncommitted insert and then does nothing, so the transaction stays usable.
  - The fields stay the same (id, normalized email, display name = raw email, `created_at` = JVM now), and an
    existing consumer is never changed.
- **Membership** get-or-create becomes `SELECT ... FOR UPDATE`. When no row is found, it inserts one with
  `on conflict (org_id, consumer_id) do nothing`, then repeats the `SELECT ... FOR UPDATE`.
  - `AudienceOrderProjector.lockOrCreateMembership` is shared by the same two callers.
  - The inserted row carries the entity's defaults: display name, `first_touch_src` = `organic`, `genres`/`tags` =
    `[]` as the converter writes them, and the DB defaults the entity mirrors.
  - The row lock serializes concurrent projections of one member. The second waits, then reads the committed row.
- **Why lock and recompute instead of retry:** totals are recomputed from source rows, not incremented
  (`MembershipProjector.recompute`, `MembershipProjector.java:44-139`, sets every aggregate from
  `findByOrgIdAndNormalizedEmail`). Both orders are committed before their events fire. So the projection that runs
  second counts each order exactly once and cannot double-count, and its consent capture still runs. A retry would
  fix the insert race only. It would leave the lost update on an existing member in place.
- `onTicketsIssued` drops the method-level `@Transactional`. It runs `project(orderId)` in a `TransactionTemplate`
  `PROPAGATION_REQUIRES_NEW` inside the try, the same idiom as `MomentumNotifier.java:43-48`. A rollback-only mark or
  a failed commit is then caught and logged once at ERROR with `LogSafe.redact`, without the throwable.
- `MembershipProjected` is still published inside the transaction. Its listener (`FanFeatureProjector.java:118-123`)
  is AFTER_COMMIT, so it still follows the membership commit.

Writers audit:

- `consumers`: only the two get-or-create paths above write it. No path updates a consumer row, so DO NOTHING loses
  no write.
- `memberships` get-or-create: `AudienceOrderProjector.upsertMembership` (shared by `AudienceBackfillJob:109`,
  `AudienceRedeemProjector:63`, `AudienceImportService:310`, `DoorOptInService:172` and `SurveyService:207`) and
  `SmsConsentService.upsertMembership`. Both now take the row lock.
- Other membership writers (`ConsentService`, fan-feature writers, DSAR) are unchanged. Fan-feature writers and
  `requestErase` already take the membership row lock (`MembershipRepository.lockByIdAndOrgId`).

### Review follow-ups (same card)

- **M1: the backfill's lock was released before its write.** The short `upsertMembership` overloads were not
  `@Transactional`, and they reached the transactional form through a self-call that bypasses the proxy. So
  `AudienceBackfillJob:109` ran each row in autocommit.
  - Its `FOR UPDATE` was released at once, and its `save` merged a stale copy over any unsubscribe committed in
    between.
  - Both short overloads are now `@Transactional` (REQUIRED). The backfill gets one transaction per row, and a
    failed row is still caught by its per-row catch.
  - Every other caller already runs in a transaction and simply joins it: Door and Survey (`@Transactional`),
    `AudienceRedeemProjector` (REQUIRES_NEW), and `AudienceImportService:310` (the 7-arg form).
  - None of them catches an exception from this call and carries on, so the rollback-only mark it can now set
    changes nothing for them.
- **M2: ConsentService wrote over concurrent changes.** `ConsentService.requireMembership` (`:322-324` on base) read
  the member without a lock. `capture`, `confirmPending` and `unsubscribe` all go through it. A capture racing a
  projection therefore wrote its stale whole row over the checkout phone and SMS opt-in.
  - `unsubscribe` only locked in its DATA_SUBJECT branch (`:237`), after it had already loaded the member, so it
    locked a stale copy.
  - Fix: `requireMembership` now flushes pending changes, locks the row (`lockByIdAndOrgId`), and calls
    `EntityManager.refresh`.
  - The separate DATA_SUBJECT lock is removed, since the lock is now taken first. It is still taken before
    `fan_features`.
  - `requireMembership` is the only membership load in `ConsentService`. All of its public writers are
    `@Transactional`.
- **L1: a lock taken after the read protects nothing.** Door (`DoorOptInService.java:165`) and Survey
  (`SurveyService.java:201`) read the member, ran their opt-out checks, and only locked it later.
  - Two patterns are used:
    - **Lock first:** `findMembership` in both now uses `lockByOrgIdAndConsumerId`, so the checks and the grant see
      the same row.
    - **Refresh after the lock:** used in `requireMembership`, for callers that loaded the member earlier
      (`SmsStopService`, `BuyerPreferencesService`, `ConsentConfirmationService`). The flush first means the
      refresh cannot discard the caller's own pending changes.

- **Redeem projector: same shape as the order projector.** `AudienceRedeemProjector.onTicketRedeemed`
  (`AudienceRedeemProjector.java:40-67` on base) had a method-level `@Transactional(REQUIRES_NEW)` with the try inside
  the body.
  - A failure at commit escaped to the async uncaught-exception handler.
  - Its log passed the raw message and the throwable.
  - Fix: the same treatment. A REQUIRES_NEW `TransactionTemplate` runs `project(orderId)` inside the try; the log is
    redacted with no throwable; the method-level annotation is gone. The constructor gains `PlatformTransactionManager`.

### Lock-order audit: every ConsentService caller

`ConsentService.requireMembership` now takes the membership row lock first. In the table below, "Holds" is what the
caller's transaction already has locked when it calls. Columns:

- **fan_features / campaign_recipients / suppressions:** whether the transaction holds a lock on that table.
- **Membership lock:** whether it already holds this member's row lock, which `requireMembership` simply re-takes.

| Caller | Call | fan_features | campaign_recipients | suppressions | Membership lock |
|---|---|---|---|---|---|
| `AudienceOrderProjector.java:239` | capture | no | no | no | yes (`lockOrCreateMembership`, `:213`) |
| `SmsConsentService.java:91` | capture | no | no | no | yes (`lockOrCreateMembership`, `:106`) |
| `DoorOptInService.java:175` | capture | no | no | no; `:168` is a plain read | yes (`findMembership`, `:201`) |
| `SurveyService.java:210` | capture | no | no | no; `:204` is a plain read | yes (`findMembership`, `:235`) |
| `ImportProvenanceWriter.java:40` | capture | no | no | no | no |
| `AudienceController.java:234` | capture | no | no | no | no |
| `AudienceController.java:251` | unsubscribe | no | no | no | no |
| `BuyerPreferencesService.java:249` | capture | no | no | no | earlier rows of its loop |
| `BuyerPreferencesService.java:253` | unsubscribe | no | no | no | earlier rows of its loop |
| `RetentionJob.java:139` | unsubscribe | no; it clears them after, at `:140` | no | no | yes (`:135`) |
| `SmsStopService.java:51` | unsubscribe | no | no | no | earlier rows of its loop |
| `PublicUnsubscribeController.java:76` | unsubscribe | no | no; marked after, at `:92` | no | no |
| `DsarService.java:261` (object) | unsubscribe | no | no | no | no; `:260` is a plain read |
| `DsarService.java:282` (requestErase) | unsubscribe | no; deleted after, at `:289` | no | no | yes (`:281`) |
| `ConsentConfirmationService.java:117` | confirmPending | no | no | no; `:138` is a plain read | no |
| `BuyerUnsubscribeRunner.java:53` | unsubscribe | no | no | no | no |

Notes on individual callers:

- **`ImportProvenanceWriter.java:40`** runs in `recordExplicit`'s own transaction. Each contact is written in its own
  transactions (`AudienceImportService.java:144-145`).
- **`AudienceController.java:234` and `:251`** have no outer transaction.
- **`BuyerPreferencesService.java:249` and `:253`** run inside `update()` (`@Transactional`, `:179`). Its only writes
  before the call are to `buyer_notification_preferences`.
- **`PublicUnsubscribeController.java:76`** has no outer transaction, and the `campaign_recipients` write comes after
  the call.
- **`ConsentConfirmationService.java:117`** holds the `consent_confirmation_tokens` row it just marked used (`:114`).
- **`BuyerUnsubscribeRunner.java:53`** is REQUIRES_NEW. Its outer transaction (`BuyerAccountDeletionService.java:232`)
  holds no membership, `fan_features`, `campaign_recipients` or `suppressions` lock.

No caller holds a `fan_features`, `campaign_recipients` or `suppressions` lock when it reaches `ConsentService`.
Every path that touches `fan_features` does so after the membership lock (`RetentionJob`, `DsarService.requestErase`,
`ConsentService.unsubscribe`). That matches the projector and `ResendWebhookProjector.java:131-139`.

Residual risk: three callers lock several memberships in a loop.

- **`SmsStopService`** loops over `findAllByPhoneE164`, which has no `ORDER BY` (`MembershipRepository.java:300`).
- **`BuyerPreferencesService.fanOut`** loops over `findAllOrgsByConsumerIdIn` (`MembershipRepository.java:81`), also
  without `ORDER BY`.
- **`BuyerAccountDeletionService`** loops too, but each row runs in its own REQUIRES_NEW transaction, so it holds only
  one lock at a time.

Two concurrent STOPs for one number, or two concurrent toggles from one account, could take the same rows in
different orders, and Postgres would abort one of them with a deadlock error (`40P01`). Before this card, a STOP or a
toggle took no membership lock except on the DATA_SUBJECT path, so this card introduced the risk, and this card closes
it.

**Fix:** both queries now end in `order by m.membershipId`. Every path that holds several membership locks at once
now takes them in ascending id order. Paths that lock one membership per transaction cannot form a cycle among
memberships:
- the projector, SMS consent, Door, Survey, Retention, `requestErase` and Resend lock a single member per
  transaction;
- the erasure job and `BuyerAccountDeletionService` loop, but use one transaction per row (REQUIRES_NEW runner).

Callers of the two queries, and whether the new order matters to each:
- `findAllByPhoneE164`:
  - `SmsStopService.java:44` is the locking loop the order is for.
  - `SmsConsentGate.java:36` is a read-only any/all check, where order makes no difference.
- `findAllOrgsByConsumerIdIn`:
  - `BuyerPreferencesService.java:307` (`reachOf`) feeds `fanOut` (`:244`), the locking loop.
  - It also feeds `organizers()` (`:164`). The organizer list follows the result order. That order was undefined
    before and is now stable, and no client or test relies on it.
  - It also feeds the aggregates `anySubscribed()` and `locked()`, where order makes no difference.

**No regression test, and why.** A test would need the two STOPs to iterate in opposite orders on the old,
unordered query. Both run the identical query, and Postgres returns its rows in physical (TID) order, whether by
sequential or index scan. Reversing that order between the two transactions' snapshots would need a committed update
that moves a row to a lower TID in between. Where Postgres puts a new row version (free space, line-pointer reuse,
HOT) is not under a test's control on the shared `memberships` table. So the red cannot be forced deterministically.
A concurrent-STOP test without opposite orders passes with or without the fix, so it would guard nothing. The
guarantee rests on the ORDER BY and the lock-order argument above.

## Affected files

- `src/main/java/com/imin/iminapi/audience/repository/ConsumerRepository.java`:
  - adds `insertIfAbsent` (native, `@Modifying`, `@Transactional` REQUIRED). The short `upsertMembership`
    overloads self-invoke past the proxy, so some callers reach it with no transaction.
  - removes `saveAndFlush`; nothing uses it any more.
- `src/main/java/com/imin/iminapi/audience/repository/MembershipRepository.java`: orders `findAllByPhoneE164` and
  `findAllOrgsByConsumerIdIn` by membership id; adds `lockByOrgIdAndConsumerId`
  (native `FOR UPDATE`) and `insertIfAbsent` (native, ON CONFLICT DO NOTHING).
- `src/main/java/com/imin/iminapi/audience/service/AudienceOrderProjector.java`:
  - adds `getOrCreateConsumer` and `lockOrCreateMembership`;
  - adds the `TransactionTemplate` inside the try, and the redacted log;
  - the constructor gains `PlatformTransactionManager`.
- `src/main/java/com/imin/iminapi/audience/service/ConsentService.java`: `requireMembership` now flushes, locks and
  refreshes, and the redundant DATA_SUBJECT lock is gone. The constructor gains `EntityManager`.
- `src/main/java/com/imin/iminapi/audienceplan/service/DoorOptInService.java` and
  `src/main/java/com/imin/iminapi/audienceplan/service/SurveyService.java`: `findMembership` locks.
- `src/test/java/com/imin/iminapi/audience/service/ConsentServiceStickyRaceTest.java` (unit test, mechanical): passes
  a mocked `EntityManager` and stubs `lockByIdAndOrgId`.
- `src/test/java/com/imin/iminapi/support/PgFaults.java`: adds `pauseReads(ds, table)`. It holds every reader of a
  table behind an ACCESS EXCLUSIVE lock and exposes `awaitBlocked`, so a read-only stretch (the recompute) can be
  held. PgFaults could only pause writes before. It is table-wide, so it is meant only for tables nothing else in the
  test reads.
- `src/test/java/com/imin/iminapi/audience/MembershipConsentLockTest.java` (new, `@IminIntegrationTest`).
- `src/main/java/com/imin/iminapi/audience/service/AudienceRedeemProjector.java`: `TransactionTemplate` inside the
  try, redacted log, constructor gains `PlatformTransactionManager`.
- `src/main/java/com/imin/iminapi/service/audience/SmsConsentService.java`: uses the two shared helpers. The email
  is gone from its exception message.
- Plain-instance constructor call sites, mechanical (add `txManager`):
  - `src/test/java/com/imin/iminapi/marketing/MarketingOptInWriteTest.java`
  - `src/test/java/com/imin/iminapi/audienceplan/service/FanFeatureTriggerEventsTest.java`
  - `src/test/java/com/imin/iminapi/audience/controller/NeverSoftOptInGuardTest.java`
- `src/test/java/com/imin/iminapi/audience/AudienceComputeIngestionTest.java`: deletes
  `a_duplicate_consumer_insert_fails_inside_the_flushing_save`. It pinned the `saveAndFlush` duplicate that no code
  relies on any more.
- `src/test/java/com/imin/iminapi/audience/AudienceOrderProjectorRaceTest.java` (new, `@IminIntegrationTest`).

## Test impact

All tests are on Postgres and go through the real async listener unless noted. "Blocked" is read from
`pg_stat_activity` (`wait_event_type = 'Lock'`).

- `concurrentOrdersForOneNewEmail_bothMembershipsExistOnOneConsumer`:
  - Two orgs, one new email.
  - `pauseWrites(memberships, org_id, A)` holds A after its consumer INSERT. The test waits until B's consumer INSERT
    is blocked on A, then releases.
  - Asserts one consumer, and memberships in A and B. B's membership has `display_name` = the email and
    `first_touch_src` = `organic`.
  - Red on base: B has no membership.
- `concurrentOrdersFromOneBuyerToOneOrg_oneMembershipCountsBothAndKeepsBothConsents`:
  - The consumer already exists. Both orders are opted in.
  - `pauseWrites(consent_records, order_id, A)` holds A after it has counted only its own order. Order B is created
    after that. The test waits until B's membership INSERT is blocked on A, then releases.
  - Asserts one membership, `orders` = 2, `spend_minor` = A + B, consent subscribed/explicit, and explicit email
    consent records for both order ids.
  - Red before the membership fix: `orders` = 1.
- `concurrentProjectionsOfAnExistingMember_neitherWritesOverTheOther`:
  - The member already exists. A is opted in to email; B carries a phone and an SMS opt-in.
  - A is held as above. The test waits until B is blocked on the row lock, or (with no lock) B has committed its
    phone.
  - Asserts `orders` = 2, `consent_status` = subscribed, B's phone, and SMS subscribed.
  - Red with the lock replaced by a plain find (scratch copy): `consent_status` = never.
- `concurrentFirstSmsConsentsForOneNewEmail_bothRecordedOnOneConsumer`:
  - Calls `SmsConsentService.submit` directly, from two threads.
  - Two orgs, one new email. A is held after its consumer INSERT. The test waits until B's consumer INSERT is blocked.
  - Asserts both calls return, there is one consumer, and both memberships have the phone with SMS subscribed.
  - Red on base: B throws "current transaction is aborted".
- `membershipCommitFailure_isCaughtAndRollsBackTheProjection`:
  - A plain instance (synchronous). The member exists before the order, so the projection only issues an UPDATE, and
    that runs at the commit flush.
  - `failWrites(memberships, org_id, org)`.
  - Asserts `onTicketsIssued` does not throw and `orders` stays 0.
  - Red with the catch moved inside the transaction callback (scratch copy).
  - No log assertion (§ Testing).

- `MembershipConsentLockTest.backfillRowRacingAnUnsubscribe_leavesTheMemberUnsubscribed` (M1):
  - A subscribed member. `pauseReads(orders)` holds a backfill row in its recompute, called as the backfill calls
    it: the 3-arg `upsertMembership` through the bean.
  - A DATA_SUBJECT unsubscribe runs. The test waits until it is done or blocked on the row lock, then releases.
  - Asserts `consent_status` = unsubscribed and `consent_basis` is null.
  - Red on the tree before M1, and red with the annotation removed (scratch copy): `subscribed`.
- `MembershipConsentLockTest.captureRacingAProjection_keepsTheCheckoutSmsOptInAndPhone` (M2):
  - An existing member. `pauseWrites(consent_records, membership_id)` holds a manual capture after its read.
  - The order carrying a phone and an SMS opt-in is projected. The test waits until the projection is blocked on the
    lock or has committed its phone, then releases.
  - Asserts the phone, SMS subscribed, and email consent subscribed/explicit.
  - Red before M2, and red with `findByIdAndOrgId` in place of the lock (scratch copy): phone null.
- `MembershipConsentLockTest.captureOverACopyReadEarlierInItsTransaction_keepsWhatAnotherWriterCommittedSince` (L1):
  - In one transaction: the member is read without a lock, another connection then commits a phone and an SMS opt-in,
    then `capture` runs.
  - Asserts the phone and SMS state survive.
  - Red before the fix, and red with the refresh removed (scratch copy): phone null.
  - The Door/Survey lock-first change has no test of its own: one test for the pattern, as agreed.

- `AudienceOrderProjectorRaceTest.redeemProjectionCommitFailure_isCaughtAndRollsBackTheProjection`:
  - A plain instance (synchronous). An existing member with `attended` reset to 0 and a redeemed ticket, so the
    redeem's recompute only issues an UPDATE, at the commit flush.
  - `failWrites(memberships, org_id)`.
  - Asserts `onTicketRedeemed` does not throw and `attended` stays 0.
  - Red with the catch moved inside the callback (scratch copy). A structural guard for the new shape, not a
    reproduction: a plain instance never had the proxied REQUIRES_NEW, so base passes it too.

No test for the repository methods on their own (framework behaviour), and none for the log line.

## Risks

- Native INSERTs bypass `@PrePersist`. `created_at`/`updated_at` are passed as `Instant.now()`, the same value the
  entity defaults give.
- The row lock makes concurrent projections of one member wait for each other. That wait is short, one projection's
  transaction. The lock is taken before consent capture, which locks no other membership, so no new lock order exists.
- Every capture, unsubscribe and confirmation now takes the membership row lock first, where before only the
  DATA_SUBJECT unsubscribe took it, and later.
  - Order: membership, then `fan_features`. That is the same order the projector, `ResendWebhookProjector` and
    `requestErase` use.
  - No caller is known to hold a `fan_features` or recipient lock when it calls `ConsentService`.
- `requireMembership` flushes the persistence context. A constraint violation pending from the caller now surfaces
  there instead of at commit; it still rolls back the same transaction.

## Definition of done

- Every race test is red before its fix (on base, or on a scratch copy without the guard) and green after.
- The targeted audience tests and `SpringContextGuardTest` are green.
- Full `./mvnw test` is green, with no skipped Testcontainers tests.

## Review rounds
round 1 → PASS (M1, M2, L1 taken into the card). round 2 → PASS (MEDIUM accepted: redeem test reworded as a structural guard; LOW fixed: lock_timeout on pauseReads).
