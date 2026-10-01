# Stop out-of-date full-entity Event saves from undoing the door, survey and geocode writes
event-switch-revert · Subagent · Notion: n/a

## Goal and scope
Some `events` columns are written only by a targeted JPQL UPDATE, but the entity still maps them as updatable. `Event` has no `@Version` and no `@DynamicUpdate`. So any Event save made from a copy loaded before the targeted write puts the old value back: Hibernate's UPDATE includes every updatable column, whether the save is a merge or a dirty-check flush.

The worst case is turning the door QR or survey page off: an out-of-date save turns it back on, and sign-ups or answers are collected after the organizer closed the page. In the other direction, a printed QR goes dead because the token is reverted to null.

The fix mirrors `radar_muted` (6d05ec4d, `Event.java:203-205`) and `deleted_at` (`Event.java:151-153`): mark the bulk-only columns `updatable = false`.

In scope:
- the `Event.java` mapping
- one-line repository Javadoc updates
- api `CLAUDE.md` notes
- tests

Out of scope:
- `status` and `updated_at`, which the entity must keep writing (see Risks)
- any `@Version` or `@DynamicUpdate` change
- endpoint and contract changes

### Every Event column written by a targeted UPDATE (10 rows)
| # | Column | Bulk writer (`EventRepository.java`) | Entity-path writers in `src/main` | Decision |
|---|---|---|---|---|
| 1 | `venue_latitude` | `updateVenueCoordinates` :303-313, called from `VenueGeocodingListener.java:88` | none (no `setVenueLatitude` outside `Event.java`; patch never touches coordinates) | `updatable = false` |
| 2 | `venue_longitude` | same | none | `updatable = false` |
| 3 | `door_optin_enabled` | `updateDoorOptin` :315-326, called from `DoorOptInService.java:120` | `DoorOptInService.java:121`: setter on an entity already detached by `clearAutomatically`, response only | `updatable = false` |
| 4 | `door_optin_token` | same | `DoorOptInService.java:122`, same as above | `updatable = false` |
| 5 | `survey_enabled` | `updateSurvey` :328-339, called from `SurveyService.java:127` | `SurveyService.java:128`, detached, response only | `updatable = false` |
| 6 | `survey_token` | same | `SurveyService.java:129`, same | `updatable = false` |
| 7 | `radar_muted` | `updateRadarMuted` :341-345 | none | already `updatable = false` (`Event.java:204`); no change |
| 8 | `deleted_at` | `softDeleteNeverPublishedDraft` :351-365 | none | already `updatable = false` (`Event.java:152`); no change |
| 9 | `updated_at` | `softDeleteNeverPublishedDraft`, `markLivePast` :404-414, `lockActiveForWrite` :371-380 (no-op self-assignment) | `@PreUpdate` `Event.java:217-224`, plus EventService :260/:333/:379, TicketTierService :271, PromoCodeService :168 (it is the PATCH ETag) | stays updatable |
| 10 | `status` | `markLivePast` :404-414 | `EventService.publish` :331, `unpublish` :378 | stays updatable (residual risk, see Risks) |

These are all the targeted UPDATEs on `events` in `src/main`. Grepping `UPDATE Event e` and `update events` across `src/main/java` finds only the 7 queries above in `EventRepository`. There are no JdbcTemplate or native-SQL updates of `events`.

### Every full-entity Event save path (8 rows)
| # | Site | How the Event is loaded | Lock before load? |
|---|---|---|---|
| 1 | `EventService.createDraft` :207 | new entity (INSERT) | n/a |
| 2 | `EventService.patch` :262 | `loadOwnedForWrite` :408-411 | yes, `lockActiveForWrite` |
| 3 | `EventService.publish` :334 | `loadOwnedForWrite` | yes |
| 4 | `EventService.unpublish` :380 | `loadOwned` :413-417 | no |
| 5 | `TicketTierService.bumpEventUpdatedAt` :269-273 (from create :150, patch :172, delete :207) | `findActive` :253 | no |
| 6 | `PromoCodeService.bumpEventUpdatedAt` :167-170 (from create :100, patch :132, delete :148) | `findActive` :157 | no |
| 7 | `MediaUploadService.upload` :128 | `findActive` :191 | no |
| 8 | `MediaUploadService.delete` :170 | `findActive` :191 | no |

No other code path dirty-checks an Event. Grepping every Event-only setter in `src/main` finds only these services, and each call is followed by one of the saves above. No scheduler or reconciler saves an Event: `EventStatusSweeper` uses `markLivePast`, and `RadarJob`, `ReforecastJob` and the payout sweepers only read. `EventRepository` is `@RepositoryRestResource(exported = false)` (:20), so there is no Spring Data REST write path.

