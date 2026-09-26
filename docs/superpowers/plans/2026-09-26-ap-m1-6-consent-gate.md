# M1-6 ConsentGate (slug `ap-m1-6-consent-gate`)

Programme plan: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M1-6 (rev 2026-09-26).
Base: imin-api `origin/master` @ `c4e7d70e` (M1-7 shipped: `import_row_provenance`, source `organizer_import_row`).
Reproduction test: n-a (new code).

## Goal and scope

A SQL-first `ConsentGate` that says which of an org's members the audience plan tool may email, and why each other member is excluded. It only reads; it sends nothing and is not yet wired into any send path (M3-2 wires arm campaigns, M3-7 every campaign behind a flag).

- `canMarket(orgId, membershipId)`, `reasons(orgId, ids)`, `mailableMembershipIds(orgId)`, `breakdown(orgId)` (members, mailable, count per exclusion reason: feeds M1-9/M1-11 `exclusions` and the M3-R0/M3-R1 "legacy not mailable" count), `canTrack()` = constant false.
- Repository queries `FanFeatureRepository.findMailableMembershipIds`, `countExclusionsByReason`, `findExclusionReasons` (one shared native SQL, H2 + Postgres).
- New key `imin.audience-plan.soft-opt-in-enabled` (`${IMIN_AUDIENCE_PLAN_SOFT_OPT_IN:false}`), both yaml files, binding test.
- All-paths guard `NeverSoftOptInGuardTest`.
- Carry-over (M1-3 review): `ConsentHistoryEntry` surfaces `textVersion` and `orderId`.

Rules carried forward verbatim:
- §2 D1: "**Decided for now:** explicit consent only (`soft-opt-in-enabled=false`)." / "M1-6 ships with the flag off."
- §2 D3: "Resend open/click tracking OFF for everyone; no tracking-consent box anywhere; lift from holdouts only." / "`canTrack` is constant false."
- §2 D6: "ConsentGate applies to ALL campaigns before the first real send." (lands in M3-7, not here)
- Decisions taken: "D1: explicit consent only now (`soft-opt-in-enabled=false`)"; "Historical `soft_opt_in` rows in prod: count by type first (paid/free, with/without proof), decide after."
- §M1-6: "`canMarket(membership, org)` true iff: status active (not `erase_pending`); email present; not deliverability-suppressed; not marketing-suppressed for org; `consent_status != 'unsubscribed'`; no sticky `marketing_optouts` row; `objected_profiling = false`; `last_contact_from_person_at` within 1095 days; and the basis is proven"
- §M1-6: "`explicit` counts only when the latest subscribing consent record's `(source, proof)` is on `legal.explicit_sources` (§4.4): **`organizer_import_row` only with a matching `import_row_provenance` row** (M1-7); **`checkout` only with `text_version` ∈ `legal.organizer_named_text_versions`** (empty until M3-P1); `door_qr` / `survey` with a text version (M4-3/M4-4)."
- §M1-6: "**Legacy rows are not mailable by the plan tool** (reason `legacy_unproven`): bulk-attested `organizer_import`, checkout captures without an organizer-named version (every c219ac58-era row, whose label says "this organiser's events"), organizer-typed `/consent/capture` entries, and pre-2026-09-08 `soft_opt_in` rows while the flag is off. They become mailable only by a new consent from the person under a named text (checkout after M3-P1, door QR, survey), never by a backfill. An old text version can be added to the allowlist only by a reviewed YAML change (lawyer sign-off recorded in the commit), not per row."
- §M1-6: "`soft_opt_in` counts only with `soft-opt-in-enabled` **and** the latest subscribing `consent_records.order_id` is a paid order (C6) of this org."
- §M1-6: "`canTrack` = constant `false` (D3). The 3-year rule applies to every org in v1 (stricter than France-only; logged as assumption)."
- §M1-6: "Exclusion reasons (for M1-9/M1-11 `exclusions`): `unsubscribed`, `suppressed`, `objected`, `no_basis`, `legacy_unproven`, `retention_3y`, `erase_pending`, `no_email`."
- C6: "`payment_method='stripe' ∧ total_minor > 0 ∧ ¬test_mode ∧ ≥ 1 ticket not in {refunded, revoked}`"
- C17: "Legacy `organizer_import` rows never count for the plan tool (M1-6)."
- Runner rule: no beta gating; `AudiencePlanAccess` stays a default-open kill switch. Real sends stay behind `sends-enabled=false`.

