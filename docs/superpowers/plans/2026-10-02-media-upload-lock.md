# Media uploads never hold the event lock during storage I/O (api)
media-upload-lock · Subagent · Notion: (card id from main session)

## Goal and scope
Since aa66f351, `MediaUploadService.loadOwned` takes `EventRepository.lockActiveForWrite` (MediaUploadService.java:190-196, lock at :192). This happens inside a method-level `@Transactional` (:46, :53, :67 for upload; :144 for delete), and that same transaction then does R2 I/O:
- upload: `storage.put` at :132, which can be up to 50 MB of video (:214). The old object is then deleted at :135-140.
- delete: `storage.delete` at :155, *before* the URL is cleared and saved at :157-170.

While R2 is slow or hung, the event row stays locked. That blocks PATCH autosaves (EventService.java:409 `loadOwnedForWrite`) and the twice-daily sweep `EventStatusSweeper.sweep` → `markLivePast` (EventStatusSweeper.java:55-59; EventRepository.java:404-414). The sweep keeps holding row locks on other ended events while it waits.

Scope: change only `MediaUploadService`, plus one additive repository query and one javadoc fix. Every R2 call moves out of the locked transaction:
- **upload**: check ownership (scalar, unlocked) → rights gate → validate → probe and mark → `put` under a unique random key with no lock and no transaction → short locked transaction that reloads, applies and flushes → on failure inside that transaction, delete the new object (the cleanup is wrapped) → after commit, delete the replaced own object.
- **delete**: short locked transaction that clears the URL (and attestation/provenance) and saves → after commit, delete the own object.

Out of scope:
- `OrgMediaService`: brand logo, which takes no event lock (OrgMediaService.java:86-111).
- `PosterImageStorage`: `ai-posters/`, no event lock (PosterImageStorage.java:112-115).
- The Stripe call made under the PATCH lock (TicketTierService.java:247), listed under OPEN_QUESTIONS.
- Roles: event media has no role gate today (`loadOwned` checks only the org, :194). Gate principals are a MEMBER placeholder that the filter scopes (AuthPrincipal.java:38-40). Nothing about roles changes.

## Repos in ship order
1. `api` (imin-api, base `master`), worktree `/Users/ivan/imin/imin-api/.claude/worktrees/media-upload-lock`, branch `fix/media-upload-lock`.

## Affected files (per repo)
**imin-api**
| File | Change |
|---|---|
| `src/main/java/com/imin/iminapi/service/event/MediaUploadService.java` | Remove `@Transactional` from the three `upload` overloads and from `delete`. The constructor gains a `PlatformTransactionManager`, used to build a `TransactionTemplate writeTx` (REQUIRED). Add an slf4j `log`. Upload flow: ownership precheck → `put` → locked `writeTx`, with a `callbackDone` flag and orphan cleanup → `afterCommit` delete of the old object. Delete flow: locked `writeTx` that returns the own key → `afterCommit` delete. `contentHash` (:289-301) is replaced by a 16-hex `SecureRandom` token. `deleteQuietly` never throws and only logs. The `// Upload to remote storage…` comment (:129-131) and the hash comment (:97-100) are rewritten, each in 1–2 lines. |
| `src/main/java/com/imin/iminapi/repository/EventRepository.java` | Add `boolean existsActiveInOrg(UUID id, UUID orgId)`, written as `SELECT COUNT(e) > 0 FROM Event e WHERE e.id = :id AND e.orgId = :orgId AND e.deletedAt IS NULL`. The same `COUNT(e) > 0` form is already used at :472-482. It is a scalar, so it never puts an `Event` into a persistence context that a joined outer transaction would later return stale to the locked load. |
| `src/main/java/com/imin/iminapi/storage/MediaStorage.java` | Javadoc on `urlFor` (:13-17) only. It currently says "persist the URL in the DB before calling put", which is no longer true. New text: "Public URL for a key, without I/O." No signature change. |
| `src/test/java/com/imin/iminapi/service/event/MediaUploadServiceTest.java` | Constructor at :32 gains a `RecordingTxManager` (described below). `storage` becomes `spy(new InMemoryMediaStorage(...))`. New helper `owned(Event e)` stubs both `existsActiveInOrg(e.getId(), e.getOrgId())` → true and `findActive(e.getId())` → e; every upload test that currently stubs only `findActive` uses it instead. Rows T1–T17 in Test impact are new or edited here; T12 replaces `reupload_with_identical_bytes_is_idempotent` (:305-320). |
| `src/test/java/com/imin/iminapi/service/event/EventStatusRevertPostgresTest.java` | Add `@MockitoSpyBean MediaStorage storage`. With nothing stubbed it calls the real `InMemoryMediaStorage` from `TestRateLimitConfig.java:30-33`, so the existing parametrized MEDIA_UPLOAD and MEDIA_DELETE runs (:101, :313-318, :332-333) are unaffected. Add T18 and T19. |