Rows 4-8 load without a lock. Under Postgres READ COMMITTED, a door or survey switch that commits between their read and their flush is overwritten by the row they write.

## Repos in ship order
1. `api` (imin-api, base `master`). There are no frontend changes.

## Affected files (per repo)
api (`/Users/ivan/imin/imin-api/.claude/worktrees/event-switch-revert`):
- `src/main/java/com/imin/iminapi/model/Event.java`:
  - add `updatable = false` to `venueLatitude` (:104) and `venueLongitude` (:107)
  - add `updatable = false` to `doorOptinEnabled` (:180) and `doorOptinToken` (:184)
  - add `updatable = false` to `surveyEnabled` (:188) and `surveyToken` (:192)
  - add a one-line comment on each pair: "Only the bulk update writes it, so a full-entity save never reverts it."
- `src/main/java/com/imin/iminapi/repository/EventRepository.java`: Javadoc only. On :315 and :328, append "the columns' only writer" to the existing line. On :280-302, add one sentence noting the columns are `updatable = false`. No query changes.
- `CLAUDE.md` (repo): docs only.
  - Door QR line (:67) and survey line (:68): add "the switch and token are written only by `EventRepository.updateDoorOptin` / `updateSurvey`, `updatable = false` on the entity, so a full-entity save cannot revert them".
  - Geocoding line (:47): add "`venue_latitude/longitude` are `updatable = false`".
- `src/test/java/com/imin/iminapi/audienceplan/service/SurveyServiceTest.java`: 3 new tests (see Test impact).
- `src/test/java/com/imin/iminapi/audienceplan/service/DoorOptInServiceTest.java`: 3 new tests.
- `src/test/java/com/imin/iminapi/service/event/VenueGeocodingLostUpdateTest.java`: 3 new tests.

Read and left unchanged:
- `SurveyService.java` and `DoorOptInService.java`. Their setters at :128-129 and :121-122 act on an entity detached by `clearAutomatically` and only build the response. With `updatable = false` they could not write even on a managed entity.
- `VenueGeocodingListener.java`. It already writes through the bulk update.
- `EventService.java`, `TicketTierService.java`, `PromoCodeService.java`, `MediaUploadService.java`. None of them sets the six columns, and the mapping fix protects them without edits.

Total: 7 files.

## Ordered steps
1. **Baseline gate.** Run `docker info`, then the api gate once on the untouched worktree and record that it is green. A gate that is already red is its own card.
2. **Door and survey mapping.** In `Event.java`, set `@Column(name = "door_optin_enabled", nullable = false, updatable = false)`, `@Column(name = "door_optin_token", length = 32, updatable = false)`, `@Column(name = "survey_enabled", nullable = false, updatable = false)` and `@Column(name = "survey_token", length = 32, updatable = false)`.
   - JPA 3.2.0 (`~/.m2/.../jakarta.persistence-api-3.2.0-sources.jar`, `Column.java:88-97`): `boolean insertable() default true;` is "Whether the column is included in SQL INSERT statements", and `boolean updatable() default true;` is "Whether the column is included in SQL UPDATE statements". So inserts still carry the values.
   - Hibernate 7.2.7 bulk HQL ignores `updatable`. `updateRadarMuted` and `softDeleteNeverPublishedDraft` already prove this, and `RadarTimelineTest.fullEventSaveNeverRevertsMute` (:370-396) stays green with it.
3. **Venue coordinate mapping.** Set `@Column(name = "venue_latitude", updatable = false)` and `@Column(name = "venue_longitude", updatable = false)`.
   - V80's CHECK (`V80…sql:26-27`) is `(venue_latitude IS NULL AND venue_longitude IS NULL) OR (venue_latitude BETWEEN -90 AND 90 AND venue_longitude BETWEEN -180 AND 180)`. It is unaffected, because the pair is still written together by `updateVenueCoordinates`.
4. **Javadoc and CLAUDE.md** edits as listed under Affected files. Comments stay at 1-2 lines with no ticket or milestone ids.
5. **Tests** as listed under Test impact.
6. **Show each guard has teeth.** For each of the 6 attributes in turn, remove only that `updatable = false`, run its class (`./mvnw test -Dtest=<Class>`), confirm the stale-save test goes red, and restore it. Record the 6 results in Review rounds.
   - Removing only one coordinate attribute makes the out-of-date save write a half-null pair, which the V80 CHECK rejects. The test still fails, just through the constraint; record that.
7. **Full gate.** Run the full api gate and confirm no Testcontainers tests were skipped.

