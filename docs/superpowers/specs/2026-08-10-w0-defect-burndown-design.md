# W0 Defect Burn-Down — Design Spec

**Date:** 2026-08-10
**Source:** `B2C-MVP-GAP-ANALYSIS.md` §5 (pre-existing defects the FE rewrite must not inherit).
**ClickUp:** W0.1 `86cb33cdm` · W0.2 `86cb33ce4` · W0.3 `86cb33cf1` · W0.4 `86cb33cfd` · W0.5 `86cb33cg0` · W0.6 `86cb33ch4`
**Repos:** `imin-api` (primary) + `imin-public` (lockstep FE). `imin-webapp` untouched except post-deploy `api:sync`.

All file:line references verified against the current working trees (3-agent investigation, 2026-08-10).

---

## W0.1 — [P0] Refunded ticket 500s public order & ticket pages

### Problem
`TicketState.fromWire` (`model/TicketState.java:22-30`) maps `pre/issued/redeemed/checkedIn/revoked` and **throws** on anything else. `RefundService.java:304` writes `Ticket.STATE_REFUNDED` (`"refunded"`, a constant that already exists at `Ticket.java:53` and is already documented in the entity javadoc). Both public endpoints normalize state through `fromWire` (`PublicOrderController.java:154-156`), so **any order containing a refunded ticket 500s `GET /public/orders/{token}` and `GET /public/tickets/{token}` forever**.

### Decision
- Add `REFUNDED` to `TicketState`; `fromWire` case `"refunded" -> REFUNDED`. Wire output stays `name().toLowerCase()` → `"refunded"`.
- No DB change (column is free-text VARCHAR, value already written).
- **FE (imin-public):** the state maps are stale — keyed on `pre`/`checkedIn`/`revoked`, but BE normalization means the FE only ever receives `issued | redeemed | revoked` (soon + `refunded`). Fix both surfaces to the real wire vocabulary:
  - `app/order/[token]/page.tsx:26-33` `ROW_STATE` → keys `issued/redeemed/revoked/refunded` (refunded chip: muted "Refunded").
  - `app/tickets/[token]/page.tsx:21-49` `STATE_LABEL` + `STATE_BANNER` → add `refunded` (banner: neutral "This ticket was refunded", no QR emphasis).
  - `lib/api/types.ts:151,167-172` — replace stale unions with `"issued" | "redeemed" | "revoked" | "refunded"`.
- Unknown future state must never 500 again: FE maps fall back to a plain-text label (already the fallthrough behavior); BE — keep `fromWire` strict for redeem paths, but the two public read endpoints are the only 500 surface and after this fix the full write vocabulary is covered (`pre/issued/redeemed/refunded`; `revoked` has no writer but is mapped).

### Tests (regression, both endpoints)
- `GET /public/orders/{token}` currently has **zero tests**. New `PublicOrderControllerTest` (`@SpringBootTest` + MockMvc + H2, fixture style copied from `PublicTicketPayloadTest.persistIssuedTicket`): happy path asserts full payload; refunded-ticket case asserts 200 + `tickets[0].state == "refunded"`.
- `PublicTicketPayloadTest`: add refunded case asserting 200 + `state == "refunded"`.

---

## W0.2 — 100%-off promo: quote vs checkout divergence

### Problem
- Quote (`QuoteService.java:149-154`): fee gate is `unitPrice == 0`, fee computed on **pre-discount** subtotal → 100%-off on a €20 tier quotes `total = fee = €1.99`.
- Checkout (`StripeCheckoutService.java:177-181`): free path triggers on `netTotal = subtotal − discount == 0` (fee not in the expression) → issues via `FreeCheckoutService`, order persisted `totalMinor = 0` (`FreeCheckoutService.java:109`). Quoted €1.99 ≠ charged €0.
- **Second-order FE failure (worse than the gap doc records):** `buy-modal.tsx:314-315` keys `isFreeOrder` off `tier.priceMinor === 0`; the email input renders only when `isFreeOrder` (`:679`) and submit omits email otherwise (`:446`). With a 100%-off promo on a paid tier the BE free path then 400s on missing email (`StripeCheckoutService.java:182-192`). **That checkout hard-fails today.**

