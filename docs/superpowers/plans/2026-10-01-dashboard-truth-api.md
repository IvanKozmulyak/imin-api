# Dashboard figures that say what they count (api)
`dashboard-truth-api` · Subagent, api only (webapp follow-up is a separate later task) · Notion: not provided

## Goal and scope
These four defects on `GET /api/v1/dashboard` were found in the M3-7b-1 review and re-verified in this worktree (origin/master 6d05ec4d):

1. **`business.audienceCount` is hard-coded 0** (DashboardService.java:165-166). In this codebase "audience" means the Audience registry `memberships`. The Audience page's "People" total is `MembershipRepository.countByOrgId` (MembershipRepository.java:194-195, read at AudienceMetricsService.java:38). **Decision:** return that same count. It is org-wide and all-time, not windowed. The type widens from `int` to `long`.
2. **`pctDelta` returns 100 when the prior window is 0** (DashboardService.java:201-204), and `Deltas(0, 0)` for `all` (:133). **Decision:** both deltas become `Integer`. They are null when the prior window's value is 0 (whatever the current value is) and null for `all`. Otherwise `Math.round(100.0 * (cur − prior) / prior)`, as today.
3. **Cycle and business revenue is a gross sum of `totalMinor`** (OrderRepository.java:137-145). That sum includes the buyer booking fee (`applicationFeeMinor`: TicketIssuanceEmailer.java:136-137 treats it as the booking fee; QuoteResponse.java:10-15 gives 5% of subtotal + €0.99 per ticket), test-mode orders, refunds and chargebacks. The count beside it is `count(o)`, an order count published as `ticketsSold`. **Decision:** reuse the only organizer-money definition in the codebase that is live-only and after fees: the payout per-event net at PostEventPayoutService.java:229-239, which is `max(0, max(0, gross − refunded) − max(0, appFee − appFeeRefunded) − disputedOpenOrLost)`, every term live-mode. It is applied to the cohort of live-mode orders created in `[since, until)`, net of those orders' succeeded refunds and OPEN/LOST disputes. `ticketsSold` becomes the tickets on those orders whose state is not `refunded`/`revoked`. That is the SOLD predicate used across TicketRepository.java:143-228, and the name finally matches. Refunded and charged-back orders still count toward revenue only through their net, so a fully refunded order adds 0.
4. **`lastEvent.metrics.avgTicketMinor`** is `(sum totalMinor − refunds − disputes) / tier.sold` (DashboardService.java:93-96). That includes the booking fee and test mode in the numerator, and test-mode tickets in the denominator. **Decision:** `Integer avgTicketMinor = liveTickets == 0 ? null : Math.round((double) liveNet / liveTickets)`. `liveNet` is the payout per-event net, built only from existing live queries. `liveTickets` counts the event's tickets on live-mode orders that are not refunded or revoked.

Out of scope, each its own card:
- `EventDto.revenueMinor` on the Now and LastEvent summaries stays the Overview definition (DashboardService.java:122-126 = EventOverviewService.java:92-94), so the home still matches Overview for an event. The webapp labels it "gross".
- `lastEvent.metrics.attended` is misnamed: it carries tier sold minus disputed tickets. Only a description is added, no rename.
- `repeatRatePct` still reads `orderCountsByEmailSince`, which includes test mode and is shared with AttributionService.java:105.
- `eventsPublished`/`eventsCompleted` are all-time under a period selector (card `metric-window-labels`).
- Multi-currency orgs: sums are currency-blind, as today.
- Payout code is not touched.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | /Users/ivan/imin/imin-api/.claude/worktrees/dashboard-truth-api | `docker info` then `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test` |

The webapp follow-up (a separate task) ships after prod OpenAPI shows the markers under Contract impact. `api:sync` runs in /ship-imin.

