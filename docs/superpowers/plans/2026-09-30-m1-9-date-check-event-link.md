# Check a date: event link and stale detection
m1-9-date-check-event-link · Subagent · Notion: filled by main session

## Goal and scope
Link a date check to the event created from it, and show the event's current check on the prediction response. The check is marked stale when the event's night is not one of the dates it scored. The organizer refreshes it with "Check again": the existing `POST /api/v1/predictions/date-checks` with `eventId`, which runs synchronously and returns 200.

In scope (imin-api only):
- `EventPatchRequest` gains `subGenre` (every create and patch) and `dateCheckId` (CREATE only, like `sourceConceptId`).
- `EventDto` gains `subGenre`.
- `PredictionStatusResponse` gains `dateCheck: EventDateCheckDto {id, result: DateCheckDateDto, checkedAt, forDate, stale}`.
- On create, the link is written both ways: `events.date_check_id`, plus `date_check.event_id` when that is empty.

Out of scope, with reasons:
- The re-check job from the master plan's M1-9 text. Handoff §7.5, decided later, makes "Check again" an organizer-triggered sync POST. Stale is derived when the prediction is read, so no job is needed.
- `dateCheckId` on `EventDto`. The link is served, gated, as `prediction.dateCheck.id`, so no ungated surface carries it.
- Staleness from a city or venue change (see Risks).
- Webapp work (see Contract impact).

## Repos in ship order
1. `api` (imin-api, base `master`), worktree `/Users/ivan/imin/imin-api/.claude/worktrees/m1-9-date-check-event-link` from `origin/master` 5bc64137.

## Affected files (per repo)
imin-api, `src/main/java/com/imin/iminapi/`:

| File | Change |
|---|---|
| `dto/event/EventPatchRequest.java` | Add `@Size(max=64) String subGenre` and `UUID dateCheckId` after `sourceConceptId` (20 components). Keep the 17-arg constructor and add an 18-arg back-compat constructor (the current shape, with both new fields null) so the 19 existing `new EventPatchRequest(` call sites compile. Javadoc: `dateCheckId` is honoured on create only, and an unknown, foreign or gated id returns 404. `subGenre`: blank clears it, and it must be a known sub-genre. |
| `dto/event/EventDto.java` | Add `String subGenre` (after `genre`, NON_NULL already on the record) and fill it in `summary`/`detail`. No external `new EventDto(` sites exist. |
| `service/event/EventService.java` | New primary `@Autowired` 14-arg constructor adding `DateCheckService dateChecks` (nullable). The 13-arg constructor delegates with null. In `applyPatch`: `subGenre` is trimmed; blank sets null; otherwise `dateChecks.requireKnownSubGenre(v)` runs (null service rejects it the same way); counts as `changed`. In `createDraft` only: if `body.dateCheckId() != null`, then before `events.save`: `DateCheck c = requireService().requireLinkable(p, id)` and `e.setDateCheckId(c.getId())`. After `events.flush()` inside the try: `dateChecks.stampEvent(c, saved.getId())`. `patch` never reads `dateCheckId`. Update the class/method javadoc for the new create behaviour. |
| `predictor/config/DateCheckAccess.java` | Add `public boolean isEnabled(UUID orgId)` with the current predicate. `requireEnabled` calls it. |
| `predictor/repository/DateCheckRepository.java` | Add `Optional<DateCheck> findFirstByOrgIdAndEventIdOrderByCreatedAtDescIdDesc(UUID orgId, UUID eventId)`. Both parameters are non-null, so the H2/PG null trap does not apply. |
| `predictor/dto/EventDateCheckDto.java` (new) | `record EventDateCheckDto(UUID id, DateCheckDateDto result, Instant checkedAt, LocalDate forDate, boolean stale)`, with a one-line javadoc. |
| `predictor/service/DateCheckStaleness.java` (new) | Pure `public static Match match(List<DateCheckDate> rowsByDateAsc, Instant startsAt, ZoneId zone)` → `record Match(DateCheckDate row, boolean stale)`, or null when the rows are empty. Night = `NightDates.nightOf(startsAt, zone)`. A row on that night gives stale=false. Otherwise stale=true and the fallback row is the lowest non-null `rankOrder`, else the earliest date. A null `startsAt` goes straight to the stale fallback. |
| `predictor/service/DateCheckService.java` | Add: (a) `@Transactional DateCheck requireLinkable(AuthPrincipal p, UUID id)`: `access.requireEnabled(p.orgId())` first, then `checks.findLockedById(id)` filtered to the org, else `ApiException.notFound("Date check")`. (b) `void stampEvent(DateCheck c, UUID eventId)`: sets `event_id` only when null, then `checks.save`. (c) `void requireKnownSubGenre(String s)`: throws 400 `FIELD_INVALID {"subGenre":"unknown"}` unless some `bank.profiles()` entry lists it. (d) `@Transactional(readOnly=true) Optional<EventDateCheckDto> currentForEvent(Event e)`: if `!access.isEnabled(e.getOrgId())` it returns empty. Candidates are `checks.findFirstByOrgIdAndEventId...(org, e.id)` and, when `e.getDateCheckId()!=null`, `checks.findById(...)` filtered to the same org. The newer by `createdAt` wins. Rows come from `dates.findByDateCheckIdOrderByCandidateDateAsc`; `DateCheckStaleness.match(rows, e.getStartsAt(), zoneOf(check))`, where the zone is `CountryTimeZones.zoneFor(check.country)` else UTC, the same zone `DateCheckInput.zone()` scores with. The row goes through the existing `dateDto` with its findings; `checkedAt = check.updatedAt`. Class javadoc: add the event link. |
| `predictor/dto/PredictionStatusResponse.java` | Add a 6th component `EventDateCheckDto dateCheck` (NON_NULL already). Keep the 4-arg constructor and add a 5-arg back-compat constructor. Javadoc: `dateCheck` is present when the gate is open and a linked check exists; `stale` means the event's night is not a date that check scored. |
| `predictor/service/PredictionRequestService.java` | Constructor gains `DateCheckService dateChecks`. `status()`: `Event e = loadOwned(...)`; `EventDateCheckDto dc = dateChecks.currentForEvent(e).orElse(null)`; pass `dc` into all four return sites (pending, none, unparseable-none, ready/benchmark-only). |
| `predictor/controller/PredictionController.java` | Javadoc of the GET line: response now includes `dateCheck?`. |
| `predictor/dto/DateCheckRequest.java` | One-line javadoc on `eventId`: links the new check to the event and becomes its current check. |

