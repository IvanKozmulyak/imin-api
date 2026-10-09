# Checkout email consent lost when the live audience projection fails: the backfill restores it from the order

## Goal

`AudienceOrderProjector.onTicketsIssued` runs `project(orderId)` in a REQUIRES_NEW `TransactionTemplate` inside a
try (`AudienceOrderProjector.java:82-89`). The buyer's checkout email consent is written in that same transaction
(`consentService.capture`, `AudienceOrderProjector.java:239-242` on base). If the commit fails (serialization,
deadlock, lost connection, any transient error), the consent is rolled back with everything else and logged once.
Nothing restores it: the nightly `AudienceBackfillJob` called the 3-arg `upsertMembership`
(`AudienceBackfillJob.java:109` on base), which passes `emailOptIn=false` (`AudienceOrderProjector.java:157` on base).
A buyer who ticked "email me" ends up with no consent and is never mailed.

**Chosen fix: (b), the backfill restores checkout consent from the order.** The order is the source of truth. It
stores the tick (`orders.marketing_opt_in`, `Order.java:153`, set at `PaidCheckoutService.java:203` and
`FreeCheckoutService.java:176`), the sentence the buyer read (`marketing_opt_in_proof`, `Order.java:162`, V97) and
its version (`marketing_opt_in_text_version`, `Order.java:166`, V151). Every order row is created only when tickets
are issued (`PaidCheckoutService.java:181`/`:259`, `FreeCheckoutService.java:159`/`:212`), so an opted-in order
always stands for a ticked box.

Why (b) over (a), a retry: a retry only covers failures it can classify as transient and only while the process is
alive. A crash or deploy between the order commit and the async projection, or a permanent-looking error that was
really transient, still loses the consent. (b) heals every such case, including history, on the next nightly or
startup pass, and is idempotent.

What the backfill now does per (org, email) pair, in one transaction (`AudienceOrderProjector.backfillMembership`,
`AudienceOrderProjector.java:235`):

1. The same projection as before (consumer + locked membership + recompute), via the extracted `projectMembership`
   (`:267`), shared with the live path.
2. **Erasure re-check under the lock** (`:238-243`). The job loads the erasure ledger once at start
   (`AudienceBackfillJob.java:90-95`), so an erasure committed after that snapshot would otherwise be rebuilt.
   `DsarService.executeErase` takes the same membership lock and writes the ledger in its transaction, so after
   the lock the ledger is final. If the address is erased (platform-wide or for this org), the row's transaction
   is marked rollback-only and returns: no consumer, membership or consent is written. `backfillMembership` owns
   its transaction (the job has none), so the rollback is quiet, not an `UnexpectedRollbackException`.
3. For each of the buyer's orders in that org (`OrderRepository.findByOrgIdAndNormalizedEmail`, the same query the
   recompute uses), `restorable` (`:253`) refuses the restore when any of these holds, in order:
   - the box is not ticked (`marketing_opt_in` false). The buyer site stores the sentence even when the box was
     left unticked (`CheckoutConsent.java:85-86`), so the sentence alone proves nothing;
   - the sentence is null or blank (the live path records nothing then, `AudienceOrderProjector.java:221-225`);
   - the membership is `erase_pending` (an erasure request waiting for the job, `DsarService.requestErase`);
   - the membership is `unsubscribed` now (same rule as the live path, `:218`);
   - the membership has `objected_profiling` set (a spam complaint sets only that, `ResendWebhookProjector.java:134`;
     the capture is `DATA_SUBJECT` and would lift it, `ConsentService.java:189-191`);
   - **the grant already exists** (`ConsentService.hasCheckoutGrant`, `ConsentService.java:338`): an email
     `checkout` `subscribed` record on this membership with `order_id` = the order, **or** with `order_id` null and
     `proof_text` ending `, order <id>`. Rows before V132 have no `order_id` (`V132__audienceplan_consent_columns.sql`
     comment: "older rows carry the id only inside proof_text"); every checkout capture since the first one
     (93cb8fac, 2026-07-16) ended its proof text with `", order " + orderId`. Without the legacy clause, every
     historical opted-in order would get a second record on the first run;
   - **the member was unsubscribed when the order came in** (`ConsentService.unsubscribedFromEmailAt`, `:350`;
     `ConsentRecordRepository.latestEmailRecordBeforeIsUnsubscribe`, `:67`): the latest email record before
     `order.created_at` that was in force then is an unsubscribe. Grants still awaiting confirmation at that time
     do not count; on a tie the unsubscribe wins. The live path refused this grant on purpose (`:218`), so a later
     re-consent must not let the backfill write it;
   - **an unsubscribe at or after the order** (`ConsentService.unsubscribedFromEmailSince`, `:344`). This covers a
     buyer who unsubscribed and has since re-subscribed elsewhere: the restore never puts an old order's grant on
     top of a newer opt-out.
   Otherwise it captures as the live path does (`captureCheckoutConsent`, `:292`): basis `explicit`, source
   `checkout`, same proof text, `textVersion` = `provenTextVersion(order)`, `order_id`, origin `DATA_SUBJECT`,
   **dated `order.created_at`** through the new `ConsentService.capture` overload with `occurredAt`
   (`ConsentService.java:128-145`). The record's default is now (`ConsentRecord.java:68`), which would give a wrong
   Art.7(1) proof date, could make the restored row the "latest" consent in `ConsentGateSql.java:45-49` over a newer
   proven one, and would restart the 3-year retention clock.
