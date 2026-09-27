# M3-R0 Audience read model for classes and taste

Slug: `ap-m3-r0-read-model` · Programme: `docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M3-R0 (incl. rev2) · Mode: autonomous runner

## Goal and scope
Expose guest classes, taste and the ConsentGate "can email" figure on the existing Audience API so the webapp redesign (M3-R1 Overview, M3-R2 Members) can bind to real fields:
- `GET /api/v1/audience/metrics` gains: `newLast30Days`, `showedUpPct` and `cameBackPct` (ranges), `mailable` (ConsentGate, the same query the plan uses), `legacyNotMailable` (`legacy_unproven`), `mailableByBasis`, `exclusions` (reason → count), `classCounts` (7 classes), `tasteShares` (8 buckets) + `tasteMembers`.
- `MemberDto` gains `guestClass` (schema `AudienceMemberClass`), `taste`, `sends30d`.
- `GET /api/v1/audience/members` gains filters `guestClass`, `genre` (8 bucket keys), `mailable`, and honours `sort` (C19).
- `GET /api/v1/audience/members/{id}/consent-history` entries gain `locale` and `legacy` (`textVersion`, `orderId` come from M1-6).
Out of scope: removing `lastEmailOpenAt/ClickAt` (M3-R6), any webapp change (M3-R1/R2), CSV export columns (unchanged, pinned).

Rules carried forward verbatim:
- Programme §M3-R0: "The existing SendGate-based "Subscribed & lawful" figure is replaced for beta orgs, so the Overview and the plan show the same "can email" number. A test asserts that equality for one fixture org."
- §M3-R0: "`mailable`, **computed by the new ConsentGate (M1-6), the same query the plan uses**, plus a separate `legacyNotMailable` count (`legacy_unproven`)."
- §M3-R0: "Not in this task: removing `lastEmailOpenAt/ClickAt`."
- C1: "**Every genre field in this plan (taste, segment rules, portrait `?genre=`, survey, UI bars) uses only these 8 keys.**"
- C6: "New classes are computed on `fan_features` from **paid** orders only: `payment_method='stripe' ∧ total_minor > 0 ∧ ¬test_mode ∧ ≥ 1 ticket not in {refunded, revoked}`."
- C13: "Opens and clicks never feed features without `canTrack`" / D3: "`canTrack` is constant false."
- C14: "Fan features never read those columns (tags can carry identity labels)." (`memberships.city/genres/tags/vibe/notes`)
- C19: "`AudienceController.listMembers` accepts `sort` (line 75) and never passes it to the service → M3-R0 (api) honours `sort`."
- §4.5: "Numbers shown must trace to a real API field (no sample numbers from the design canvas)." / "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0."
- Decisions taken: "Guest classes (loyal/repeat/first_timer/lapsing/dormant/imported) REPLACE the 7 lifecycle stages in the Audience tab (M3-R); RFM stays in the member drawer only."
- D1: "explicit consent only (`soft-opt-in-enabled=false`)."
- Runner (Ivan 2026-09-26): no beta gating. The plan's "populated only when `AudiencePlanAccess.isEnabled(orgId)`" is kept only as the existing global kill switch (default open): kill switch off → new metric/member fields null, new filters 404. No new flag.

Design choices (recorded):
- `sends30d` is counted live from `campaign_recipients` (status sent/delivered/opened/clicked, `last_event_at` ≥ now − 30 d, campaign of this org), the same predicate as `CampaignVolumeGuard`'s frequency floor. `fan_features.sends_30d` is never written by any code on origin, so reading it would render a fabricated 0.
- `guestClass`/`taste` are null for a member with no `fan_features` row (not yet computed); never a made-up `none`. `classCounts` counts a member without a row under `none` so the counts sum to `totalMembers`.
- `showedUpPct` = redeemed / countable tickets (not refunded/revoked) of paid orders (C6) for this org's events whose end (or start) is past; `cameBackPct` = members with `fan_features.paid_orders` ≥ 2 / ≥ 1. Both as `{low, mid, high, n}` in percent (0–100, like the sibling `*Pct` fields): mid = k/n, low/high = Beta(k+1, n−k+1) p10/p90; `null` when n = 0.
- `tasteShares`: mean of the members' normalized taste vectors over the 8 buckets (every bucket present, sums to 1); `null` when no member has taste; `tasteMembers` = members with a non-empty taste.
- Consent-history `locale` = `orders.buyer_locale` of the record's `order_id` (null otherwise); `legacy` = subscribing record with a basis that ConsentGate's own `PROVEN` predicate rejects (one SQL source of truth).
- `sort` ∈ `created_at` (default) | `spend_minor` | `last_purchase` | `events`, all descending, nulls last, tie-break `membership_id` desc; keyset cursor carries the sort key and full-precision timestamps. Unknown sort → 400.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-r0-read-model` | `./mvnw test` |