## Verification commands
From `/Users/ivan/imin/imin-api/.claude/worktrees/event-switch-revert`:
- `docker info`
- `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest=SurveyServiceTest,DoorOptInServiceTest,VenueGeocodingLostUpdateTest,RadarTimelineTest`
- `/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test` (the api check command). It is red if any Testcontainers test is skipped, even when Maven prints BUILD SUCCESS. Re-run it after any rebase.

## Test impact
What the change controls: whether Hibernate's UPDATE includes a column (excluded by the guard), whether the bulk JPQL still writes it (it must), and whether INSERT still includes it (it must). Each column group gets one test per branch.

| Test (new) | File | Setup that forces the branch | Asserts | Red without the guard? |
|---|---|---|---|---|
| `fullEventSaveLoadedBeforeEnable_neverRevertsSurveySwitch` | `SurveyServiceTest` | `fresh = event(orgId, LIVE)`; `stale = events.findById(fresh.getId())` (detached, the class is a non-transactional `@SpringBootTest`); `service.setEnabled(organizer, fresh.getId(), true)`; `stale.setName("Renamed")`; `events.save(stale)` | jdbc: `survey_enabled` true, `survey_token` equals the token in the response URL, `name` is "Renamed" (the save went through) | yes, for each of the 2 attributes |
| `saveThenEnable_writesSurveySwitch` | `SurveyServiceTest` | load, rename, `events.save`, then `setEnabled(true)` | jdbc: `survey_enabled` true, token matches `[A-Za-z0-9_-]{22}`, `name` is "Saved First" | no (it shows the bulk path still writes) |
| `insertCarriesSurveyValues` | `SurveyServiceTest` | `new Event` with `surveyEnabled = true` and `surveyToken = <22 chars>`, saved | jdbc: both values stored | no (it shows insertable is kept) |
| `fullEventSaveLoadedBeforeEnable_neverRevertsDoorSwitch` | `DoorOptInServiceTest` | same as the survey case, using `event(orgId, LIVE, true)` and `service.setEnabled` | jdbc: `door_optin_enabled` true, `door_optin_token` equals the response token, name changed | yes, for each of the 2 attributes |
| `saveThenEnable_writesDoorSwitch` | `DoorOptInServiceTest` | save, then enable | jdbc: both written | no |
| `insertCarriesDoorValues` | `DoorOptInServiceTest` | new Event with both set | jdbc: both stored | no |
| `staleSnapshotSavedAfterGeocode_keepsCoordinates` | `VenueGeocodingLostUpdateTest` | `stale = findById`; `em.detach(stale)`; `new VenueGeocodingListener(events, fixedPoint).geocodeAndStore(id)`; `stale.setName("Renamed")`; `events.save(stale)`; `em.flush()`; `em.clear()` | coordinates are 52.5111 and 13.4432, name changed | yes (removing one attribute fails via the V80 CHECK) |
| `saveThenGeocode_writesCoordinates` | `VenueGeocodingLostUpdateTest` | save a renamed entity, flush, clear, then geocode | coordinates written | no |
| `insertCarriesCoordinates` | `VenueGeocodingLostUpdateTest` | new Event with a coordinate pair, `save`, `flush`, `clear` | jdbc or `em` re-read returns the pair | no |

Notes on the tests:
- The stale-save tests go through the real beans: the services and the `@Modifying` repository proxy. The `events.save(stale)` merge is the same Hibernate UPDATE that paths 4-8 issue. A real concurrent race cannot be reproduced in a single thread.
- The insert tests matter because `PublicEventServiceTest.venue_carries_coordinates_when_the_event_has_them` reads back from the same persistence context (`@DataJpaTest`), so it does not prove the INSERT.

Existing tests that read these columns, checked by grep and left unedited:
- `SurveyServiceTest` and `DoorOptInServiceTest` read the columns only after `setEnabled` (the bulk path).
- `PublicEventServiceTest:664-679` only inserts.
- `VenueGeocodingLostUpdateTest:136-158` sets coordinates on the out-of-date copy but asserts only `deletedAt` and `sold`.
- `ReforecastServiceTest`, `VenueGeocodingTest` and `ApplePassContentTest` (:464) use a mocked `EventRepository`.
- `CatchmentServiceTest` uses an in-memory Event only.
- `RadarTimelineTest` is untouched.
- No test fixture or data file changes.

## Live-test
There is no endpoint or contract change, and the race cannot be reproduced on demand in prod. After `/ship`, an optional prod smoke on a test event in a beta org:
1. `PUT /api/v1/events/{id}/survey {enabled:true}` and `PUT /api/v1/events/{id}/door-optin {enabled:true}`.
2. Edit the event through a tier patch, a promo patch, a media upload and an event PATCH.
3. `GET` both settings: `enabled` is true and the URLs are unchanged.

The automated tests are the evidence for the race ordering.