4. **The live path skips a grant that already exists** (`AudienceOrderProjector.java:227`): when the order id is
   known it checks `hasCheckoutGrant` under the membership lock before capturing. A delayed live projection that
   runs after the backfill has restored the order therefore writes no second record.

`ConsentService.capture` does **not** dedup (`ConsentService.java:120-153` appends a row on every call), so the
dedup lives in the backfill check above. It runs under the membership row lock taken by `lockOrCreateMembership`,
and the live projection takes the same lock, so a live capture and a backfill row for one member cannot both miss.

**SMS is not lost this way, so it is not changed.** The SMS opt-in is written synchronously by
`SmsConsentService.submit` in the request's own transaction: it stamps the order (`SmsConsentService.java:82-84`)
and captures the `sms` consent record and membership state itself (`:88-92`). `orders.sms_marketing_opt_in` is set
nowhere else. The projector's SMS branch (`AudienceOrderProjector.java:219-222` on base) only re-applies state that
is already committed, so its rollback loses nothing.

### Writers audit (consent state on `memberships` and `consent_records`)

The new writer is the backfill's capture. It goes through `ConsentService.capture`, which flushes, takes the
membership row lock and refreshes (`ConsentService.requireMembership`, `ConsentService.java:342-348`). Every other
writer of `consent_status`/`consent_basis`/`objected_profiling` takes the same lock first:

- `ConsentService.capture` / `confirmPending` / `unsubscribe` (`:123`, `:161`, `:221`, via `requireMembership`); all
  callers listed in `2026-10-08-fix-audience-projector-race.md` § Lock-order audit.
- `AudienceOrderProjector.upsertMembership` (live) and `backfillMembership`: `lockOrCreateMembership` (`:108-115`),
  then `membershipRepo.save(m)` of the locked, recomputed row.
- `SmsConsentService.upsertMembership` (`SmsConsentService.java:102-110`): `lockOrCreateMembership`.
- `ResendWebhookProjector` complaint branch (`ResendWebhookProjector.java:133-138`): `lockByIdAndOrgId`.
- `DoorOptInService`/`SurveyService`: lock-first `findMembership`; `RetentionJob`, `DsarService.requestErase`: lock.

So an unsubscribe that commits while a backfill row runs waits for the row lock, or is seen by it; the backfill's
whole-row save happens under that lock and the capture refreshes before writing. Both orderings end unsubscribed:
unsubscribe first → the backfill sees the status/record and skips; backfill first → the unsubscribe runs after it
and wins. No new lock order: the backfill takes the same single membership lock as the live projection.

### Affected buyers in prod today (read-only, not run)

Opted-in orders with a proof sentence, whose member has no checkout grant for that order, and no later opt-out:

