# Hold payouts of cancelled events
cancelled-event-payouts · Subagent (money) · Notion: none

## Goal and scope
Claim verified (true, with one caveat):
- `PostEventPayoutService.payOneEvent` loads the event (`PostEventPayoutService.java:220-221`) and goes to org eligibility, recoveries, guards and `payouts().create` (`:477`) without reading `event.getStatus()`.
- `EventRepository.findPayoutCandidatesAfter` (`:605-634`) filters `deletedAt IS NULL`, `endsAt < cutoff`, org eligibility, payout runs, test mode only.
- Sweeper ticks 03:00 Europe/Amsterdam (`PostEventPayoutSweeper.java:80-81`), cutoff `now − payoutBufferDays` (`:100-103`) → a CANCELLED event of a manual-schedule org is paid on the first tick after `endsAt + buffer`.
- Caveat: nothing in the API writes CANCELLED (writers: `EventService.publish` LIVE `:328`, `unpublish` DRAFT `:373`, `markLivePast` LIVE→PAST `EventRepository.java:455-461`; `EventPatchRequest` has no status; repo not exported `:20`). CANCELLED exists only via manual SQL today; the parallel cancel-refunds task will add a cancel path that depends on this guard.

Scope: hold the post-event payout of a CANCELLED event on every path to `payouts().create`; make held state visible to ops; resume automatically once not CANCELLED.
Out of scope: refunding buyers (cancel-refunds task), organizer-facing held notice/email, cancelling an already SUBMITTED payout, cancel endpoint, automatic-schedule orgs (Risks).

| status / state (`EventStatus.java:4`) | payout | reason |
|---|---|---|
| PAST | pay | event happened |
| LIVE | pay | past `endsAt`, only waiting for `EventStatusSweeper` (`:59`) |
| DRAFT | pay | unpublish refused while any tier sold (`EventService.java:361-365`); remaining money is earned |
| CANCELLED | **hold** | buyers may be owed refunds from this balance (`reverse_transfer`) |
| soft-deleted | hold (already excluded; re-checked) | delete only for never-published draft with no orders |
| postponed | n/a | LIVE with new dates; moved `endsAt` keeps it out |

Held funds stay on the connected balance (manual schedule for every candidate org, `EventRepository.java:614`); no run row, no attempt spent; org recoveries still run (`PostEventPayoutSweeper.java:145-160`, step 0b); sweeper logs held ids daily; retention monitor flags at 75 days with a cancelled-specific message.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/cancelled-event-payouts` @137edd2a | `docker info && ./mvnw test` |

## Affected files (per repo)
| # | file | change |
|---|---|---|
| 1 | `src/main/java/com/imin/iminapi/payout/PostEventPayoutService.java` | step 0c after `settleDisputes(org)` (`:241`), before 0a (`:243`): hold when CANCELLED/deleted unless a RETRYING run is replayed; status re-read right before step 5 (`:426`); reuse one RETRYING lookup in 1c (`:293`); `static held(Optional<EventStatus>)`; javadoc `:63-130`, comment `:223` |
| 2 | `src/main/java/com/imin/iminapi/repository/EventRepository.java` | `findPayoutCandidatesAfter`: `AND (e.status <> CANCELLED OR EXISTS RETRYING run)`; new `Optional<EventStatus> findStatusIfNotDeleted(UUID)` (scalar JPQL); new `List<UUID> findHeldCancelledPayoutEventIds(Instant cutoff, Pageable)` |
| 3 | `src/main/java/com/imin/iminapi/payout/PostEventPayoutSweeper.java` | after loop (`:137`): held report, ERROR when non-empty, own try/catch |
| 4 | `src/main/java/com/imin/iminapi/payout/PayoutRetentionMonitor.java` | CANCELLED branch message "held, refund or release" (WARN) in loop `:69-76`; query unchanged |
| 5 | `CLAUDE.md` | one sentence near `:258` (hold, RETRYING exemption, daily ERROR, un-cancel) |
| 6 | `src/test/java/com/imin/iminapi/payout/PostEventPayoutServiceTest.java` | `FakeStripe.onBalanceRead` hook (`:155-162`, cleared in `reset()`); T1–T4, T6 |
| 7 | `src/test/java/com/imin/iminapi/payout/PostEventPayoutSweeperTest.java` | T5, T7 |
Unchanged, checked: `PayoutTestModeExclusionTest`, `PostEventPayoutDisputeRecoveryTest`, `OrderSettlementStamperTest` (fixtures PAST/LIVE), `PredictorPagedScanOrderTest` (comment), `StripePayoutScheduleService`, `PayoutScheduleBackfillSweeper`, `service/payout/PayoutService` (`:138-153`), `SettlementIngestService` (`:177-187`), `PayoutController` (`:43-69`, no pay-now).

## Ordered steps
1. Repository: candidate predicate `AND (e.status <> com.imin.iminapi.model.EventStatus.CANCELLED OR EXISTS (SELECT 1 FROM PayoutRun rr WHERE rr.eventId = e.id AND rr.status = com.imin.iminapi.payout.PayoutRunStatus.RETRYING))`; `findStatusIfNotDeleted` = `select e.status from Event e where e.id = :id and e.deletedAt is null`; `findHeldCancelledPayoutEventIds` = org-eligibility EXISTS (`:610-617`, without in-flight NOT EXISTS) + `e.status = CANCELLED`, not deleted, `endsAt < :cutoff`, EXISTS live order (`testMode = false AND totalMinor > 0`), NOT EXISTS run in (PLANNED, SUBMITTED, RETRYING, PAID); `ORDER BY e.endsAt, e.id`; PARTIAL stays in.
2. Step 0c after `settleDisputes(org)`: `replaying = findFirstByEventIdAndStatusOrderByAttemptDesc(eventId, RETRYING).isPresent()`; `if (!replaying && held(Optional.ofNullable(event.getDeletedAt() == null ? event.getStatus() : null))) { log.warn; return; }`. Writes nothing → no-bank park (`:356-367`, `:698-729`) and attempt cap (`:430-436`) cannot fire. Step 1c uses `!replaying`.
3. Race re-check immediately before `nextAttempt(eventId)` (`:430`): `if (!replaying && held(events.findStatusIfNotDeleted(eventId))) { log.warn; return; }` — nothing written yet.
4. Helper `static boolean held(Optional<EventStatus> s) { return s.map(x -> x == EventStatus.CANCELLED).orElse(true); }` + 1-line comment.
5. Sweeper report: `findHeldCancelledPayoutEventIds(cutoff, PageRequest.of(0, 100))`, ERROR `[payout-sweep] {N}[+] cancelled event(s) past the payout buffer have live paid orders and get no payout; refunding buyers or releasing the funds needs a human: {ids}`; try/catch with ERROR.
6. Retention monitor CANCELLED message: `[payout-retention] HELD FUNDS: event {} org {} is CANCELLED, ended {} (>{} d ago), revenue_minor={} {} (gross, refunds not netted) and has no PAID payout run; refund buyers or release the funds before Stripe's retention window closes`.
7. Javadocs + CLAUDE.md in the same edit.
8. Tests T1–T7; prove each guard red.
9. Gate.
Every path to the state machine: `payouts().create` only `:477` (grep: `:477`, `:601` retrieve); `payOneEvent` only from `PostEventPayoutSweeper.java:119`; `reconcileSubmittedRun` (`:585`), `recoverForOrg` (`:558`), `returnRecoveredDisputes` (`:1021`) need no guard; no pay-now endpoint.

