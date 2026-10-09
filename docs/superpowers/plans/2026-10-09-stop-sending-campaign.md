# Stop a campaign that is already sending

## Goal

An organizer can stop a campaign while it is `sending`. Today `CampaignService.cancel`
(`CampaignService.java:634-646`) only takes `scheduled` (`cancelIfScheduled`, `CampaignRepository.java:80-85`),
so a `sending` campaign answers 409 and the whole audience is mailed.

The drive already stops when the status leaves `sending`:
- `EmailChannelSender.sendNextBatch` re-reads it before each batch (`EmailChannelSender.java:118-123`);
- `processOne` returns before `finish` (`CampaignSendUnit.java:103`);
- `markSentIfSending`, `markFailedIfActive`, `recordMaterialized` never write over another status.

## Decisions

1. **Same endpoint, same `canceled` status.** `POST /api/v1/marketing/campaigns/{id}/cancel` now takes
   `scheduled` or `sending`. No separate `stop` endpoint or status: whether a cancel happened mid-send is
   derivable from the rows (`stats.sent > 0`), and the UI copy "Stopped after N of M sent" reads
   `stats.sent` and `recipientCount`. The webapp `CampaignStatus` union is unchanged.
2. **Pending rows become `skipped` / `skip_reason='campaign_canceled'`.** Leaving them `pending` would
   be a lie: the recipients table shows `pending` as "waiting for the sending system"
   (`imin-webapp` `RecipientsTable.tsx:45`, `copy.*.ts` `detail.pending`).
   `campaign_recipients.status` has no CHECK (V53:12, VARCHAR(16)), `skip_reason` is VARCHAR(32).
   Readers of `pending`, and why none treats a canceled campaign as still-to-send:
   - `claimPendingBatch` / `countByCampaignIdAndStatus(...,'pending')` — drive only, which stops on the re-read;
   - `failExhaustedPending` / `countRetryablePending` — `finish` only, never reached for a non-`sending` campaign;
   - `divertPendingForErasedMembership` (DSAR) — finds nothing left on a canceled campaign;
   - `stats()` `sent` and `chipCounts` — `skipped` is its own bucket, so `sent` stays exact and the
     Skipped chip includes the stopped rows;
   - `claimDue` never takes `canceled`, so the stale reclaim cannot pick it up;
   - `retryIfFailed` only takes `failed` (`CampaignRepository.java:88-92`), so retry refuses a canceled campaign.
   Rows the provider already accepted keep `sent`; rows a terminal batch failure marked `failed` keep `failed`.
3. **Order of writes, so nothing deadlocks and nothing is left pending.**
   - The cancel is two commits, with no service-level transaction:
     1. a CAS `status IN ('scheduled','sending') → canceled` (`cancelIfActive`), committed at once;
     2. `skipPendingOfCanceled`, which marks the campaign's `pending` rows.
   - Step 2 waits on the rows of a batch that is at the provider (they are locked by `claimPendingBatch`)
     until that batch commits. Postgres then re-checks `status='pending'`, so rows that went out stay
     `sent`, and rows a transient failure left `pending` are skipped.
   - The response therefore comes back after the in-flight batch has settled, with true counts.
   - The campaign row lock from step 1 is released before step 2. The drive's `touch` at the end of the
     batch would otherwise wait on the cancel while the cancel waits on the batch's rows.
   - A cancel that commits between the claim and `materialize`'s row lock: `materialize` re-reads the
     status under its lock and writes the would-be pending rows as `skipped`/`campaign_canceled`.
     `recipientCount` still counts them, so "Stopped after 0 of M" stays true. A cancel that arrives
     while materialize holds the lock waits for it, then skips the new rows in step 2.
4. **Known ceiling: the batch already at the provider still goes out.** Documented on the controller
   method and in the UI contract below: "stops before the next batch". A batch is at most
   `EmailChannelSender.BATCH_SIZE` (100) emails.