```sql
-- READ ONLY. Buyers whose checkout email opt-in never reached consent_records.
select o.org_id, o.id as order_id, o.created_at, lower(o.email) as email,
       m.membership_id, m.status as membership_status, m.consent_status, m.objected_profiling,
       coalesce((select r.status = 'unsubscribed' from consent_records r
                 where r.membership_id = m.membership_id and r.channel = 'email' and r.occurred_at < o.created_at
                   and (r.status = 'unsubscribed' or r.confirmation_required = false
                        or r.confirmed_at < o.created_at)
                 order by r.occurred_at desc, (r.status = 'unsubscribed') desc limit 1), false)
         as unsubscribed_when_ordered
from orders o
left join consumers c on c.normalized_email = lower(o.email)
left join memberships m on m.org_id = o.org_id and m.consumer_id = c.consumer_id
where o.marketing_opt_in
  and o.marketing_opt_in_proof ~ '\S'   -- not null and not all whitespace, as Java isBlank
  and not exists (select 1 from erased_addresses e
                  where e.email_normalized = lower(o.email) and (e.org_id is null or e.org_id = o.org_id))
  and not exists (select 1 from consent_records r
                  where r.membership_id = m.membership_id and r.channel = 'email' and r.source = 'checkout'
                    and r.status = 'subscribed'
                    and (r.order_id = o.id or (r.order_id is null and r.proof_text like '%, order ' || o.id::text)))
order by o.created_at;
-- Of those, the ones the backfill will restore:
--   and not unsubscribed_when_ordered   (as a subquery or in an outer select)
--   and coalesce(m.status, 'active') <> 'erase_pending'
--   and coalesce(m.consent_status, 'never') <> 'unsubscribed'
--   and not coalesce(m.objected_profiling, false)
--   and not exists (select 1 from consent_records u where u.membership_id = m.membership_id
--                   and u.channel = 'email' and u.status = 'unsubscribed' and u.occurred_at >= o.created_at)
```

Rows with `unsubscribed_when_ordered = true` are expected in the first set: the live path skipped them on purpose
(`AudienceOrderProjector.java:218`), and the backfill keeps that refusal.

## Affected files

- `src/main/java/com/imin/iminapi/audience/service/AudienceOrderProjector.java`: extracts `projectMembership` and
  `captureCheckoutConsent` from the 9-arg `upsertMembership`; the live path also skips an existing grant for its
  order; adds `backfillMembership` and `restorable`; the constructor gains `ErasedAddressRepository`.
- `src/main/java/com/imin/iminapi/audience/service/AudienceBackfillJob.java`: calls `backfillMembership` instead of
  the 3-arg `upsertMembership`.
- `src/main/java/com/imin/iminapi/audience/service/ConsentService.java`: adds `hasCheckoutGrant`,
  `unsubscribedFromEmailSince`, `unsubscribedFromEmailAt` (reads) and a `capture` overload taking `occurredAt`
  (null = now; every existing overload passes null). No constructor change.
- `src/main/java/com/imin/iminapi/audience/repository/ConsentRecordRepository.java`: adds `existsCheckoutGrant`,
  `existsEmailUnsubscribeSince` and `latestEmailRecordBeforeIsUnsubscribe` (native).
- Plain-instance constructor call sites, mechanical (add `erasedAddresses`):
  `src/test/java/com/imin/iminapi/audienceplan/service/FanFeatureTriggerEventsTest.java`,
  `src/test/java/com/imin/iminapi/marketing/MarketingOptInWriteTest.java`,
  `src/test/java/com/imin/iminapi/audience/AudienceOrderProjectorRaceTest.java`,
  `src/test/java/com/imin/iminapi/audience/controller/NeverSoftOptInGuardTest.java`.
- `src/test/java/com/imin/iminapi/audience/CheckoutConsentBackfillTest.java` (new, `@IminIntegrationTest`).
- `docs/superpowers/plans/2026-10-09-fix-checkout-consent-loss.md` (this plan).

## Test impact

All in `CheckoutConsentBackfillTest`, on Postgres, through the real async listener and the real `@SchedulerLock`ed
job (`run()` after expiring the `audience_backfill` row). The live failure is
`PgFaults.failWrites(consent_records, order_id, order)`: the consent INSERT is rejected inside the projection's own
transaction, so the whole projection rolls back as in the defect.

Every guard is proven by removing it alone in a scratch copy (never in the worktree) and running the class.

- `failedProjectionCommit_backfillRestoresTheCheckoutConsent` (parameterized `PLAIN`, `ORGANIZER_NAMED`): after the
  failed projection, no grant; after one backfill, exactly one email record for the order,
  `subscribed`/`explicit`/`checkout`, the live path's proof text with the order id, `occurred_at` = the order's stored
  `created_at`, `text_version` null for `PLAIN` and `checkout-org-named-2026-09` (on the allowlist in
  `audienceplan/logic-v1.yaml:37`, sentence naming the org) for `ORGANIZER_NAMED`, and the membership
  `subscribed`/`explicit`. Red on base (0 records); red with `occurredAt` passed as null; `ORGANIZER_NAMED` red with
  the version passed as null.