Files in the blast radius that need no change:
- `EventMediaController.java`: calls `upload` (:54) and `delete` (:65) with unchanged signatures.
- `EventMediaControllerTest.java`: mocks the service with unchanged signatures (:41, :67, :191).
- `CrossOrgScopingTest.java:308-321`: the foreign-org upload still returns 404, now from the precheck before validation, and the foreign delete still returns 404 from the locked load. The test reads no fixture this plan changes.
- `InMemoryMediaStorage.java`: its `HashMap` (:8) is only touched with happens-before (latches or `Future.get`).
- `R2MediaStorage.java`: `put`/`delete`/`urlFor`/`keyFor` (:28-50) unchanged.
- `TestRateLimitConfig.java`: unchanged bean.
- `OrgMediaServiceTest.java`: does not use this service. Its regex at :68 is for org logos.
- `EventStatusSweeper.java`: no code change; it only benefits.

## Ordered steps
1. Baseline: run `docker info`, then run the full gate on the untouched worktree (Verification commands). Record whether it is green and the count of Testcontainers tests, which must show no skips.
2. `EventRepository`: add `existsActiveInOrg` next to `lockActiveForWrite` (:367-380), with a one-line javadoc: "Ownership check that loads no entity; for checks made before the row lock."
3. `MediaUploadService` constructor: add `PlatformTransactionManager txManager` and set `this.writeTx = new TransactionTemplate(txManager)`. spring-tx 7.0.6 has `public TransactionTemplate(PlatformTransactionManager)` and `public <T> T execute(TransactionCallback<T>) throws TransactionException` (javap of `~/.m2/.../spring-tx-7.0.6.jar`). Spring autowires it; `ConsentExportService.java:64-68` already injects the same type.
4. `upload` (8-arg overload; the two short overloads just delegate and lose `@Transactional`):
   1. `if (!events.existsActiveInOrg(eventId, p.orgId())) throw ApiException.notFound("Event");` Same 404 and message as `loadOwned` (:193-194), and it runs before any R2 I/O.
   2. Rights gate (:78-82), `validate` (:83), video probe (:84-92) and AI marker (:93-96), all unchanged and in that order.
   3. `String key = "events/" + eventId + "/" + kind.wireValue() + "-" + randomToken() + "." + extensionFor(...)`. `randomToken()` is 8 bytes from a static `SecureRandom`, hex-encoded with `HexFormat`. Comment: "Unique per upload, so a failed write's object can be deleted without touching a live one."
   4. `MediaStorage.Stored stored = storage.put(key, bytes, contentType);` runs with no transaction and no lock. If it throws, the exception propagates: nothing was locked or saved, and there is nothing to clean up.
   5. `boolean[] callbackDone = {false};` then `String oldUrl;` and
      `try { oldUrl = writeTx.execute(s -> { Event e = loadOwnedLocked(p, eventId); String prev = urlOf(e, kind); apply(e, kind, url, aiGenerated); events.saveAndFlush(e); callbackDone[0] = true; return prev; }); }`
      `catch (RuntimeException ex) { if (!callbackDone[0]) { try { storage.delete(key); } catch (RuntimeException cleanup) { ex.addSuppressed(cleanup); } } else { log.warn("Commit outcome unknown; keeping uploaded object {}", key); } throw ex; }`
      - `saveAndFlush` makes DB errors surface inside the callback, where a rollback is certain.
      - Comment: "Delete only when the failure was inside the callback; after it, the commit may have landed."
      - `apply` holds the existing per-kind setters from :110-127, unchanged: POSTER URL + aiGenerated, VIDEO URL, DJ_PHOTO URL + attestation timestamp + version.
   6. If `oldUrl != null && !oldUrl.equals(url)`: compute `oldKey = storage.keyFor(oldUrl)` (pure string). If it is not null, differs from `key` and passes `isOwnUploadKey(eventId, oldKey)` (:186-188), call `afterCommit(() -> deleteQuietly(oldKey))`.
   7. Return `new MediaUploadResponse(stored.url(), stored.sizeBytes(), stored.contentType(), durationSec)`, the same as :141.
