# Poster text gate: exact text, one occurrence, regenerate on every failure
poster-text-gate · Subagent (api only) · Notion: n/a

## Goal and scope
First prod live-test (2026-10-12, 3 posters, "The Midnight Bean Society" / 31 OCT 2026) shipped: a render printing "BEAn SOCIETY" with 4–5 ghost repeats of "BEAN SOCIETY" that passed the gate; an extra-only failure ("31 OCT" fragment) remixed at weight 70 that lost the date (a fresh generate then passed; poster-lab remix fixed 0/2); fine print still slips (earlier C3 lab: 1/4 caught).
Root cause: the gate never compares text in code — the vision model classifies itself ("exactly or near-exactly", `OpenRouterPosterTextValidationClient.java:33`), sees the expected lists (`:118-123`), is told to deduplicate (`:41`); the service only counts returned lists (`PosterTextValidationService.java:48-53`).

In scope:
1. **Exact text.** Model only transcribes (verbatim case, one entry per text block, `count`, unreadable marks); new code `PosterTextMatcher` decides missing/extra. A listed string matches **as written or in ALL CAPS** ("BEAn" fails; Title Case/lowercase of a differently-cased string fails). Date ("31 OCT 2026", built upper-case `PosterTextSpecFactory.java:19`) and city (`:30`) exact; venue and DJ names follow the title rule.
2. **One occurrence.** Each required/allowed string may appear once; further copies and fragments are extra.
3. **Regenerate on every text failure.** Delete the corrective remix path; remix stays only for the DJ palette regrade (`PosterOrchestrator.java:396-415`).
4. **Fine print.** (a) Prevention sentence appended to the code-built text sentence when the poster has text. (b) Detection: second vision call on four 12% edge bands at 2x, only when the main pass accepted, behind `POSTER_TEXT_VALIDATION_EDGE_PASS_ENABLED`, same model. (c) Model switch only via `POSTER_TEXT_VALIDATION_MODEL` on Railway after the lab; code default stays `openai/gpt-4o`.
5. Journal records `text.read` = not_run|read|unusable|failed and `text.edge` = off|skipped|clean|marks|unusable|failed.
Out of scope: date format, render speed, best-of ordering (`isBetter`), style gate, webapp, Ideogram V4.