## Affected files (per repo)
### imin-api (13 files plus this plan)
Main:
1. `src/main/java/com/imin/iminapi/service/dashboard/DashboardRevenue.java` (new, `@Component`). Constructor takes `OrderRepository`, `RefundRepository`, `DisputeRepository`, `TicketRepository`. API:
 - `public static long net(long gross, long refunded, long appFee, long appFeeRefunded, long disputed)`: `Math.max(0L, Math.max(0L, gross - refunded) - Math.max(0L, appFee - appFeeRefunded) - disputed)`, the exact expression at PostEventPayoutService.java:233,238-239. One-line comment naming that mirror.
 - `public record Window(long netRevenueMinor, long ticketsSold) {}`
 - `public Window forOrgWindow(UUID orgId, Instant since, Instant until)`, reading the four new org-window queries below.
 - `public long liveNetForEvent(UUID eventId)`, built only from existing queries: `orders.sumLiveTotalMinorByEventId` (OrderRepository.java:85-87), `refunds.sumSucceededLiveRefundMinorByEventId` (RefundRepository.java:77-83), `orders.sumLiveApplicationFeeMinorByEventId` (OrderRepository.java:90-92), `refunds.sumSucceededLiveRefundApplicationFeeMinorByEventId` (RefundRepository.java:86-92), `disputes.sumOpenOrLostLiveMinorByEventId` (DisputeRepository.java:166-168). These are the same five calls payout makes.
 - `public long liveTicketsForEvent(UUID eventId)`
2. `src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java`:
 - Constructor adds `DashboardRevenue revenue` and `MembershipRepository memberships`.
 - `buildCycle` and `buildBusiness` read `revenue.forOrgWindow`. `all` uses `Instant.EPOCH → now` with `new Deltas(null, null)`.
 - `pctDelta` returns `Integer`, null when `prior == 0`.
 - LastEvent: `avgTicket` as in Goal 4. The no-past-event fallback becomes `new LastEventMetrics(0, 0, null, null)`.
 - Business: `audienceCount = memberships.countByOrgId(p.orgId())`.
 - `revenueNetOfRefundsAndDisputes` and `soldNetOfDisputes` stay; they still feed the EventDto summaries and `attended`.
3. `src/main/java/com/imin/iminapi/dto/dashboard/DashboardResponse.java`:
 - `Deltas(Integer revenuePct, Integer ticketsPct)`
 - `LastEventMetrics(int attended, int capacity, Integer avgTicketMinor, Integer nps)`
 - `Business(long totalRevenueMinor, long eventsPublished, long eventsCompleted, long audienceCount, int repeatRatePct)`
 - `@io.swagger.v3.oas.annotations.media.Schema(description = …)` on the 8 components listed under Contract impact, using the CampaignRequests.java:54-56 pattern. On the two Deltas fields and `avgTicketMinor`, also set `types = {"integer", "null"}`. `String[] types()` exists in swagger-annotations-jakarta 2.2.43, the version springdoc 3.0.2 pins (javap on `~/.m2/.../swagger-annotations-jakarta-2.2.43.jar`). If the rendered 3.1 doc rejects it, drop `types` and keep the description, which is the marker.
4. `src/main/java/com/imin/iminapi/repository/OrderRepository.java`:
 - Add `List<Object[]> sumLiveTotalAndApplicationFeeByOrgInWindow(orgId, since, until)`: `select coalesce(sum(o.totalMinor),0), coalesce(sum(o.applicationFeeMinor),0) from Order o where o.orgId = :orgId and o.testMode = false and o.createdAt >= :since and o.createdAt < :until`.
 - Delete `sumRevenueAndCountByOrgInWindow` (:133-145). Its only callers were DashboardService and DashboardServiceTest (grep).
 - Repoint the `{@link #sumRevenueAndCountByOrgInWindow}` in `countByOrgIdSince`'s javadoc (:55) to the new method.
 - Rewrite the sentence at :80-83 to: "Overview, Sales and the event list still show the full history; the org home's window and per-ticket figures read the live variants."
 - Select only, no `@Modifying`, so `OrderEmailNormalizedInvariantTest` is unaffected.