## Contract impact
none. No DTO, path or schema changes; OpenAPI is unchanged; there is nothing to sync in the webapp or to add to `PUBLIC_PAGE_API.md`.

## i18n impact
none. No user-facing strings, so no copy ledger rows.

## Blast radius
- **Shared entity.** `Event` is read by every module, but only six column mappings change, and only on the UPDATE side.
  - Readers are unaffected: `PublicEventResponse:58`, `AppleWalletPassService:365/497`, `CatchmentService:32`, `ReforecastService:193`, and the door and survey public gates (`DoorOptInService:186-188`, `SurveyService:220`).
  - Writers: `VenueGeocodingListener:88`, `DoorOptInService:120`, `SurveyService:127`. All use bulk updates, which are unaffected. These files are under Affected files with "no edit".
- **Every save path that reaches these columns:** `EventService.createDraft` :207 (insert, unchanged), `patch` :262, `publish` :334, `unpublish` :380, `TicketTierService` :272, `PromoCodeService` :169 and `MediaUploadService` :128/:170. All are protected by the mapping, with no per-path edit needed. No scheduler, reconciler or admin endpoint saves an Event (see the save-path table).
- **Consent and legal.** The door and survey switches decide whether consent is collected. This change only stops an organizer's "off" from being silently undone. Money, auth, Stripe, Flyway and `/api/v1` are untouched.

## Risks
- **Residual `status` revert, out of scope.** `markLivePast` writes `status` in bulk, but `status` must stay updatable for publish and unpublish. An unlocked save (rows 4-8) racing the sweeper can turn PAST back into LIVE. Proposed follow-up: take `lockActiveForWrite` in `TicketTierService` :253, `PromoCodeService` :157, `MediaUploadService` :191 and `EventService.unpublish`.
  - `@DynamicUpdate` was considered and rejected. A merge copies every field of the out-of-date copy, so all of them count as dirty and it would not help.
- **Hidden entity writers in the future.** If someone later adds `setSurveyEnabled` before a save, the write is silently dropped. The mapping comment and the CLAUDE.md lines name the bulk update as the only writer.
- **Same-transaction view after a merge.** A merge that copies an out-of-date value into the managed instance leaves that value in memory for the rest of the transaction. No response built in those paths reads the six fields (`EventDto` does not expose them), so this is harmless.
- **Size.** 7 files, one concern; no split needed.

## Definition of done
- All 6 attributes are `updatable = false`, and the Javadoc and CLAUDE.md lines are updated.
- All 9 new tests are green, and each stale-save test was shown red with each attribute removed (6 recorded runs).
- The full api gate is green with Docker up and no skipped Testcontainers tests, re-run after any rebase.
- The follow-up card for the `status` revert is raised, or explicitly declined by Ivan.

## Live-test evidence
(filled during /do-task)

## Review rounds
### Implement: guard-removal runs (Step 6), 2026-10-01
Each run removed one `updatable = false` from `Event.java` (backup in `mktemp -d`, restored with `cp` + `touch`, `cmp` clean afterwards), then ran `./mvnw test -Dtest=<Class>` through test-serial.

| Attribute removed | Class | Result |
|---|---|---|
| `door_optin_enabled` | `DoorOptInServiceTest` (52) | red: 1 failure, `fullEventSaveLoadedBeforeEnable_neverRevertsDoorSwitch:555` (`door_optin_enabled` false) |
| `door_optin_token` | `DoorOptInServiceTest` (52) | red: 1 failure, same test `:556` (token reverted to null) |
| `survey_enabled` | `SurveyServiceTest` (66) | red: 1 failure, `fullEventSaveLoadedBeforeEnable_neverRevertsSurveySwitch:630` |
| `survey_token` | `SurveyServiceTest` (66) | red: 1 failure, same test `:631` |
| `venue_latitude` | `VenueGeocodingLostUpdateTest` (5) | red: 1 failure, `staleSnapshotSavedAfterGeocode_keepsCoordinates:172` (latitude null) |
| `venue_longitude` | `VenueGeocodingLostUpdateTest` (5) | red: 1 failure, same test `:173` (longitude null) |

The coordinate runs did NOT fail through the V80 CHECK, as Step 6 predicted. They failed on the assertion. `ck_events_venue_coords_valid` (`V80…sql:25-28`) evaluates to NULL for a half pair such as `(NULL, 13.4432)`, and a CHECK passes on NULL. So the constraint does not reject a lone coordinate, on H2 or on Postgres.

## Decisions (main session, 2026-10-01)
- Venue coordinates are included (same bug, same fix).
- The `status` revert (unlocked tier/promo/media/unpublish saves racing the sweeper) is a separate follow-up card, raised now.