Model candidates (OpenRouter, 2026-10-12, USD/1M in/out): `openai/gpt-4o` 2.50/10.00 (current); `openai/gpt-4o-mini` 0.15/0.60 (under-reads); `google/gemini-3.8-flash` 0.75/3.75 (lab arm); `anthropic/claude-haiku-5.5` 0.10/0.50 (lab arm; no temperature param); `openai/gpt-6-luna` 0.10/0.50 (lab arm; no temperature); `qwen/qwen3.8-flash` 0.15/0.47 (reserve); `anthropic/claude-sonnet-5.5` 2.00/10.00 (reserve); `openai/gpt-6.1-sol` 2.00/10.00 (reserve). `google/gemini-2.0-flash-001` (yaml `:114`) no longer listed → drop that comment. Client always sends `temperature: 0` and `provider.data_collection=deny`; lab sends identical body.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/poster-text-gate` (origin/master 8935a2f5, contains 6cbb65e5) | `./mvnw test` (Docker up, zero skipped) |
Two commits, one push: commit 1 retry policy, commit 2 gate.

## Affected files (per repo)
| # | File | Change |
|---|---|---|
| 1 | `service/poster/PosterOrchestrator.java` | loop `:301-346`: any non-last text failure → `MODE_REGENERATE`, `nextSeed`, `generate(variant.ideogramPrompt(), seed, style.parts(), style.preset(), brandPalette, characterRef)`; delete `correction`, remix branch `:309-311`, `MODE_REMIX` `:63`, `remixImageWeight` `:78,:96,:112`, `buildCorrectionPrompt` `:475-491`, `isDescription` `:494-497`, `describesUnreadableText` `:499-501`, `quoteJoin` `:503-505`; `paletteRegrade` `:399-401` drops trailing null; `attemptJson` adds `text.read`/`text.edge`; `failedRenderJson` adds `text.read: "not_run"`; javadocs |
| 2 | `service/poster/IdeogramV3Client.java` | `remix`/`buildRemixParts` lose `characterRef` (only caller = regrade, passes null); javadoc: remix = regrade only |
| 3 | `service/poster/PosterTextValidationClient.java` | `Transcript read(byte[] png)`, `List<Transcript> readEdges(List<PosterEdgeBands.Band>)` (top, bottom, left, right); `record Seen(String text, int count)`, `record Transcript(List<Seen> texts, List<String> unreadable)`; `UnusableReadException` |
| 4 | `service/poster/OpenRouterPosterTextValidationClient.java` | `VALIDATION_PROMPT` → `READ_PROMPT` (no lists sent); new `EDGE_PROMPT`, multi-image request; package-private static `parseTranscript`/`parseEdges` (count missing/invalid → 1, capped 10; missing arrays/band, non-JSON, empty choices → Unusable); delete `validationPrompt`/`bulletLines`; transport errors propagate |
| 5 | `service/poster/PosterTextValidationService.java` | flow below; ctor `@Value("${poster.text-validation.edge-pass.enabled:true}")`; `ValidationDecision` + `ReadOutcome read`, `EdgeOutcome edge`; keep 4-/5-arg ctors; `pass()` = not run; new `accepted(EdgeOutcome)` |
| 6 | `service/poster/PosterTextMatcher.java` (new) | pure static `normalize`, `match`, `edgeExtras` |
| 7 | `service/poster/PosterEdgeBands.java` (new) | `crop(byte[] png)` → bands, `BAND_FRACTION = 0.12`, `SCALE = 2`, bicubic, PNG; decode failure → `IllegalStateException` |
| 8 | `service/poster/PosterTextSpecFactory.java` | `textSentence` `:63-64`: when any element present append `" These are the only words on the poster: no other lettering, captions, credits, fine print, or vertical text anywhere."`; no-text sentence unchanged |
| 9 | `service/poster/BrandSnapshot.java` | `:14` wording → "retry and regrade renders" |
| 10 | `model/PosterGeneration.java` | `:46-47` same |
| 11 | `src/main/resources/application.yaml` | delete `ideogram.remix.image-weight` `:84`; comments `:65-67,:80,:109-114,:122`; add `poster.text-validation.edge-pass.enabled: ${POSTER_TEXT_VALIDATION_EDGE_PASS_ENABLED:true}` (default set from lab) |
| 12 | `.env.example` | `# POSTER_TEXT_VALIDATION_EDGE_PASS_ENABLED=true` + one-line comment |
| 13 | `CLAUDE.md` | `:15`, `:40` (drop `IDEOGRAM_REMIX_IMAGE_WEIGHT`, add edge var, "text gate + regenerate"), request flow `:165-180`, `:190` |
| 14 | `PosterOrchestratorTest.java` | see Test impact |
| 15 | `PosterTextValidationServiceTest.java` | rewritten around fake transcripts |
| 16 | `PosterTextMatcherTest.java` (new) | M1, M2 |
| 17 | `PosterEdgeBandsTest.java` (new) | B1 |
| 18 | `OpenRouterPosterTextValidationClientTest.java` (new) | P1 |
| 19 | `IdeogramV3ClientTest.java` | delete remix half `:78-80`, `remixCarriesCharacterRef` `:83-91`; remix calls `:167,:239` drop last arg |
| 20 | `PosterTextSpecFactoryTest.java` | 3 sentences `:45-54` gain the appended sentence |
| 21 | `ConceptStudioServiceTest.java` | `:609-610` same |
| 22 | `AiEventDescriptionServiceTest.java` | `SENTENCE` `:586-588` same |
Outside repo: lab scripts `~/Claude Cowork/OUTPUTS/IMIN/poster-lab/results/2026-10-12_text-gate-v2/`.

