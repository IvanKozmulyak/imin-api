# Fix: Resend projection read-then-save race and soft-bounce count

## Goal

Close two gaps named under Risks in `2026-10-08-p3-resend-out-of-order.md`.

1. **Read-then-save race.** In `ResendWebhookProjector` the bounced, complained, opened and
   clicked branches loaded the `CampaignRecipient` entity (`ResendWebhookProjector.java:92-93`),
   changed it, and saved the whole entity (`:156`, `:187`). `CampaignRecipient` has no `@Version`
   and no `@DynamicUpdate`, so the UPDATE writes every column. If a concurrent webhook for the
   same message commits between the read and the save, the save puts the stale values back. For
   example, a complaint whose transaction is still writing its suppression row and membership
   (`:135-143`) overwrites a `delivered_at` or `opened_at` that committed meanwhile.
2. **Soft-bounce count.** `countSoftBouncesByMembership` (`CampaignRecipientRepository.java:369-371`)
   counted every row with `error_code = 'soft_bounce'`. `markDelivered` keeps `error_code`
   (`:300-309`), so a soft bounce that was later delivered still counted toward the three-strike
   org suppression (`ResendWebhookProjector.java:181-196`). That escalation is meant to answer
   "does mail to this person keep failing" (repository javadoc `:362-368`), and a delivered row
   is not failing.

**Fix (option b, conditional UPDATEs).** The projector stops loading and saving the entity. Each
branch issues one column-scoped `@Modifying` UPDATE, like the existing `markDelivered`:
- `markBounced(id, code, at)`: `status` becomes `bounced` unless it is `complained`. `error_code`
  becomes `:code` unless it is already `hard_bounce`. `last_event_at` is set to `:at`.
- `markComplained(id, at)`: sets `status = 'complained'` and `last_event_at`. A complaint
  overrides any earlier status.
- `markOpened(id, at)` / `markClicked(id, at)`: set the timestamp and `last_event_at`, never `status`.

Postgres re-evaluates each UPDATE's `CASE` on the latest committed row after it takes the row
lock, so precedence holds under concurrency.

**Precedence after the fix.** "Earlier" and "later" refer to arrival order.
- complained beats every status. The other writers already never overwrite it
  (`markDelivered`, `markUnsubscribed`), and the new bounce update does not either. Before this
  fix, a bounce arriving after a complaint overwrote it (the `:111` unconditional set).
- A hard bounce beats delivered, unsubscribed and a soft bounce. Before this fix, a soft bounce
  arriving after a hard bounce overwrote `error_code` with `soft_bounce`. A later delivered then
  walked the row back to `delivered`.
- A soft bounce and delivered: the later one wins on `status`. This is unchanged.
- Bounced beats unsubscribed in either order. This is unchanged.

The complained update runs after the suppression and membership writes and before the breaker.
In the complaint branch the recipient row is therefore locked last, after the suppression row
and the membership.

The webhook paths do not share one lock order. The bounce branch writes the recipient row first,
and on the third transient strike it then writes the org marketing suppression (`addMarketing`):
recipient first, then suppression. The complaint branch takes the opposite order. That was also
true before this fix, when the complaint's recipient UPDATE was flushed by `addMarketing`'s
`saveAndFlush`.

A deadlock needs a complaint and a third-strike soft bounce for the same membership in flight at
the same moment, on different campaign rows. The cycle also needs those two transactions to
contend on one row, and the only shared row is the membership's suppression (V114's unique
index). Postgres would detect the cycle and abort one transaction. Resend would retry it, and
the dedup claim rolls back with it. In practice this cannot happen, and it is not addressed here.

**Soft-bounce count.** `countSoftBouncesByMembership` now also requires `status = 'bounced'`.

Option (a), `@DynamicUpdate`, was rejected. It changes the UPDATE shape for every
`EmailChannelSender` writer, and it still lets a status decided on a stale read
overwrite a status that committed meanwhile.

## Affected files

- `src/main/java/com/imin/iminapi/marketing/webhook/ResendWebhookProjector.java`
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java`
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookOutOfOrderTest.java`
- `src/test/java/com/imin/iminapi/marketing/ResendWebhookProjectorTest.java`
- this plan

### Writers of the `campaign_recipients` row (audited against the new UPDATEs)

- `RecipientMaterializer.java:95,123` inserts rows. No webhook can resolve to a row before it
  has a `provider_message_id`.
- `EmailChannelSender.java:213` saves the entity as `sent` with its `provider_message_id`. The
  controller resolves a webhook by that id (`ResendWebhookController.java:96-97`), which
  becomes visible only when this save commits. So no webhook UPDATE can be overwritten by
  this save.
- `EmailChannelSender.java:228,241,302,330,354` saves claimed `pending` rows as `failed`,
  backoff or `skipped`. These rows have no provider id (never sent, or rejected), so no webhook
  reaches them.
- `markUnsubscribed`, `markDelivered`, `failExhaustedPending`, `requeueFailed`,
  `divertPendingForErasedMembership` and `redactPiiByMembershipId` are column-scoped UPDATEs.
  The new UPDATEs write disjoint columns or apply the same precedence.
- After this change the projector writes no entity save, so nothing in a webhook transaction
  rewrites the full row.

## Test impact

- `ResendWebhookOutOfOrderTest`:
  - New pairs in both orders: complained with a hard bounce, and a hard bounce with a soft
    bounce. These assert the new precedence statements.
  - New parameterized interleave. A complaint is held inside its transaction, after its read,
    by `PgFaults.pauseWrites` on its suppression row. A delivered or opened event commits
    meanwhile. After release, the row is `complained` and keeps the concurrent timestamp.
- `ResendWebhookProjectorTest`: two soft bounces plus one soft bounce later delivered do not
  write the org suppression.
- Existing `ResendWebhookOutOfOrderTest`, `ResendWebhookProjectorTest` and
  `ResendWebhookControllerTest` stay green.

## Risks

- The interleave with the roles reversed cannot be produced with a trigger: an opened event
  that read a stale row and overwrites a complaint. The opened branch performs no write
  between its read and its save. It is closed structurally, because the branch no longer
  reads or saves the entity.
- `last_event_at` still follows arrival order. This is unchanged.

## Definition of done

- Both regression tests are red on `origin/master` code and green after the fix.
- `ResendWebhook*Test` and `SpringContextGuardTest` are green.
- Full `./mvnw test` is green with no skipped Testcontainers tests.

## Review rounds
round 1 → PASS (MEDIUM fixed: last_event_at asserted per pair and in the interleave, red-proven per UPDATE).
