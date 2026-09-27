# M1-9 CandidateBuilder

Slug: `ap-m1-9-candidate-builder` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M1-9 · Mode: autonomous runner (no gates) · Reproduction test: n-a (new code).

## Goal and scope

Turn the org's plan-mailable members into invitation segments for one event: class × genre fit, one person one segment, fatigue and purchase exclusions, "other" genre only when needed, small groups hidden, and an exclusion breakdown with counts only. Engine is pure (`engine/`); a thin read-only service gathers its inputs with org-scoped SQL. No REST, no persistence, no sends (M1-10 / M1-11 consume it).

Programme text for this task (verbatim):
- Files (new): `engine/CandidateBuilder.java`, `engine/Exclusions.java`.
- Steps: mailable ids from ConsentGate SQL; person → (class, genre fit from the event's bucket vs the person's top taste bucket via adjacency); exclusions: bought this event, contacted within the 48 h floor (`CampaignVolumeGuard`), `sends_this_event ≥ 2`, `sends_30d ≥ 4` (counts from `campaign_recipients` with status sent, joined to `campaigns.event_id`); one person one segment = highest mid rate; `other` fit only if coverage mid from same + adjacent < 0.15; segments under 10 mailable reported as `smallGroupsNotShown` (count only).
- Output carries the **exclusion breakdown**: ConsentGate reason counts (M1-6) + `bought_this_event`, `contacted_48h`, `event_cap`, `monthly_cap`, `small_group` — counts only.
- Tests, one per branch: bought-this-event excluded; 48 h excluded; 2 sends this event excluded; 4 sends in 30 d excluded, 3 kept; person matching two segments lands in the higher-rate one; `other` omitted at coverage 0.15, included at 0.149; 9-member segment hidden and counted; person with empty taste gets fit `other`; breakdown counts equal the excluded totals per reason.

Rules carried forward verbatim:
- (§4.4) "Logic files (resources, versioned, never single numbers)"; "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0."
- (C1) "**Every genre field in this plan (taste, segment rules, portrait `?genre=`, survey, UI bars) uses only these 8 keys.**"
- (C5) "All three bands use the mid tickets-per-order; the range stays visible as an editable assumption."
- (§5) "**Coverage is computed from the rounded totals**, not the raw sums" — `Rounding`: "each segment `Math.round(raw)`, totals `Math.round(Σ raw)`".
- (Deferred) "Format / city in plan segments (logic 3.3, 3.4) | Segments are class × genre (3.8)".
- (C13/C14) opens and clicks never feed features; fan features never read `memberships.city/genres/tags/vibe/notes`.
- (D1) "explicit consent only now (`soft-opt-in-enabled=false`)" — enforced by ConsentGate, used as-is.
- (D3) "Resend open/click tracking OFF for everyone" — nothing here reads opens/clicks.
- (Runner) No beta gating; no new per-feature flag; no sends.

