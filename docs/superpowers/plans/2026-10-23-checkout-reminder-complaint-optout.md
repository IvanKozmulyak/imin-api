# Checkout reminder: a complaint's opt-out is retried until recorded

cr-complaint · Mode: Direct · Founder: "УВІМКНИ ВСІ" (2026-10-23) — pre-flip follow-up from `docs/superpowers/plans/2026-10-20-w2-abandoned-checkout.md` review round 2.

## Goal

A spam complaint on a checkout reminder writes the sticky per-organizer opt-out. Today a failed write is logged and the webhook answers 200, so Resend never retries and the person can be emailed again. Make the complaint branch fail loud like one-click unsubscribe: the write throws, the webhook's transaction (including the svix dedup claim) rolls back, Resend retries.

## Repos in ship order

| repo | worktree |
|---|---|
| api | `imin-api/.claude/worktrees/cr-complaint` |

## Affected files (per repo)

api:
- `src/main/java/com/imin/iminapi/checkoutreminder/service/CheckoutReminderService.java` — drop the lenient `recordStickyOptOut`; `recordStickyOptOutOrFail(email, orgId, source)` becomes public and takes the source.
- `src/main/java/com/imin/iminapi/checkoutreminder/service/CheckoutReminderDeliveryProjector.java` — complaint branch calls `recordStickyOptOutOrFail(..., "complaint")`.
- `src/test/java/com/imin/iminapi/checkoutreminder/CheckoutReminderDeliveryTest.java` — failed write → 500, nothing stamped; same svix id retried → 200, opt-out + `complained_at` recorded.
- this plan.

## Contract impact

none

## Verification

`docker info >/dev/null && ./mvnw test`. Mutation: swallowing the exception in the projector turns the new test red (`Status expected:<500> but was:<200>`).
