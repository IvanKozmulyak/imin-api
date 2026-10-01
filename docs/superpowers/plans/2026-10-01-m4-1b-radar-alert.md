# M4-1b: Radar alert + email (api)
m4-1b-radar-alert · Subagent · Notion: not provided

## Goal and scope

This is part 2 of the M4-1 split (`/Users/ivan/imin/docs/superpowers/plans/2026-10-01-m4-1-radar.md`, Risks → M4-1b, and its "Decisions (main session, 2026-10-01)"). It is api only. It builds on M4-1a, which is on origin/master `b9e6c6d8`: `RadarJob`, `DateCheckService.radarRerun`, V167 and current-check ranking.

What M4-1b delivers:
- **The alert rule.** A radar run alerts only when **both** of these hold (programme plan `2026-09-28-predictor-date-check.md` §M4-1 line 492; main-session decisions):
  - **The verdict worsens.** Severity is good(0) < adjust(1) < move(2). Any change to or from `not_enough_data`, and any unknown value, never alerts.
  - **A finding qualifies.** That means one of:
    - the run has a FOUND **risk** from a `structured` or `internal` source whose `questionId` had no FOUND risk row in the baseline;
    - the run has a FOUND **web** risk whose `questionId` plus normalised URL also appears as a FOUND web risk in the baseline (two consecutive runs).
  - The baseline is the check `radarRerun` copied from (`radar_prev_id`), scored on the night. A baseline that did not score the radar night (stale or no match) never alerts.
- **A daily dedupe per event, shared by both alert kinds.** It lives in the new table `predictor_alert` (V168). The first alert of the event's local day wins. The claim commits in its own `REQUIRES_NEW` transaction and a lost claim is skipped. It covers the existing band-crossing alert and the new radar alert.
- **`NotificationPreferences.predictorShift` gates both kinds** (`NotificationPreferences.java:19`). Today no server code reads it. A missing preferences row counts as "on", the entity default.
- **The radar alert is delivered twice:**
  - one in-app `Notification` row for `Event.createdBy`, written in that user's `locale`, kind `predictor.radar.worsened`, linked to `/events/{id}/predictor`;
  - one email to `organizations.contact_email`, the same recipient as `SalesMilestoneNotifier.java:128-135`. The template is `predictor-radar-alert` in EN/ES/FR/UK, html and txt. With no contact email the alert goes in-app only.
- **The copy is generic.** No finding or action labels are copied into the api (decision). It carries the verdict words, `Date risk {old}/10 → {new}/10` and the night formatted per locale.
- **`RadarJob` notifies after the per-event transaction has committed.** A failed notification never counts as a failed run.

Out of scope:
- The timeline endpoint, per-event mute, the `predictorShift` relabel and all webapp work (M4-1c).
- Web research. No web questions exist: `question-bank-v2.yaml` sources are 15 structured, 5 internal, 2 organizer and 1 input, and `DateCheckService.java:99-100` sets `RESEARCH_OFF`. The web branch of the rule is therefore tested with fixtures only.
- Turning the radar flag on. It stays `false`, and flipping it is a separate step with Ivan's OK.
- Push or other channels.
- Localising the band-crossing in-app copy. It stays English and unchanged.

Facts this plan rests on (worktree at origin/master `b9e6c6d8`):
- `ReforecastAlertNotifier.java:34-53` has a one-argument constructor and `notifyBandChange(Event, ProjectionBand, ProjectionBand, ReforecastResult)`. It writes in-app only, in English, with `userId = event.getCreatedBy()` (NOT NULL: `Event.java:139`, `V6__events.sql:31`).
- Every band alert goes through `ReforecastService.recompute` → `:156-159` → `notifyBandChange`. `recompute` has no `@Transactional`. Its callers are:
  - `ReforecastJob.java:48`;
  - `ReforecastTriggerService.java:74` (reached from the AFTER_COMMIT `@Async` listeners at `:94-151` and the direct hooks at `:158-170`);
  - `ReforecastRequestService.java:49` (manual);
  - `PredictorReactivityService.java:102,125` (AFTER_COMMIT `@Async`).
- `ReforecastServiceTest.java:67` mocks the notifier. `:437` and `:453` verify `notifyBandChange` calls on the mock only.
- `radarRerun` (`DateCheckService.java:350-380`) is `@Transactional` and is called through the Spring proxy from `RadarJob.pass()` (`RadarJob.java:66`), which is not transactional. So a value it returns has already been committed when `pass()` sees it.
  - Inside it, `match`/`scoresNight` (`:365-367`) say whether the baseline `prev` scored the night.
  - `run(c, in, List.of(night))` (`:378`) writes exactly one `date_check_date` row for the night, plus its findings (`:431-479`).
- The `date_check_finding` wire values are lowercase enum names (`DateCheckService.java:646-648`):
  - `kind` is risk or opportunity;
  - `status` is found, clear or not_checked;
  - `source_kind` is structured, internal, web, organizer or input;
  - `url` is VARCHAR(2048).
- `date_check_date.verdict` is good, adjust, move or not_enough_data (`DateResult.java:14-21`). `risk_score` is 0..10, capped (`Scorer.java:50`).
- A verdict can become `move` through a stop factor without the risk score rising (`Scorer.java:83-88`). So the copy never says "risk went up".
- Email helpers:
  - `EmailLocale.choose(locale, en, es, fr, uk)` (`EmailLocale.java:53-60`);
  - `EmailTemplateRenderer.render(name, locale, values)` throws on a missing placeholder and falls back to EN when a locale file is missing (`EmailTemplateRenderer.java:35-75`);
  - `EmailService.send(to, subject, html, text)` throws on failure (`EmailService.java:4-8`).
- Quiet hours (`QuietHours.java:10-27`, 22:00–09:00 org-local) are enforced only for marketing sends (`CampaignDispatcher`, `TimingArmScheduler`). Organizer transactional notifiers (`SalesMilestoneNotifier`, `OrganizerPayoutNotifier`) send at any hour and have no email kill switch. The radar flag `PREDICTOR_DATE_CHECK_RADAR_ENABLED` (default false) is the kill switch for the radar path.
- `notifications.title` is VARCHAR(255) NOT NULL (`V9__notifications.sql:4-5`). `events.name` is VARCHAR(255) (`V6__events.sql:4`), so a long event name can overflow the title, and the plan truncates.
- The webapp bell renders `title`, `body` and relative time, with the default Bell icon for unknown kinds (`imin-webapp` origin/main `9b66717`, `src/app/NotificationsBell.tsx:15-19,90-104`). A click calls `navigate(n.link)` (`:55-58`). The route `events/:id/:tab` exists (`src/app/router.tsx:99`), and `predictor` is a valid tab (`EventDetailPage.tsx:49-50`).
- The highest migration on origin/master is V167. No other api worktree holds V168.

## Repos in ship order

1. `api` (`imin-api`, base `master`), worktree `/Users/ivan/imin/imin-api/.claude/worktrees/m4-1b-radar-alert`.