## Affected files (per repo)
api (`src/main/java/com/imin/iminapi/...`):
- new `audience/dto/AudienceMemberClass.java` (enum, `@Schema(enumAsRef = true)` → OpenAPI marker), `audience/dto/RateRange.java`
- new `audience/service/MemberListQuery.java` (dynamic native SQL: filters + sort + keyset)
- new `audienceplan/service/AudienceReadModel.java` (class counts, taste shares, per-member class/taste, sends30d, show-up, came-back, new-30d)
- mod `audience/dto/AudienceMetricsDto.java`, `audience/dto/MemberDto.java`, `audience/dto/ConsentHistoryEntry.java`
- mod `audience/service/AudienceMetricsService.java`, `audience/service/AudienceService.java`, `audience/service/DsarService.java`, `audience/controller/AudienceController.java`
- mod `audienceplan/service/ConsentGate.java` + `audienceplan/repository/ConsentGateSql.java` (expose the gate's SQL parameters and a per-record proven query; no behaviour change)
- mod `marketing/repository/CampaignRecipientRepository.java` (sends per member in a window), `repository/TicketRepository.java` (show-up counts), `audience/repository/MembershipRepository.java` (new-in-window, basis among ids; the two unused keyset JPQL queries are replaced by `MemberListQuery`), `audienceplan/repository/FanFeatureRepository.java` (class counts, tastes, paid-order counts, unproven grant ids)
- tests under `src/test/java/com/imin/iminapi/audience/` and `.../audienceplan/`

## Ordered steps
1. Baseline `./mvnw test` on the untouched worktree.
2. Wait for M1-6 ConsentGate on origin/master; rebase.
3. DTOs + enum; read model service; member list query; controller params and validation; consent-history fields.
4. Tests (below); `./mvnw test`; rebase onto latest origin/master (tagged stash) and re-run.

## Verification commands
`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-r0-read-model && ./mvnw test`

## Test impact
Reproduction test: n-a (new code; C19 sort drop is covered by the per-sort tests, which fail on base because every sort returns created_at order).
New tests, one per branch:
- filters: `guestClass` match; no feature row matches `none` only (as in classCounts); invalid class → 400; `genre` match (taste has bucket) / other bucket excluded; non-bucket genre → 400; `mailable=true` returns exactly ConsentGate's mailable ids; `mailable=false` the rest; kill switch off + any new filter → 404.
- sort: each of `created_at`, `spend_minor`, `last_purchase` (nulls last), `events` orders the page and paginates via cursor without gaps/dupes; unknown sort → 400; legacy cursor still decodes.
- metrics: `newLast30Days` 30 d counted / 31 d not; `cameBackPct` from paid orders only (fan_features.paid_orders); `showedUpPct` counts only ended events and paid orders, excludes refunded/revoked, n = 0 → null; `mailable` equals `ConsentGate.breakdown(org).mailable()` for the fixture org and `legacyNotMailable` its `legacy_unproven`; exclusions + mailable sum to `totalMembers`; class counts sum to `totalMembers`; taste shares from this org's rows only, all 8 keys, sum 1; kill switch off → all new fields null.
- member: `guestClass`/`taste` from fan_features; no row → null; `sends30d` counts only this org's sends within 30 days and only sent-or-later statuses; kill switch off → null.
- consent-history: `textVersion`, `orderId`, `locale` (from the order), `legacy` true for an unproven checkout row, false for a provenance-backed import row and for an unsubscribe row.
- CSV export header unchanged (pinned).

## Live-test
Not needed locally beyond `./mvnw test`: read-only endpoints over H2 + the Postgres Testcontainers suite; prod check after ship is the orchestrator's (`AudienceMemberClass` in `/v3/api-docs.yaml`).

## Contract impact
`/api/v1` · marker `AudienceMemberClass` (+ metrics `newLast30Days`, `showedUpPct`, `cameBackPct`, `mailable`, `legacyNotMailable`; consent-history `textVersion`, `locale`, `orderId`, `legacy`). Additive only.

## i18n impact
None (api only).

## Blast radius
`AudienceService.listMembers` query path rewritten (same default order and semantics for lifecycle/search); `MemberDto` and `AudienceMetricsDto` additive; `DsarService.consentHistory` adds two fields (also in the Art.15 export). ConsentGate gets read-only accessors.

## Risks
- Keyset over nullable `last_purchase` → explicit NULLS LAST + null-aware cursor, tested across pages.
- H2 vs Postgres null-String trap → dynamic SQL binds only present parameters; Postgres run via the existing Testcontainers pattern for the list query.
- ConsentGate much stricter than SendGate → `mailable` near 0 until M3-P1; `legacyNotMailable` explains it.

## Definition of done
All endpoints above return the new fields; tests green; `./mvnw test` green after rebase on latest origin/master.

## Live-test evidence
Not run (read-only endpoints; see Live-test). Verification 2026-09-27 on origin/master `94745c41` (ConsentGate landed):
- baseline `./mvnw test` on untouched `a9d2ae80`: 3729 tests, 0 failures.
- `./mvnw test` with this change: 4012 tests, 0 failures, 0 errors (new: `AudienceReadModelTest` 32 on H2, `AudienceReadModelPostgresTest` 32 on Postgres 17, `RateRangeTest` 5, `AudienceMemberClassTest` 3, `AudienceControllerWebTest` +4).
- One earlier full run hit `BuyerMailListenerTest.the_send_happens_with_no_transaction_in_scope` once (unrelated, pre-existing race: `verify(timeout)` can pass before the stubbed answer records `txActive`); green on re-run.

## Review rounds
(appended by the orchestrator)