## Ordered steps
**Step 0 — baseline.** `docker info`; untouched `./mvnw test` → `<scratchpad>/gate-api-poster-text-gate.log`. Prod images (read-only):
```sql
SELECT v.id, g.created_at, v.variant_style, v.validation_verdict, v.seed,
       v.raw_url, v.final_url, v.validation_attempts_json
FROM poster_variants v JOIN poster_generations g ON g.id = v.generation_id
WHERE g.created_at >= '2026-10-12' AND v.ideogram_prompt ILIKE '%midnight bean society%'
ORDER BY g.created_at DESC, v.variant_style;
```
Download the 3 `raw_url`s to `images/prod_<variant_style>.png` + journals. `truth.json` before any new-gate run: 28 lab images with eyeball records (32 in `2026-10-12_date-text/images` minus 4 `*_E.png`) + 3 prod; should-reject = `eyeball.reqOk == false` OR `eyeball.microtext == true` OR note names invented/side/credit text; prod: BEAn = reject, the other two from the coordinator's eye (accepted, clean: speaker poster, face poster). Old-gate baseline via jshell on `target/classes` (`new OpenRouterPosterTextValidationClient(key, "https://openrouter.ai/api", "openai/gpt-4o", 1500, 60)` + `new PosterTextValidationService(client, true, 0)`), 31 images (~$0.16).
**Step 1 — retry policy (commit 1).** O1 first; red on base for extra-only (remix invoked, `PosterOrchestrator.java:341-343`). Rows 1, 2, 9, 10, 11 (remix key + comments), 13 (retry wording), 14, 19. test-compile; greps; full gate.
**Step 2 — gate (commit 2).**
- Matcher: `normalize` = NFC; whitespace runs → one space; trim; `‐‑‒–—―` → `-`; `‘’` → `'`; `“”` → `"`; case never folded. `matches(seen, target)` = equal to normalized target OR to its `toUpperCase(Locale.ROOT)`. `match`: expand each Seen into `count` copies; targets = required in order, then allowed not already required; per target consume first unconsumed matching entry, else first run of 2–4 consecutive unconsumed entries whose space-join matches, else (required) → missing; leftovers → extra, one per distinct normalized text, ` ×n` when n > 1; each unreadable → `"[" + d + "]"`. `edgeExtras`: per band, ignore texts with < 2 letters/digits or that are a case-insensitive normalized substring of any required/allowed string; else `"[edge <band>: <text>]"`; unreadable → `"[edge <band>: <d>]"`.
- Bands: top/bottom = full width × round(0.12·H); left/right = round(0.12·W) × full height; ×2.
- `READ_PROMPT` (verbatim):
```
You are transcribing every piece of text on an event poster exactly as it is drawn. You do not check spelling or meaning.
- Copy each piece of text character for character, keeping the exact upper- and lower-case of every letter. Do not correct spelling, change case, or complete cut-off words.
- One entry per text block; join the lines of one block with a single space, in reading order.
- "count" is how many separate places that exact text appears, including faint, ghosted, repeated, mirrored or background-pattern copies.
- Include small, faint, rotated, vertical and marginal text: fine print, credit lines, captions, and logos or watermarks with letters.
- Letter-like marks you cannot read go in "unreadable", once per area, as a short description with its position (e.g. "fine print, bottom-left"). A pattern with no letter-like shapes is not text.
- At most 40 entries. Ignore QR codes.
Return JSON only: {"texts":[{"text":"...","count":1}],"unreadable":[]}
```
- `EDGE_PROMPT` (verbatim):
```
The images are four strips cut from the edges of one event poster, in this order: top, bottom, left, right. Side strips may hold rotated or vertical text.
For each strip, transcribe every letter, digit or letter-like mark exactly as drawn (case kept, no corrections); text cut off by the strip edge counts. Marks you cannot read go in "unreadable" as a short description. A pattern with no letter-like shapes is not text.
Return JSON only: {"top":{"texts":[],"unreadable":[]},"bottom":{"texts":[],"unreadable":[]},"left":{"texts":[],"unreadable":[]},"right":{"texts":[],"unreadable":[]}}
```
- Service flow: (1) disabled/empty contract → `pass()`; (2) `read` Unusable → rejected, evaluated=false, read UNUSABLE; other RuntimeException → read FAILED; edge SKIPPED; (3) `match`; missing non-empty or extra > maxExtra → rejected (edge SKIPPED, no edge call); (4) edge off → `accepted(OFF)`; (5) crop + `readEdges`: Unusable → accepted, edge UNUSABLE; crop failure/other throw → accepted, edge FAILED (fail OPEN to the main-pass verdict, Gate 1); (6) `allExtra = extra + edgeExtras`; > maxExtra → rejected MARKS, else `accepted(CLEAN|MARKS)`. Reason format unchanged.
- Rows 8, 3, 11 (edge key), 12, 13 (gate wording), 15–18, 20–22. Full gate.
**Step 3 — lab "after" + decisions.** Rebuild classpath; jshell runs new service (edge on) on 31 images with gpt-4o, gemini-3.8-flash, claude-haiku-5.5, gpt-6-luna; per model: BEAn verdict + raw transcript, fine-print misses caught (/4), should-reject caught, false rejects, edge catches, mean `usage.cost` per main/edge call, rejected calls. Prevention arm P (8 Ideogram TURBO ≈ $0.24): lab B prompts + new sentence, seeds 424242/777001, 4 events; eyeball title/date/venue + fine print vs B (B: 8/8 title+date; fine print 4/8: e1_B_s1, e2_B_s1, e1_B_s2, e4_B_s1). Record in Live-test evidence; user decides edge default, sentence, Railway model; apply choices; full gate.
**Step 4.** Rebase if needed; full gate.