Tests are listed under Test impact. No migration, `application.yaml` or `.env.example` change (no new property).

## Ordered steps
1. Run the check command once on the untouched worktree (`docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`) and record the baseline. If it is already red, stop and report.
2. `DateCheckAccess.isEnabled` plus its tests.
3. `DateCheckStaleness` plus `DateCheckStalenessTest` (pure, no Spring).
4. `EventDateCheckDto`; the `PredictionStatusResponse` component and back-compat constructor.
5. `DateCheckRepository` finder; `DateCheckService` methods (a)–(d).
6. `PredictionRequestService.status` wiring on all four return sites; update the `PredictionRequestServiceTest` constructor and add its branch tests.
7. `EventPatchRequest` fields and constructors; `EventDto.subGenre`; `EventService` constructor, `applyPatch` subGenre, and `createDraft` link. The entry paths into the linked state are `EventService.createDraft` (sets `events.date_check_id`) and `DateCheckService.create` with `eventId` (already org and soft-delete checked; sets `date_check.event_id`). Both enforce the gate. `EventService.patch` deliberately ignores `dateCheckId`. The only code that changes the event's date (`setStartsAt`) or timezone is `EventService.applyPatch`, per grep over `src/main/java`, and stale is derived when the prediction is read, so all of them are covered without a hook.
8. Javadocs listed in Affected files.
9. `DateCheckEventLinkTest` and `DateCheckEventLinkGateOffTest`.
10. Run the full check command. Read the diff for comments containing ticket or milestone ids.

## Verification commands
- Targeted: `cd /Users/ivan/imin/imin-api/.claude/worktrees/m1-9-date-check-event-link && docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=DateCheckStalenessTest,DateCheckEventLinkTest,DateCheckEventLinkGateOffTest,PredictionRequestServiceTest,DateCheckAccessTest,DateCheckControllerTest,PredictionControllerTest,EventServiceTest,EventControllerTest`. Use a comma list and judge it by its own `Tests run:` line.
- Gate: `docker info >/dev/null && /Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`

## Test impact
Branch map:
- `DateCheckStaleness`: night found / not found / no start / unranked fallback / rollover / zone.
- `currentForEvent`: gate closed / no link / only `events.date_check_id` / only `date_check.event_id` / both, newer wins / foreign id ignored / no dates.
- `createDraft`: no id / own check / check already has `event_id` / foreign / unknown / gate closed.
- `patch`: ignores `dateCheckId`.
- `subGenre`: known / unknown / blank / too long.
- `status()`: 4 return sites.
- `isEnabled`: closed / open.

New `src/test/java/com/imin/iminapi/predictor/DateCheckStalenessTest.java` (unit, 6 tests):