5. `src/main/java/com/imin/iminapi/refund/RefundRepository.java`: add `List<Object[]> sumSucceededLiveRefundAndFeeByOrgInWindow(orgId, since, until)`: `select coalesce(sum(r.amountMinor),0), coalesce(sum(r.applicationFeeRefundMinor),0) from Refund r where r.status = SUCCEEDED and r.orderId in (select o.id from Order o where o.orgId = :orgId and o.testMode = false and o.createdAt >= :since and o.createdAt < :until)`.
6. `src/main/java/com/imin/iminapi/dispute/DisputeRepository.java`:
 - Add `@Query long sumLiveMinorByOrgOrderWindowAndStatusIn(orgId, since, until, statuses)`: `d.orgId = :orgId and d.status in :statuses and d.testMode = false and d.orderId in (select o.id from Order o where o.orgId = :orgId and o.testMode = false and window)`.
 - Add a default `sumOpenOrLostLiveMinorByOrgOrderWindow(orgId, since, until)` passing `DisputeWithholding.STATUSES`, so the withholding set stays single-sourced.
7. `src/main/java/com/imin/iminapi/repository/TicketRepository.java`:
 - Add `long countSoldLiveByOrgInWindow(orgId, since, until)`: `select count(t) from Ticket t, Order o where t.orderId = o.id and o.orgId = :orgId and o.testMode = false and o.createdAt >= :since and o.createdAt < :until and t.state not in ('refunded','revoked')`.
 - Add `long countSoldLiveByEventId(eventId)`: same join, `t.eventId = :eventId and o.testMode = false and t.state not in ('refunded','revoked')`.
 - No nullable parameters (avoids the H2/PG bytea trap).
8. `src/main/java/com/imin/iminapi/dispute/DisputeWithholding.java`: javadoc only, at :12-16 and :40. Note that the org home's window and avg figures use the LIVE variant through `DisputeRepository` default methods over `STATUSES`.

Docs:
9. `docs/superpowers/API_CONTRACT.md` §4 (:380-420):
 - Example `deltas` gains `null`.
 - Field notes in one line each: revenue definition, ticketsSold = tickets, avg nullable, audienceCount = Audience total.
 - Remove the stale `squadRatePct`.

Tests:
10. `src/test/java/com/imin/iminapi/service/dashboard/DashboardRevenueTest.java` (new, `@SpringBootTest @Import(TestRateLimitConfig.class)`). H2 fixtures, `@AfterEach` wipe in the PayoutTestModeExclusionTest.java:396-405 order. Instants relative to `Instant.now()` truncated to micros. Cases under Test impact.
11. `src/test/java/com/imin/iminapi/service/dashboard/DashboardRevenueNetTest.java` (new, plain JUnit): `net(...)` branches.
12. `src/test/java/com/imin/iminapi/service/dashboard/DashboardServiceTest.java`:
  - `sut` construction adds the `DashboardRevenue` and `MembershipRepository` mocks.
  - `stubEmptyAuxiliary` stubs `revenue.forOrgWindow` → `new Window(0, 0)` instead of the deleted query.
  - `populated_org_…` (:82-126): avg now from `liveNetForEvent`/`liveTicketsForEvent` stubs. Stub 475_200 and 198, expected `avgTicketMinor` stays 2400.
  - `cycle_30d_…` (:225-250) rewritten as the worked delta test.
  - New branch tests under Test impact.
  - `now_and_last_event_cards_are_net_of_disputes` and `now_card_revenue_subtracts_refunds…` stay as they are: they pin the EventDto path, which does not change. They do read `orders.sumTotalMinorByEventId`, which is still used.
13. `src/test/java/com/imin/iminapi/controller/dashboard/DashboardControllerTest.java`:
  - The existing constructor literals still compile (`0` autoboxes to `Integer`, widens to `long`).
  - Add a null-serialization test and an OpenAPI marker test.

