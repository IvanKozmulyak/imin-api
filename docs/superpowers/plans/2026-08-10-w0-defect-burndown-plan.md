# W0 Defect Burn-Down — Implementation Plan

**Spec:** `../specs/2026-08-10-w0-defect-burndown-design.md` (authoritative for every decision; this plan is execution mechanics only).

## Lanes (parallel, file-disjoint)

| Lane | Tasks | Branch | Worktrees |
|---|---|---|---|
| A | W0.1 + W0.6 (minus venueName) | `w0/lane-a` | `.worktrees/w0-a/{imin-api,imin-public}` |
| B | W0.2 | `w0/lane-b` | `.worktrees/w0-b/{imin-api,imin-public}` |
| C | W0.4 → W0.3 → W0.5 + venueName | `w0/lane-c` | `.worktrees/w0-c/{imin-api,imin-public}` |

Worktree root: `/Users/ivan/imin/.worktrees/`. Each lane runs its full test suite in its own checkout (`./mvnw test` / `pnpm build`), commits locally on its branch, never pushes.

## Lane A steps
1. `TicketState`: add `REFUNDED`, `fromWire` case `"refunded"`.
2. `PublicOrderResponse`: Event + `eventId,endsAt,posterUrl,metaPixelId`; Ticket + `qrPayload`. `PublicTicketResponse`: Event + `eventId,endsAt,posterUrl`.
3. `PublicEventService.resolveMetaPixelId` → reusable (public method); `PublicOrderController` populates new fields (signer already injected).
4. New `PublicOrderControllerTest`: happy path, refunded 200, allow-list snapshots for order + ticket responses, metaPixelId present/null, qrPayload prefix. `PublicTicketPayloadTest`: refunded case + new Event fields.
5. FE: `types.ts` (state unions, PublicOrderEvent/Ticket fields), order-page `ROW_STATE`, ticket-page `STATE_LABEL`/`STATE_BANNER`.

## Lane B steps
1. `QuoteService` fee gate zero-net-aware; `QuoteResponse` javadoc rewrite.
2. `QuoteServiceTest`: 100%-off case; existing promo tests stay green.
3. FE `buy-modal.tsx`: quote-aware `isFreeOrder`; email input/guard/display follow.
4. Both `PUBLIC_PAGE_API.md`: quote + free-flow-trigger sections.

## Lane C steps
1. W0.4: `EventRepository` — `status <> CANCELLED` in `findPublicListing`/`findDistinctPublicCities`/`findDistinctPublicGenres`; tests (list + facets); detail-cancelled-200 test untouched.
2. W0.3: extract `TierAvailability.isPurchasable`; `PublicTierDto.from` delegates; replace two rollup queries with one enabled-tiers fetch; fee-inclusive priceFrom; `PublicEventListItem` + `venueName`; ListTest updates + new cases; allow-list + venueName; FE `event-card.tsx` (venueName render, Free/soldOut handling).
3. W0.5: migration `notified_at` + partial index; re-arm in `NotifySubscriptionService`; `NotifyReleaseSender` (60s ShedLock sweep, deliverability suppression, transactional EmailService, at-least-once); `notify-release.{html,txt}` template; sender tests + re-arm test.
4. Both `PUBLIC_PAGE_API.md`: priceFrom + venueName + notify sections.

## Merge & verify (after all lanes)
1. Merge order into `master`/`main`: A → B → C. Expected conflicts: `types.ts` (A vs C venueName line), `PUBLIC_PAGE_API.md` (B vs C sections) — mechanical.
2. Full `./mvnw test` + `pnpm build` on merged trees.
3. Remove worktrees + branches.
4. Hold for push approval (push → Railway/Vercel auto-deploy). Post-deploy: `npm run api:sync` in imin-webapp, reconcile, commit.
5. ClickUp: comment + close W0.1–W0.6 (blocked until MCP rate limit clears; do from session or manually).