### Decision: zero-net orders are free — fee is waived
Rationale: matches existing behavior for free tiers (fee gated off), matches what checkout already does, avoids the absurd "€0 ticket + €1.99 fee via a fee-only Stripe session", and comp tickets are organizer intent. Partial discounts keep the current intentional semantics (fee on pre-discount subtotal, `StripeCheckoutService.java:254-258`).

- **QuoteService:** fee gate becomes zero-net-aware: `fee = (unitPrice == 0 || subtotal - discount <= 0) ? 0 : computeFee(subtotal, qty, bps, fixed)`. Total for 100%-off → `0`. Nothing else changes.
- **Checkout:** unchanged (already correct under this semantics).
- **QuoteResponse javadoc** (`QuoteResponse.java:9-12`): stale "feeMinor is always 0" claim rewritten to the real contract (buyer-visible 5% + €0.99/ticket; zero-net orders waive the fee). (This absorbs gap-doc P2 row 8.)
- **FE (imin-public) `buy-modal.tsx`:** free-flow flag becomes quote-aware: `isFreeOrder = tier.priceMinor === 0 || (quote loaded && quote.totalMinor === 0)`. Email input, validation guard (`:423,:513`), fee/total display (`:323-331`) all key off the same flag — with a 100%-off quote the footer shows €0.00 total, no fee line, email field visible. Read the deliberate-design comment at `:307-313` before changing; it is superseded by this decision.
- **Docs:** `PUBLIC_PAGE_API.md` in **both** repos: quote section (100%-off → `feeMinor 0, totalMinor 0`) and the stale imin-public paragraph at `:1094-1097` ("tier price 0 is the only trigger for free flow… still goes through Stripe") rewritten: any zero-net total takes the free path.

### Tests
- `QuoteServiceTest`: new case — paid tier + 100% promo → `discount == subtotal`, `feeMinor == 0`, `totalMinor == 0`. Existing 10%-promo test (fee on pre-discount subtotal) must stay green.
- `StripeCheckoutServiceTest:401` (promo-zeroes-paid-tier → free dispatch) already exists — keep.
- FE: quote-driven email-input visibility if buy-modal has component tests; otherwise manual verification note in PR.

---

## W0.3 — priceFrom: purchasable-aware, fee-inclusive

### Problem
`priceFromMinor` = `MIN(priceMinor)` over `enabled = true` tiers only (`TicketTierRepository.java:29-36`) — includes sold-out and not-yet-on-sale tiers and excludes the booking fee. Card says "from €15" when only a €35 tier is buyable, then a fee appears at checkout. `docs/PUBLIC_PAGE_API.md:351-357` currently pins the buggy behavior as intended.

### Decision
`priceFromMinor` keeps its name and becomes: **min over purchasable tiers, fee-inclusive, single ticket**.

