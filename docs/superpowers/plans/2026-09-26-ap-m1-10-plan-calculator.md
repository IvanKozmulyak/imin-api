# M1-10 Gap, mode, coverage, actions, PlanCalculator

Slug: `ap-m1-10-plan-calculator` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M1-10) · Mode: Subagent (autonomous runner, no gates)

## Goal and scope

Pure engine that turns one event's plan-mailable candidates (M1-9 `CandidateBuilder`, M1-8 `ResponseModel`, logic/priors YAML) into a plan: mode, capacity, target, rounded expected tickets, coverage + verdict, gap, reach needed, target realism, timing (`daysToEvent`, launch and D-3 dates) and rule-based actions. No Spring bean, no persistence, no REST (M1-11), no LLM, no sends.

Out of scope: `excludeSegments` overrides and the 422 mapping of "no capacity" (M1-11 maps `PlanCalculator.NoCapacityException` to `AUDIENCE_PLAN_NO_CAPACITY`), tribe size and portrait groups (M2-5/M2-8 pass a tribe range in; M1 passes `null`), holdout assignment (M3-1), door QR / survey actions (M4).

Rules carried forward verbatim from the programme plan:
- §5: "**Coverage is computed from the rounded totals**, not the raw sums: 26/255 = 0.1020, 52/255 = 0.2039, 93/255 = 0.3647 → `0.10 / 0.20 / 0.36` (2 decimals, half-up). From the raw high (93.12/255 = 0.3652) half-up would give **0.37**, which contradicts spec §11 and what the organizer sees (93); a dedicated test pins that `coverage.high` = 0.36 from 93, not 0.37 from 93.12. Verdict on mid 52/255 = 0.204 → `medium` (strong ≥ 0.30, medium ≥ 0.15). Gap = 255 − 93 .. 255 − 26 = **162-229** (also from rounded totals). Mode: 345 mailable → `warm` (cold < 50, hot ≥ 500)."
- §5: "Cold pass: 0 mailable → `cold`, expected 0/0/0, verdict `cold`, gap 255-255, `newPeople` sizes `null` until M2."
- §5: "`Rounding`: each segment `Math.round(raw)`, totals `Math.round(Σ raw)`"; "tickets per order 1.6 on every band" (C5: "All three bands use the mid tickets-per-order").
- M1-10: "Capacity = Σ `quantity` of enabled `ticket_tiers`; target = `round(capacity × pct / 100)`; **coverage and gap from the rounded totals (§5)**; `reachNeeded.metaAds` = gap / (ctr × landing_to_ticket) as a range, emitted `null` while the prior is flagged unverified; `instagramOrganic` = `"unknown"`. `timing.daysToEvent` = whole days from today to the event start date in the event timezone (logic 0.2). Actions (rule-based, dated in the event timezone): invite per shown segment with arms `launch` (on-sale date or today) and `d3` (start − 3 days; omitted when that date is today or past, so `daysToEvent < 4`); `import_with_proof` when verdict weak or cold; `door_qr` / `survey` actions only once M4 ships (hidden before)."
- M1-9 carry-over: "M1-10: the `other` gate compares the unrounded ratio `Math.round(Σ same+adjacent mid) / target` with `<` against 0.15 (149 of 1000 invites, 150 of 1000 does not); keep the verdict boundaries consistent with it."
- C22: "a member with empty/no taste (incl. every `imported`) gets genre fit `unknown`, not `other` … not subject to the `other` coverage gate and not counted in that coverage (only same + adjacent are). … M1-10/M1-11 must carry `unknown` through the segment DTO and UI copy."
- M2-8 (logic 7.5): "plan gains `gapExceedsTribe` (`true` when `gap.low` > tribe-size `high` within the catchment, `false` when not, `null` when the tribe size is null) and, when true, an action `rethink_target`".
- Deferred: "'Enough days to close the gap' (logic 7.6) | Needs a sales-velocity model … v1 shows `daysToEvent` and drops past arms".
- §4.4: "`unknown` is a legal value and flows to the API as `null` / `"unknown"`, never as 0." §4.5: "Ranges only"; "a `null` number renders `?`". Logic file header: "never single numbers".
- Workspace: no fabricated data (a number must trace to a real field or not render).

Decisions made here (documented in code):
1. **Verdict rule = the other-genre gate's rule.** Verdict reads the unrounded ratio `expected.mid (rounded total) / target` with `≥ strong` → strong, `≥ medium` → medium, else weak (consistent with the shipped M1-9 gate). **Deviation from §5 "half-up" (review 2026-09-27, C23):** shown coverage ratios are truncated to 2 decimals (`RoundingMode.DOWN`) so a shown number never crosses a threshold the verdict has not: 1,496/10,000 shows 0.14 and is `weak`; 149/1000 shows 0.14 weak, 150/1000 shows 0.15 medium. The §5 fixture is unchanged (0.10/0.20/0.36); totals are still rounded first (59 × 0.25 = 14.75 → 15 → 0.15, not 0.14 from the raw sum), pinned by a test.
2. **Cold mode** (< 50 mailable) shows no segments: `expected` and the coverage ratios are **null** (not computed; review 2026-09-27, the UI shows the cold state), verdict `cold`, gap = target (logic bank block 10.7 "instead of segments the portrait, gap = the whole target"); the builder does not run, exclusions carry only the ConsentGate counts, and `mailable` is reported.
3. **d3 arm** is kept only when its date is strictly after the launch date (with launch = today this is exactly "omitted when today or past"); invites are omitted for a past event (`daysToEvent < 0`) and on the event day once `now` is at or after the start instant (`Timing.eventStarted`).
4. **Holdout pct** on an invite = `holdout_pct` when the segment has ≥ `holdout_min_mailable`, else 0 (§5: loyal 40 < 60 → no holdout).
5. **Tribe size unknown until M2** → `gapExceedsTribe = null` (renders `?`), no `rethink_target`. The rule itself is implemented and tested with a supplied range.
6. **Reach needed** is `Math.round`ed like every count; a channel with no evidence is status `unknown`, a prior flagged unverified is status `unverified`, both with null bounds A bound whose people count exceeds the int range (near-zero rate) is null, not an error.
7. **Verdict vs gate population.** The verdict counts every shown segment incl. `other` and `unknown` fits; the other-genre gate counts same + adjacent only (Javadoc on `CoverageVerdict`).