## Verification commands
```
docker info >/dev/null && echo docker-up
cd /Users/ivan/imin/imin-api/.claude/worktrees/poster-text-gate
./mvnw test 2>&1 | tee <scratchpad>/gate-api-poster-text-gate.log
grep -E "Tests run:.*Skipped: [1-9]" <scratchpad>/gate-api-poster-text-gate.log && echo RED-skipped
grep -rn "buildCorrectionPrompt\|isDescription\|MODE_REMIX\|remixImageWeight\|remix.image-weight\|IDEOGRAM_REMIX_IMAGE_WEIGHT" src CLAUDE.md .env.example   # no hits
grep -rni "corrective remix" src/main CLAUDE.md .env.example                                                     # no hits
grep -rn "ValidationResult(" src/main/java/com/imin/iminapi/service/poster/PosterTextValidation*.java              # no hits
grep -n "EDGE_PASS" CLAUDE.md .env.example src/main/resources/application.yaml                                    # 3 files
```

## Test impact
All unit (no Spring).
- **O1** `PosterOrchestratorTest.everyTextFailure_isRegeneratedFromTheOriginalPrompt_neverRemixed` (missing / extra-only / `[fine print, bottom-left]` / unevaluated): remix never called; generate twice with the original prompt; seeds differ; modes `[generate, regenerate]`; ACCEPTED. Bug: date lost by a remix (prod 2026-10-12). Red on base (extra-only).
- **M1** `PosterTextMatcherTest.match_appliesCaseRepeatAndJoinRules` (13 rows): exact pass; ALL-CAPS pass; `THE MIDNIGHT BEAn SOCIETY` → missing + extra; lowercase → missing; split title pass; title count 3 → `×2` extra; ghost `BEAN SOCIETY ×5` → title pass + `BEAN SOCIETY ×4`; `31 OCT` fragment → extra; city once pass / DJ twice extra; unreadable → bracketed; line break/double space/en dash pass; empty → all missing; invented → extra.
- **M2** `edgeExtras_flagOnlyTextThatIsNotTheEventText`: `socie` ignored; `© ODD MOOD CO.` flagged; unreadable left flagged; `I` ignored; nothing → none.
- **S3** `unreadableMainPass_isNotAccepted_andRecordsWhy` (Unusable → UNUSABLE; ResourceAccessException → FAILED): not accepted, evaluated false, no edge call.
- **S4** `mainPassRejects_edgePassNeverSent`.
- **S5** `mainPassClean_edgeOutcomeDecides` (OFF/CLEAN accepted; MARKS rejected; UNUSABLE/FAILED accepted with the outcome recorded).
- **S6** `extraTolerance_countsMainAndEdgeTogether` (maxExtra 1).
- **P1** `OpenRouterPosterTextValidationClientTest.parseTranscript_and_parseEdges` (valid; count absent → 1; 0 → 1; 99 → 10; texts missing / non-JSON / band missing → Unusable).
- **B1** `PosterEdgeBandsTest.crops_fourEdgeBands_atTwiceTheirSize` (896×1120: top/bottom 1792×268, left/right 214×2240; corner mark in bottom+left only; centre in none).
Changed: spec-sentence tests (3 rows + ConceptStudioServiceTest:609 + AiEventDescriptionServiceTest:586); PosterTextValidationServiceTest keeps disabled / empty-contract / allowed-only (moved to transcripts), deletes the model-verdict tests (rules now in M1/S3/S6); PosterOrchestratorTest deletes `textFails_thenRemixCorrects_remixPromptCarriesExtraText` (`:148`), `djModeCorrectiveRemixCarriesCharacterRef` (`:411`), `missingRequiredText_isRetriedWithFreshGenerateAndNewSeed_notRemix` (`:571`), `extraTextOnly_isStillRetriedWithRemix` (`:593`), `unevaluatedGate_isRetriedWithFreshGenerate` (`:684`), `unreadableMarksDescription_isRegenerated_neverQuotedIntoARemix` (`:817`), `correctionPrompt_namesGarbledWords_butNeverQuotesADescription` (`:832`) (routing folded into O1); `brandColors_forwardedToIdeogramGenerateAndRemix` → `brandColors_forwardedToEveryGenerate`; ctor helpers drop remixImageWeight; remix matchers 8 → 7 args.
Prove red: M1 r3 `equalsIgnoreCase`, r2 drop caps branch, r5 drop run-joining, r6/r7 unlimited consume; M2 r1 case-sensitive edge match, r4 drop 2-char floor; S4 always call edges; S5 MARKS drop edge extras, FAILED fail open; P1 default count 0; B1 swap left/right; O1 on base + re-add remix branch; spec sentence drop / add to no-text.
Not tested: prompt wording, yaml defaults, journal key/reason strings. Stay green unedited: `PosterStyleValidationServiceTest`, `PosterProvenanceTest`, `ConceptStudioServiceDjPhotoTest`, `RenderPromptTest`, `StyleCardVisualOnlyTest` (Spring tests have text validation disabled, `src/test/resources/application.yaml:37-39`).