- **Purchasable** = exactly `PublicTierDto.onSale` semantics (`PublicTierDto.java:24-44`): enabled ∧ event not over (status ∉ {PAST, CANCELLED}) ∧ tier opened (`event.onSaleAt`, `tier.saleStartsAt`) ∧ not closed (`tier.saleClosesAt`, `event.saleClosesAt`) ∧ `remaining > 0`.
- **Single source of truth:** extract the predicate from `PublicTierDto` into a small static helper (e.g. `TierAvailability.isPurchasable(event, tier, now)`); `PublicTierDto.from` delegates to it. W0.5's sender reuses the same helper. No JPQL re-derivation of on-sale semantics.
- **Implementation shape:** replace the two per-page rollup queries (`findMinEnabledPriceByEventIds` + remaining-rollup, `TicketTierRepository.java:29-36,50-59`) with one `findByEventIdInAndEnabledTrue` fetch; compute `priceFrom`, `soldOut`, `lowStock` in Java from the same rows (`soldOut`/`lowStock` math unchanged: sum/max of `quantity − reserved − sold`). Page size is bounded; feed is CDN-cached 60s.
- **Fee-inclusive:** `p == 0 ? 0 : p + QuoteService.computeFee(p, 1, bps, fixedMinor)` with rates from `StripeProperties` (5% + €0.99). Consistent with W0.2: free tier → `0`.
- **Null** when no purchasable tier (sold out / not yet on sale / sales ended).
- **FE (imin-public) `event-card.tsx`:** number becomes honest automatically. Two touches: verify the local formatter (`:70-89`) renders `0` as "Free"; when `soldOut && priceFromMinor == null`, suppress "Price TBA" (sold-out chip already communicates state).
- **Docs:** rewrite `PUBLIC_PAGE_API.md` priceFrom contract in both repos: purchasable-aware, fee-inclusive, null semantics, explicit note that detail-page tier prices remain face-value (fee shown at quote).

### Tests (`PublicEventServiceListTest`)
- Update the 3 existing priceFrom tests to fee-inclusive expectations.
- New: sold-out tier excluded (min comes from next purchasable); not-yet-on-sale (`saleStartsAt` future) excluded; sale-closed excluded; event `onSaleAt` future → null; all-unpurchasable → null; free tier → 0; `soldOut`/`lowStock` flags unchanged after the query swap.

---

## W0.4 — Exclude CANCELLED from public feed

### Problem
Eligibility predicate (`deletedAt IS NULL ∧ visibility=PUBLIC ∧ publishedAt NOT NULL ∧ status <> DRAFT`) admits CANCELLED. A cancelled future event passes every clause of `findPublicListing` and also leaks into `/cities` + `/genres` facets. No cancel writer exists in main code today (only direct DB / fixtures), but the hole is real and the fan feed is the future primary surface.

### Decision
- Add `AND e.status <> CANCELLED` to **listing-side** queries only: `findPublicListing` (`EventRepository.java:119-153`), `findDistinctPublicCities` (`:155-165`), `findDistinctPublicGenres` (`:218-228`).
- **`findPublic` (detail + notify subscribe) unchanged** — detail intentionally serves cancelled events (test `PublicEventServiceTest:141` pins 200; FE renders the "event cancelled" banner, `event-detail.tsx:51-56`). Tier suppression already handles purchasability (`eventOver`).
- PAST events stay excluded by the date window only — out of scope.

### Tests
- `PublicEventServiceListTest`: `excludes_cancelled_events` (mirror `excludes_draft_events` at `:121`; CANCELLED + future `startsAt` + non-null `publishedAt`).
- Facet coverage: cancelled event's city/genre absent from `listCities`/`listGenres`.
- Existing detail-serves-cancelled test must stay green.

---

## W0.5 — Notify-me release sender (kill the false promise)

### Problem
`POST /public/events/{id}/notify` stores rows (`V23`, UNIQUE(event_id, email)); UI promises **"We'll email you if tickets release."** (`event-detail.tsx:315,448`). No sender exists — the migration header itself says so. The form renders only on live events with zero purchasable tiers (not-yet-on-sale / sold-out / sales-ended), so the promise is precisely: *tickets became purchasable again*.

### Decision
One-shot transactional release notification, sweeper-driven.