No change needed, one line each:
- `payout/PostEventPayoutService.java`: the formula is copied, not shared. Touching the money path is not needed for a readout.
- `service/event/EventOverviewService.java`, `EventSalesTotals.java`, `SalesDashboardService.java`: per-event all-mode readouts keep their definition (Goal, out of scope).
- `service/analytics/AttributionService.java`: `orderCountsByEmailSince` is unchanged.
- `audience/repository/MembershipRepository.java`: `countByOrgId` is reused as is.
- `config/CacheConfig.java`: same cache name and key.
- `DashboardCacheTest`, `DraftEventDeletionServiceTest`, `CrossOrgScopingTest`: grep shows they read only `now().ticketsTotal()`, `activity()` and the body string (CrossOrgScopingTest.java:425-435). None of the changed fields or fixtures, so they stay green unedited.
- `MembershipProjectorTest.java:59`, `PayoutTestModeExclusionTest`, `EventSalesTotalsTest`: they reference queries this plan keeps.

## Ordered steps
0. Run `docker info`, then the full gate on the untouched base. A gate that is already red is its own card.
1. Add the four repository methods (files 4-7) and delete `sumRevenueAndCountByOrgInWindow`, including the javadoc edits.
2. Add `DashboardRevenue` (file 1).
3. Change the DTO (file 3) with the `@Schema` descriptions given verbatim under Contract impact.
4. Rewire `DashboardService` (file 2):
 - `buildCycle(all)`: `Window w = revenue.forOrgWindow(org, EPOCH, now)` → `new Cycle(wire, w.netRevenueMinor(), (int) w.ticketsSold(), active, new Deltas(null, null))`.
 - Windowed cycle: current `[now−d, now)` and prior `[now−2d, now−d)`, then `pctDelta(cur.net, prior.net)` and `pctDelta(cur.tickets, prior.tickets)`.
 - `buildBusiness`: `revenue.forOrgWindow(org, all ? EPOCH : now−d, now).netRevenueMinor()`, plus `memberships.countByOrgId`.
 - LastEvent avg from `liveNetForEvent`/`liveTicketsForEvent`.
 - Code comments are 1–2 lines and name no ticket.
5. Write the tests (files 10-13). Then prove that each guard test fails without its guard: remove each filter line listed under Test impact once, see the named test go red, restore it. Record the pairs in the handoff.
6. Update `DisputeWithholding` javadoc and `API_CONTRACT.md` (files 8, 9).
7. Run the full gate, read the `Tests run:` line, and confirm 0 skipped Testcontainers tests.

## Verification commands
- `docker info >/dev/null && cd /Users/ivan/imin/imin-api/.claude/worktrees/dashboard-truth-api && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`
- Targeted (comma-separated): `./mvnw test -Dtest=DashboardRevenueTest,DashboardRevenueNetTest,DashboardServiceTest,DashboardControllerTest`. Judge it by its own `Tests run:` line.
- `grep -rn 'sumRevenueAndCountByOrgInWindow' src` → 0 hits.
- `grep -n 'audienceCount \*/ 0\|current == 0 ? 0 : 100' src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java` → 0 hits.

## Test impact
Branch map → test (one minimal setup each).

**Worked-example fixtures** (minor units; fees set by hand, realistic per StripeProperties.java:66-67 5% + 99):

| order | mode | lines | appFee | total | extra |
|---|---|---|---|---|---|
| A | live | 2 × 2000 | 398 | 4398 | tickets issued + redeemed |
| B | live | 1 × 1000 | 149 | 1149 | SUCCEEDED refund 1149 / fee refund 149; ticket refunded |
| C | live | 1 × 1000 | 149 | 1149 | LOST dispute 1149, live; ticket revoked |
| D | test | 1 × 5000 | 349 | 5349 | SUCCEEDED refund 1000 / fee 0; OPEN dispute 5349, test_mode = true; 1 ticket issued |
| E | live, created now−40d | 1 × 3000 | 249 | 3249 | 1 ticket issued |

That is 5 rows. Current 30d window = A, B, C, D. Prior window = E.

