# M1-2 Check a date: schema (V162, entities, repositories, ledger + events columns)
m1-2-date-check-schema · api only · programme `docs/superpowers/plans/2026-09-28-predictor-date-check.md` §4 M1-2 · spec §3

## Goal and scope
Persistence layer for "Check a date". No endpoint, no service logic, no DTO change.
In scope: `V162__predictor_date_check.sql`; tables `date_check`, `date_check_date`, `date_check_finding`, `reference_calendar`, `predictor_job`, `genre_week_count`, `org_connector`; `prediction_ledger` (event_id nullable + 2 named CHECKs + 6 columns); `events.sub_genre`, `events.date_check_id`; 7 entities in `predictor/model`, 7 repositories in `predictor/repository` (`@RepositoryRestResource(exported = false)`); `PredictionSurface.DATE_CHECK`; null-event guards in two ledger loops.
Out of scope: job-claim SQL (M1-3), calendar sync (M1-4), DTO/OpenAPI changes (sub_genre NOT in EventDto), `RecordCommand` shape, `PublicHolidayCalendar` (M0-1).

Decisions (main session, 2026-09-30): column renames accepted for H2/PG portability (`window`→`time_window`, `date`→`candidate_date`/`calendar_date`, `rank`→`rank_order`, `week`/`count`→`week_start`/`event_count`; `reference_calendar.region` and `genre_week_count.sub_genre` are `NOT NULL DEFAULT ''`); ledger cost column is `cost_usd NUMERIC(12,6)` (OpenRouter reports USD; same as V160); `reference_calendar` gets nullable `end_date` (null = single day, school holidays are ranges); second ledger CHECK `date_check_id IS NOT NULL OR surface <> 'DATE_CHECK'`; one task, no split. `date_check.status` values `pending|running|done|partial|failed`. Amendment (main session, 2026-09-30): `date_check_finding.source_kind` also allows `input`; soft-strength and stop-source CHECKs unchanged, so `input` is a soft source.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `imin-api/.claude/worktrees/m1-2-date-check-schema` | `./mvnw test` |

## Affected files (per repo)
Create: `src/main/resources/db/migration/V162__predictor_date_check.sql`; `predictor/model/{DateCheck,DateCheckDate,DateCheckFinding,ReferenceCalendarEntry,PredictorJob,GenreWeekCount,OrgConnector}.java`; `predictor/repository/{DateCheckRepository,DateCheckDateRepository,DateCheckFindingRepository,ReferenceCalendarEntryRepository,PredictorJobRepository,GenreWeekCountRepository,OrgConnectorRepository}.java`; tests `src/test/java/com/imin/iminapi/migration/{DateCheckMigrationScenarios,DateCheckMigrationH2Test,DateCheckMigrationPostgresTest}.java`, `src/test/java/com/imin/iminapi/predictor/{DateCheckSchemaTest,CalibrationViewServiceTest}.java`; this plan.
Modify: `predictor/model/PredictionSurface.java`, `predictor/model/PredictionLedger.java`, `model/Event.java`, `predictor/service/CalibrationViewService.java`, `predictor/service/PredictionScoringJob.java`, tests `PredictionScoringJobTest.java`, `PredictorPagedScanOrderTest.java`.