No other repo changes. The webapp gets nothing in M4-1b: in-app rows render through the existing bell, and there are no contract changes.

## Affected files (per repo)

### api (`/Users/ivan/imin/imin-api/.claude/worktrees/m4-1b-radar-alert`)

| # | File | Change |
|---|---|---|
| 1 | `src/main/resources/db/migration/V168__predictor_alert.sql` (new; take the next free number above origin/master's max at execution time, V167 today) | Table `predictor_alert` (step 2) |
| 2 | `src/main/java/com/imin/iminapi/predictor/service/PredictorAlertStore.java` (new) | JDBC `claim(eventId, day, kind, dateCheckId)` in `REQUIRES_NEW`; constants `KIND_BAND`/`KIND_RADAR` and `CLAIM_SQL`/`WINNER_SQL` |
| 3 | `src/main/java/com/imin/iminapi/predictor/service/RadarAlertRule.java` (new) | Pure: records `Signal`, `Snapshot`, `Alert`; `shouldAlert`, `worsens`, `qualifies`, `normaliseUrl` |
| 4 | `src/main/java/com/imin/iminapi/predictor/service/ReforecastAlertNotifier.java` | New constructor dependencies. `notifyBandChange` gains the preference gate and band claim before its unchanged body. New `notifyRadarWorsened(RadarAlertRule.Alert)`. Copy helpers. Class javadoc rewritten: email for radar, one predictor alert per event per day, preference-gated |
| 5 | `src/main/java/com/imin/iminapi/predictor/service/DateCheckService.java` | `public record RadarRun(RadarOutcome outcome, RadarAlertRule.Alert alert)` plus `static RadarRun of(RadarOutcome)`. `radarRerun` returns `RadarRun` and builds the alert in-transaction when the baseline scored the night. Private `snapshot(DateCheckDate)`. Class javadoc radar paragraph mentions the alert |
| 6 | `src/main/java/com/imin/iminapi/predictor/service/RadarJob.java` | Inject `ReforecastAlertNotifier`. On `RAN` with a non-null alert, notify in a separate try/catch (WARN with throwable). `Result` unchanged. Javadoc names the alert |
| 7 | `src/main/resources/email-templates/predictor-radar-alert.html` (new) | EN html, structure copied from `payout-arrived.html` |
| 8 | `src/main/resources/email-templates/predictor-radar-alert.txt` (new) | EN txt |
| 9 | `src/main/resources/email-templates/predictor-radar-alert.es.html` (new) | ES html, `<html lang="es">` |
| 10 | `src/main/resources/email-templates/predictor-radar-alert.es.txt` (new) | ES txt |
| 11 | `src/main/resources/email-templates/predictor-radar-alert.fr.html` (new) | FR html, `<html lang="fr">` |
| 12 | `src/main/resources/email-templates/predictor-radar-alert.fr.txt` (new) | FR txt (plain UTF-8, no HTML entities) |
| 13 | `src/main/resources/email-templates/predictor-radar-alert.uk.html` (new) | UK html, `<html lang="uk">` |
| 14 | `src/main/resources/email-templates/predictor-radar-alert.uk.txt` (new) | UK txt |
| 15 | `CLAUDE.md` | Line 50 (radar entry): replace "No alert or email yet, so keep the flag off until the alert path ships." with the alert rule, the dedupe, the preference gate, the recipients, the template name and the `predictor_alert` table purpose (step 9) |
| 16 | `src/test/java/com/imin/iminapi/predictor/service/RadarAlertRuleTest.java` (new) | Pure rule branches, including 3 of the 6 spec tests |
| 17 | `src/test/java/com/imin/iminapi/predictor/service/ReforecastAlertNotifierTest.java` (new) | Notifier unit tests with mocks and the real `EmailTemplateRenderer` |
| 18 | `src/test/java/com/imin/iminapi/predictor/RadarAlertFlowTest.java` (new) | `@SpringBootTest` end to end: real store, mocked `RuleEngine`, recording email |
| 19 | `src/test/java/com/imin/iminapi/migration/DateCheckMigrationScenarios.java` | `predictor_alert` CHECK cases, unique key, claim SQL, cascade/SET NULL (inherited by `DateCheckMigrationH2Test` and `DateCheckMigrationPostgresTest`, which need no edit) |
| 20 | `src/test/java/com/imin/iminapi/email/OrganizerEmailLocaleVariantsTest.java` | Add `predictor-radar-alert` with its 7 placeholders to `TEMPLATES` |
| 21 | `src/test/java/com/imin/iminapi/predictor/service/RadarJobTest.java` | `job()` passes a mocked notifier. `thenReturn(RadarOutcome.X)` becomes `thenReturn(RadarRun.of(RadarOutcome.X))`. Two new tests |
| 22 | `src/test/java/com/imin/iminapi/predictor/RadarRerunTest.java` | Mechanical: each `service.radarRerun(x)` assertion becomes `service.radarRerun(x).outcome()` (`:249-620`). No behaviour assertion changes |
| 23 | `src/test/java/com/imin/iminapi/predictor/RadarRerunGateOffTest.java` | `:125` becomes `.outcome()` |

Rows 1-15 are main code, templates and docs (8 templates). Rows 16-23 are tests (8).

**Named under Blast radius, no change needed:**
- `ReforecastService.java`: the call at `:158` and its arguments are unchanged; the guard sits inside the callee.
- `ReforecastJob.java`, `ReforecastTriggerService.java`, `ReforecastRequestService.java`, `PredictorReactivityService.java`: they reach the band alert only through `recompute` → `notifyBandChange`, which now carries the guard.
- `ReforecastServiceTest.java`: it mocks the notifier (`:67`) and verifies call counts on the mock (`:437`, `:453`). The constructor change and the guard live in the real class only. A grep shows it reads no fixture or constant this plan changes.
- `NotificationPreferences.java` and `NotificationPreferencesRepository.java`: read only.
- `NotificationsBell.tsx` (webapp): renders any kind with the default icon.
- `application.yaml` and `.env.example`: no new config key.
- `DateCheckMigrationH2Test` and `DateCheckMigrationPostgresTest`: they inherit row 19.
- `NotificationPrefsServiceTest`: preference write paths are untouched.

## Ordered steps

TDD for each step: write the failing test, watch it fail for the right reason, then implement.

1. **Baseline gate.** Run `docker info`, then `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test` on the untouched worktree. Record the result, and treat skipped Testcontainers tests as red. A red baseline is its own card.

2. **Migration (rows 1 and 19).** Write the scenarios first. SQL, compatible with H2 and Postgres (V166 style):
   ```sql
   -- V168: at most one predictor alert (band crossing or radar) per event per event-local day; the first claim wins.
   CREATE TABLE predictor_alert (
       id UUID PRIMARY KEY,
       event_id UUID NOT NULL,
       alert_day DATE NOT NULL,
       kind VARCHAR(8) NOT NULL,
       date_check_id UUID,
       created_at TIMESTAMP WITH TIME ZONE NOT NULL,
       CONSTRAINT uq_predictor_alert_event_day UNIQUE (event_id, alert_day),
       CONSTRAINT fk_predictor_alert_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE,
       CONSTRAINT fk_predictor_alert_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE SET NULL,
       CONSTRAINT ck_predictor_alert_kind CHECK (kind IN ('band','radar')),
       CONSTRAINT ck_predictor_alert_band_shape CHECK (kind = 'radar' OR date_check_id IS NULL)
   );
   ```
   - `kind` is written only from the Java constants `KIND_BAND="band"` and `KIND_RADAR="radar"`, which match `ck_predictor_alert_kind CHECK (kind IN ('band','radar'))`. No config value reaches this column, so no startup loader is needed. A test pins the constants against the CHECK set.
   - Scenarios to add:
     - CheckCases `ck_predictor_alert_kind` (`kind='push'`) and `ck_predictor_alert_band_shape` (`kind='band'` with a `date_check_id`).
     - `predictorAlertUniquePerEventDay`: a second row for the same `(event_id, alert_day)` raises `DataIntegrityViolationException` containing `uq_predictor_alert_event_day`, while the next day is accepted.
     - `predictorAlertClaimSqlKeepsTheFirstRow`: run `PredictorAlertStore.CLAIM_SQL` twice with two ids for the same key, with no exception. `WINNER_SQL` returns the first id. Runs on H2 and Postgres.
     - `predictorAlertFollowsItsEventAndCheck`: deleting the event deletes the row; deleting the date check sets `date_check_id` null.
   - Helper: `predictorAlert(jdbc, overrides)` next to `radarCheck` (`DateCheckMigrationScenarios.java:463`).

3. **`RadarAlertRule` (rows 3 and 16).**
   ```java
   public final class RadarAlertRule {
       public record Signal(String questionId, String kind, String status, String sourceKind, String url) {}
       public record Snapshot(String verdict, int riskScore, List<Signal> findings) {
           public Snapshot { findings = findings == null ? List.of() : List.copyOf(findings); }
       }
       public record Alert(UUID eventId, UUID runCheckId, LocalDate night, String fromVerdict, String toVerdict,
                           int fromRisk, int toRisk) {}
       // Map.of rejects null keys, so worsens() null-checks before get().
       private static final Map<String, Integer> SEVERITY = Map.of("good", 0, "adjust", 1, "move", 2);

       public static boolean shouldAlert(Snapshot baseline, Snapshot run) { baseline != null && run != null && worsens(..) && qualifies(..) }
       static boolean worsens(String from, String to)            // both known and to > from
       static boolean qualifies(Snapshot baseline, Snapshot run)
       static String normaliseUrl(String raw)
   }
   ```
   - `qualifies` keeps only run signals with `status="found"` and `kind="risk"`. A signal counts when either:
     - `sourceKind` is `structured` or `internal`, and the baseline has no found risk signal with that `questionId` (from any source); or
     - `sourceKind` is `web`, `normaliseUrl(url)` is not null, and the baseline has a found web risk with the same `questionId` and the same normalised URL.
   - `normaliseUrl`:
     - trim, then parse with `java.net.URI`; a parse failure gives null;
     - require scheme `http`/`https` and a non-null host, otherwise null;
     - the key is lowercase host minus a leading `www.`, then `:port` if one is set, then the path without a trailing `/`, then `?query` if present;
     - the scheme and fragment are dropped.
   - Comments are 1-2 lines and name no ticket or milestone.

4. **`PredictorAlertStore` (row 2).**
   ```java
   @Component
   public class PredictorAlertStore {
       public static final String KIND_BAND = "band";
       public static final String KIND_RADAR = "radar";
       static final String CLAIM_SQL = "INSERT INTO predictor_alert (id, event_id, alert_day, kind, date_check_id, created_at)"
               + " VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";
       static final String WINNER_SQL = "SELECT id FROM predictor_alert WHERE event_id = ? AND alert_day = ?";

       /** Claims the event's alert day; own transaction so the claim survives the caller. True only for the first claim. */
       @Transactional(propagation = Propagation.REQUIRES_NEW)
       public boolean claim(UUID eventId, LocalDate day, String kind, UUID dateCheckId) {
           UUID id = UUID.randomUUID();
           jdbc.update(CLAIM_SQL, id, eventId, day, kind, dateCheckId, Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
           return id.equals(jdbc.queryForObject(WINNER_SQL, UUID.class, eventId, day));
       }
   }
   ```
   - It uses `ON CONFLICT DO NOTHING` plus a read-back, not a caught `DataIntegrityViolationException`. A caught JPA or JDBC violation would leave the transaction rollback-only, or abort the Postgres transaction.
   - The read-back does not depend on H2 and Postgres reporting the same update count for a skipped insert.
   - The same pattern already runs on H2 in `DateVerdictFeedbackStore.java:36-43` and `CampaignAiSuggestions.java:37-48`.
   - JDBC only, so nothing is exported through Spring Data REST.

5. **`DateCheckService.radarRerun` returns the alert (rows 5, 22, 23).**
   - Every early return becomes `RadarRun.of(<outcome>)`.
   - After `run(c, in, List.of(night))`:
     ```java
     RadarAlertRule.Alert alert = null;
     if (scoresNight) {   // a baseline that did not score the night never alerts
         DateCheckDate base = match.row();
         DateCheckDate now = dates.findByDateCheckIdOrderByCandidateDateAsc(c.getId()).get(0);
         RadarAlertRule.Snapshot b = snapshot(base), r = snapshot(now);
         if (RadarAlertRule.shouldAlert(b, r)) {
             alert = new RadarAlertRule.Alert(e.getId(), c.getId(), night, b.verdict(), r.verdict(), b.riskScore(), r.riskScore());
         }
     }
     return new RadarRun(RadarOutcome.RAN, alert);
     ```
   - `snapshot(row)` reads `findings.findByDateCheckDateIdIn(List.of(row.getId()))` and maps each to `Signal(questionId, kind, status, sourceKind, url)`.
   - These are reads only. The rows that `run` wrote in this transaction are flushed by the query, so the run's own writes are unchanged.
   - Update the edited tests (rows 22 and 23) in the same step and run them to confirm they are still green.

6. **`ReforecastAlertNotifier` (rows 4 and 17).**
   - Constructor: `NotificationRepository notifications, NotificationPreferencesRepository prefs, PredictorAlertStore alerts, EventRepository events, OrganizationRepository orgs, UserRepository users, EmailService email, EmailTemplateRenderer renderer, EmailProperties emailProps, Clock clock`.
   - `private boolean wants(Event e)`: `prefs.findById(e.getCreatedBy()).map(NotificationPreferences::isPredictorShift).orElse(true)`.
   - `LocalDate alertDay(Event e)`: `clock.instant().atZone(zone).toLocalDate()`, where the zone is `ZoneId.of(e.getTimezone())`, falling back to UTC when blank or invalid (same fallback as `ReforecastService.java:455-462`). Both kinds use this one function, so they share a calendar day.
   - `notifyBandChange` (signature unchanged):
     - if not `wants` → log info, return;
     - if `!alerts.claim(id, alertDay, KIND_BAND, null)` → log info "already alerted today", return;
     - then the existing body, byte-for-byte.
   - `notifyRadarWorsened(Alert a)`:
     1. `events.findById(a.eventId())`. If missing or `deletedAt != null`, log and return.
     2. Not `wants` → log and return. A lost claim (`!alerts.claim(id, alertDay, KIND_RADAR, a.runCheckId())`) → log and return.
     3. `locale = users.findById(createdBy).map(User::getLocale).orElse(null)`.
     4. In-app row: kind `predictor.radar.worsened`, `title = fitTitle(...)` (ledger row 1, at most 255 chars by shortening the event name with `…`), `body` (ledger row 2), link `/events/{id}/predictor`.
     5. Recipient: `orgs.findById(e.getOrgId())`, then `contactEmail`. If blank, log WARN "in-app only" and return.
     6. Render `predictor-radar-alert` with values `eventName, night, fromVerdict, toVerdict, fromRisk, toRisk, dashboardUrl` (`emailProps.getAppBaseUrl() + "/events/" + id + "/predictor"`). Then `email.send(to, subject, html, text)` inside try/catch: a failure logs WARN with the throwable, and the in-app row stays. Log success with `LogSafe.email(to)`.
   - Copy helpers (package-private static, so they can be unit-tested):
     - `verdictWord(String, String locale)`;
     - `night(LocalDate, String locale)`: patterns EN `EEEE d MMMM`, ES `EEEE d 'de' MMMM`, FR `EEEE d MMMM`, UK `EEEE, d MMMM` (format-context month gives the genitive), with the first letter upper-cased under that locale;
     - `eventName(Event, locale)`, with a fallback per ledger row 14.

7. **`RadarJob` (rows 6 and 21).**
   ```java
   DateCheckService.RadarRun run = service.radarRerun(id);   // committed when it returns
   if (run.outcome() == DateCheckService.RadarOutcome.RAN) { ran++; if (run.alert() != null) alert(run.alert()); }
   else skipped++;
   ...
   private void alert(RadarAlertRule.Alert a) {
       try { notifier.notifyRadarWorsened(a); }
       catch (Exception ex) { log.warn("RadarJob: alert failed for event {}", a.eventId(), ex); }
   }
   ```
   The alert call sits outside the try that counts `failed`, so a committed run is never reported as failed.

8. **Templates (rows 7-14 and 20).**
   - Copy the structure of `payout-arrived{,.es,.fr,.uk}.html` and `.txt`: kicker, h1, label line, one boxed block with the two change lines, the heads-up paragraph, the button, the footer reason and the footer tag.
   - Remove the payout-only "GOOD TO KNOW" list.
   - Every string comes from the copy ledger. Placeholders are exactly `{{eventName}} {{night}} {{fromVerdict}} {{toVerdict}} {{fromRisk}} {{toRisk}} {{dashboardUrl}}`.
   - The txt files are plain UTF-8 with no HTML entities (`payout-arrived.fr.txt` has `&Eacute;`; do not copy that).
   - Add the template to `OrganizerEmailLocaleVariantsTest.TEMPLATES` with sample values for those 7 placeholders.

9. **Docs (row 15).** In `CLAUDE.md` line 50, replace the last sentence with a short description of:
   - the alert rule (worsens and qualifies, NED excluded, stale baseline excluded);
   - `predictor_alert` (V168, one row per event per event-local day, first claim wins across `band`/`radar`, claim in `REQUIRES_NEW`);
   - the `predictorShift` gate on both kinds;
   - delivery: in-app in the creator's locale plus `predictor-radar-alert` to `organizations.contact_email`, in-app only when blank;
   - no quiet hours, same as other organizer notices;
   - "keep the flag off until the alert has been live-tested".

   Update the class and method javadocs named in rows 4-6 in the same edit.

10. **Integration tests (row 18), then guard proofs.** Write the tests as listed under Test impact. For each guard in Definition of done, remove its line once and confirm its test goes red.

11. **Full gate.** Run `docker info`, then `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`. Read the surefire summary: skipped Testcontainers tests are red. Re-run after any rebase. If the migration is renumbered, run `./mvnw clean` first.

## Verification commands

api, in `/Users/ivan/imin/imin-api/.claude/worktrees/m4-1b-radar-alert`:
- `docker info`. It must be up, or the Postgres migration scenarios skip, which counts as red.
- `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test`
- Targeted run, comma-separated. Judge only its own `Tests run:` line:
  `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=RadarAlertRuleTest,ReforecastAlertNotifierTest,RadarAlertFlowTest,RadarJobTest,RadarRerunTest,RadarRerunGateOffTest,OrganizerEmailLocaleVariantsTest,DateCheckMigrationH2Test,DateCheckMigrationPostgresTest,ReforecastServiceTest`

No webapp command: the webapp does not change.

## Test impact

Branch map of the changed logic:

| Logic | Branch | Test |
|---|---|---|
| `worsens` | good→adjust, adjust→move, good→move alert; same verdict and improvements do not | `RadarAlertRuleTest.alertsOnlyWhenVerdictWorsens` (parameterized over the 9 good/adjust/move pairs, each with a qualifying structured risk, so only the verdict decides) |
| | to or from not_enough_data (good→NED, NED→move, NED→NED) | `RadarAlertRuleTest.notEnoughDataNeverAlerts` |
| | null or unknown verdict | `RadarAlertRuleTest.unknownVerdictNeverAlerts` |
| `qualifies` structured/internal | new found structured risk | `RadarAlertRuleTest.structuredRiskAlertsImmediately` |
| | new found internal risk | `RadarAlertRuleTest.internalRiskAlertsImmediately` |
| | same questionId already found as a risk in the baseline | `RadarAlertRuleTest.riskAlreadyFoundDoesNotQualify` |
| | baseline found that questionId from another source | `RadarAlertRuleTest.questionFoundFromAnotherSourceDoesNotQualify` |
| | found opportunity, not risk | `RadarAlertRuleTest.newOpportunityDoesNotQualify` |
| | status clear / not_checked | `RadarAlertRuleTest.unfoundRowDoesNotQualify` |
| | organizer / input source | `RadarAlertRuleTest.softSourceDoesNotQualify` |
| | worsened verdict with no qualifying finding | `RadarAlertRuleTest.worseVerdictWithoutQualifyingFindingDoesNotAlert` |
| `qualifies` web | web risk only in the new run | `RadarAlertRuleTest.singleWebSignalDoesNotAlert` |
| | same questionId and normalised URL in both runs (`https://www.Example.com/a/` vs `http://example.com/a#x`) | `RadarAlertRuleTest.repeatedWebSignalAlerts` |
| | same questionId, different URL; or same URL, different questionId | `RadarAlertRuleTest.webSignalMustMatchQuestionAndUrl` |
| | null or unparseable URL in both | `RadarAlertRuleTest.webSignalWithoutUrlNeverQualifies` |
| `shouldAlert` | baseline null | `RadarAlertRuleTest.missingBaselineNeverAlerts` |
| `normaliseUrl` | host case and `www.`, trailing slash, fragment, scheme dropped; query and port kept; relative, non-http, blank and garbage give null | `RadarAlertRuleTest.normaliseUrl` (parameterized) |
| kind constants vs CHECK | `{KIND_BAND, KIND_RADAR}` equals the V168 CHECK set {band, radar} | `RadarAlertRuleTest.alertKindsMatchSchemaCheck` |
| `radarRerun` alert | baseline did not score the night (stale), run worsens with a structured risk → RAN, alert null, no notification, no email, no `predictor_alert` row | `RadarAlertFlowTest.staleBaselineNeverAlerts` |
| | baseline scored the night, worsens and qualifies → alert values | `RadarAlertFlowTest.structuredRiskAlertsImmediately` (end to end, below) |
| `RadarJob` | RAN with alert → notifier called once with the alert | `RadarJobTest.ranWithAlertNotifies` |
| | notifier throws → pass continues, counts unchanged (`Result(2, 2, 0, 0)`), WARN with throwable | `RadarJobTest.notifierFailureDoesNotStopThePass` |
| | RAN without alert, or NOT_DUE → notifier never called | covered by the existing `cleanPassLogsInfo` / `duplicateRunCountsAsSkipped` plus `verifyNoInteractions(notifier)` added to `oneFailureDoesNotStopTheRest` |
| notifier, radar | creator locale en/es/fr/uk/null/`de` → in-app title and body, email subject and `<html lang>` | `ReforecastAlertNotifierTest.emailLocaleFollowsOrganizer` (parameterized; asserts exact strings from the ledger) |
| | preference off → no claim, no in-app, no email (both kinds) | `ReforecastAlertNotifierTest.optedOutGetsNothing` (parameterized band/radar) |
| | no preferences row → treated as on | `ReforecastAlertNotifierTest.missingPrefsRowMeansOn` |
| | claim lost → nothing written (both kinds) | `ReforecastAlertNotifierTest.lostClaimSendsNothing` (parameterized band/radar) |
| | blank / null contact email → in-app only, WARN | `ReforecastAlertNotifierTest.noContactEmailSendsInAppOnly` |
| | email send throws → in-app row kept, WARN, no rethrow | `ReforecastAlertNotifierTest.emailFailureKeepsInAppRow` |
| | event missing or soft-deleted → nothing | `ReforecastAlertNotifierTest.goneEventSendsNothing` |
| | 255-char event name → title ≤ 255 chars, ends with ` (Good → Move)` | `ReforecastAlertNotifierTest.titleFitsTheColumn` |
| | blank event name → fallback per locale | `ReforecastAlertNotifierTest.blankEventNameUsesFallback` |
| | link and dashboardUrl point at the Predictor tab | asserted in `emailLocaleFollowsOrganizer` (`/events/{id}/predictor`, `https://dashboard.imin.wtf/events/{id}/predictor` in html and txt) |
| `alertDay` | clock `2026-10-01T22:30:00Z`, event zone `Europe/Paris` → claims 2026-10-02 (Paris is UTC+2 until 25 Oct 2026: EU summer time ends the last Sunday of October, Directive 2000/84/EC) | `ReforecastAlertNotifierTest.alertDayIsTheEventsLocalDay` |
| | invalid zone → UTC day 2026-10-01 | `ReforecastAlertNotifierTest.badZoneFallsBackToUtc` |
| notifier, band | preference on, claim won → existing title/body/kind unchanged, claim made with `band` and null check id | `ReforecastAlertNotifierTest.bandAlertClaimsTheDayThenWritesAsBefore` |
| store | first claim true, second same day (other kind) false, next day true | `RadarAlertFlowTest.claimFirstWins` |
| | claim survives an outer rollback (called through the Spring bean inside a `TransactionTemplate` that sets rollback-only) | `RadarAlertFlowTest.claimSurvivesOuterRollback` |
| dedupe across kinds | band first, then radar same day → one notification (band), no email; radar first, then band → one notification (radar) plus one email | `RadarAlertFlowTest.noDoubleAlertWithBandCrossingSameDay` (both orderings, one test each: `…BandFirst`, `…RadarFirst`) |
| end to end | `radarJob.run()` on the Spring bean (shedlock expired first, as `RadarRerunTest.java:646`): radar row written, one `predictor.radar.worsened` notification with the EN ledger strings and the `/events/{id}/predictor` link, one email to the org contact, one `predictor_alert` row `kind='radar'` with `date_check_id` = the radar row | `RadarAlertFlowTest.structuredRiskAlertsImmediately` |
| after commit | the notifier sees no active transaction, and the radar row is readable from a fresh `JdbcTemplate` query inside the notifier call | `RadarAlertFlowTest.alertIsSentAfterTheRunCommits` (`@MockitoSpyBean ReforecastAlertNotifier` with `doAnswer` asserting `!TransactionSynchronizationManager.isActualTransactionActive()` before `callRealMethod`) |
| templates | 4 locales render with the 7 placeholders, differ from EN, declare `lang`; `de` falls back to EN | `OrganizerEmailLocaleVariantsTest` (row 20) |
| migration | CHECKs, unique key, claim SQL keeps the first row, cascade / SET NULL | `DateCheckMigrationScenarios` (row 19), on H2 and Postgres |

The six spec tests are `alertsOnlyWhenVerdictWorsens`, `singleWebSignalDoesNotAlert`, `repeatedWebSignalAlerts`, `structuredRiskAlertsImmediately` (in both rule and flow), `noDoubleAlertWithBandCrossingSameDay` and `emailLocaleFollowsOrganizer`. The extras are `optedOutGetsNothing`, `noContactEmailSendsInAppOnly`, `staleBaselineNeverAlerts` and `notEnoughDataNeverAlerts`.

`RadarAlertFlowTest` fixtures:
- `@SpringBootTest`, `@Import({TestRateLimitConfig.class, EmailServiceTestConfig.class})`, `@TestPropertySource` with date-check enabled, all-orgs and radar-enabled.
- A `@TestBean Clock` fixed at `2026-10-01T10:00:00Z`. `@MockitoBean RuleEngine` returns, for the night, findings built from the loaded `QuestionBank` by id:
  - `Finding.found(q("4.1"), RISK, 2, …)` = 4 points;
  - `Finding.found(q("4.3"), RISK, 3, …)` = min(4, 9) = 4 points;
  - risk 8 ≥ `move_min_risk` 7 → `move`. Coverage is 2 of 2 star ids = 1.0 (`question-bank-v2.yaml:6` thresholds `{adjust_min_risk: 3, move_min_risk: 7, min_coverage: 0.6, max_points_per_finding: 4}`).
- French org, so the check zone is Europe/Paris. Event `startsAt 2026-10-15T20:00:00Z`, timezone default `UTC`, night Thursday 15 October 2026 (weekday checked with JDK 17.0.20 `DateTimeFormatter`).
- Baseline: a raw organizer check dated `2026-09-20T10:00:00Z`, with one `date_check_date` for 15 Oct, verdict `good`, `risk_score` 0, no findings.
- `staleBaselineNeverAlerts` dates the baseline row 8 Oct instead.
- The EN expected strings (ledger rows 1-2):
  - title: `Radar Night: date check changed (Good → Move)`
  - body: `Thursday 15 October · Date risk 0/10 → 8/10. Open the Predictor tab to see what we found.`
- `@AfterEach` deletes `predictor_alert`, `notifications`, findings, dates, ledger, events, `date_check` (with `radar_prev_id` nulled first), users and orgs, and clears the recording mail.

Existing tests re-checked for changed fixtures or constants:
- `ReforecastServiceTest`: a mock of the notifier, only the `notifyBandChange` signature (unchanged). Stays green.
- `RadarRerunTest`, `RadarRerunGateOffTest`, `RadarJobTest`: they call or stub `radarRerun`, whose return type changes, so they are edited (rows 21-23).
- `RadarRerunTest.scheduledBeanRunWritesRadarRow`: now also reaches the notifier when an alert is built. It uses the real engine on identical inputs, and its assertion is only the radar row, which is unaffected either way.
- `OrganizerPayoutNotifierTest`, `DisputeNotifierTest`, `NotificationPrefsServiceTest`: they read none of the changed files.
- `IminApiApplicationTests`: the context gains `PredictorAlertStore` and new notifier dependencies, all existing beans.

## Live-test

`/live-test api` after `/ship-imin`. The radar flag stays **off**.
1. Railway boot is healthy. In `railway psql`, `select version from flyway_schema_history order by installed_rank desc limit 1` returns 168.
2. `\d predictor_alert` shows the 6 columns, `uq_predictor_alert_event_day`, the two FKs (CASCADE / SET NULL) and the two CHECKs.
3. The band path is live with no flag. Over the following days, `select kind, alert_day, count(*) from predictor_alert group by 1,2 having count(*) > 1` returns no rows. Every `notifications` row with `kind like 'predictor.trajectory.%'` created after the deploy has a matching `predictor_alert` row `kind='band'` for its event and day.
4. `select count(*) from date_check where origin='radar'` stays 0 (flag off).
5. **Only with Ivan's explicit OK** (an env save is a deploy), as a separate step: turn on `PREDICTOR_DATE_CHECK_RADAR_ENABLED=true` for an org whose event qualifies, and after 05:50 Europe/Amsterdam confirm:
   - the radar row exists;
   - if it alerted: one `predictor.radar.worsened` notification in the creator's locale, linked to `/events/{id}/predictor`;
   - the email arrived at the org contact address in that locale;
   - one `predictor_alert` row `kind='radar'`;
   - no band notification for that event that day.

## Contract impact

None:
- No endpoint, DTO or OpenAPI schema changes. `Notification` rows gain a new `kind` value `predictor.radar.worsened` inside the existing string field, and the webapp renders unknown kinds with the default icon (`NotificationsBell.tsx:15-19`).
- No OpenAPI marker. The webapp `api:check` stays clean.

## i18n impact

The new api strings are the radar in-app row and the email (html and txt), in EN/ES/FR/UK in the same task. No webapp, public or fan-app strings change. The copy follows UX deck §5.10 (`docs/superpowers/specs/2026-09-28-predictor-date-check-ux.md:291-294`), with these deviations:
- No `{finding}`/`{action}` (decision: generic).
- "date risk went up" becomes "date check changed": a worsened verdict does not guarantee a higher risk score (`Scorer.java:84`).
- The UK name of the tab is «Прогноз», as in the webapp (`copy.uk.ts:193`), not "Предиктор".
- "check the source" is dropped: the tab is not verified to show sources.

**Copy ledger.** "Built in" means `ReforecastAlertNotifier` (row 4) for Java strings, or `predictor-radar-alert{,.es,.fr,.uk}.{html,txt}` (rows 7-14) for template strings.

| # | String (EN / ES / FR / UK) | Field(s) behind it (`file:line`) | Meaning, scope, range | Renders next to it |
|---|---|---|---|---|
| 1 | Subject, in-app title, html `<title>` and h1: `{event}: date check changed ({from} → {to})` / `{event}: cambió la revisión de la fecha ({from} → {to})` / `{event} : la vérification de la date a changé ({from} → {to})` / `{event}: перевірка дати змінилась ({from} → {to})` | `{event}` = `Event.name` (`Event.java:25`); `{from}` = baseline `date_check_date.verdict` of the row that scored the night (`DateCheckStaleness.Match.row()`, `DateCheckService.java:365-367`); `{to}` = radar row `date_check_date.verdict` (`DateCheckService.java:448`) | Per event, per night, one radar run vs its baseline. `from`/`to` ∈ good/adjust/move only (NED never alerts), and `to` is strictly worse than `from`. "changed" claims only a verdict change, which the rule guarantees | In-app: bell icon, body (row 2), relative time (`NotificationsBell.tsx:90-104`). Email: inbox subject; h1 above the kicker/label |
| 2 | In-app body: `{night} · Date risk {old}/10 → {new}/10. Open the Predictor tab to see what we found.` / `{night} · Riesgo de la fecha {old}/10 → {new}/10. Abre la pestaña Predictor para ver lo que encontramos.` / `{night} · Risque de date {old}/10 → {new}/10. Ouvrez l’onglet Prédicteur pour voir ce que nous avons trouvé.` / `{night} · Ризик дати {old}/10 → {new}/10. Відкрийте вкладку «Прогноз», щоб побачити, що ми знайшли.` | `{night}` = `date_check.radar_night` (`radarCopy`, `DateCheckService.java:404`); `{old}`/`{new}` = baseline and radar `date_check_date.risk_score` (`DateCheckService.java:449`); "Date risk" copied from webapp `dateCheck.score.risk` (`copy.ts:3355`, `copy.es.ts:3215`, `copy.fr.ts:3211`, `copy.uk.ts:3245`); tab name from `tabsDetail.predictor` (`copy.ts:178`, `copy.es.ts:186`, `copy.fr.ts:175`, `copy.uk.ts:193`) | Risk: integer 0..10, capped sum of found risk points for that night only (`Scorer.java:50`). Not a percentage or a delta claim; can be equal or lower when the verdict moved by a stop factor. "what we found" = the current check's findings shown in the tab (radar row unless an organizer re-score overtook it) | Below the title in the bell; row click goes to `/events/{id}/predictor` (`router.tsx:99`) |
| 3 | Email kicker. html eyebrow: `DATE CHECK` / `REVISIÓN DE LA FECHA` / `VÉRIFICATION DE LA DATE` / `ПЕРЕВІРКА ДАТИ` (the header above it already shows imin.wtf); txt first line: `DATE CHECK · imin` / `REVISIÓN DE LA FECHA · imin` / `VÉRIFICATION DE LA DATE · imin` / `ПЕРЕВІРКА ДАТИ · imin` | Constant; names the feature (`dateCheck.tab.title`, UX deck §5.10 line 280) | Static label | Above the h1 |
| 4 | Email intro (also the html preheader): `We re-checked this night with the data we have today, and the verdict changed.` / `Volvimos a revisar la fecha de tu evento con los datos que tenemos hoy y el veredicto cambió.` / `Nous avons revérifié cette soirée avec les données dont nous disposons aujourd'hui, et le verdict a changé.` / `Ми повторно перевірили цей вечір за даними, які маємо сьогодні, і вердикт змінився.` | Radar run time = injected `Clock` (`radarCopy`, `DateCheckService.java:406-408`); same rule engine over current DB data (`run`, `:431-479`) | Per run; "today" = the day `RadarJob` ran (05:50 Amsterdam). No web research is claimed | Below the h1, above the label line |
| 5 | Label line: `{{eventName}} · {{night}}` (all locales) | As rows 1-2 | Event name and night of this run | Heads the boxed block holding rows 6-7 |
| 6 | Verdict line: `Verdict: {{fromVerdict}} → {{toVerdict}}` / `Veredicto: …` / `Verdict : …` / `Вердикт: …` | As row 1 `{from}`/`{to}` | As row 1 | In the boxed block, above row 7 |
| 7 | Risk line: `Date risk: {{fromRisk}}/10 → {{toRisk}}/10` / `Riesgo de la fecha: …` / `Risque de date : …` / `Ризик дати: …` | As row 2 `{old}`/`{new}` | As row 2 | In the boxed block, below row 6 |
| 8 | Heads-up: `This is a heads-up, not a decision: open the Predictor tab to see what we found before you act.` / `Es un aviso, no una decisión: abre la pestaña Predictor para ver lo que encontramos antes de actuar.` / `C'est une alerte, pas une décision : ouvrez l'onglet Prédicteur pour voir ce que nous avons trouvé avant d'agir.` / `Це попередження, а не рішення: відкрийте вкладку «Прогноз», щоб побачити, що ми знайшли, перш ніж діяти.` | Tab name as row 2 | Static guidance; points at the tab, which renders the current check's findings (`DateCheckSection.tsx:133-156`) | Below the boxed block, above the button |
| 9 | Button and txt link label: `Open the Predictor` / `Abrir el Predictor` / `Ouvrir le Prédicteur` / `Відкрити «Прогноз»` | `{{dashboardUrl}}` = `EmailProperties.appBaseUrl` (`EmailProperties.java:13`) + `/events/{id}/predictor` | Deep link to this event's Predictor tab | The CTA; txt: `<label>: {{dashboardUrl}}` |
| 10 | Footer reason: `These alerts follow the Predictor notification setting of the person who created this event in imin.` / `Estos avisos siguen la configuración de notificaciones del Predictor de la persona que creó este evento en imin.` / `Ces alertes suivent le réglage des notifications du Prédicteur de la personne qui a créé cet événement dans imin.` / `Ці сповіщення залежать від налаштувань сповіщень «Прогнозу» людини, яка створила цю подію в imin.` | `notification_preferences.predictor_shift` of `Event.createdBy` (`NotificationPreferences.java:19`), checked in `wants()` before any send | Per event creator; true or no row. The email goes to the org contact address, so the footer names the creator's setting, never the recipient's account; never sent when it is false | Footer, below the button |
| 11 | Footer tag: `imin.wtf · date check for {{eventName}}` / `imin.wtf · revisión de la fecha de {{eventName}}` / `imin.wtf · vérification de la date de {{eventName}}` / `imin.wtf · перевірка дати: {{eventName}}` | `Event.name` | As row 1 | Last line |
| 12 | Verdict words for `{from}`/`{to}`: good `Good`/`Buena`/`Bonne`/`Добра`; adjust `Adjust`/`Ajustar`/`À ajuster`/`Підлаштуйтесь`; move `Move`/`Cambiar`/`À déplacer`/`Перенесіть` | Copied from webapp `dateCheck.verdict` (`copy.ts:3327,3329,3331`; `copy.es.ts:3187,3189,3191`; `copy.fr.ts:3182,3184,3186`; `copy.uk.ts:3217,3219,3221`, origin/main `9b66717`); mapped from `date_check_date.verdict` | Exactly the three alertable values; `not_enough_data` is never rendered | Inside rows 1 and 6 |
| 13 | Night `{night}`: EN `Thursday 15 October`, ES `Jueves 15 de octubre`, FR `Jeudi 15 octobre`, UK `Четвер, 15 жовтня` (for 2026-10-15; JDK 17.0.20 `DateTimeFormatter` patterns of step 6, first letter upper-cased) | `date_check.radar_night` (`DateCheckService.java:404`) | The event's night resolved in the check's zone at run time (`NightDates.nightOf`, `:361`); a date, no time | Inside rows 2 and 5 |
| 14 | Event name fallback when blank: `Your event` / `Tu evento` / `Votre événement` / `Ваша подія` | `Event.name` blank (column default `''`, `V6__events.sql:4`; publish requires a name, `EventValidator.java:18`) | Fallback only; always at the start of a line or label, so nominative and capitalised forms read correctly | Replaces `{event}` in rows 1, 5, 11 |

14 ledger rows.

## Blast radius

- **Flyway V168 (new table `predictor_alert`).** Additive, and no existing table is altered. The FK to `events` cascades on delete (hard deletes of never-published drafts take their rows with them). The FK to `date_check` sets null on delete. Org deletion reaches it through `events`. A duplicate version number fails boot, so take the next free number at execution.
- **Band-crossing alert (live in prod, no flag).** Two changes ship with the deploy:
  1. Users with `predictor_shift = false` stop getting band notifications. That is the intent: the preference was stored but read nowhere.
  2. At most one predictor alert per event per event-local day. A second band crossing that day, such as a recovery, is no longer shown in-app.

  Every entry path reaches the guard because it sits in `notifyBandChange`, the only writer of `predictor.trajectory.*` rows, called only from `ReforecastService.recompute:158`. The paths are:
  - `ReforecastJob.java:48` (06:00 daily);
  - `ReforecastTriggerService.java:74` (tickets issued `:94`, sales milestone `:124`, campaign sent `:142`, campaign scheduled `:149`, tier transition `:158`, campaign send `:163`, campaign scheduled `:168`);
  - `ReforecastRequestService.java:49` (manual `POST`);
  - `PredictorReactivityService.java:102` (publish) and `:125` (edit).

  None runs inside a transaction (`recompute` has none, and each caller is a scheduler, an `@Async` AFTER_COMMIT listener or a plain service), so no outer rollback can drop the in-app row after a claim. The claim commits in `REQUIRES_NEW` anyway.
- **Radar alert entry paths.** The only writer of `origin='radar'` rows is `RadarJob.pass()` → `DateCheckService.radarRerun`, and the alert is wired there. Organizer checks (`create`) and `patchAssumptions` are deliberately not alert sources: the organizer made those themselves. The radar path is still behind `PREDICTOR_DATE_CHECK_RADAR_ENABLED` (default false) and `DateCheckAccess.isEnabled`.
- **Zero-candidate case.** No radar candidate means no alert, the correct outcome. The dedupe is per event, so nothing at org scope is missed.
- **Writers of `predictor_alert`.** Only `PredictorAlertStore.claim`, insert-only, no updates. So there is no lost-update ordering to test beyond the claim race, which `noDoubleAlertWithBandCrossingSameDay` tests in both orders.
- **`DateCheckService.radarRerun` signature.** `RadarOutcome` becomes `RadarRun`. Its callers are `RadarJob` and the tests (rows 21-23). No controller calls it. Behaviour inside the transaction only adds reads.
- **Email.**
  - It goes out through the existing `EmailService` (Resend) from the configured transactional sender. Recipient: `organizations.contact_email`, as for milestone, payout and dispute mails.
  - No buyer data is in the payload.
  - No quiet hours, consistent with other organizer notices (`QuietHours` applies to marketing only).
  - Volume is bounded at 1 per event per day, and in practice at most 4 runs per event (30/14/7/2).
- **`Notification` rows.** New kind `predictor.radar.worsened` (23 chars, below VARCHAR(64)). The title is truncated to 255.
- **Money, auth, Stripe, `/api/v1` contract, `/api/v1/public`, shared modules:** not touched. `EmailLocale`/`EmailTemplateRenderer` are only called, not changed.

## Risks

- **Size: 23 files, above the ~15 guideline.** 8 of them are mechanical template variants and 3 are mechanical test edits. Proposed split, if the gate prefers it:
  - (i) **M4-1b-1:** V168, `PredictorAlertStore`, and the preference gate plus band claim in `notifyBandChange`, with migration scenarios and notifier band tests. It ships a user-visible change on its own (preference respected, daily dedupe).
  - (ii) **M4-1b-2:** `RadarAlertRule`, `RadarRun`, `notifyRadarWorsened`, the `RadarJob` hook, templates, the locale test and the flow tests.

  Recommended: keep it as one task, because (ii) is useless without (i) and the radar flag is off anyway.
- **Claim-then-send.** If the in-app insert fails after the claim, that day's alert is lost. If the email fails, the in-app row is still delivered. This is accepted under "first-come wins". Releasing the claim on failure would add a cleanup path that can itself fail.
- **Band recoveries hidden on the same day.** The first claim of the event's local day wins across both kinds, and a suppressed alert is never re-sent: a band crossing after a radar alert, or a second crossing, is dropped for good, not delayed, and a band crossing between 00:00 and 05:50 (event-local) suppresses that day's radar alert. The bell then lags the Predictor tab, which still shows the live band. Accepted (Decisions, review round 1).
- **Email time.** It sends at about 05:50 Amsterdam, inside the marketing quiet window. See OPEN_QUESTIONS.
- **Alert day zone.** It uses `events.timezone` (default `UTC`), not the check's country zone. Both kinds share it, so the dedupe is consistent. Near midnight, the day can differ from the night's zone by one.
- **H2 vs Postgres.** `ON CONFLICT DO NOTHING` plus the read-back is exercised on both by `predictorAlertClaimSqlKeepsTheFirstRow`. No nullable String parameters reach `lower()`/`concat`.
- **Shared H2 across Spring contexts.** `RadarAlertFlowTest` gets its own context (`@MockitoBean RuleEngine`). Expire the `predictor_radar_daily` shedlock row before each `run()` and clean committed rows in `@AfterEach`.
- **The `predictorShift` settings label still reads "score changes by more than 10 points"** (webapp `copy.ts:2532`). That is wrong for both alert kinds and is fixed in M4-1c. The email footer avoids quoting it.

## Definition of done

- Every file in Affected files is changed as stated, and nothing else. `git diff --stat` lists exactly rows 1-23.
- `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test` is green with Docker up and no skipped Testcontainers tests, on a baseline that was green (step 1).
- Each guard was proved by removing its line once and seeing the named test go red:
  - the `wants()` check in `notifyBandChange` → `optedOutGetsNothing[band]`;
  - the `wants()` check in `notifyRadarWorsened` → `optedOutGetsNothing[radar]`;
  - the band claim check → `noDoubleAlertWithBandCrossingSameDayRadarFirst`;
  - the radar claim check → `noDoubleAlertWithBandCrossingSameDayBandFirst`;
  - `REQUIRES_NEW` on `claim` → `claimSurvivesOuterRollback`;
  - the `scoresNight` condition around the alert in `radarRerun` → `staleBaselineNeverAlerts`;
  - the NED exclusion (unknown-severity check) → `notEnoughDataNeverAlerts`;
  - the `to > from` comparison → `alertsOnlyWhenVerdictWorsens`;
  - the "not found in baseline" condition → `riskAlreadyFoundDoesNotQualify`;
  - the web "also in baseline" condition → `singleWebSignalDoesNotAlert`;
  - the blank-contact guard → `noContactEmailSendsInAppOnly`;
  - the separate try in `RadarJob.alert` → `notifierFailureDoesNotStopThePass`;
  - the email try/catch → `emailFailureKeepsInAppRow`.
- Every string in the diff's templates and `ReforecastAlertNotifier` matches a copy-ledger row in all four locales. `grep -n '&[A-Za-z]*;' src/main/resources/email-templates/predictor-radar-alert*.txt` returns nothing.
- `CLAUDE.md` line 50 and the javadocs of `ReforecastAlertNotifier`, `RadarJob` and `DateCheckService` are updated in the same diff.
- No comment names a ticket, milestone or review item. The commit message carries no AI attribution.
- `PREDICTOR_DATE_CHECK_RADAR_ENABLED` stays `false` in prod. Live-test steps 1-4 pass. Step 5 runs only with Ivan's OK, with evidence recorded below.

## Live-test evidence

(filled in by /live-test)

## Review rounds

(filled in by /do-task)

## Decisions (main session, 2026-10-01)
- Radar emails send right after the 05:50 run, like the other organizer notifications (payouts, sales milestones); the quiet window applies to marketing only.
- One task, not split: the alert rule, the shared dedupe and the email ship together because the radar flag must not be turned on with only part of the alert path.
- The radar flag stays off in prod after this ships; turning it on is a separate step after M4-1c.
- Review round 1: the dedupe is accepted as built. The first claim of the event's local day wins across band and radar, and a suppressed alert is never re-sent later; so a band crossing between 00:00 and 05:50 event-local time suppresses that day's radar alert.
- Review round 1: a failed preference lookup or claim in `notifyBandChange` is caught and logged at WARN with the throwable, so it can never fail `ReforecastService.recompute` or the manual POST.