## Verification commands
```bash
docker info >/dev/null && cd /Users/ivan/imin/imin-api/.claude/worktrees/cancelled-event-payouts && ./mvnw test -Dtest=PostEventPayoutServiceTest,PostEventPayoutSweeperTest,PayoutTestModeExclusionTest,PostEventPayoutDisputeRecoveryTest
docker info >/dev/null && cd /Users/ivan/imin/imin-api/.claude/worktrees/cancelled-event-payouts && ./mvnw test
```
Base full run first; targeted run judged by its own Tests-run line; skipped Testcontainers = red.

## Test impact
| # | file | name | kind | bug noticed | prove red |
|---|---|---|---|---|---|
| T1 | PostEventPayoutServiceTest | `only_a_cancelled_event_is_held` (`@EnumSource(EventStatus.class)`, 10_000/1_000 order, 50_000 available → one 9_000 payout unless CANCELLED) | integration | cancelled event's money in organizer's bank; or DRAFT/LIVE/PAST never paid; new enum value forces a decision | remove 0c + 3 → CANCELLED red; `held` → `!= PAST` → DRAFT/LIVE red |
| T2 | PostEventPayoutServiceTest | `a_held_cancelled_event_pays_at_attempt_1_once_restored` | integration | un-cancelled event never paid or loses an attempt | hold writes a FAILED/BLOCKED row → red |
| T3 | PostEventPayoutServiceTest | `an_event_cancelled_while_its_payout_is_computed_is_not_paid` (`onBalanceRead` commits CANCELLED on another connection) | integration / concurrency | cancel during the 03:00 tick still pays | remove step-3 re-check |
| T4 | PostEventPayoutServiceTest | `a_timed_out_payout_of_a_since_cancelled_event_is_still_replayed_with_its_key` | integration / idempotency | payout stuck unknown; later un-cancel double-pays with a dropped key | remove `!replaying` from 0c |
| T5 | PostEventPayoutSweeperTest | `cancelled_event_is_not_a_payout_candidate_unless_a_retrying_run_needs_replay` (excluded cancelled ends first) | integration (query) | cancelled events selected; RETRYING replay starved | drop each clause |
| T6 | PostEventPayoutServiceTest | `a_cancelled_event_on_an_account_without_a_bank_sends_no_blocked_notice` | integration | organizer emailed "add a bank" for money they won't get | remove 0c |
| T7 | PostEventPayoutSweeperTest | `held_report_lists_cancelled_events_with_live_paid_orders_only` (excluded fixtures end earliest) | integration (query) | ops told about non-held money or miss held money | remove each predicate |
Isolation: `OrgRows.delete` in `@AfterEach`; T5/T7 assert on own ids; no new context/mock bean/property source; relative instants (no calendar-day logic). Not tested: log wording/levels, retention-monitor message branch, deleted half of `held` (unreachable with money).