Arithmetic:
- gross = 4398 + 1149 + 1149 = 6696
- refunded = 1149, so gross − refunded = 5547
- netAppFee = 696 − 149 = 547
- disputed = 1149
- **net = 5547 − 547 − 1149 = 3851**. Cross-check per order: A 4000 + B 0 + C (1149 − 149 − 1149 = −149) = 3851.
- Live tickets = 2.
- Old figures, for contrast: gross 12045 over 4 orders.
- Prior: 3249 − 249 = **3000**, 1 ticket.
- Deltas: round(100 × 851 / 3000) = round(28.37) = **28**; round(100 × 1 / 1) = **100**.

`DashboardRevenueTest` (H2), 13 cases:

| # | branch | setup | expect | guard proven by removing |
|---|---|---|---|---|
| 1 | worked example, window | A, B, C, D in window | `Window(3851, 2)` | — |
| 2 | test-mode order out of gross and fee | A + D (no refund or dispute) | net 4000 | `o.testMode = false` in the order query |
| 3 | test-mode order's refund ignored | A + D with refund 1000 | net 4000 (3000 without the guard) | `o.testMode` in the refund subquery |
| 4 | test-mode dispute ignored | A + OPEN dispute 4398, test_mode = true, on A | net 4000 | `d.testMode = false` |
| 5 | dispute on a test-mode order ignored | A + D, dispute on D with test_mode = false | net 4000 | `o.testMode` in the dispute subquery |
| 6 | non-SUCCEEDED refund ignored | A + PENDING refund 4398 | net 4000 | refund status predicate |
| 7 | WON dispute ignored | A + WON dispute 4398 | net 4000 | `d.status in :statuses` |
| 8 | half-open window | orders at `since` (A) and at `until` (B), explicit bounds | B excluded, net 4000 | `<` on `until` |
| 9 | other org excluded | org-B order 4398 in window | org-A net 0 | `o.orgId` |
| 10 | ticket SOLD set | one live order, 5 tickets: issued, pre, redeemed, refunded, revoked | ticketsSold 3 | state predicate |
| 11 | test-mode tickets out of window count | D only | ticketsSold 0 | `o.testMode` in the ticket query |
| 12 | per-event live net | A, B, C, D on one event | `liveNetForEvent` 3851 | — (reuses payout queries) |
| 13 | per-event live tickets | same event | `liveTicketsForEvent` 2 | `o.testMode` in `countSoldLiveByEventId` |

`DashboardRevenueNetTest`, 4 cases:
- normal: (6696, 1149, 696, 149, 1149) → 3851
- refunds exceed gross: (100, 300, 0, 0, 0) → 0
- fee refunds exceed fee: (1000, 0, 100, 150, 0) → 1000
- negative result clamps: (1149, 0, 149, 0, 1149) → 0

`DashboardServiceTest` (mocks), 7 cases:
- windowed deltas: current `Window(3851, 2)`, prior `Window(3000, 1)` → revenueMinor 3851, ticketsSold 2, deltas 28 / 100
- negative delta: current 1500, prior 3000 → −50
- prior 0, current > 0 → both deltas null
- prior 0, current 0 → both null
- `all` → both null, and `forOrgWindow` called with `Instant.EPOCH`
- avg: live net 3851 / 2 tickets → 1926 (1925.5 rounds half up); 0 tickets → null; no past event → null
- business: `forOrgWindow` with since = now−90d → `totalRevenueMinor`; `memberships.countByOrgId` stub 7 → `audienceCount` 7

`DashboardControllerTest`, 2 cases:
- A response with null deltas and null avg serializes `$.cycle.deltas` with `hasKey("revenuePct")` and `nullValue()`, and the same for `$.lastEvent.metrics.avgTicketMinor` (the AudienceControllerWebTest.java:418-419 pattern).
- `GET /v3/api-docs`: `$.components.schemas.Deltas.properties.revenuePct.description` contains "Null when the prior window is empty", and `Business.properties.audienceCount.format` == `int64` (the RadarTimelineTest.java:401-413 pattern).