- `orderWithoutATickedBoxAndItsSentence_backfillRecordsNothing` (parameterized `NOT_TICKED` with the sentence,
  `NULL_PROOF`, `BLANK_PROOF`): no grant. `NOT_TICKED` red with the tick guard removed; the other two red with the
  sentence guard removed.
- `optOutAfterTheOrder_winsOverTheBackfill` (parameterized): an existing member, a failed opted-in order, then
  - `UNSUBSCRIBED`: a one-click `DATA_SUBJECT` unsubscribe (held by both the status check and the since-check);
  - `UNSUBSCRIBED_THEN_RESUBSCRIBED`: that, then the person's own re-consent (`DATA_SUBJECT`, which lifts the
    objection); red with the since-check removed;
  - `OBJECTED`: `objected_profiling` set alone, as a spam complaint does; red with the objection check removed;
  - `ERASE_PENDING`: membership status `erase_pending`; red with that check removed.
  Asserts no grant for the order and consent status, basis and objection unchanged.
- `unsubscribedWhenTheOrderCameIn_backfillKeepsTheLiveRefusalAfterAReconsent` (parameterized): unsubscribe, then an
  opted-in order whose live projection records nothing (on purpose), then the person's own re-consent, then the
  backfill: no grant.
  - `UNSUBSCRIBED`: red with the at-order check removed.
  - `UNSUBSCRIBED_THEN_UNCONFIRMED_SIGNUP`: an unconfirmed `door_qr` sign-up between the unsubscribe and the order.
    Red with the "awaiting confirmation does not count" clause removed.
- `grantAlreadyRecordedForTheOrder_backfillWritesNoSecond` (parameterized), one checkout record after a second run:
  - `EARLIER_BACKFILL_RUN`: failed projection, backfill, backfill. Red on base (0); red with the `order_id` clause
    removed (2).
  - `RECORDED_BEFORE_ORDER_ID_COLUMN`: a pre-V132-shaped row (no `order_id`, id at the end of `proof_text`). Red with
    the legacy clause removed (2).
- `liveProjectionAndBackfillOfOneOrder_eitherOrder_recordOneGrant` (parameterized `LIVE_FIRST`, `BACKFILL_FIRST`):
  one checkout record either way. `BACKFILL_FIRST` red with the live path's existing-grant check removed;
  `LIVE_FIRST` is held by the backfill's `order_id` dedup. Both paths take the membership row lock before the check,
  so a concurrent run serializes into one of these two orders.
- `erasureCommittedAfterTheJobsLedgerSnapshot_backfillRowWritesNothing`: a failed opted-in order, then
  `DsarService.recordErasure` for the org, then `backfillMembership` called directly, as the job calls it with a
  snapshot taken before that erasure. Asserts no grant and no membership. Red with the re-check removed.

No test for the two repository reads on their own (they are owned by the backfill test), none for the log.

## Risks

- **Basis of very old orders.** The restore records `explicit`, as the live path does today for any order with the
  tick and a sentence. The buyer-site box has been unticked since 2026-09-08 (c219ac58 message), the same day the
  proof column shipped (V97, cef48512). Orders from the pre-ticked era that kept their `soft_opt_in` record are
  deduped by the legacy clause; one whose record was lost and that carries a proof sentence would be restored as
  `explicit`. The prod SQL above lists them by `created_at` for a manual look before the next startup pass.
- **First run after deploy** writes one record per affected order (see SQL). Each publishes `ConsentChanged`, so
  fan-feature recompute work follows; scale is the affected-row count, small today.
- **Per-row cost.** Two ledger `exists`, one extra order query, and up to three consent reads per opted-in order,
  inside the row's transaction.
- The legacy match is a `like` on `proof_text` with a UUID suffix (no `%`/`_` in a UUID); a proof sentence that
  itself ended in `, order <that same id>` is the only false hit, and it can only cause a skip.

## Definition of done

- Every test above is red where stated, and each guard is red in its own scratch copy; all green on the fix.
- Targeted: `./mvnw test -Dtest='Audience*Test,MembershipConsentLockTest,ConsentService*Test,MarketingOptInWriteTest,CheckoutConsentBackfillTest,SpringContextGuardTest'` green.
- Full `./mvnw test` green with no skipped tests.