5. `delete`:
   - `String key = writeTx.execute(s -> { Event e = loadOwnedLocked(p, eventId); String url = urlOf(e, kind); if (url == null) return null; clear(e, kind); events.save(e); String k = storage.keyFor(url); return k != null && isOwnUploadKey(eventId, k) ? k : null; });`
   - `clear` holds the existing per-kind clears from :157-169.
   - Then `if (key != null) afterCommit(() -> deleteQuietly(key));`
   - Comment: "Object goes only after the cleared URL commits; a rollback keeps both."
6. `loadOwnedLocked` is the existing `loadOwned` renamed (lock, then `findActive`, then org check, :190-196). Its comment stays.
7. `afterCommit(Runnable r)`: `if (TransactionSynchronizationManager.isSynchronizationActive() && TransactionSynchronizationManager.isActualTransactionActive()) registerSynchronization(new TransactionSynchronization() { public void afterCommit() { r.run(); } }); else r.run();`
   - Comment: "Joined to a caller's transaction, wait for its commit; otherwise ours already committed."
   - spring-tx 7.0.6 `TransactionSynchronization.java:162` says `afterCommit` exceptions "will be **propagated to the caller**". That is why `deleteQuietly` must catch everything: `try { storage.delete(k); } catch (RuntimeException ex) { log.warn("Orphaned media object {}: {}", k, ex.getMessage()); }`.
8. Remove the unused imports: `MessageDigest`, `NoSuchAlgorithmException`, `Transactional`.
9. `MediaStorage.urlFor` javadoc fix.
10. Tests T1–T17 in `MediaUploadServiceTest`, then T18–T19 in `EventStatusRevertPostgresTest`.
11. Guard proofs G1–G7 (Test impact). Revert each edit after you see the test go red.
12. Run the full gate (Verification commands). Read the surefire summary to confirm 0 skipped `*PostgresTest`.

## Verification commands
```
docker info >/dev/null && echo docker-up
cd /Users/ivan/imin/imin-api/.claude/worktrees/media-upload-lock
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test -Dtest='MediaUploadServiceTest+EventStatusRevertPostgresTest+CrossOrgScopingTest+EventMediaControllerTest'
/Users/ivan/imin/.claude/bin/test-serial.sh ./mvnw test
grep -h "Tests run" target/surefire-reports/*EventStatusRevertPostgresTest*.txt   # Skipped: 0
```

## Test impact
**Unit-test stub transaction manager.** `RecordingTxManager` is a static nested class in `MediaUploadServiceTest` that extends `AbstractPlatformTransactionManager`. Its hooks in 7.0.6 (javap): `doGetTransaction`, `doBegin`, `doCommit`, `doRollback` are abstract; `isExistingTransaction` and `doSetRollbackOnly` can be overridden.
- `doGetTransaction` returns `new Object()`.
- `isExistingTransaction` returns `TransactionSynchronizationManager.isActualTransactionActive()`, so nested templates join.
- `doBegin` is a no-op.
- `doCommit` appends `"commit"` to a shared `List<String> log`, and throws `TransactionSystemException` when `failCommit` is set.
- `doRollback` appends `"rollback"`.
- `doSetRollbackOnly` is a no-op. The default throws when a participant fails.

The storage spy appends `"put:"+key` and `"delete:"+key` to the same `log` through `doAnswer(... callRealMethod)`.

**Branch map.** Rows T1–T19; there are 19 tests.

