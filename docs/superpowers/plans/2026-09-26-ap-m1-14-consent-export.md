# M1-14 Consent export per organizer

Slug: `ap-m1-14-consent-export` · Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (M1-14) · Mode: autonomous runner (no gates) · Base: imin-api `origin/master` @ `c4e7d70e`

## Goal and scope

Rule 9 (data-sources §5): "Every consent and soft opt-in record is exportable per organizer." Today only the per-member `GET /audience/members/{id}/consent-history` exists.

Add `GET /api/v1/audience/consent/export`: a CSV of every consent record of the caller's org, streamed row by row, OWNER/ADMIN only, audit-logged, excluding `erase_pending` memberships. Carries `text_version`, `order_id` and, for `organizer_import_row` records, the matching `import_row_provenance` fields. Not beta-gated (programme plan: "it is a compliance duty for every org").

Out of scope: the webapp button (M3-R7, after this is live).

Rules carried forward verbatim from the programme plan:
- "api: `GET /api/v1/audience/consent/export` → CSV (email, basis, source, text_version, proof_text, order_id, captured_at, origin, status) for the caller's org, owner/admin role only, audit-logged; excludes `erase_pending` rows. Not beta-gated (it is a compliance duty for every org)."
- "Tests: org-scoped (other org's rows absent); member role → 403; erased excluded; header row exact; audit row written."
- "NO beta gating ... Do not add beta-org checks or new per-feature enable flags" (runner, Ivan 2026-09-26).
- "Numbers shown must trace to a real API field" / no fabricated data: a column is only filled from a stored field.

Deviation (recorded, not silent): `consent_records` has **no `origin` column**; `ConsentOrigin` is a method parameter only and is never persisted, and `ConsentOrigin`'s own javadoc forbids inferring it from `source`. Deriving it would fabricate a field, so the export carries `channel` (a real column) instead of `origin`. Persisting origin is a separate card (migration + every `ConsentService` write path).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-14-consent-export` | `./mvnw test` |

## Affected files (per repo)

api:
- new `src/main/java/com/imin/iminapi/audience/service/ConsentExportService.java` — role check, streaming JDBC query, CSV writer.
- new `src/main/java/com/imin/iminapi/audience/controller/ConsentExportController.java` — `GET /api/v1/audience/consent/export`, `StreamingResponseBody`, audit row.
- `src/main/java/com/imin/iminapi/service/audit/AuditActions.java` — `CONSENT_EXPORTED`.
- new test `src/test/java/com/imin/iminapi/audience/ConsentExportTest.java`.

## Ordered steps

1. `AuditActions.CONSENT_EXPORTED`.
2. `ConsentExportService`:
   - `requirePrivileged(principal)` → `RoleGuard.requireAtLeast(p, ADMIN, "export consent records")` (403; gate principals refused).
   - `write(orgId, Writer, AtomicLong rows)`: one native query run by `JdbcTemplate` with a fetch size inside a read-only `TransactionTemplate` (PostgreSQL only uses a cursor when autocommit is off), rows written as they arrive — memory is O(1) in org size.
   - Query: `consent_records` ⨝ `memberships` (org_id = caller, `status = 'active'`, i.e. `erase_pending` excluded, fail-closed for any other status) ⨝ `consumers` (email); `LEFT JOIN import_row_provenance` on the same membership, `accepted = TRUE`, only for `source = 'organizer_import_row'`, matched on the import id + row number that `AudienceImportService.proofText` writes at the start of the record's `proof_text` (the two rows are written in one transaction by `ImportProvenanceWriter.recordExplicit`; there is no FK). Order by `occurred_at, id`.
   - Header (exact): `record_id,captured_at,email,channel,status,basis,source,text_version,order_id,proof_text,import_id,import_row,source_platform,export_date,proof_ref`.
   - RFC 4180 quoting + CSV-injection guard (`= + - @ \t \r` prefixed with `'`), same rule as `AttendeeExportService.csv`.