## Live-test
After the Railway deploy:
1. `curl -s https://imin-api-production.up.railway.app/v3/api-docs.yaml | grep -c 'Null when the prior window is empty'` ≥ 2.
2. As a prod organizer, `GET /api/v1/dashboard?cyclePeriod=all&businessPeriod=all`:
 - `cycle.deltas` are null.
 - `business.audienceCount` equals the People total from `GET /api/v1/audience/metrics`.
 - `cycle.revenueMinor == business.totalRevenueMinor`.
3. Cross-check (2) against a read-only `railway psql` query for that org. Live orders give `total` and `fee` sums. Refunds come from `refunds where status = <SUCCEEDED literal> and order_id in live orders`. Disputes come from `disputes where test_mode = false and status in <OPEN, LOST literals> and order_id in live orders`. Then apply the `net()` formula. First confirm the stored literals with `select distinct status` on both tables: refunds use `EnumType.STRING`; disputes use `DisputeStatusConverter`, form unverified.
4. `GET` with `cyclePeriod=7d` for an org with no sales 8–14 days ago → deltas null.

## Contract impact
`/api/v1/dashboard` (organizer surface; not `/api/v1/public`, so PUBLIC_PAGE_API.md is not affected). These 8 components get `@Schema` descriptions, used verbatim:

| field | change | description (marker) |
|---|---|---|
| `Deltas.revenuePct` | int → nullable Integer | "Percent change of cycle.revenueMinor against the equal-length window before it, rounded. Null when the prior window is empty or the period is all." |
| `Deltas.ticketsPct` | int → nullable Integer | "Percent change of cycle.ticketsSold against the equal-length window before it, rounded. Null when the prior window is empty or the period is all." |
| `Cycle.revenueMinor` | semantics | "Live-mode orders placed in the window: totals less their succeeded refunds, unrefunded booking fee and open or lost chargebacks. Minor units, never negative." |
| `Cycle.ticketsSold` | semantics: orders → tickets | "Tickets on live-mode orders placed in the window that are not refunded or revoked." |
| `Business.totalRevenueMinor` | semantics | "Same definition as cycle.revenueMinor, over the business period." |
| `Business.audienceCount` | int32 → int64, real value | "People in the org's audience, all time; the Audience page total. Not windowed." |
| `LastEventMetrics.avgTicketMinor` | nullable, semantics | "The last event's live-mode net (as cycle.revenueMinor) divided by its live-mode tickets not refunded or revoked, rounded half up. Null when there are none." |
| `LastEventMetrics.attended` | description only | "Tickets sold net of chargebacks, all modes. Not door scans." |

Markers in prod OpenAPI: "Null when the prior window is empty" (2 hits), and `audienceCount` with `format: int64`.

Webapp `src/shared/api/types.ts` edits are in the follow-up below (DashboardData at types.ts:1023-1039).

## i18n impact
None in the api: no email or UI strings. The webapp follow-up adds strings in EN/ES/FR/UK.

**Copy ledger** for the follow-up (the api side of each string):

| string (webapp) | field ← computed at | meaning, scope, range | rendered next to it |
|---|---|---|---|
| Cycle "Revenue" + new sub "after booking fee" | `cycle.revenueMinor` ← `DashboardRevenue.forOrgWindow` → `net()` | live-mode orders created in [now−period, now), net of their refunds, unrefunded fee and OPEN/LOST disputes; org-wide, currencies summed; ≥ 0; a later refund lowers a past window | delta sub; period selector |
| `vsPrior("+28%")` under revenue | `cycle.deltas.revenuePct` ← `pctDelta` | rounded % change against the equal prior window; null when the prior is 0 or the period is all; can be negative or > 100 | nothing when null |
| Cycle "Tickets sold" (key `ticketsSold` replaces `orders`) | `cycle.ticketsSold` ← `countSoldLiveByOrgInWindow` | tickets not refunded/revoked on live orders in the window, free tickets included | `ticketsPct` sub |
| `vsPrior` under tickets | `cycle.deltas.ticketsPct` | as revenuePct | nothing when null |
| Business "Total revenue" + "after booking fee" | `business.totalRevenueMinor` ← `forOrgWindow` (business period) | same as cycle, business period | business period selector |
| Last event "Avg per ticket" + "after booking fee" | `lastEvent.metrics.avgTicketMinor` ← `liveNetForEvent / liveTicketsForEvent` | per event (most recent past), live only, free tickets in the denominator, can sit below face when a chargeback costs the fee; null = hidden | last event "Revenue" = `EventDto.revenueMinor` (all modes, incl. fee), so that row needs the existing "gross" sub (`copy.dashboard.gross`) |
| optional "People in your audience · all time" | `business.audienceCount` ← `MembershipRepository.countByOrgId` (AudienceMetricsService.java:38) | org-wide, all-time membership count, equals the Audience page | must say "all time": it sits under the business period selector |