5. **Audit:** `CAMPAIGN_CANCELED` as before. The summary now reads `Campaign canceled: N sent, M not sent`,
   counted after step 2.

## Writer audit — `campaign_recipients.status` on a canceled campaign

| writer | file:line | interaction |
|---|---|---|
| materialize insert | `RecipientMaterializer.java:66-125` | writes `skipped`/`campaign_canceled` when the locked re-read says `canceled` |
| batch `sent` / `failed` / backoff | `EmailChannelSender.java:205-255` | holds the row locks; the cancel's skip waits and re-checks |
| divert (gate, guard, address) | `EmailChannelSender.java:146-160` | inside the batch transaction, same as above |
| `failExhaustedPending` | `CampaignSendUnit.java:125` | `finish` only, not reached once canceled |
| `requeueFailed` | `CampaignService.retry` | retry refuses `canceled` |
| DSAR divert | `CampaignRecipientRepository.java:423-426` | pending only, so it is a no-op after the skip |
| **cancel skip (new)** | `CampaignRecipientRepository.skipPendingOfCanceled` | `pending → skipped` only |

## Affected files

- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRepository.java` — `cancelIfActive` replaces `cancelIfScheduled`
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java` — `skipPendingOfCanceled`
- `src/main/java/com/imin/iminapi/marketing/model/CampaignRecipient.java` — `SKIP_CAMPAIGN_CANCELED` constant
- `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java` — `cancel`
- `src/main/java/com/imin/iminapi/marketing/send/RecipientMaterializer.java` — canceled re-read under the lock
- `src/main/java/com/imin/iminapi/marketing/controller/CampaignController.java` — javadoc only
- `src/test/java/com/imin/iminapi/marketing/send/CampaignDispatcherConcurrencyTest.java`
- `src/test/java/com/imin/iminapi/marketing/CampaignServiceTest.java`
- `src/test/java/com/imin/iminapi/marketing/CampaignControllerTest.java`
- this plan

## Ordered steps

1. Write the tests below; run the repro tests on the unfixed code and record red.
2. Repository methods, model constant, service `cancel`, materializer re-read, controller javadoc.
3. Targeted, then full `./mvnw test`.

## Test impact

`CampaignDispatcherConcurrencyTest` (`@IminIntegrationTest`, Postgres, concurrency):
1. `stoppingACampaignMidDrive_sendsNoFurtherBatchAndSkipsTheRest` (repro, red on base: 409) —
   `BATCH_SIZE + 50` rows. The first provider answer fires the real `service.cancel` on another thread,
   waits until its skip is blocked on the batch's rows, then returns. Asserted:
   - cancel returns normally;
   - status `canceled`;
   - one batch sent;
   - the 50 others are `skipped`/`campaign_canceled`, 0 `pending`;
   - `detailWithStats` `stats.sent` is 100 and the chip counts are `skipped` 50 of 150 total;
   - the audit summary reads `100 sent, 50 not sent`;
   - a later `runOnce` with a stale heartbeat sends nothing and the status stays `canceled`.
2. `anOrganizerActionRacingTheClaim_*` splits:
   - `aRetryRacingTheClaim_answers409AndTheCampaignSends` — the RETRY case, unchanged;
   - `aCancelRacingTheClaim_stopsTheCampaignBeforeItsFirstBatch` (repro, red on base: 409, both rows sent).
     The claim is held on its flip and the cancel's CAS is blocked on it; then the claim is released. The
     cancel succeeds, nothing is sent, both rows are skipped. Deterministic because the waiting cancel
     holds the tuple lock ahead of the drive's `lockForMaterialize`.
3. `materializingACanceledCampaign_queuesNoRecipient` (repro, red on base: rows `pending`) — the drive's
   copy says `sending`, the row is `canceled`; `materialize` writes both sendable rows as
   `skipped`/`campaign_canceled`, and `recipient_count` is 2.

`CampaignServiceTest`:
- `cancel_rejects_a_non_scheduled_campaign` becomes a parameterized test over `sent`, `failed`, `draft`,
  `canceled`. Each answers 409 INVALID_STATE and keeps its status.