| Test | Setup forcing the branch | Asserts |
|---|---|---|
| `eventNightAmongDatesIsCurrent` | rows 2026-10-23, 2026-10-30; start 2026-10-30T20:00Z, Europe/Paris | row = 30 Oct, stale=false |
| `missingNightFallsBackToRankOne` | rows 23 Oct (rank 2), 30 Oct (rank 1); start 6 Nov | row = 30 Oct, stale=true |
| `unrankedRowsFallBackToEarliest` | both rank null | row = earliest, stale=true |
| `noStartIsStale` | startsAt null | stale=true, rank-1 row |
| `startBeforeSixLocalBelongsToPreviousNight` | row 23 Oct only; start 2026-10-23T23:30Z (01:30 Sat 24 Oct, CEST UTC+2) | stale=false |
| `zoneIsTheChecksZone` | row 24 Oct only; start 2026-10-24T04:30Z (06:30 Paris → night 24; 04:30 UTC would be night 23) | stale=false |

The offsets rest on EU summer time ending on the last Sunday of October (Directive 2000/84/EC), which is 25 Oct 2026, so CEST (UTC+2) still applies on 23–24 Oct. 24 Oct 2026 is a Saturday.

New `src/test/java/com/imin/iminapi/predictor/DateCheckEventLinkTest.java`. Setup: `@SpringBootTest @AutoConfigureMockMvc`, `@TestPropertySource(properties={"imin.predictor.date-check.enabled=true","imin.predictor.date-check.all-orgs=true"})`, `@TestBean Clock` fixed at 2026-10-01T10:00Z as in `DateCheckControllerTest`, and `TestRateLimitConfig`. It never mutates `DateCheckProperties`. 14 tests:

| Test | Asserts |
|---|---|
| `createWithDateCheckLinks` | POST `/api/v1/events` with own `dateCheckId` → event row `date_check_id` = check; check `event_id` = new event |
| `createKeepsAnExistingCheckEventId` | check with `event_id`=A; create B from it → check `event_id` still A, B linked |
| `foreignDateCheckIs404` | other org's check → 404 `NOT_FOUND`, event count unchanged |
| `unknownDateCheckIs404` | random UUID → 404, no event |
| `patchIgnoresDateCheckId` | PATCH with `dateCheckId` → row `date_check_id` stays null |
| `knownSubGenreIsStoredAndReturned` | PATCH `subGenre` from bank → row plus `$.subGenre` |
| `unknownSubGenreIs400` | 400, `$.error.fields.subGenre` = `unknown` |
| `blankSubGenreClears` | "  " → row null, `$.subGenre` absent |
| `predictionStatusIncludesDateCheck` | event from check on night D → GET prediction: `status=none`, `dateCheck.id`, `forDate`=D, `stale=false`, `result.date`=D, `checkedAt` present |
| `editingDateMarksStale` | PATCH startsAt to a night not checked → `stale=true`, `forDate` = rank-1 date |
| `checkAgainWithEventIdBecomesCurrent` | then POST date-check `{eventId, dates:[new night]}` → `dateCheck.id` = new check, `stale=false` |
| `checkWithEventIdLinksAnUnlinkedEvent` | event without `dateCheckId`; POST check with `eventId` → `dateCheck` present |
| `foreignCheckOnEventIsIgnored` | repo sets `events.date_check_id` to another org's check → `$.dateCheck` absent |
| `openApiPublishesEventDateCheck` | `/v3/api-docs`: `components.schemas.EventDateCheckDto.properties.stale`, `PredictionStatusResponse.properties.dateCheck`, `EventPatchRequest.properties.dateCheckId`, `EventDto.properties.subGenre` exist |

`noLinkOmitsDateCheck` and `checkWithoutDatesOmitsDateCheck` would make 16. Add them as rows 15–16: a plain event → `$.dateCheck` absent; a check row seeded without date rows → absent.

New `src/test/java/com/imin/iminapi/predictor/DateCheckEventLinkGateOffTest.java` (default properties, gate closed, 3 tests):
- `gateOffOmitsDateCheck`: event and check linked via repositories → `$.dateCheck` absent.
- `gateOffCreateWithDateCheckIdIs404`: same 404 envelope, no event row.
- `gateOffSubGenreStillAccepted`: pins the ungated decision.

Modified `src/test/java/com/imin/iminapi/predictor/PredictionRequestServiceTest.java`: pass `mock(DateCheckService)` returning `Optional.of(dto)`. Add `pendingCarriesDateCheck`, `readyCarriesDateCheck`, `unparseableCarriesDateCheck`. The none branch is covered in `DateCheckEventLinkTest`.

Modified `src/test/java/com/imin/iminapi/predictor/config/DateCheckAccessTest.java`: add `isEnabledFalseWhenClosed` and `isEnabledTrueForBetaOrg`. Both build their own `DateCheckProperties`.

Existing `EventServiceTest`, `EventSlugConflictTest`, `EventServiceAuditIntegrationTest` and `DateCheckControllerTest` still compile through the back-compat constructors. No assertion changes.