| # | Test (file) | Branch forced | Minimal setup | Asserts |
|---|---|---|---|---|
| T1 | `upload_to_another_orgs_event_is_404_and_stores_nothing` (MediaUploadServiceTest, edits `other_org_event_returns_NOT_FOUND` :363-371) | Precheck fails (foreign, missing or soft-deleted all give the same false) | `existsActiveInOrg` left unstubbed (false) | 404 `NOT_FOUND`; `verify(storage, never()).put(...)`; `verify(events, never()).lockActiveForWrite(any(), any())` |
| T2 | `dj_photo_without_attestation_is_rejected` (edit :51-64) | Rights gate before put | `owned(e)`, `rightsAttested=null` | `RIGHTS_ATTESTATION_REQUIRED`; URL null; `never().put` |
| T3 | `poster_with_bad_mime_returns_FIELD_INVALID` (edit :353-361) | `validate` before put | `owned(e)`, `image/gif` | `FIELD_INVALID`; `never().put` |
| T4 | `failed_put_writes_nothing` (new) | `put` throws | `owned(e)`; `doThrow(RuntimeException).when(storage).put(...)` | Original exception rethrown; `never().lockActiveForWrite`; `never().saveAndFlush`; log has no `"commit"`; `never().delete` |
| T5 | `event_gone_at_the_lock_deletes_the_new_object_and_keeps_the_old` (new) | Callback throws (404 inside the locked transaction) → orphan cleanup | `existsActiveInOrg` true; `findActive` → empty; old own blob `events/{id}/poster-aaaa….png` present | 404; blobs contain only the old key; log is `[put:new, rollback, delete:new]` |
| T6 | `failed_orphan_cleanup_is_suppressed_on_the_original_error` (new) | Cleanup throws inside the catch | As T5, plus `doThrow` on `storage.delete(newKey)` | Thrown exception is the 404 `ApiException`; its `getSuppressed()` has one element, the delete failure |
| T7 | `unknown_commit_outcome_keeps_the_new_object` (new) | Callback done, commit throws | `owned(e)`, `failCommit=true` | `TransactionSystemException` rethrown; new blob still present; `never().delete(newKey)` |
| T8 | `replaced_own_object_is_deleted_only_after_commit` (new) | Old own key differs → after-commit delete, no outer transaction | `owned(e)` with own old poster blob | Old blob gone; new present; `log.indexOf("commit") < log.indexOf("delete:"+old)` |
| T9 | `failed_old_object_delete_still_returns_the_upload` (new) | `deleteQuietly` swallows | As T8, plus `doThrow` on `delete(oldKey)` | Response URL equals `e.getPosterUrl()`; no exception |
| T10 | `inside_an_outer_transaction_the_old_object_waits_for_its_commit` (new) | Joined transaction → deferred to outer `afterCommit` | Outer `new TransactionTemplate(txm).execute(...)` around the upload, checking `blobs().containsKey(old)` *inside* the outer callback | Old present inside the outer callback; absent after the outer commit |
| T11 | `outer_rollback_keeps_the_old_object` (new) | Joined transaction rolled back → synchronization never fires | Outer callback calls the upload, then `status.setRollbackOnly()` | Old blob still present after the outer returns |
| T12 | `reupload_with_identical_bytes_gets_a_new_key_and_drops_the_old` (replaces :305-320) | Random key; old own key ≠ new | Same bytes uploaded twice | `r1.url() != r2.url()`; both match `poster-[0-9a-f]{16}\.png`; `blobs()` has exactly `keyFor(r2.url())` |
| T13 | `delete_of_another_orgs_event_is_404_and_touches_no_object` (new) | Locked load 404 on delete | `findActive` → event of another org | 404; `never().delete` |
| T14 | `delete_with_no_url_saves_nothing` (new) | `url == null` early return | `findActive` → event with null poster | `never().save`; `never().delete`; log has `"commit"` and no delete |
| T15 | `delete_removes_own_object_only_after_commit` (new) | Own key → after-commit delete | Event with own poster blob | URL null; blob gone; `indexOf("commit") < indexOf("delete:"+key)` |
| T16 | `failed_object_delete_still_clears_the_url` (new) | `deleteQuietly` on the delete path | As T15, plus `doThrow` on `delete` | No exception; `e.getPosterUrl()` null |
| T17 | `delete_inside_a_rolled_back_transaction_keeps_the_object` (new) | Joined, outer rollback | Outer callback runs the delete, then `setRollbackOnly()` | Blob still present |
| T18 | `sweepCommitsWhileAnUploadIsStoringTheObject` (EventStatusRevertPostgresTest, new) | Lock is not held during `put` (Postgres 17, real JPA transaction manager, Spring bean) | `doAnswer` on `storage.put`: `putEntered.countDown(); await(releasePut); return callRealMethod()`. Pool thread runs `mediaUploadService.upload(principal, eventId, POSTER, realPng(40,50), "image/png", "p.png")` | After `putEntered` (10 s), `pool.submit(() -> events.markLivePast(now(), now())).get(5, SECONDS) == 1`; then release; upload result URL equals `posterUrl()`; `status()` is `PAST` |
| T19 | `sweepCommitsWhileADeleteIsRemovingTheObject` (new) | Lock is not held during the R2 delete | Setup puts the setUp poster key `events/{id}/poster-x.png` (:138) into `storage`; `doAnswer` blocks `storage.delete` on a latch; pool thread runs `mediaUploadService.delete(principal, eventId, POSTER)` | After `deleteEntered`, `markLivePast` returns 1 within 5 s; release; `posterUrl()` null; `status()` is `PAST` |