`CampaignControllerTest`:
- `anotherOrgsCampaign_isNotFound_andUntouched` gains `/cancel, sending` (no-leak 404, status kept).

Red proofs:
- 1, 2 (cancel) and 3 against base, run before the fix;
- `skipPendingOfCanceled` (1 and 2 go red with `pending` rows) and the materialize re-read (3) by one-line removals in a scratch copy.

## Contract for the webapp (follow-up UI card)

- `POST /api/v1/marketing/campaigns/{id}/cancel`, no body, any org member (unchanged auth).
- `200` empty body: canceled. Allowed from `scheduled` and now `sending`. The call returns after a batch
  that was already at the provider settles. That batch (≤100 emails) still goes out, so the copy
  should say "stops before the next batch".
- A repeat cancel of a `canceled` campaign that still has pending rows answers `200` and skips them (round 2).
- `409 INVALID_STATE` from `draft`, `sent`, `failed`, a `canceled` campaign with nothing pending (message
  `Only scheduled or sending campaigns can be canceled`), and when the campaign finished or failed meanwhile.
- `404 NOT_FOUND`: unknown id or another org's campaign.
- After a stop, the counts are in `GET /campaigns/{id}` (detail):
  - `status='canceled'`;
  - `stats.sent` is what went out;
  - `recipientCount` is the snapshot.
  "Stopped after `stats.sent` of `recipientCount`" holds for both paths. Recipient rows that were never
  sent are `status='skipped'`, `skipReason='campaign_canceled'`, which needs a `describeReason` entry
  in EN/ES/FR/UK.
- OpenAPI: no schema or path change (the controller javadoc is not in the spec), so `api:sync` shows no
  diff. The UI card is behaviour-only: show a Stop button while `sending`.

## Round 2 (review, two MEDIUMs)

6. **Revenue of a campaign stopped mid-send.** `hasSent` (`CampaignService.hasSent`) keyed on `sentAt`/`sent`/`sending`,
   so a stopped campaign showed `revMinor=null` ("no link in any inbox") although its first batches went out with utm links.
   `canceled` now counts as sent when any recipient row has left the queue (`LEFT_THE_QUEUE`):
   - the list asks in one query over the page's canceled ids (`findCampaignIdsWithStatusIn`), so no N+1;
   - `get` uses one count.
   A campaign canceled before any send stays null.
7. **A cancel can be repeated.** If the stored status is already `canceled`:
   - pending rows left behind (a skip that failed after the status committed, or rows queued before this shipped) are
     skipped, and the call answers 200;
   - `CAMPAIGN_CANCELED` is audited only when no such row exists yet for the campaign;
   - a canceled campaign with nothing pending is still 409.
   The repeat writes no campaign row, so the lock order is unchanged. This replaces the ponytail in Risks.
   Files: `CampaignService.java`, `CampaignRecipientRepository.java`, `repository/AuditLogRepository.java`
   (`existsByOrgIdAndActionAndTargetTypeAndTargetId`).
   Tests:
   - the stop mid-drive test asserts list and get `revMinor` 0, not null; the claim-race test asserts null (0 sent);
   - `CampaignServiceTest.a_cancel_whose_skip_failed_can_be_repeated_and_finishes_it_once` (`PgFaults.failWrites` on the
     skip, then a second cancel → 200, rows skipped, one audit row, a third → 409);
   - `a_repeated_cancel_that_finds_rows_left_queued_skips_them_without_a_second_audit_row`.
   Red: all three on the round-1 code; then each of the batched list check, the audit-exists check and the
   nothing-pending 409, by a one-line removal.

## Risks

- The cancel request holds a DB connection while it waits for an in-flight batch, bounded by the
  provider call.
- If step 2 fails after step 1 committed, the campaign is `canceled` with rows still `pending`. Nothing
  sends them, because the drive and the claim both stop on the status. A repeat cancel finishes the skip (round 2).