## Live-test
After Railway deploys:
- `curl -s https://imin-api-production.up.railway.app/v3/api-docs.yaml | grep -n 'EventDateCheckDto'` shows the schema.
- `curl -s -o /dev/null -w '%{http_code}' https://imin-api-production.up.railway.app/api/v1/events/00000000-0000-0000-0000-000000000000/prediction` returns 401.
- With the gate closed in prod, an organizer's GET prediction must show no `dateCheck` key.
- Flag-on flow (create from check → PATCH date → stale → Check again → current) runs locally with `IMIN_PREDICTOR_DATE_CHECK_ENABLED=true` and `ALL_ORGS=true` against docker Postgres, unless a prod beta org exists (open question).

## Contract impact
All `/api/v1` changes add fields only:
- `PredictionStatusResponse.dateCheck` (new schema `EventDateCheckDto`, reusing `DateCheckDateDto`).
- `EventPatchRequest.subGenre`, `EventPatchRequest.dateCheckId`.
- `EventDto.subGenre`.

Prod OpenAPI marker for `/ship-imin`: `EventDateCheckDto` in `/v3/api-docs.yaml`.

`/api/v1/public` is untouched (`EventDto` is used only by `EventController` and dashboard), so `PUBLIC_PAGE_API.md` needs no change.

Webapp follow-up (separate card, api-only here): run `npm run api:sync`, then in `src/shared/api/types.ts` add optional `dateCheck?` to `PredictionEnvelope`, `subGenre?` to `Event` and the patch type, and `dateCheckId?` to the create body. The `DateCheckDateDto` TS type does not exist yet (M3). Webapp `api:check` fails against prod after this deploys until that sync lands.

## i18n impact
None. The API emits structured fields and template keys only, and no organizer-facing string is added.

## Blast radius
- `EventService`: every organizer event create and patch, including autosave. It only adds a nullable `subGenre` branch and a create-only `dateCheckId` branch. When both are absent the behaviour is unchanged, and the existing Event tests pin that.
- `EventPatchRequest`: 19 constructor call sites in main and test. The 17- and 18-arg constructors keep them all compiling with the new fields null.
- `EventDto`: organizer list, detail and dashboard. It adds `subGenre` (NON_NULL). No public surface.
- `PredictionStatusResponse` / `PredictionRequestService`: `GET /events/{id}/prediction`. It adds `dateCheck`, absent while the gate is closed. The constructor change touches `PredictionRequestServiceTest` (the only construction site).
- `DateCheckService` / `DateCheckRepository` / `DateCheckAccess`: the date-check endpoints get a new finder and a new predicate method. `requireEnabled` behaviour is unchanged.
- `date_check.event_id` stamping changes which own event `InternalEvaluator` excludes when a check is re-scored (`PATCH .../assumptions`), which is the intended effect.
- Not touched: money, auth, Stripe, Flyway, shared modules, and public endpoints.

## Risks
- The "newest linked check wins" rule means a newer check made with `eventId` for alternative dates marks the event stale even when an older check covered its night. This is acceptable because "Check again" always sends the current night.
- Staleness tracks the date only. A venue city or country change after the check is not detected, which could be a follow-up.
- When async research ships and a newer check has no date rows yet, `currentForEvent` returns empty rather than the previous check. That card must revisit this.
- Two Spring contexts are added (gate-on properties plus fixed clock; the gate-off one shares the default context). Expect slower runs, not failures.
- Existing `DateCheckControllerTest` mutates the shared `DateCheckProperties` bean, which breaks an engineering rule. The new tests don't. Fixing the old ones is a separate card.
- File count: 12 main files (2 new) and 5 test files (3 new). This is at the ~15 threshold but is one concern, so no split.

## Definition of done
- All steps done, and the full check command is green in the worktree (baseline recorded first).
- Every test listed under Test impact exists and passes.
- `/v3/api-docs` shows `EventDateCheckDto` locally (test `openApiPublishesEventDateCheck`).
- Javadocs updated where behaviour moved. No comment references a ticket or milestone.
- The webapp follow-up card is opened by the main session.

## Live-test evidence
(filled by /live-test)

## Review rounds
(filled by review)

## Decisions (main session, 2026-09-30)
- Webapp follow-up is a separate card (api:sync + optional fields); this task is api-only.
- Night not among checked dates → return rank-1 date (else earliest) as forDate/result with stale=true — accepted.
- `id` of the date check included in `dateCheck` — accepted.
- `subGenre` accepted while the gate is closed, validated against every bank sub-genre — accepted.
- Prod gate is OFF (no PREDICTOR_DATE_CHECK_* vars on Railway); live test is OpenAPI marker + flag-off check; flag-on flow runs locally.
- Existing DateCheckControllerTest mutating the shared DateCheckProperties bean is a separate card — don't fix here; new tests must not mutate beans.