3. `ConsentExportController`: role check **before** streaming (a refused request is a plain 403 and writes no audit row), then `StreamingResponseBody`; after the last row the audit row "Consent records exported (N row(s))"; if the stream fails midway, "Consent export interrupted after N row(s)" is recorded (partial data did leave) and the exception is rethrown. `AuditLogger.record` never throws, so the catch cannot mask the original exception. Headers: `text/csv; charset=UTF-8`, `Content-Disposition: attachment; filename="consent-records.csv"`, `Cache-Control: no-store`.
4. Tests (below), then `./mvnw test`.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-14-consent-export && ./mvnw test`

## Test impact

Reproduction test: n-a (new endpoint). `ConsentExportTest` (`@SpringBootTest` + MockMvc + H2, real repositories, `AuditLogger` mocked), one test per branch:
- header row exact (empty org → header only, audit "0 row(s)").
- OWNER exports own rows with every column: `text_version`, `order_id`, `captured_at`, `status`, `basis`, `source`, `channel`, email.
- ADMIN allowed.
- MEMBER → 403, no audit row.
- gate principal → 403.
- org-scoped: another org's record is absent.
- `erase_pending` membership's records absent.
- `organizer_import_row` record via the real `AudienceImportService` path carries `import_id`, `import_row`, `source_platform`, `export_date`, `proof_ref`; a non-import record leaves them empty; an organizer-typed record whose `source` is `organizer_import_row` but whose proof text names no import gets no provenance.
- CSV injection guard + quoting for a proof text with comma/quote/newline.
- audit row written with action `CONSENT_EXPORTED`, target type `organization`, org id, row count, no email in the summary.
- interrupted stream → "interrupted" audit row and the exception propagates (unit test on the controller with a failing service).

## Live-test

Not required by the programme plan (M1 DoD live-test is the plan endpoint). Optional local curl if time permits; evidence below.

## Contract impact

`/api/v1` — new path, marker `/api/v1/audience/consent/export` in prod OpenAPI. No DTO change. Webapp consumer is M3-R7 (later).

## i18n impact

None (api only; CSV header is a machine format, English column names).

## Blast radius

Read-only new endpoint; no migration, no change to any existing write path. New audit action string (constants class, no enum). Holds one DB connection for the duration of the download.

## Risks

- Provenance match depends on the `proof_text` prefix `AudienceImportService` writes; pinned by a test that goes through the real import path, so a format change turns it red.
- A long download holds a read-only transaction; acceptable at current org sizes, and far better than materialising the file in memory.
- `origin` not exported (see Deviation).

## Definition of done

`./mvnw test` green; endpoint present in local OpenAPI; tests above green; no new strings outside the CSV header; comments 1–2 lines, no ticket refs.

## Live-test evidence

No server boot (programme plan requires none for M1-14). Postgres check against local PG :5433 (Flyway V134), inside `BEGIN … ROLLBACK`: one real-format `organizer_import_row` record plus one hand-typed record with the same source on the same membership. The export query returned the provenance (`import_id`, row 7, `shotgun`, `2026-09-01`, `ref-7`) on the real record only; the hand-typed record had empty provenance cells. Nothing was persisted.

Verification: baseline `./mvnw test` on `c4e7d70e` exited 0. After rebasing onto `origin/master` @ `92c50824` (tagged stash `ap-m1-14-consent-export-wip`, SHA `65944f9f`), `./mvnw test` → 3679 tests, 0 failures, 0 errors; `ConsentExportTest` 15/15.

## Review rounds

(appended by orchestrator)

## Review round 1 fixes (supersede "no migration" and prefix matching)
V150 adds import_row_provenance.consent_record_id (FK CASCADE, index, backfilled from the proof_text prefix); ImportProvenanceWriter stores it; the export joins on it only. Query statement timeout 60 s, whole export capped at 10 min (audited as interrupted), spring.mvc.async.request-timeout 11m. Accepted gap: a row written by an old instance during a rolling deploy after V150 has no consent_record_id (seconds).
