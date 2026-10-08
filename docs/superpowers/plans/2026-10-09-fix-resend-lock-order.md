# Fix: Resend projector lock order (complaint vs third soft bounce)

## Goal

Every path in `ResendWebhookProjector` takes its locks in one order: suppression slot (with the FK
`KEY SHARE` on the membership its INSERT takes), then membership, then the recipient row last. That
matches the complaint branch and `DsarService.executeErase`. Today the bounce branch writes the
recipient row first, so a complaint and a third-strike soft bounce for the same message deadlock
(`40P01`). The soft-bounce strike rule must not change. Second part: the projector's system writes
of a suppression become insert-if-absent. A concurrent writer of the same row is then a no-op,
not a 409 that leans on Resend's retry, so both events succeed on their first delivery.

## The deadlock (base 515b411d)

- T1 complaint: `addMarketing` INSERT holds the `(scope, org_id, membership_id)` unique slot
  (`SuppressionService.java:84`, index from `V114__suppression_entries_unique.sql`). Then it locks
  the membership `FOR UPDATE` (`ResendWebhookProjector.java:133`, before the fix) and finally
  updates the recipient (`:141`).
- T2 soft bounce: `markBounced` locks the recipient row (`:108` before the fix). At the third
  strike its `addMarketing` INSERT waits on T1's slot. T1 then waits on T2's recipient row. That
  is a cycle, and Postgres aborts T2.

## Writers / lockers of the rows involved

| path | file:line | takes, in order |
|---|---|---|
| Resend complaint (`addMarketingIfAbsent`) | `ResendWebhookProjector.java:130,133,136,141` | suppression slot + membership KEY SHARE → membership FOR UPDATE → fan_features → recipient |
| Resend bounce, permanent (after fix) | `ResendWebhookProjector.java:108,117` | deliverability slot → recipient |
| Resend bounce, transient (after fix) | `ResendWebhookProjector.java:177-187,117` | (count, no lock) → suppression slot + membership KEY SHARE → recipient |
| Resend delivered/opened/clicked | `ResendWebhookProjector.java:95,146,149` | recipient only |
| Webhook dedup claim, same tx | `ResendWebhookController.java:59,103,113` | provider_events row (own key) before any of the above |
| DSAR erase | `DsarService.java:306,311,319-320` | membership FOR UPDATE → suppression delete → recipients |
| Import provenance unsubscribe (`addMarketing`, unchanged) | `ImportProvenanceWriter.java:51` → `SuppressionService.addMarketing` | suppression slot + membership KEY SHARE; no recipient |

After the fix no path holds a recipient row while it waits on a suppression slot or a membership,
so the cycle above cannot form.

## Change

1. `CampaignRecipientRepository.countSoftBouncesWithThisBounce(membershipId, recipientId)`. It
   returns what `countSoftBouncesByMembership` will return once `markBounced(recipientId,
   "soft_bounce")` has run. The other rows count when `bounced`/`soft_bounce`. This row counts
   unless markBounced's CASE keeps it out: `complained`, or `error_code = 'hard_bounce'`. A row
   that is already a strike is counted once, because `count(r)` counts rows, not disjuncts.
2. `ResendWebhookProjector` bounce branch: the suppression work runs first (deliverability insert
   for permanent bounces, strike count + marketing insert for transient ones), and `markBounced`
   runs last. `escalateRepeatedSoftBounce` takes `recipientId` and uses the new count. It falls
   back to the old count when `recipientId` is null, where markBounced does not run.
3. `SuppressionRepository.insertMarketingIfAbsent` / `insertDeliverabilityIfAbsent`: native
   `INSERT … ON CONFLICT (scope, org_id, membership_id) DO NOTHING` and `ON CONFLICT (scope,
   normalized_email) DO NOTHING`. The targets are V114's two unique indexes, which have no
   partial-index predicate, so no `WHERE` is needed. `channel` and `since` take their column
   defaults (V55, V50). Against an in-flight conflicting row, Postgres waits for that writer, then
   does nothing if it committed or inserts if it rolled back.
4. `SuppressionService.addMarketingIfAbsent` (`requireMembership`, then the insert; writes the
   SUPPRESSION_ADDED audit only when a row was inserted) and `addDeliverabilityIfAbsent` (no audit,
   like `addDeliverability`). Neither publishes an event. The projector's three suppression writes
   use these. The organizer-facing `addMarketing` / `addDeliverability`, used by
   `ImportProvenanceWriter` and the audience paths, keep the read-then-`saveAndFlush` behaviour and
   still answer 409 on a lost race.

## Deterministic regression test

`ResendWebhookOutOfOrderTest.aComplaintAndAThirdSoftBounceQueueInsteadOfDeadlocking`. It sends
real signed webhooks through MockMvc:

- The test seeds two earlier soft-bounced recipients for the membership, plus the target
  recipient (`sent`).
- `PgFaults.pauseWrites(audit_logs, target_id, membershipId)` holds the complaint on the
  SUPPRESSION_ADDED audit write. That write runs in its own REQUIRES_NEW transaction after the
  suppression INSERT, so the complaint is paused while holding the slot and before it has touched
  the membership or the recipient.
- The test then fires the transient bounce and uses `PgLocks.awaitLockWait("insert into
  suppression_entries")` to wait until the bounce is queued on that slot. Then it releases the
  pause.
- Assertions:
  - No `40P01` in either request.
  - Both requests get 200 on their first delivery.
  - End state: recipient `complained` / `soft_bounce`, exactly one marketing suppression with
    reason `spam`, exactly one SUPPRESSION_ADDED audit row for the membership, and the membership
    `objected_profiling = true`.
- Red on base: the bounce request ended with `SQLState 40P01 deadlock detected`.
- Red with the lock order fixed but the old `addMarketing` still in the projector: the bounce got
  409 / `23505`, because both transactions insert the same `(scope, org_id, membership_id)` row.

## Test impact

- New: the regression test above (Postgres concurrency, which needs a test per `CLAUDE.md` §
  Testing).
- New: `ResendWebhookProjectorTest.aSoftBounceCountsItsOwnRowAsMarkBouncedLeavesIt`, one
  parameterized test over the target row's state before a transient bounce:
  - `sent` → counts
  - `delivered`/`soft_bounce` → counts
  - already `bounced`/`soft_bounce` → counts once
  - `complained` → does not count
  - `bounced`/`hard_bounce` → does not count
  It pins that the strike rule is unchanged now that the count is read before the write. Mutating
  the `complained` clause or the `hard_bounce` clause turns it red.
- New: `ResendWebhookProjectorTest.aPermanentBounceForAnAddressAlreadyListedKeepsTheOneRow`. A
  second permanent bounce for a listed address leaves one deliverability row and still marks its
  recipient. Removing the deliverability `ON CONFLICT` turns it red (`23505`).
- The race test's single-audit-row assertion guards "audit only on insert". Removing the
  `if (inserted)` turns it red (2 rows).
- Unchanged and green: `AudienceSuppressionUniquenessTest` and
  `AudienceSendGateConsentSuppressionTest`, which cover the organizer-facing writers.
- Unchanged and green: `ResendWebhookProjectorTest` (strike tests),
  `ResendWebhookOutOfOrderTest` (pair orderings, in-flight complaint race),
  `ComplaintRateBreakerTest`, `ResendWebhookControllerTest`, `SpringContextGuardTest`.

## Risks

- The strike count is now read without the recipient row lock. Suppose another event commits on
  the same message between the count and `markBounced`; a hard bounce or complaint is the only
  kind that changes the outcome. Then the count can include a row that ends up not counted, and
  the membership is suppressed one strike early. In both of those cases the address is already
  suppressed through another path (deliverability list, or the complaint's own marketing
  suppression).
- Insert-if-absent keeps the first writer's `reason`. When a soft-bounce escalation and a complaint
  race, the row says whichever committed first. Before, the loser 409'd and its retry found the row
  already there, so the outcome is the same.
- Two code paths now write suppressions: system (`…IfAbsent`) and organizer (read-then-insert).
  Only the organizer path reports a duplicate, which is intended.
- The permanent-bounce branch now inserts the deliverability row before the recipient update. The
  outcome is the same; only the order changed.

## Affected files

- `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java`
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java`
- `src/main/java/com/imin/iminapi/audience/service/SuppressionService.java`
- `src/main/java/com/imin/iminapi/audience/repository/SuppressionRepository.java`
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookOutOfOrderTest.java`
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookProjectorTest.java`
- `docs/superpowers/plans/2026-10-09-fix-resend-lock-order.md`

## Verification commands

- `./mvnw test -Dtest='ResendWebhook*Test,ComplaintRateBreakerTest,SuppressionService*Test,SpringContextGuardTest'`
- `docker info` then `./mvnw test` (no skipped tests)

## Definition of done

- The regression test was red on base with `40P01` before the fix existed, red with `23505` before
  the insert-if-absent variant existed, and is green after both.
- The strike-count parameterized test is green, and its guard clauses were proven by mutation.
- Both verification commands are green, with 0 skipped in the full run.
- No commit and no push; the change ships through `/ship-imin`.

## Review rounds
round 1 → PASS (LOW fixed by orchestrator: unreachable null-recipient fallback and the now-unused countSoftBouncesByMembership removed). Open product call: first committer keeps the suppression reason (soft-bounce vs spam).
