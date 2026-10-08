# P3 gap: out-of-order Resend webhook events

## Goal

Resend delivers webhooks at least once and in no particular order. Prove, through the signed
webhook endpoint and the real projector, which final recipient state each arrival order leaves,
and that a complaint or hard bounce is never undone by a later delivered/opened event.

What the code did before this change (`ResendWebhookProjector.java`):
- No precedence rule existed: every branch wrote unconditionally (`:96` delivered set
  `status=delivered`, `:108` bounced, `:122` complained). Last arrival won on `status`.
- `delivered` never touches `error_code` or `opened_at`, so a bounce's `error_code` and an
  earlier open survive it.
- `opened`/`clicked` touch only their timestamp (`:146`, `:149`); `opened_at` is overwritten on
  every open, so it holds the last-arrived open.
- `touch` (`:156`) sets `last_event_at` to the event's `created_at` regardless of order.
- `email.sent` is not a projected type (`ProviderEvent.java:30-34`) and falls to the no-op `default`.
- Nothing in the projector removes a suppression row or clears `objected_profiling`.

**Fix (approved).** A late `email.delivered` made a complained, unsubscribed or hard-bounced
recipient show as `delivered`, against the repo's own rule that a complaint is never walked
back (`CampaignRecipientRepository.markUnsubscribed`). The delivered branch now goes through a
conditional UPDATE, `CampaignRecipientRepository.markDelivered`: it always stamps
`delivered_at`/`last_event_at` and keeps `status` when it is `complained`, `unsubscribed`, or
`bounced` with `error_code = 'hard_bounce'`. The status decision happens in SQL on the current
row, not on a value read earlier, so a complaint that commits first is not lost to a stale read.
The branch returns before the entity save, and the loaded entity is left unmodified, so no
full-entity flush follows the UPDATE in the same transaction.

## Affected files

- `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java` (delivered branch)
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java` (new `markDelivered`)
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookOutOfOrderTest.java` (new)
- this plan

### Writers of `campaign_recipients.status` (checked against the new conditional UPDATE)

- `RecipientMaterializer.java:118,131` insert rows `pending`/`skipped` before any send; no webhook
  can resolve to them yet (no `provider_message_id`).
- `EmailChannelSender.java:213,228,241,302,330,354` full-entity saves of rows claimed `pending`
  via `claimPendingBatch` (`FOR UPDATE SKIP LOCKED`). The `:213` save is what writes
  `provider_message_id`, so a delivered event cannot find the row before it; nothing in the
  sender saves a row again after it is `sent`.
- `CampaignRecipientRepository.markUnsubscribed`, `failExhaustedPending`, `requeueFailed`,
  `divertPendingForErasedMembership` are conditional UPDATEs on statuses a delivered row can
  never hold except `markUnsubscribed` (which the CASE now respects); `redactPiiByMembershipId`
  does not touch status.
- `ResendWebhookProjector.java:153` (`save`) and `:184` (`saveAndFlush`): the bounced,
  complained, opened and clicked branches. In the same transaction, the delivered branch now
  returns before `:153`. Across transactions, these branches are still read-then-save with no
  `@Version`/`@DynamicUpdate`: one that read its row before a concurrent write committed
  rewrites every column with what it read. That can revert this UPDATE (status and
  `delivered_at`), and an opened/clicked save can equally revert a concurrent complaint to
  `sent`. That race predates this change (the delivered branch was a full save too) and is
  left as is.

## Test impact

New `@IminIntegrationTest` class through `POST /api/v1/public/webhooks/resend` with real Svix
signing (secret flipped via `PropertyFlips`), unique addresses from `fx.email`, campaigns
removed with `CampaignRows.delete` in `@AfterEach`. No new context.

1. `finalRecipientStateAfterTwoEventsInEitherOrder` — parameterized over event pairs in both
   arrival orders: delivered+opened, delivered+late sent, delivered+hard bounce,
   delivered+complained, complained+opened, delivered+unsubscribe (the owned opt-out via
   `markUnsubscribed`), delivered+transient bounce. Asserts status, error_code, delivered_at,
   opened_at. A transient bounce is not terminal: transient then delivered ends `delivered`
   with `error_code = soft_bounce`; delivered then transient ends `bounced`.
2. `ResendWebhookControllerTest.validDeliveredWebhookMarksTheRecipientDelivered` also asserts
   `last_event_at` equals the event's `created_at`, now that `markDelivered` writes it.

No suppression test here: no code removes a suppression row, and `ResendWebhookProjectorTest`
owns suppression creation.

Red on the unfixed code (`./mvnw -q test -Dtest=ResendWebhookOutOfOrderTest`, before the fix):

```
[ERROR] Tests run: 16, Failures: 3, Errors: 0, Skipped: 0
finalRecipientStateDoesNotDependOnArrivalOrder(List, Expected)[6]   expected: "bounced"      but was: "delivered"
finalRecipientStateDoesNotDependOnArrivalOrder(List, Expected)[8]   expected: "complained"   but was: "delivered"
finalRecipientStateDoesNotDependOnArrivalOrder(List, Expected)[12]  expected: "unsubscribed" but was: "delivered"
```

Green after the fix:

```
ResendWebhookOutOfOrderTest  tests=14 errors=0 skipped=0 failures=0
ResendWebhookProjectorTest   tests=13 errors=0 skipped=0 failures=0
ResendWebhookControllerTest  tests=4  errors=0 skipped=0 failures=0
SpringContextGuardTest       tests=25 errors=0 skipped=0 failures=0
```

Each green guard was proven red by a mutation in a scratch copy: delivered clearing
`opened_at`, `email.sent` projected, opened writing a status; `markDelivered` keeping every
`bounced` row (drops the error_code check) turns transient-then-delivered red
(`expected "delivered" but was "bounced"`); `markDelivered` without `r.lastEventAt = :at`
turns the controller test red (`expected 2026-07-11T00:00:00Z but was null`).

Single-event behaviour stays with `ResendWebhookProjectorTest` / `ResendWebhookControllerTest`.

## Risks

- `last_event_at` still follows arrival order and `opened_at` still holds the last-arrived open;
  neither has a defined rule, so neither is tested.
- Card: read-then-save race in the bounced, complained, opened and clicked branches
  (`ResendWebhookProjector.java:101-153`, `:184`; `CampaignRecipient` has no `@Version` or
  `@DynamicUpdate`). A branch that read the row before a concurrent write committed rewrites
  every column, so it can revert `markDelivered` or a complaint on the recipient row. Rare,
  and it costs stats only: suppression rows are separate and unaffected. Fix idea:
  `@DynamicUpdate` on `CampaignRecipient`, plus a deterministic two-transaction test.
- Card: `countSoftBouncesByMembership` (`CampaignRecipientRepository.java:370-372`) counts every
  row with `error_code = 'soft_bounce'`, including a soft bounce that was later delivered, so
  such a row still counts toward the three-bounce marketing suppression.

## Definition of done

- Targeted classes and `SpringContextGuardTest` green.
- Full `./mvnw test` green, no skipped Testcontainers tests.

## Live-test after deploy

Send to a test address, report it as spam, then replay (or wait for) a later `email.delivered`
for the same message: the recipient row stays `complained`.

## Review rounds
round 1 → FIX_REQUIRED (HIGH: soft-bounce CASE branch untested; lastEventAt not asserted; MEDIUM: suppression test guarded absent code) → fixed, both red-proven; round 2 not run (test-only additions, src/main unchanged since round 1).