## Ordered steps
1. Baseline `./mvnw test` on untouched base; red → stop and report.
2. Migration (header 1–2 lines; JSON in TEXT; named constraints only):
```sql
CREATE TABLE date_check (
    id UUID PRIMARY KEY, org_id UUID NOT NULL,
    created_by UUID NOT NULL,      -- no FK: TeamService.countRetainedReferences lists every users FK
    city VARCHAR(100) NOT NULL, country VARCHAR(2) NOT NULL, genre_family VARCHAR(64) NOT NULL,
    sub_genre VARCHAR(64), capacity INT, price_minor BIGINT, format VARCHAR(32),
    start_hour SMALLINT, end_hour SMALLINT, lineup_json TEXT, known_events_json TEXT,
    assumptions_json TEXT NOT NULL DEFAULT '[]', research BOOLEAN NOT NULL DEFAULT FALSE,
    status VARCHAR(16) NOT NULL, question_bank_version VARCHAR(32) NOT NULL, event_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_date_check_org FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT fk_date_check_event FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE SET NULL,
    CONSTRAINT ck_date_check_status CHECK (status IN ('pending','running','done','partial','failed'))
);
CREATE INDEX ix_date_check_org_created ON date_check (org_id, created_at);
CREATE INDEX ix_date_check_event ON date_check (event_id);

CREATE TABLE date_check_date (
    id UUID PRIMARY KEY, date_check_id UUID NOT NULL, candidate_date DATE NOT NULL,
    verdict VARCHAR(16) NOT NULL, risk_score SMALLINT NOT NULL, opp_score SMALLINT NOT NULL,
    coverage NUMERIC(4,3) NOT NULL, rank_order SMALLINT, actions_json TEXT NOT NULL DEFAULT '[]',
    CONSTRAINT fk_date_check_date_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE CASCADE,
    CONSTRAINT uq_date_check_date UNIQUE (date_check_id, candidate_date),
    CONSTRAINT ck_date_check_date_verdict CHECK (verdict IN ('good','adjust','move','not_enough_data')),
    CONSTRAINT ck_date_check_date_risk CHECK (risk_score BETWEEN 0 AND 10),
    CONSTRAINT ck_date_check_date_opp CHECK (opp_score BETWEEN 0 AND 10),
    CONSTRAINT ck_date_check_date_coverage CHECK (coverage BETWEEN 0 AND 1)
);

CREATE TABLE date_check_finding (
    id UUID PRIMARY KEY, date_check_date_id UUID NOT NULL, question_id VARCHAR(16) NOT NULL,
    kind VARCHAR(16) NOT NULL, status VARCHAR(16) NOT NULL, strength SMALLINT NOT NULL, weight SMALLINT NOT NULL,
    source_kind VARCHAR(16) NOT NULL, time_window VARCHAR(8) NOT NULL, stop_factor BOOLEAN NOT NULL DEFAULT FALSE,
    facts_json TEXT NOT NULL DEFAULT '{}', url VARCHAR(2048), quote VARCHAR(1000), fetched_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_date_check_finding_date FOREIGN KEY (date_check_date_id) REFERENCES date_check_date (id) ON DELETE CASCADE,
    CONSTRAINT ck_date_check_finding_kind CHECK (kind IN ('risk','opportunity')),
    CONSTRAINT ck_date_check_finding_status CHECK (status IN ('found','clear','not_checked')),
    CONSTRAINT ck_date_check_finding_source_kind CHECK (source_kind IN ('structured','internal','web','organizer','input')),
    CONSTRAINT ck_date_check_finding_window CHECK (time_window IN ('night','week','month')),
    CONSTRAINT ck_date_check_finding_strength CHECK (strength BETWEEN 0 AND 3),
    CONSTRAINT ck_date_check_finding_weight CHECK (weight BETWEEN 1 AND 3),
    CONSTRAINT ck_date_check_finding_soft_strength CHECK (source_kind IN ('structured','internal') OR strength <= 2),
    CONSTRAINT ck_date_check_finding_stop_source CHECK (stop_factor = FALSE OR source_kind IN ('structured','internal'))
);
CREATE INDEX ix_date_check_finding_date ON date_check_finding (date_check_date_id);

CREATE TABLE reference_calendar (
    id UUID PRIMARY KEY, country VARCHAR(2) NOT NULL,
    region VARCHAR(16) NOT NULL DEFAULT '',   -- '' = whole country; NULL would break the unique key
    calendar_date DATE NOT NULL, end_date DATE, kind VARCHAR(16) NOT NULL, name VARCHAR(255) NOT NULL,
    source_url VARCHAR(2048) NOT NULL, synced_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_reference_calendar_entry UNIQUE (country, region, calendar_date, kind, name),
    CONSTRAINT ck_reference_calendar_kind CHECK (kind IN ('holiday','school','pont','dst','hijri','fixture')),
    CONSTRAINT ck_reference_calendar_range CHECK (end_date IS NULL OR end_date >= calendar_date)
);

CREATE TABLE predictor_job (
    id UUID PRIMARY KEY, kind VARCHAR(64) NOT NULL, payload_json TEXT NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL, attempts INT NOT NULL DEFAULT 0,
    run_after TIMESTAMP WITH TIME ZONE NOT NULL, locked_until TIMESTAMP WITH TIME ZONE, last_error VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL, updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT ck_predictor_job_status CHECK (status IN ('queued','running','done','failed'))
);
CREATE INDEX ix_predictor_job_status_run_after ON predictor_job (status, run_after);

-- ODbL-derived weekly counts; kept separate so the licence stays separable.
CREATE TABLE genre_week_count (
    id UUID PRIMARY KEY, city_key VARCHAR(100) NOT NULL, genre_family VARCHAR(64) NOT NULL,
    sub_genre VARCHAR(64) NOT NULL DEFAULT '', week_start DATE NOT NULL, event_count INT NOT NULL,
    sources_json TEXT NOT NULL DEFAULT '[]', updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_genre_week_count UNIQUE (city_key, genre_family, sub_genre, week_start),
    CONSTRAINT ck_genre_week_count_nonneg CHECK (event_count >= 0)
);

CREATE TABLE org_connector (
    id UUID PRIMARY KEY, org_id UUID NOT NULL, kind VARCHAR(16) NOT NULL, token_enc TEXT NOT NULL,
    scopes VARCHAR(512), connected_by UUID NOT NULL,   -- no FK, same reason as date_check.created_by
    connected_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_org_connector_org FOREIGN KEY (org_id) REFERENCES organizations (id) ON DELETE CASCADE,
    CONSTRAINT ck_org_connector_kind CHECK (kind IN ('shotgun','dice','instagram'))
);
CREATE INDEX ix_org_connector_org_kind ON org_connector (org_id, kind);

-- A date check has no event yet; surface is @Enumerated(STRING), so the literal is the enum name.
ALTER TABLE prediction_ledger ALTER COLUMN event_id DROP NOT NULL;
ALTER TABLE prediction_ledger ADD COLUMN date_check_id UUID;
ALTER TABLE prediction_ledger ADD COLUMN question_bank_version VARCHAR(32);
ALTER TABLE prediction_ledger ADD COLUMN tokens_in INT;
ALTER TABLE prediction_ledger ADD COLUMN tokens_out INT;
ALTER TABLE prediction_ledger ADD COLUMN cost_usd NUMERIC(12,6);
ALTER TABLE prediction_ledger ADD COLUMN searches INT;
ALTER TABLE prediction_ledger ADD CONSTRAINT ck_prediction_ledger_event_or_date_check
    CHECK (event_id IS NOT NULL OR surface = 'DATE_CHECK');
ALTER TABLE prediction_ledger ADD CONSTRAINT ck_prediction_ledger_date_check_id
    CHECK (date_check_id IS NOT NULL OR surface <> 'DATE_CHECK');
CREATE INDEX ix_prediction_ledger_date_check ON prediction_ledger (date_check_id);

ALTER TABLE events ADD COLUMN sub_genre VARCHAR(64);
ALTER TABLE events ADD COLUMN date_check_id UUID;
ALTER TABLE events ADD CONSTRAINT fk_events_date_check FOREIGN KEY (date_check_id) REFERENCES date_check (id) ON DELETE SET NULL;
CREATE INDEX ix_events_date_check ON events (date_check_id);
```
   `prediction_ledger.date_check_id` has no FK (append-only audit, like V70's org_id). Re-check V162 is still free right before ship.
3. `PredictionSurface.DATE_CHECK` (1-line comment "check-a-date render; event_id may be null"). Column is VARCHAR(16).
4. `PredictionLedger`: `event_id` no longer `nullable = false`; add `UUID dateCheckId`, `String questionBankVersion` (32), `Integer tokensIn`, `Integer tokensOut`, `BigDecimal costUsd`, `Integer searches`. `RecordCommand` unchanged.
5. `Event`: `@Column(name="sub_genre", length=64) String subGenre; @Column(name="date_check_id") UUID dateCheckId;` with 1-line javadoc. No DTO/service/duplication changes.
6. Entities: Lombok `@Getter @Setter`, `@Id @GeneratedValue(strategy = GenerationType.UUID)`, `Instant` via `Times.nowMicros()` truncated in `@PrePersist/@PreUpdate`, `updated_at` refreshed in `@PreUpdate`, enum-like columns as `String` (lowercase values in javadoc; no EnumType.STRING), JSON as `String` `columnDefinition="TEXT"` defaulting to "[]"/"{}", `region`/`subGenre` default `""` in Java, `coverage`/`costUsd` BigDecimal, dates `LocalDate`, plain UUID FK fields (no associations).
7. Repositories (`JpaRepository<X, UUID>`, exported = false): `DateCheckRepository.findByOrgIdOrderByCreatedAtDesc(UUID, Pageable)`; `DateCheckDateRepository.findByDateCheckIdOrderByCandidateDateAsc(UUID)`; `DateCheckFindingRepository.findByDateCheckDateIdIn(Collection<UUID>)`; `ReferenceCalendarEntryRepository.findByCountryAndRegionInAndCalendarDateBetween(String, Collection<String>, LocalDate, LocalDate)` (callers pass `List.of("", region)`); `PredictorJobRepository` (no methods); `GenreWeekCountRepository.findByCityKeyAndGenreFamilyAndSubGenreAndWeekStart(...)`; `OrgConnectorRepository.findByOrgIdAndRevokedAtIsNull(UUID)`.
8. Null-event guards: `CalibrationViewService.render()` joined rows require `r.getEventId() != null`; `PredictionScoringJob.aggregateSegments` `if (row.getEventId() == null) continue;` (comment "date-check renders have no event outcome"). `findJoinable` already excludes them — pinned by a test.
9. Tests, then `./mvnw test`. Read the diff: no DTO/controller change, no PublicHolidayCalendar reference, comments 1–2 lines.

## Verification commands
Baseline `./mvnw test`; targeted `./mvnw test -Dtest='DateCheckSchemaTest,DateCheckMigrationH2Test,DateCheckMigrationPostgresTest,CalibrationViewServiceTest,PredictionScoringJobTest,PredictorPagedScanOrderTest,PredictionLedgerServiceTest'` (Postgres test needs Docker — run it at least once, report if skipped); gate `./mvnw test`; `git diff origin/master --stat -- 'src/main/java/**/dto/**' 'src/main/java/**/controller/**'` empty.

## Test impact
Reproduction test: n/a (new schema). Branch map: ledger CHECKs (null event + DATE_CHECK ok; null event + other surface rejected; event + DATE_CHECK ok; DATE_CHECK without date_check_id rejected; existing rows untouched); each named CHECK rejects one out-of-domain value with a valid sibling accepted; unique keys incl. '' default; FK cascades/SET NULL; null-event loops.
- `DateCheckMigrationScenarios` (abstract, fresh DB per test via `Flyway.target`, pattern `ExperimentForeignKeysMigrationScenarios`) + H2 and Postgres (postgres:17-alpine, `@Testcontainers(disabledWithoutDocker = true)`) subclasses: `existingLedgerRowsUnaffected` (migrate 161, insert PRE_PUBLISH row + event, migrate 162, columns unchanged, new ones NULL), `ledgerAcceptsNullEventIdOnlyForDateCheckSurface`, `ledgerRejectsNullEventIdForEventSurfaces`, `ledgerAcceptsDateCheckRowWithEvent`, `ledgerRejectsDateCheckRowWithoutDateCheckId`, `namedChecksRejectOutOfDomainValues` (parameterized, every CHECK incl. `ck_reference_calendar_range`), `referenceCalendarUniqueTreatsOmittedRegionAsOneKey`, `genreWeekCountUniqueTreatsOmittedSubGenreAsOneKey`, `dateCheckDateUniquePerCandidateDate`, `orgDeleteCascadesDateCheckTreeAndConnectors` (org without events/users), `deletingDateCheckNullsEventLink`, `deletingEventNullsDateCheckLink`.
- `DateCheckSchemaTest` (`@SpringBootTest @Import(TestRateLimitConfig.class) @Transactional`; save, flush, clear, reload, assert every field): round-trip per entity with its finder; `ledgerEntityWritesDateCheckSurfaceWithoutEvent`; `ledgerEntityRejectsNullEventForPrePublish`; `eventsSubGenreAndDateCheckIdPersist`. (Named `*Test` — surefire only runs `*Test`.)
- `CalibrationViewServiceTest.renderSkipsJoinedRowWithoutEvent` (mocks).
- `PredictionScoringJobTest.aggregateSkipsJoinedRowWithoutEvent`.
- `PredictorPagedScanOrderTest.joinableScanExcludesRowsWithoutEvent`.
`PredictionLedgerServiceTest` untouched and green.

## Live-test
After deploy: `/actuator/health` UP; `/v3/api-docs.yaml` has no `subGenre`/`dateCheck` (contract unchanged).

## Contract impact
none

## i18n impact
none

## Blast radius
Prod Flyway on PG 17: nullable column adds are metadata-only; FK/CHECK validation is quick on current data; V162 is transactional, a failure keeps the old deploy. `events` gains two nullable unmapped-in-DTO columns. `prediction_ledger` loses NOT NULL (consumers checked; two loops guarded). Org delete now cascades the new tables. No users FK (TeamService). No money/auth.

## Risks
~27 files but one concern (schema). Later plans must use the renamed columns. Named CHECK domains need a migration to widen. H2 leniency: the Postgres subclass must actually run.

## Definition of done
V162 applies on H2 and PG 17; 7 entities + 7 repositories (exported = false); DATE_CHECK; ledger/event fields; guards + tests; every listed test passes; `./mvnw test` green; no DTO change; nothing committed by the worker.

## Live-test evidence

## Review rounds

round 1 → PASS (0 CRITICAL/HIGH; 1 MEDIUM: start-date-only calendar finder — overlap query owned by M1-4 findOverlapping; 3 LOW noted for M1-8/M4-2)