Decisions made in this task (not in the programme text):
- `sends_30d` in `fan_features` is never written today, so the 30-day count comes from `campaign_recipients` directly, like the event count.
- "Sent" = status in `('sent','delivered','opened','clicked')` and the time is `last_event_at`, exactly `CampaignVolumeGuard`'s predicate; the 48 h floor reads `imin.marketing.guard.frequency-floor-hours` (default 48). Channel-agnostic, as the guard. `unsubscribed` / `complained` rows are deliberately not sends: they lose the delivery state they reached, and ConsentGate already drops those people, so they never reach the caps.
- Send counts run as `UNION ALL` of a windowed branch (`last_event_at >= scanSince`, served by V116 `(membership_id, last_event_at)`) and an event branch (`campaigns.event_id = :eventId`, served by the new V152 index), then aggregated — no `OR` across tables.
- "Bought this event" = a non-test-mode order of this org for this event, matched on `orders.email_normalized`, with at least one ticket not in `{refunded, revoked}` (free RSVPs count: they already hold a ticket).
- Exclusion order per person follows `logic.exclusions` (a listed-out exclusion is not applied); first match counts.
- A mailable member without a class (`none`, or no `fan_features` row yet) cannot form a class × genre segment → new reason `no_class` (OUT_OF_PLAN, keeps the breakdown summing).
- Members held back because `other` is not needed are reported as `otherGenreHeldBack`, not as an exclusion.
- (Review, orchestrator decision) A member with empty/no taste — including every `imported` member — gets fit `unknown`, not `other`. Rationale: §4.4 "`unknown` is a legal value … never as 0"; `other` means "known taste, far from this event" and would both penalise (×0.2) and gate people we simply know nothing about. `unknown` has modifier ×1.0 in `priors-v1.yaml` (`modifiers.genre_fit.unknown`; neutral because the class prior is already low; the loader rejects ≤ 0), forms its own segment (class × unknown), is not subject to the `other` coverage gate, and does not count toward that coverage (only same + adjacent are genre evidence). Small-group hiding applies as for any segment. This supersedes the programme test line "person with empty taste gets fit `other`".
- Coverage for the `other` decision = `Math.round(Σ expected mid of shown same + adjacent segments) / target`, compared unrounded with `<` against `invite_other_genre_only_if_coverage_below` (carried over to M1-10 in the programme plan).
- Equal mid rates keep the closer fit (iteration SAME → ADJACENT → OTHER, strict `>`).
- Top taste bucket ties: every tied bucket yields a candidate fit and the person lands in the one with the highest mid rate. Event without a whitelisted genre → every fit is `other`.
- `smallGroupsNotShown` = number of hidden segments; `exclusions.small_group` = people in them.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-9-candidate-builder` | `./mvnw test` |

## Affected files (per repo)

api (all new unless noted):
- `src/main/java/com/imin/iminapi/audienceplan/engine/CandidateBuilder.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/Exclusions.java`
- `src/main/java/com/imin/iminapi/audienceplan/service/CandidateLoader.java` (OUT_OF_PLAN: input gathering)
- `src/main/java/com/imin/iminapi/audienceplan/repository/CandidateSql.java` (native SQL, H2 + PG)
- `src/main/java/com/imin/iminapi/audienceplan/repository/FanFeatureRepository.java` (modify: three read queries)
- (review) `src/main/java/com/imin/iminapi/audienceplan/engine/ResponseModel.java` (modify: `Fit.UNKNOWN`), `config/AudiencePlanLogic.java` + `config/LogicLoader.java` (modify: `genre_fit.unknown`, validated > 0), `src/main/resources/audienceplan/priors-v1.yaml` (modify: `unknown: 1.0`; the genre-fit modifiers live in the priors file, not `logic-v1.yaml`)
- (review) `src/main/resources/db/migration/V152__campaigns_event_id_index.sql` (new: index on `campaigns(event_id)`, none existed)
- (review) `src/test/java/com/imin/iminapi/audienceplan/engine/ResponseModelTest.java`, `config/LogicLoaderTest.java` (modify)
- `src/test/java/com/imin/iminapi/audienceplan/engine/CandidateBuilderTest.java`
- `src/test/java/com/imin/iminapi/audienceplan/service/CandidateLoaderScenarios.java`, `CandidateLoaderTest.java` (H2), `CandidateLoaderPostgresTest.java`

## Ordered steps

1. `Exclusions`: reason constants + ordered breakdown (ConsentGate reasons, then builder reasons).
2. `CandidateBuilder` (pure): Person/Input/Segment/Result records; per-person exclusion → class check → candidate fits → `ResponseModel.rate` → best mid; group by (class, fit); small-group rule on same/adjacent; coverage → `other` decision; small-group rule on `other`; sorted output.
3. `CandidateSql` + repository methods: features of org members, bought-this-event ids, send counts per member.
4. `CandidateLoader`: ConsentGate breakdown + mailable ids, the three queries, `MarketingGuardProperties` floor, `Clock`, then `CandidateBuilder.build`.
5. Tests.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-9-candidate-builder && ./mvnw test`

## Test impact

New `CandidateBuilderTest` (pure, one per branch) and `CandidateLoader` scenarios on H2 and Postgres 17 (Testcontainers, skipped without Docker). No existing test changes.

## Live-test

Not needed: no endpoint, no job, no config; the loader is exercised on H2 and Postgres in tests. M1-11 live-tests the plan endpoint.

## Contract impact

none

## i18n impact

none

## Blast radius

Additive read-only code. Three new read queries on `FanFeatureRepository`. One migration (V152, a plain index on `campaigns(event_id)`). One new priors key (`genre_fit.unknown`, required by the loader, so a priors file without it fails startup). No bean replaced, nothing calls the new service yet.

## Risks

- Tie-break and "no genre" choices above are assumptions; M1-10/M1-11 review may change them.
- `last_event_at` moves on later provider events (delivered/opened), so a send can count slightly later than it happened; same behaviour as `CampaignVolumeGuard`.

## Definition of done

Every programme test above green plus the loader scenarios; `./mvnw test` green on the rebased tree.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by the orchestrator)
