# M2-1 LlmPayloadGuard

Slug: `ap-m2-1-llm-guard` · Programme: `docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (workspace) § M2-1 · Mode: Subagent (autonomous runner)

## Goal and scope

Add `com.imin.iminapi.audienceplan.service.LlmPayloadGuard`: a pure check that every outgoing audience-plan LLM prompt carries no personal data. It rejects a payload that contains:
- an email address;
- a phone number: E.164 (`+` then 7+ digits, spaces / dots / hyphens / parentheses allowed between digits) or French national (`0[1-9]` then four digit pairs, optional single separator);
- any name from the org's memberships/consumers name sample passed in by the caller (whole-word, case-, accent- and whitespace-insensitive; names under 2 letters are ignored because they would match ordinary words).

The rejection is a `LlmPayloadGuard.Rejected` exception carrying only the category (`EMAIL`, `PHONE`, `NAME`), never the matched value, so logs cannot leak it.

Out of scope: callers. Summarizer (M2-2) and PortraitService (M2-7) do not exist yet; the programme's "guard is invoked by Summarizer and PortraitService (spy test)" lands with those tasks. This task adds a wiring test that fails if any `audienceplan` class holds a `ChatClient`/`ChatModel` without also holding an `LlmPayloadGuard`, so those tasks cannot skip it. No config key, no flag, no migration.

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-1-llm-guard` | `./mvnw test` |

## Affected files

api:
- `src/main/java/com/imin/iminapi/audienceplan/service/LlmPayloadGuard.java` (new)
- `src/test/java/com/imin/iminapi/audienceplan/service/LlmPayloadGuardTest.java` (new)
- `src/test/java/com/imin/iminapi/audienceplan/service/LlmCallersUseGuardTest.java` (new)

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Write `LlmPayloadGuardTest` (one test per branch), then `LlmPayloadGuard`.
3. Write `LlmCallersUseGuardTest` (scan + a violating fixture proving the rule fails).
4. Rebase onto latest origin/master, run `./mvnw test`.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m2-1-llm-guard && ./mvnw test`

## Test impact

New tests only. Branches of `check(payload, names)`:
- clean aggregate payload (numbers, percentages, genre keys, a French date) passes;
- email → `EMAIL`; `+33 6 12 34 56 78` → `PHONE`; compact E.164 `+447700900123` → `PHONE`; FR national `06 12 34 56 78` / `0612345678` / `06.12.34.56.78` → `PHONE`;
- known member name → `NAME`; case/accent/whitespace variants → `NAME`; name as part of a longer word does not match; null / blank / 1-letter names ignored; null name collection treated as empty;
- null payload → `NullPointerException`;
- exception message never contains the matched value.
Wiring rule: current `audienceplan` classes pass; a fixture class with a `ChatClient` field and no guard fails the rule.

## Notes for M2-2 / M2-7

- The guard also applies when an audience-plan class wraps an existing LLM helper from another package (e.g. a service that holds its own `ChatClient`): call `check` on the prompt before handing it over; the wiring test only sees handles held directly.
- The Summarizer must not write `+` before large space-grouped numbers (`+1 234 567`); the guard reads that as a phone and fails closed.

## Live-test

Not needed: no endpoint, no bean wiring change reachable at runtime (no caller yet).

## Contract impact

none

## i18n impact

none

## Blast radius

New class in a new package directory, no callers. A `@Component` bean with no dependencies is added to the context.

## Risks

- False positives on aggregate text that looks like a phone number (e.g. `+1 234 567`). Fail-closed by design; the Summarizer (M2-2) falls back to its code template on rejection.
- Name sample is caller-supplied; a caller that passes an empty sample only gets the email/phone checks. M2-2/M2-7 own passing the sample.

## Definition of done

`./mvnw test` green after rebase; guard and wiring tests as above.

## Live-test evidence

n-a (see Live-test).

Verification 2026-09-26:
- Baseline (origin/master e15a478e, untouched): 3375 run, 1 error: `MarketingOptInWriteTest.freeCheckout_leavesBuyerLocaleNull_whenUnsupportedOrAbsent` teardown FK violation (`delete from consumers` while a membership row remains); unrelated, order-dependent, passed on the next full run. Its own card.
- Targeted: `LlmPayloadGuardTest` 23/23, `LlmCallersUseGuardTest` 3/3.
- After rebase onto origin/master 38ddf336: `./mvnw test` 3403 run, 0 failures, 0 errors, BUILD SUCCESS.
- Round 1 fixes, rebased onto origin/master 86c92ed7: `./mvnw test` 3441 run, 0 failures, 0 errors (LlmPayloadGuardTest 48, LlmCallersUseGuardTest 4, MarketingOptInWriteTest 15).

## Review rounds

Round 1 (FIX_REQUIRED) applied:
- HIGH: wiring scan reads every `.class` via `PathMatchingResourcePatternResolver` + `MetadataReaderFactory`, so `@Conditional*`/`@Profile` classes are kept; fixture `@ConditionalOnProperty` caller without guard is flagged.
- Caller detection widened: `ChatClient`, `ChatClient.Builder`, `ChatModel`, `StreamingChatModel`, inside any generic (`ObjectProvider`, `Supplier`, `Optional`, nested, wildcards), fields and constructor parameters; fixtures for each.
- Phones: one consistent separator for FR national (dates pass), ES/PT 9-digit (6/7/9), UA `0XX XXX XX XX` / `(0XX) XXX-XX-XX`, `00` international, brackets only right after the country code. Dates, ranges and percentages are clean-pass tests.
- EMAIL anchored with a lookbehind + possessive quantifiers; 50k-char run without `@` < 200 ms. NFKC before email/phone checks (full-width `＠`, digits, NBSP / U+202F).
- Names: folded once into a single alternation; `-` ≡ space, `’` ≡ `'`, `ß` ≡ `ss`.
- Out of scope but fixed: `MarketingOptInWriteTest` teardown now drains the default `taskExecutor` before deleting, so the AFTER_COMMIT `@Async` `AudienceOrderProjector` cannot insert a membership between the `memberships` and `consumers` deletes (the baseline FK error).

Round 2 (CLEAN, follow-ups applied): payload capped at 20,000 chars (`TOO_LONG`); international digit groups bounded `{5,15}`, email labels `{1,20}` (no StackOverflowError on `"+" + "1 ".repeat(4000)` / `"a@b" + ".c".repeat(4000)`); `(+33) …`, `+ 33 …`, `+33 - …` rejected; pinned gaps: mixed-separator FR number passes, `+1 234 567` rejected.