T18 and T19 release their latches in `finally` and shut the pool down as the existing tests do (:191-194).

**Guard proofs.** Remove each line once and watch the named test go red, then restore it.

| # | Edit | Red test |
|---|---|---|
| G1 | Move `storage.put` into the `writeTx` callback after `loadOwnedLocked` | T18 (`TimeoutException` on `markLivePast`) |
| G2 | Move the delete-path `storage.delete` into the callback before `save` | T19, T17 |
| G3 | Remove the `existsActiveInOrg` precheck | T1 (`put` invoked) |
| G4 | Remove the orphan-cleanup `storage.delete(key)` | T5 |
| G5 | Drop the `callbackDone` check (always clean up) | T7 |
| G6 | Replace `afterCommit(...)` with a direct `deleteQuietly` inside the callback | T8, T15 (order), T10, T11 |
| G7 | Replace `randomToken()` with the old content hash | T12 |

**Existing tests that stay green without edits.** I grepped them for the changed fixtures, the key regex and the constructor:
- the 16 parametrized runs and 2 unpublish tests in `EventStatusRevertPostgresTest` (the media writers still lock before load, and the spy is pass-through);
- `EventMediaControllerTest`;
- `CrossOrgScopingTest`;
- `OrgMediaServiceTest`;
- `PosterImageStorageTest`.

In `MediaUploadServiceTest`, the tests other than T1–T3/T12 change only through the `owned(e)` stub swap and the constructor. Their assertions are unchanged; `poster_png_under_5mb_uploads_and_sets_url` (:219) still matches `poster-[0-9a-f]{16}\.png`.

## Live-test
API only. After Railway deploy, on a throwaway LIVE event in a test org through dashboard.imin.wtf:
1. Upload a poster → 200, the image renders at the returned URL.
2. Upload a different poster → the old URL returns 404 from R2 after a few seconds.
3. Upload the same file again → new URL; the previous one is gone.
4. Delete the poster → 204; the field is cleared and the object returns 404.
5. Repeat step 1 for video (≥20 MB). While it uploads, autosave an event field from a second tab: the PATCH returns promptly instead of waiting for the upload.

## Contract impact
none. Paths, request parts and the `MediaUploadResponse` shape are unchanged, and the URL shape `events/{id}/{kind}-{16 hex}.{ext}` keeps its format. Behaviour change: identical re-uploads no longer reuse a URL.

## i18n impact
none. No user-facing string is added or changed. Copy ledger: no rows, because the API error messages are reused unchanged (`RIGHTS_ATTESTATION_REQUIRED` text :79-81, `fieldErr` texts :211-280, `notFound("Event")`), and the two new log lines are not user-facing.