- **Migration `V<next>__notify_notified_at.sql`:** `ALTER TABLE notify_subscriptions ADD COLUMN notified_at TIMESTAMPTZ NULL;` (+ partial index `ON notify_subscriptions(event_id) WHERE notified_at IS NULL` for the pending scan).
- **Re-arm on resubscribe:** `NotifySubscriptionService.subscribe` — if the existing row has `notified_at != null`, reset it to null (buyer re-subscribing after a restock notification must not be silently ignored; today the UNIQUE pre-check would eat it — same false-promise bug one level down).
- **Sender `NotifyReleaseSender`** (new, `service/event/`): `@Scheduled(fixedDelay = 60s, initialDelay 30s)` + `@SchedulerLock("NotifyReleaseSender.sweep")` — `ReservationSweeper` pattern (`SchedulingConfig` already global; no property gate, matching every other job).
  1. Distinct event ids with pending subs (new repo query, `notified_at IS NULL`).
  2. Load events + enabled tiers; keep events that are publicly eligible (reuse `findPublic`-equivalent checks: not deleted, PUBLIC, published, not DRAFT) **and not CANCELLED/PAST** **and** have ≥1 purchasable tier via the shared `TierAvailability` helper from W0.3.
  3. For each qualifying event: load pending subs; batch-check deliverability suppression (`SuppressionRepository.findDeliverabilityEmailsIn` — the only email-keyed gate that exists; suppressed rows get `notified_at` set with no send, logged). Send per-sub via the **transactional lane** (`EmailService`/Resend, same as ticket mail); set `notified_at` on success, per-sub try/catch (failure logged, row stays pending → retried next tick).
  - **Delivery semantics: at-least-once** (crash between send and mark → duplicate next tick). Acceptable for a one-shot informational email; documented in the class javadoc.