## Live-test
1. Pre-ship: Step 0 + Step 3 lab numbers.
2. After deploy: one Poster Studio generation (mixed-case title, dated test event); read-only `SELECT variant_style, validation_verdict, seed, final_url, validation_attempts_json FROM poster_variants v JOIN poster_generations g ON g.id=v.generation_id ORDER BY g.created_at DESC LIMIT 3;` — modes only generate/regenerate/palette-regrade; every entry has `text.read`; accepted entries `text.edge` ∈ {clean, marks, off}; one `shipped:true` per variant; eyeball: title case as typed or caps, no ghost repeats, no fine print. No `[ideogram v3 remix]` log lines except regrade.
Cost estimate per variant (lab replaces with measured `usage.cost`): main read ≈ $0.005; edge ≈ $0.009; first-try $0.035 → $0.044; worst case $0.105 → $0.114; retry change cost-neutral.

## Contract impact
None.

## i18n impact
None (journal internal; appended sentence goes to Ideogram only).

## Blast radius
Every poster render (create/regenerate) + DJ palette regrade gets the stricter gate and edge pass → more regenerates (bounded by max-regenerations 2) and possibly more BEST_EFFORT; +1 vision call (~3–8 s) per accepted render. `IdeogramV3Client.remix` signature (one caller). `IDEOGRAM_REMIX_IMAGE_WEIGHT` inert. New optional `POSTER_TEXT_VALIDATION_EDGE_PASS_ENABLED`. No money/auth/Stripe/Flyway/contract.

## Risks
- 22 files, two concerns → two commits one push (commit 1 alone fixes the "remix lost the date" incident).
- Case fidelity rests on the vision model: if no lab model keeps "BEAn", stop at Step 3 and report.
- New false rejects (strict case, punctuation drift, out-of-order blocks, edge texture) — lab measures; retries + best-of are the net.
- Prevention sentence may prime fine print — arm P decides.
- Edge pass fails open: an edge outage or unusable edge reply keeps the main-pass verdict (journal `text.edge` = failed/unusable), so fine print can slip through during an outage.
- ImageIO decode assumed (PNG 896×1120).
- Lab size: 31 images.

## Definition of done
- O1, M1, M2, S3–S6, P1, B1 pass; each guard proven red; changed tests carry new expectations.
- `./mvnw test` green, zero skipped, after any rebase; greps clean.
- Lab baseline + after numbers recorded (BEAn verdict + transcript per model, fine-print catches, false rejects, cost per call).
- User decided edge default, prevention sentence, Railway model.
- CLAUDE.md / yaml / .env.example describe regenerate-only retries, matcher rules, edge pass; no "corrective remix".
- Post-deploy live-test recorded.

## Live-test evidence
Step 0 (2026-10-12/13). Baseline `./mvnw clean test` on 8935a2f5..a54553b3: 7420 run, 0 failed, 0 skipped. Prod SQL read-only: 6 rows over two generations; the 3 posters of the incident are generation 19:57 (`typographic` 380748ff = BEAn, BEST_EFFORT after a regenerate; `object` f5b9b7ac = speaker; `people` c8d017cb = face, whose journal shows the extra-only "31 OCT" remix losing the date). Required = ["The Midnight Bean Society", "31 OCT 2026"]. Lab files: `~/Claude Cowork/OUTPUTS/IMIN/poster-lab/results/2026-10-12_text-gate-v2/` (truth.json: 31 images, 19 should-reject, 12 should-pass; calls_*.jsonl = every OpenRouter call with usage.cost; armP/).