## Blast radius
- **Callers:** `EventMediaController.upload`/`delete` (EventMediaController.java:54, :65) are the only callers in src/main (grep). The signatures do not change.
- **Lock holders that benefit:** `EventStatusSweeper.sweep` (:55-59) and `EventService.patch` via `loadOwnedForWrite` (:408-411).
- **Same-row writers:** every media write still runs `lockActiveForWrite` and then `findActive`, inside the transaction that saves. The ordering aa66f351 established is preserved, and the precheck loads no entity. Other writers (`TicketTierService.java:254`, `PromoCodeService.java:158`, `EventService.java:409`) are untouched.
- **Storage:** keys stay under `events/{eventId}/`, so `isOwnUploadKey` (:186-188) still protects `ai-posters/` and other events' keys. `OrgMediaService` and `PosterImageStorage` use other prefixes and are untouched.
- **Cost:** an identical re-upload now does one extra PUT plus one DELETE.
- **Orphans:** an object can still be left behind in three cases: unknown commit outcome (T7), a failed old-object delete (T9), and an outer transaction rolling back after a successful inner write (no production caller has one). This is the same bounded-storage debt as today; there is no cleanup job (grep src/main: none).
- **Data:** no migration, no money/auth path, no shared module.

## Risks
- Unit tests run against a stub transaction manager. Real join, rollback and commit behaviour is covered by T18/T19 and by the existing outer-transaction runs in `EventStatusRevertPostgresTest.sweepWaitingOnASavePath_stillMarksThePast` (:198-229), which exercise the joined path on Postgres.
- `saveAndFlush` replaces `save` on upload. The `when(events.save(...))` stubs that remain in the unit tests become unused, which is harmless with plain `mock()`, which has no strict stubs.
- Up to 50 MB is still buffered on the heap (the controller calls `getBytes()`, EventMediaController.java:54), as today. No change.
- The change is 5 files with one concern, so no split is needed. The PATCH-lock Stripe call is a separate card (OPEN_QUESTIONS).

## Definition of done
- [ ] Baseline gate recorded on the untouched base.
- [ ] No storage call runs between `lockActiveForWrite` and commit in `MediaUploadService` (code read: `put` is before `writeTx`, deletes happen only through `afterCommit` or the catch).
- [ ] T1–T19 pass; G1–G7 each shown red and then restored.
- [ ] Full `./mvnw test` is green through `test-serial.sh`, with 0 skipped Testcontainers tests.
- [ ] Comments are 1–2 lines with no ticket or milestone ids; no AI attribution.
- [ ] Live-test steps 1–5 pass in prod after deploy.

## Decisions (main session)
- Plan accepted as written.
- OPEN_QUESTION 1 (Stripe syncTier under the PATCH lock): separate queued card, not this task.
- OPEN_QUESTION 2 (orphaned R2 objects in the three rare cases): accepted; an orphan sweeper is a queued follow-up card.
- OPEN_QUESTION 3: checked origin/main imin-webapp src — no poster/video/djPhoto URL identity comparisons; new URL per re-upload is fine.

## Live-test evidence

## Review rounds
### Implementation round 1 (2026-10-02)
Baseline gate (untouched base, Docker up): 6984 run, 0 failures, 3 skipped (2 H2 assumeTrue(postgres) in AudiencePlanInvitationWebTest, 1 @Disabled in SimulatorEvalTest); 30 Testcontainers classes / 486 tests / 0 skipped.
Final gate: 6999 run, 0 failures, same 3 skips; 30 Testcontainers classes / 488 tests / 0 skipped; EventStatusRevertPostgresTest 20/0 skipped.

Guard proofs (each mutation applied alone, run, then restored byte-identical from a saved copy):
| # | Mutation | Red |
|---|---|---|
| G1 | `put` moved into the `writeTx` callback after `loadOwnedLocked` | T18 `sweepCommitsWhileAnUploadIsStoringTheObject` (TimeoutException) |
| G2 | delete-path `storage.delete` moved into the callback before `save` | T19 (TimeoutException), T17 |
| G3 | precheck removed | T1 |
| G4 | orphan-cleanup `storage.delete(key)` removed | T5 (and T6) |
| G5 | `callbackDone` check dropped | T7 |
| G6 | old-object / own-object delete done directly inside the callback | T8, T15, T10, T11 (and T17) |
| G7 | `randomToken()` replaced by the SHA-256 prefix | T12 |