## Blast radius
- **Money readout, no money movement.** No Stripe call, payout or refund path changes. `PostEventPayoutService` is read for its formula only (no edit; see Affected files).
- **Shared repositories.** `OrderRepository`, `RefundRepository`, `DisputeRepository` and `TicketRepository` gain read-only methods. `sumRevenueAndCountByOrgInWindow` is deleted; its callers were only `DashboardService` and `DashboardServiceTest`, and both are edited.
- **`DisputeWithholding`**: javadoc only. `STATUSES` is reused through the new default method, so the withholding set stays single.
- **Readouts that keep the old definition**: `EventOverviewService`, `EventSalesTotals`, `SalesDashboardService`, and the home's `EventDto` summaries. They are deliberately unchanged (Goal, out of scope). After this change the home's cycle revenue and an event's Overview revenue are different, separately labelled figures.
- **Contract**: field semantics and nullability change on `/api/v1/dashboard`; the only consumer is imin-webapp (DashboardPage.tsx). There is no migration and no config.

## Risks
- **Visible drop** for any org with test-era orders. That is intended, but it reverses OrderRepository.java:80-83 (open question).
- **Booking fee on disputed orders** is subtracted twice (the payout formula plus a dispute amount that includes the fee, DisputeIngestService.java:135). Inherited from payouts (open question).
- **Unattributed disputes** (`order_id` null) cannot be placed in a window, so they don't reduce cycle or business revenue. They do reduce `liveNetForEvent`, because that sums by `event_id`.
- **Deploy gap**: the old webapp renders a null delta as "±0%" and shows tickets under the "Orders" label until the follow-up ships. Ship the follow-up back-to-back.
- **Query count** per uncached build rises from 3 to about 18 org-indexed queries (`idx_orders_org_id`, `idx_tickets_order_id`, `refunds_order_id_idx`). The 30 s cache still applies.
- **`types = {"integer","null"}`** might render oddly with springdoc 3.0.2. The fallback is the description alone.
- Size is 13 files, under 15. No split needed.

## Definition of done
- Gate green with Docker up and no skipped Testcontainers tests. Each guard-removal pair in Test impact went red, and the pairs are recorded in the handoff.
- The grep checks under Verification commands return nothing.
- Prod OpenAPI shows both markers. Live-test steps 1–4 pass.
- The webapp follow-up task is created with the list below.