| gate / model | BEAn | fine print /4 | should-reject caught /19 | false rejects /12 | unusable main reads | $/main call | $/edge call |
|---|---|---|---|---|---|---|---|
| OLD gate, gpt-4o | **accepted** | 2 | 16 (missed e1_C_s1, e2_B_s1, BEAn) | 0 | 0 | 0.00339 | – |
| new, gpt-4o | rejected (read `THE MIDNIGHT BEAn SOCIETY` ×1, `BEAN SOCIETY` ×5) | 4 | 19 | **10** | 0 | 0.00344 | 0.00925 |
| new, gemini-3.8-flash | rejected only as unusable (hit max_tokens 1500) | 4 | 19 | 9 | 12 | 0.00508 | 0.0076 |
| new, claude-haiku-5.5 | rejected only as unusable (```json fence; transcript itself correct) | 4 | 19 | 8 | 3 | 0.00032 | 0.00059 |
| new, gpt-6-luna | rejected (read `BEAn SOCIETY`, ghost ×6) | 4 | 19 | 11 | 3 | 0.00048 | 0.00091 |

New-gate false rejects (gpt-4o): 5 = e4 title typed "NUIT CHROME", rendered Title Case "Nuit Chrome" (case rule working as specified); 1 = one block "3 OCT 2026 LA STATION" covering two targets (matcher cannot split a block); 3 = edge pass (e2_C_s1 `CAP SUD` + "pattern", e3_B_s2 `OTEE`, prod_face `THE B` = stacked title cut by the left strip); 1 = prod_speaker sticker text `RAVIT COMIB ×36` (labelled should-pass, but the stickers do carry pseudo-text). Edge pass caught no should-reject image the main pass missed. Lab spend: $0.105 old + $0.371 new + $0.24 Ideogram arm P = ≈ $0.72.

Prevention arm P (B prompts + the only-words sentence, 8 TURBO renders, `armP/`): title+date+venue 8/8 (e4 title Title Case again); fine print / invented text 6/8 (P_e1_s1, P_e1_s2, P_e2_s1, P_e2_s2, P_e3_s2 fine print; P_e3_s1 leaked label "VENUE") vs B 4/8 — no improvement.

Targets not met (false rejects 10/12 with gpt-4o) → Step-3 decisions (edge default, sentence, Railway model) NOT applied; awaiting the user.

After the Step-3 decisions (below), re-run with the tuned gate, gpt-4o, edge pass off, same 31 images (`new_gate_v2_edgefalse_openai_gpt-4o.json`, `calls_new_gate_v2.jsonl`). prod_speaker relabelled should-reject on a full-res crop: its stickers carry letter-like pseudo-text (e.g. "FUMRORORUTEF") → 20 should-reject / 11 should-pass.

| gate / model | BEAn | fine print /4 | should-reject caught /20 | false rejects /11 | unusable | $/call |
|---|---|---|---|---|---|---|
| tuned, gpt-4o, edge off | rejected (`THE MIDNIGHT BEAn SOCIETY`, `BEAN SOCIETY` ×5) | 4 | 20 | 2 (e2_C_s1 `JAMES`; e2_D_s1 `©`, `miil studio.com` — the full image does show a © mark after SOCIETY and tiny vertical print bottom-right, so its "clean" label looks wrong) | 0 | 0.00356 (31 calls, $0.110) |

Total lab spend ≈ $0.83.

## Review rounds
- Step-3 decisions (coordinator, under the user's autonomous-work authorization, 2026-10-13): (1) case rule per word — as written, ALL CAPS, Title Case or lowercase; a word re-cased inside (e.g. "BEAn") fails; (2) one block may cover several required strings joined by spaces, in any order; (3) edge pass default OFF (code + tests kept); (4) prevention sentence dropped (arm P 6/8 fine print vs B 4/8); (5) model stays openai/gpt-4o, parser strips ```json fences, max_tokens unchanged; (6) re-run gpt-4o edge-off lab. Applied in commit 3.
- Gate 1 approved by the user 2026-10-12 as recommended: case rule as written or ALL CAPS (date, city exact); lab targets as stated; edge pass FAILURE → accept the main-pass verdict (fail OPEN on edge outage, journal edge=failed), edge UNUSABLE likewise; lab spend ~$1.6 OK and worker runs the prod SQL read-only; the other two prod posters (speaker, face) are labelled clean/should-pass by the coordinator; unset IDEOGRAM_REMIX_IMAGE_WEIGHT on Railway after ship (coordinator). Repro: O1 red on base (extra-only remix).
