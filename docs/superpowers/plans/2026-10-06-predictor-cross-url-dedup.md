# Predictor: one event found on two sites counts once
Slug `predictor-cross-url-dedup` · Mode Direct (one repo, no contract, no migration)

## Goal and scope
`FindingValidator.assign` skips a big event (5.3) only when a same-genre night item (2.1) **from the same URL** names the same event (`FindingValidator.java` `sameGenreNightByUrl` + `namesSameEventOnPage`). One event found on a club page (2.1) and a city agenda (5.3) still scores +4 and +4, which with calendar risk can reach MOVE (≥7). Fix: compare a 5.3 against every same-genre night item of the check, whatever its URL, with the existing `sameEvent` rule (an event word of either name in the other's quote, city words ignored). Out of scope: 2.2 vs 5.3 (different nights by definition), same-genre duplicates (already one finding per question per night).

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master @e804bf8f | /Users/ivan/imin/imin-api/.claude/worktrees/predictor-cross-url-dedup | `docker info >/dev/null && ./mvnw test` |

## Affected files (per repo)
- `src/main/java/com/imin/iminapi/predictor/research/FindingValidator.java`: `sameGenreNightByUrl` → `List<Checked> sameGenreNight`; the 5.3 arm checks `namesSameEvent(c, sameGenreNight, cityWords)`; javadoc + comment updated.
- `src/test/java/com/imin/iminapi/predictor/research/FindingValidatorTest.java`: new `sameEventOnTwoSitesCountsOnce` (2.1 on URL, 5.3 naming it on URL+"/c" → only 2.1, both orders); `differentBigEventOnAnotherSiteIsKept` (2.1 Amelie Lens on URL, 5.3 Fête des Lumières on URL+"/c" → 2.1 + 5.3); `webFindingNeverStopFactor` keeps its assertion that all three questions appear by making its /c big item a different event.
- `CLAUDE.md` line 49: "a big event on a page that gave the night's 2.1" → "a big event that names the same event as any of the night's same-genre items".

## Ordered steps
1. Write the two new tests; `sameEventOnTwoSitesCountsOnce` red on current code.
2. Implement; both green; mutation: restore per-URL lookup → `sameEventOnTwoSitesCountsOnce` red.
3. Update CLAUDE.md, full gate.

## Verification commands
`docker info >/dev/null && ./mvnw test`

## Test impact
Repro: `sameEventOnTwoSitesCountsOnce` — expected red `Expecting actual: ["2.1", "5.3"] to contain exactly: ["2.1"]`.

## Live-test
No (depends on what Exa returns; unit-covered).

## Contract impact
none

## i18n impact
none (5.3 copy unchanged; now shown less often for the same event)

## Blast radius
Only web 5.3 assignment. Fewer 5.3 findings when names overlap across sites → lower risk on those nights. Stored checks unchanged; cache hits re-assigned with the new rule.

## Risks
Generic shared word (club, festival) across two sites now also hides a real 5.3 from another site — accepted direction ("soften, not invent"), same ceiling as today's per-page rule.

## Definition of done
Tests above green, mutation shown red, full gate green, shipped.

## Live-test evidence
n/a

## Review rounds
- round 1 → APPROVE (CLEAN). LOW fixed: inline comment matched to the javadoc. LOW skipped: test URLs share a host (still distinct keys).