Notes for M1-11 (review 2026-09-27):
- The top-3 segment cap and the 2–3 steps cap are response shaping and belong to M1-11; this engine returns every shown segment and every action.
- Wiring: `Event.timezone` defaults to UTC, so an event without an explicit zone dates its actions in UTC; pass `Event.onSaleAt` as `onSaleAt`, not a tier's `saleStartsAt`.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-10-plan-calculator` | `./mvnw test` |

## Affected files

api (all new):
- `src/main/java/com/imin/iminapi/audienceplan/engine/Rounding.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/ModeSelector.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/CoverageVerdict.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/GapCalculator.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/ActionPlanner.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/PlanCalculator.java`
- `src/test/java/com/imin/iminapi/audienceplan/engine/{RoundingTest,ModeSelectorTest,CoverageVerdictTest,GapCalculatorTest,ActionPlannerTest,PlanCalculatorTest}.java`

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. `Rounding` (count, 2-dp truncated ratio, integer percent-of half-up).
3. `ModeSelector` (cold/warm/hot on logic thresholds).
4. `CoverageVerdict` (coverage from rounded totals; verdict on the unrounded ratio; cold).
5. `GapCalculator` (gap, reach needed per channel, `gapExceedsTribe`).
6. `ActionPlanner` (timing, invites with arms and holdout pct, `import_with_proof`, `rethink_target`).
7. `PlanCalculator` (capacity, target, builder, segment and total rounding, assembles the plan).
8. Tests per branch; §5 fixtures verbatim.
9. Rebase onto latest `origin/master` (tagged stash + SHA), final `./mvnw test`.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-10-plan-calculator && ./mvnw test`

## Test impact

New unit tests only (pure, no Spring context). Reproduction test: n-a (new code). Branch map:
- Rounding: count half-up; ratio2 truncated (0.3647 → 0.36, 1/8 → 0.12, 1496/10000 → 0.14); percentOf 300·85 → 255, 301·85 → 256; zero denominator refused.
- ModeSelector: 0/49 cold, 50/499 warm, 500 hot.
- CoverageVerdict: 3000/10000 strong, 2999 medium, 1500 medium, 1499 weak; 1496/10000 → mid 0.14 weak, 2996/10000 → 0.29 medium; 149/1000 → 0.14 weak, 150/1000 → 0.15 medium; cold → cold with null coverage; §5 26/52/93 of 255 → 0.10/0.20/0.36 medium; `verdict_on` low/high read that point.
- GapCalculator: 255 − 93..255 − 26 = 162-229; cold (null expected) → target-target; people beyond int range → null bound; expected ≥ target → 0-0; meta unverified → null bounds `unverified`; meta verified → rounded range; instagram unknown → `unknown`; instagram band → range; zero rate bound → null high; gap 0 → reach 0-0; tribe null → null; 700 vs 606 → true; 162 vs 606 → false; equal (606 vs 606) → false.
- ActionPlanner: daysToEvent 26.09 → 24.10.2026 = 28 in Europe/Paris; event timezone decides "today" (23:30Z); launch = on-sale date (01.10) when in the future, today when past or null; d3 = 21.10.2026; daysToEvent 3 → no d3, 4 → d3; past event → no invites; event day after the start instant → no invites, before it → launch arm today; holdout 59 → 0, 60 → 15; weak → import_with_proof; cold → import_with_proof; medium/strong → none; gapExceedsTribe true → rethink_target, null/false → none.
- PlanCalculator: §5 warm fixture end to end (every rate, raw, shown, totals, coverage, verdict, gap, mode, mailable, actions with dates); `coverage.high` read from 93 not 93.12; totals rounded first (14.75 → 15 → 0.15 medium); cold expected and coverage null; cold fixture (0 mailable) and cold with 49 mailable (no segments, mailable reported); duplicated person counted once toward the mode; 500 mailable → hot; capacity sums enabled tiers only; capacity 0 → `NoCapacityException`; target 300 → 255, 301 → 256; targetPct outside 1..100 refused; gap never negative; `unknown` fit segment carried with its expected tickets and invite; 149/1000 weak (mid 0.14) + other invited, 150/1000 medium (mid 0.15) + other held back; versions carried; tribe range passes to `gapExceedsTribe`.

## Live-test

Not needed: pure engine with no endpoint, bean or persistence (M1-11 exposes it and carries the §5 live-test).

## Contract impact

none

## i18n impact

none (api engine; action and verdict keys are machine keys, copy lands in M3-W2a).

## Blast radius

New classes in `audienceplan.engine` only; nothing calls them yet. No migration, no config key, no shared module touched.

## Risks

- Truncated coverage can show a ratio up to 0.01 below the exact one; accepted so the shown number and the verdict never disagree at a threshold.
- Cold mode hides up to 49 mailable people's segments by design (logic bank 10.7); `mailable` is reported so the card can say how many can be emailed.

## Definition of done

All branch tests above green; `./mvnw test` green on the worktree rebased onto the latest `origin/master`; no comment references a ticket; no new strings shown to users.

## Live-test evidence

n-a (no endpoint).

## Review rounds

(appended by the orchestrator)