Interpretation (orchestrator rev notes, 2026-09-26):
- Organizer-typed `/consent/capture` rows are `legacy_unproven`: that endpoint can never set `text_version`, so every allowlisted proof also requires a non-null `text_version` (for `organizer_import_row` the writer always stores the attestation version). An organizer typing `source=checkout` or `organizer_import_row` therefore stays legacy.
- A matching provenance row = an `accepted` `import_row_provenance` row with `marketing_status='opted_in'` for this membership whose import belongs to this org.
- 3-year rule in the org timezone (`organizations.timezone`, invalid/missing → UTC): cutoff date = today (org tz) − `legal.retention_days` (1095); within iff the last contact's org-tz date ≥ cutoff. Last contact = latest of `fan_features.last_contact_from_person_at`, the occurred_at of the proving consent when its source is a person source (`checkout`, `door_qr`, `survey`), and any accepted provenance row's `last_purchase_date` (a DATE, compared directly). A null last contact never counts as within 1095 days (reason `retention_3y`). The proving consent's own time is included so a fresh checkout consent is not refused while M1-5's async projection lags; import consents are never a contact.
- Reason precedence (one reason per member, first match): `erase_pending`, `no_email`, `unsubscribed` (status or sticky opt-out), `suppressed` (marketing or deliverability), `objected`, `no_basis`, `legacy_unproven`, `retention_3y`. Every org member gets exactly one reason or none, so reason counts sum to members − mailable.
- Only `channel='email'` consent records count; SMS records never prove email consent.
- Soft opt-in additionally requires `source='checkout'`: the paid order must be the capture point, so a `soft_opt_in` row from door QR, import or survey never counts even with a paid order id.
- Latest record tie-break: on equal `occurred_at` a proven record beats a legacy one, then the higher id wins, so the verdict never depends on UUID order.
- `reasons()` pushes the id filter into the consent ranking (never ranks the rest of the org) and queries in chunks of ≤1000 ids. Null org / null ids answer empty, never throw.
- Overview `retention_3y` over-reports until M1-5 ships: `fan_features.last_contact_from_person_at` is not projected yet, so only a proving person consent or a provenance `last_purchase_date` counts as contact today.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-6-consent-gate` | `./mvnw test` |

## Affected files (per repo)

api (new): `audienceplan/service/ConsentGate.java`, `audienceplan/repository/ConsentGateSql.java`; tests `audienceplan/service/ConsentGateTest.java`, `audienceplan/service/ConsentGatePostgresTest.java`, `audience/controller/NeverSoftOptInGuardTest.java` (moved from `audienceplan/service/`: it drives the package-private `AudienceController.ConsentRequest`).
api (modified): `audienceplan/repository/FanFeatureRepository.java` (three native queries), `audienceplan/config/AudiencePlanProperties.java` (+`softOptInEnabled`), `src/main/resources/application.yaml`, `src/test/resources/application.yaml`, `audience/dto/ConsentHistoryEntry.java` (+`textVersion`, `orderId`), `CLAUDE.md` (env var line), tests `AudiencePlanAccessTest` (binding), consent-history test.

## Ordered steps

1. Properties key + both yaml files + binding tests.
2. `ConsentGateSql` (shared SQL) + repository methods.
3. `ConsentGate` service (params from `AudiencePlanLogic.legal`, org timezone, flag).
4. `ConsentGateTest` one per clause; Postgres twin for the SQL.
5. `NeverSoftOptInGuardTest` (every path + source scan).
6. `ConsentHistoryEntry` fields + test.
7. Rebase onto latest origin/master (tagged stash + SHA), `./mvnw test`.

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-6-consent-gate && ./mvnw test`

## Test impact

New: `ConsentGateTest` (organizer_import_row + provenance → true; without provenance → legacy_unproven; legacy organizer_import → legacy_unproven; checkout allowlisted version → true; null version → legacy; non-allowlisted version → legacy; organizer-typed capture → legacy; soft_opt_in flag off → legacy; flag on + paid org order → true; flag on + free order → false; flag on + no order id → false; flag on + other org's order → false; unsubscribed; marketing suppressed; deliverability suppressed; sticky opt-out; erase_pending; no email; objected; no basis; 1095 days → true; 1096 → false; null last contact → retention_3y; provenance last_purchase_date within → true; org timezone boundary; SMS-only record → no_basis; canTrack false; reason counts sum to members − mailable; other org's member absent), Postgres twin, `NeverSoftOptInGuardTest`, binding tests, consent-history fields.

## Live-test

Not needed: no endpoint, no send path, no UI; the gate is exercised end to end against H2 and Postgres 17 (Testcontainers) in `./mvnw test`.

## Contract impact

`/api/v1` additive: `ConsentHistoryEntry` gains `textVersion`, `orderId` (M1-3 review carry-over; `GET /audience/members/{id}/consent-history` and the DSAR export). Marker: `ConsentHistoryEntry.textVersion` in prod OpenAPI. No webapp change required (webapp reads it in M3-R2); webapp `api:check` shows drift until `api:sync` after deploy.

## i18n impact

None (api only).

## Blast radius

Read-only new service; no existing path calls it yet. `ConsentHistoryEntry` gains two nullable fields (additive JSON). New config key defaults false.

## Risks

- Stricter than SendGate by design: until M3-P1 ships almost no member is plan-mailable (`legacy_unproven`).
- Native SQL must hold on H2 and Postgres (window function, IN lists, Instant/LocalDate binding): covered by the Postgres twin test.
- Empty allowlists would make `IN ()` invalid: a never-matching sentinel is bound instead.

## Definition of done

`./mvnw test` green on a base rebased onto latest origin/master; every §M1-6 test clause present; reason breakdown exposed; no send path changed.

## Live-test evidence

n-a (see Live-test). Verification 2026-09-27:
- Baseline on untouched `c4e7d70e`: `./mvnw test` green (3552 tests, 0 failures).
- Rebased onto `origin/master` `63eb745f` (tagged stash "ap-m1-6-consent-gate pre-rebase base=c4e7d70e", SHA `253aa051`), clean pop.
- Final `./mvnw test`: EXIT 0, 3757 tests, 0 failures, 0 errors; `ConsentGateTest` 41/41 (H2), `ConsentGatePostgresTest` 41/41 (Postgres 17, Testcontainers), `NeverSoftOptInGuardTest` 8/8.
- Review-fix 2026-09-27: origin/master still `63eb745f` (no rebase needed); `./mvnw test` EXIT 0, 3847 tests, 0 failures; `ConsentGateTest` 52/52, `ConsentGatePostgresTest` 52/52, `NeverSoftOptInGuardTest` 9/9.

## Review rounds

(appended by the orchestrator)