## Decisions (main session)
- Ivan, 2026-10-03: the home-dashboard figures EXCLUDE test-mode orders, refunds and disputes (live only), as planned. Update the OrderRepository javadoc that recorded the old "don't hide test orders" decision. The event overview and sales pages are unchanged.
- The booking fee may be subtracted twice on disputed orders. The dashboard mirrors the payout formula as is. Ivan approved checking the payout side as a SEPARATE investigation task; do not change payout code here.
- The webapp follow-up ships straight after the api deploy, as its own task: nullable types, showDelta `pct != null`, label back to ticketsSold, "after booking fee" sub, avg per ticket, gross sub.
- The worktree was fast-forwarded to origin/master 3eaebc2d. Re-verify every file:line the plan cites before editing; code has moved since the plan was written.
- Ivan 2026-10-03 (revised): include test-mode orders; no live filter. This overrides bullet 1 and every "live" in the body above. As built: `OrderRepository.sumTotalAndApplicationFeeByOrgInWindow`, `RefundRepository.sumSucceededRefundAndFeeByOrgInWindow`, `DisputeRepository.sumMinorByOrgOrderWindowAndStatusIn` + default `sumOpenOrLostMinorByOrgOrderWindow`, `TicketRepository.countSoldByOrgInWindow` / `countSoldByEventId`; `DashboardRevenue.netForEvent` / `ticketsForEvent` reuse the all-mode per-event queries (`sumTotalMinorByEventId`, `sumSucceededRefundMinorByEventId`, `sumApplicationFeeMinorByEventId`, `sumSucceededRefundApplicationFeeMinorByEventId`, `sumOpenOrLostMinorByEventId`). OrderRepository's "full history" javadoc is kept. Worked example with D included: gross 12045, refunded 2149, fee 1045 − 149 = 896, disputed 1149 + 5349 = 6498 → net 2502 (A 4000 + B 0 + C −149 + D −1349), 3 tickets; prior 3000 / 1 → deltas −17 / +200. Test-mode guard cases (2, 3, 4, 5, 11, and 13's guard) are dropped; `@Schema` descriptions say "test mode included" instead of "Live-mode".

## Live-test evidence

## Review rounds

### Implement round 1 (2026-10-03): guard proofs
Each predicate was mutated once in a scratch copy (never this worktree), the named `DashboardRevenueTest` case run alone, then restored; the scratch copy was diffed identical to the worktree afterwards.

| guard mutated | test | red result |
|---|---|---|
| refund `r.status = SUCCEEDED` removed (window query) | `a_refund_that_has_not_succeeded_is_not_netted` | expected 4000, was 0 |
| dispute `d.status in :statuses` removed (window query) | `a_won_dispute_is_not_withheld` | expected 4000, was 0 |
| order `createdAt < :until` → `<=` | `the_window_includes_since_and_excludes_until_…` | Window(4000,2) → (5000,2) |
| refund subquery `< :until` → `<=` | same | → (3000,2) |
| dispute subquery `< :until` → `<=` | same | → (2851,2) |
| ticket `< :until` → `<=` | same | → (4000,3) |
| order `>= :since` → `>` | same | → (0,2) |
| order `o.orgId` neutralised | `another_orgs_…_are_not_counted` | → (8000,2) |
| refund subquery `o.orgId` neutralised | same | → (3000,2) |
| ticket `o.orgId` neutralised | same | → (4000,4) |
| dispute `d.orgId` and subquery `o.orgId` both neutralised (redundant pair, cannot be proven one at a time) | same | → (3000,2) |
| window ticket state predicate removed | `window_tickets_sold_leave_out_refunded_and_revoked` | expected 3, was 5 |
| `countSoldByEventId` state predicate removed | `event_tickets_leave_out_refunded_and_revoked_and_other_events` | expected 3, was 5 |
| `countSoldByEventId` `t.eventId` neutralised | same | expected 3, was 4 |

Base gate (3eaebc2d, untouched) was already red: 7071 run, 2 failures, 156 errors, 3 skipped — H2 `Check constraint invalid` (23514) across predictor/reservation suites; `SourceSyncDatesTest`, `NeverSoftOptInGuardTest`, `PayoutTestModeExclusionTest` pass alone (35/0), so it is suite-order pollution of the shared H2, not this diff.

Full gate on the change (Docker up): 7094 run, 0 failures, 0 errors, 3 skipped (2 H2-only `assumeTrue(postgres())` in `AudiencePlanInvitationWebTest`, whose Postgres twin ran 30/0 skipped; 1 `@Disabled` in `SimulatorEvalTest`); all 33 `*PostgresTest` classes ran with 0 skipped. The base's H2 constraint errors did not recur here, so they are intermittent and depend on test order. Targeted: `-Dtest=DashboardRevenueTest,DashboardRevenueNetTest,DashboardServiceTest,DashboardControllerTest` 31 run, 0 failed.