## Live-test
Before ship, read-only prod (statuses: `events.status` uppercase, `refunds.status` uppercase, `payout_runs.status` lowercase):
Q0 `select status, count(*) from events where deleted_at is null group by status order by status;`
Q1 cancelled events with imin payout runs:
```sql
select e.id as event_id, e.org_id, e.name, e.ends_at, e.updated_at,
       r.id as run_id, r.status as run_status, r.attempt, r.amount_minor, r.remaining_minor,
       r.currency, r.stripe_payout_id, r.created_at as run_created_at, r.submitted_at, r.paid_at
  from events e join payout_runs r on r.event_id = e.id
 where e.status = 'CANCELLED' and r.test_mode = false
   and r.status in ('planned','submitted','retrying','paid','partial')
 order by r.created_at;
```
Q2 cancelled events with live paid orders:
```sql
select e.id as event_id, e.org_id, e.name, e.ends_at, e.deleted_at,
       org.stripe_payout_schedule_manual, org.stripe_connect_state,
       count(o.id) as live_paid_orders, sum(o.total_minor) as gross_presentment_minor,
       coalesce(sum(rf.refunded_minor), 0) as refunded_presentment_minor,
       string_agg(distinct o.currency, ',') as currencies,
       exists (select 1 from payout_runs r where r.event_id = e.id and r.test_mode = false
                 and r.status in ('submitted','paid','partial')) as has_imin_payout
  from events e
  join organizations org on org.id = e.org_id
  join orders o on o.event_id = e.id and o.test_mode = false and o.total_minor > 0
  left join (select order_id, sum(amount_minor) as refunded_minor from refunds where status = 'SUCCEEDED' group by order_id) rf on rf.order_id = o.id
 where e.status = 'CANCELLED'
 group by e.id, e.org_id, e.name, e.ends_at, e.deleted_at, org.stripe_payout_schedule_manual, org.stripe_connect_state
 order by e.ends_at nulls last;
```
After deploy: next 03:00 tick logs `[payout-sweep] tick done` (+ ERROR line with Q2 ids if any qualify); `select id, status, created_at from payout_runs where event_id in (<Q2 ids>) and created_at > '<deploy time>';` → 0 rows. No positive demo without a prod write (needs Ivan's OK).

## Contract impact
None.

## i18n impact
None (ops log lines only; ledger in steps 2, 3, 5, 6).

## Blast radius
- Global 03:00 sweep for every manual-schedule org; change can only remove payouts; T1 pins DRAFT/LIVE/PAST payable.
- `findPayoutCandidatesAfter` single caller (`PostEventPayoutSweeper.java:110,133`); new methods no callers.
- No new writer; existing `events.status` writers lock then read (`EventService.java:405-408`, `TicketTierService:241`, `PromoCodeService:158-159`, `MediaUploadService:144`).
- No migration, no contract, no new Stripe call. Org recoveries unaffected. Other open claims (dispute 1 `:269`, unsized lost 1b `:276`, open refund 1c `:293`) still hold after un-cancel.

## Risks
- Automatic-schedule orgs are paid by Stripe regardless (candidates require manual `:614`; backfill flip every 10 min; `ensureManual` failures `StripePayoutScheduleService.java:62-80`) — cancel feature must check manual first.
- Organizer-initiated Express/instant payouts — unverified; hold doesn't cover them.
- SUBMITTED payout at cancel time not cancelled (follow-up).
- Residual ms race between step-3 read and `create`; closing it belongs to the cancel endpoint (lock + run check).
- RETRYING exemption may pay the pre-cancel amount.
- Daily ERROR noise while funds are held.

## Definition of done
- Steps 1–7 implemented; javadoc + CLAUDE.md updated.
- T1–T7 added, each guard proven red once.
- Targeted + full `./mvnw test` green, Docker up, zero skipped; base recorded first.
- No new context/mock bean/property source.
- Q0–Q2 run read-only and recorded before /ship-imin.
- Follow-ups proposed: cancel-event feature (lock, run check, refunds, notice — covered by cancel-refunds task), Express payout permissions, SUBMITTED payout cancel.

## Live-test evidence
Pre-ship read-only prod 2026-10-12: Q0 DRAFT 36, LIVE 1, PAST 33, no CANCELLED; Q1 0 rows; Q2 0 rows; STRIPE_PAYOUT_BUFFER_DAYS=1. No money exposed today; guard is preventive for the coming cancel feature.

## Review rounds
- Gate 1 approved by the user 2026-10-12: RETRYING on a cancelled event → replay with the same key; daily held line at ERROR. Repro: n/a (T1 is red on base for CANCELLED).
- round 1 → APPROVE (MEDIUM: held report counts fully refunded orders as live → false nightly ERROR after cancel-refunds; LOW: held() splits nextAttempt javadoc). Reviewer: missing @Transactional on payOneEvent (on static payoutDescription since 367ecaef) is a separate prod defect → own card; this change is safe without it.