- **Consent stance:** this is a transactional one-shot the buyer explicitly requested at the point of collection — not marketing. No `ConsentService`/`SendGateService` involvement (both are membership-keyed and a notify-me subscriber who never bought has no membership; gap doc §4.5's platform-consent lane is W2 scope). Deliverability suppression is checked. Email copy states the contract: "You asked us to email you when tickets for {event} become available. This is a one-time notification." No List-Unsubscribe (one-shot, not a stream).
- **Template:** `email-templates/notify-release.{html,txt}` — copy the standalone table-layout convention (e.g. `sales-milestone-50.html`). EN-only (all buyer mail is EN-only today; locale variants are the gap doc's §4.6 work). Content: event name, date/time **in the event timezone**, venue name/city, CTA → `EmailProperties.buyerSiteBaseUrl + "/e/" + eventId`, the one-time-notification line.
- **Out of scope (noted, not built):** `/notify` rate limiting, consent-record capture at subscribe, unsubscribe stream semantics, erasure-cascade wiring, wishlist trigger — all land with W2/W5 streams.

### Tests
- Sender (`@SpringBootTest`, fixed clock, mocked `EmailService`): pending sub + purchasable tier → one send + `notified_at` set; second sweep → no resend; no purchasable tier → untouched; CANCELLED event → untouched; suppressed email → marked, no send; `EmailService` throw → row stays pending.
- `NotifySubscriptionControllerTest`: re-subscribe after `notified_at` set → row re-armed (`notified_at` null again), response unchanged.

---

## W0.6 — DTO widening

### Problem
`PublicOrderResponse.Event` lacks `metaPixelId` (FE types **already declare it** — `types.ts:145` — and `PurchasePixel` on the order page silently no-ops on every order: `app/order/[token]/page.tsx:163-168`). Order & ticket Events lack `endsAt`/`eventId`/`posterUrl` (calendar button impossible, no back-link, no poster). Order tickets lack `qrPayload` (offline pre-cache is N+1 via per-ticket fetches). List cards lack `venueName`.

### Decision — field additions (additive, no renames)
| DTO | Add | Source |
|---|---|---|
| `PublicOrderResponse.Event` | `eventId` (UUID), `endsAt`, `posterUrl`, `metaPixelId` | `Event.id/endsAt/posterUrl`; pixel via the same resolution as the event page — expose `PublicEventService.resolveMetaPixelId` (`PublicEventService.java:93-99`) as a public/reusable method and call it from `PublicOrderController` (event-scoped connection → org-wide → active-only → null) |
| `PublicTicketResponse.Event` | `eventId`, `endsAt`, `posterUrl` | same (no pixel — purchase pixel is order-page-only) |
| `PublicOrderResponse.Ticket` | `qrPayload` | `QrPayloadSigner.sign(ticketToken)` per ticket (already injected for the ticket endpoint) |
| `PublicEventListItem` | `venueName` | `Event.venueName` (nullable) |

- **Leak guardrails:** `PublicEventControllerTest` list allow-list (`:342-347`) gains `venueName`. **New** allow-list snapshot tests for `PublicOrderResponse` and `PublicTicketResponse` (none exist today — same no-leak discipline as events; lands naturally inside the new `PublicOrderControllerTest` from W0.1).
- **FE (imin-public):**
  - `types.ts`: `PublicOrderEvent` + `eventId/endsAt/posterUrl` (metaPixelId already declared); `PublicOrderTicket` + `qrPayload`; `PublicEventListItem` + `venueName`.
  - `event-card.tsx`: render venue as `venueName · venueCity` when present (`:55`).
  - Purchase pixel starts firing with zero FE changes once BE deploys.
  - Calendar/offline consumers are the FE rewrite's job — W0.6 only unblocks them.
- **OpenAPI:** springdoc emits automatically; **post-deploy** run `npm run api:sync` in imin-webapp (fetches prod spec — deploy imin-api first).

### Tests
- New order/ticket payload assertions: `eventId/endsAt/posterUrl` values, `metaPixelId` present (active connection) and null (none), per-ticket `qrPayload` prefix `imin1.<token>.`.
- List: `venueName` in item + allow-list updated.

---

## Cross-cutting

### File-collision analysis → 3 lanes
- **Lane A — W0.1 + W0.6 (minus venueName):** `TicketState`, `PublicOrderController`, `PublicOrderResponse`, `PublicTicketResponse`, `PublicEventService.resolveMetaPixelId` visibility, new `PublicOrderControllerTest`, `PublicTicketPayloadTest`; FE `types.ts`, order/ticket pages.
- **Lane B — W0.2:** `QuoteService`, `QuoteResponse`, `QuoteServiceTest`; FE `buy-modal.tsx`; both `PUBLIC_PAGE_API.md` (quote sections).
- **Lane C — W0.4 + W0.3 + W0.5 + venueName:** `EventRepository`, `PublicEventService`, `TicketTierRepository`, `PublicTierDto` (+ new `TierAvailability`), `PublicEventListItem`, `NotifySubscriptionService/Repository`, new migration + sender + template, `PublicEventServiceListTest`, `NotifySubscriptionControllerTest`, `PublicEventControllerTest` allow-list; FE `event-card.tsx`; both `PUBLIC_PAGE_API.md` (listing sections).

Within C, order: W0.4 (trivial) → W0.3 (creates `TierAvailability`) → W0.5 (consumes it). Lanes A/B/C are file-disjoint except `PUBLIC_PAGE_API.md` (B and C touch different sections; merge is mechanical) and `types.ts` (A owns it; C's `venueName` type line coordinated at merge).

### Deploy order (per sync rule)
1. imin-api → master → Railway (all six changes are additive or fixing broken surfaces; no FE breakage window: new fields ignored by old FE, `refunded` state only makes today's 500 into a 200).
2. imin-public → main → Vercel (state maps, buy-modal flag, card tweaks, types).
3. imin-webapp: `npm run api:sync` against prod, reconcile `types.ts`, commit (drift gate only — no UI work expected).

### Risks
- W0.3 changes a rendered number's meaning (face-value → fee-inclusive). Deliberate, per task title; documented in both contract docs. Old FE shows the new number with the old "FROM" label — acceptable during the deploy window and honest afterward.
- W0.2 relaxes platform revenue on 100%-off orders (was: theoretical €1.99 quote never actually charged). No real revenue change — the checkout already charged €0.
- W0.5 at-least-once duplicates on crash — accepted, logged semantics.
- H2-vs-PG: no new string-concat/lower queries introduced; new JPQL is status-enum + timestamp comparisons only (safe per the known trap).
