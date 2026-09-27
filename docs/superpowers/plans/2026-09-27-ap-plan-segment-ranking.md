# ap-plan-segment-ranking — rank plan segments by expected tickets

## Goal and scope
`CandidateBuilder.order()` ranks shown segments by per-person mid rate, so the M1-11 top-3 cap (`PlanService.MAX_SEGMENTS`)
can keep a 10-person loyal segment (expected mid ~4) and drop a 200-person lapsing segment (mid ~8), understating
expected tickets and coverage (found by the demo seed). Rank by the segment's unrounded expected mid tickets
(mailable × rate × tickets per order, with each person's no-show band), tie-break by higher mid rate, then class order
of the logic file, then fit enum order (same, adjacent, other, unknown).

Spec check: logic bank 10.2 / tech-spec §2 say "top 3 segments" and never define the order by rate; tech-spec §11's
response example lists `first_timer/same` (expected mid 23, the largest of the fixture) as `segments[0]`, and ux-flow /
tech-spec "Later" say Momentum takes the plan's *best* segment, which `MomentumPlanTarget` (M4-2) already defines as the
highest expected mid. The spec supports the new order; nothing says otherwise. Tech-spec §265 "highest expected rate
wins" is about which segment one person lands in (unchanged).

Out of scope: the person → segment choice (still highest mid rate), the other-genre gate, `MomentumPlanTarget`.

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | /Users/ivan/imin/imin-api/.claude/worktrees/ap-plan-segment-ranking | `./mvnw test` (via `/Users/ivan/.imin-pipeline/mvnlock.sh test`) |

## Affected files (per repo)
api:
- `src/main/java/com/imin/iminapi/audienceplan/engine/CandidateBuilder.java` — `order()` comparator + javadoc.
- `src/main/java/com/imin/iminapi/audienceplan/engine/PlanCalculator.java` — comment only ("highest rate first").
- `src/test/java/com/imin/iminapi/audienceplan/engine/CandidateBuilderTest.java` — ranking tests; §5 order.
- `src/test/java/com/imin/iminapi/audienceplan/engine/PlanCalculatorOverridesTest.java` — cap tests.
- `src/test/java/com/imin/iminapi/audienceplan/engine/PlanCalculatorTest.java` — §5 segment/action order.
- `src/test/java/com/imin/iminapi/audienceplan/controller/AudiencePlanControllerScenarios.java` — §5 JSON indexes.
- any other test that pins plan segment position (found by the suite run).

## Ordered steps
1. Reproduction tests (red on base): `CandidateBuilderTest.bigLowRateSegment_outranksTinyHighRateOne` and
   `PlanCalculatorOverridesTest.cap_dropsTheSmallestExpectedSegment_notTheLowestRate`.
2. Change `order()` to expected mid desc → rate mid desc → class order → fit.
3. Add one test per tie-break branch (rate, class order, fit).
4. Update §5 order pins (see Test impact), comments.
5. Full suite.

## Verification commands
- `./mvnw test -Dtest='CandidateBuilderTest,PlanCalculator*Test,AudiencePlanController*,MomentumPlanTarget*Test'` (targeted)
- `/Users/ivan/.imin-pipeline/mvnlock.sh test` (full)

## Test impact
§5 warm fixture expected mids: loyal 16.00, repeat 13.44, first_timer 22.56 → new order first_timer, loyal, repeat.
Every number of §5 is unchanged (per-segment values, totals 26/52/93, coverage, gap, holdouts); only positions move,
and the spec's own §11 example puts first_timer first. Invite actions follow the segment order, so action order moves
the same way. `capZero_keepsEverySegment` order becomes first_timer, loyal, repeat, lapsing (lapsing 0.80 is still last).
`cap_keepsTheHighestRateSegments…` renamed to the expected-mid rule; with lapsing 20 (mid 0.80) the kept set is unchanged.

## Live-test
Not needed: pure engine ordering; covered by unit + web tests. The demo seed that found the bug is not in this repo.

## Contract impact
none (same schema; `segments[]` order changes).

## i18n impact
none.

## Blast radius
Plan segment order in the GET/POST response and stored `audience_plan_segments.position`, invite action order,
which segments survive the top-3 cap (so expected/coverage/gap/verdict can rise for orgs with 4+ segments).
`inputs_hash` does not include the ranking rule, so stored plans keep the old order until their 24 h freshness lapses
(no action, per the task). `MomentumPlanTarget` picks highest stored expected mid, tie → lower position: consistent.

## Risks
Floating ties: expected mids are sums of doubles; exact comparison is deterministic (total order), a near-tie just
resolves by value. The other-genre gate still sees every segment before the cap (existing ponytail).

## Definition of done
Repro tests red on base, green after; full suite green; §5 numbers unchanged.

## Live-test evidence
n/a

## Review rounds
- Round 1 → SHIP. One MEDIUM fixed: `equalExpectedMid_higherRateFirst` now uses repeat/other ×25 vs lapsing/unknown ×24
  (equal expected mid, lapsing wins only on rate, loses on class order and fit); proven by deleting the rate comparator → test red.
