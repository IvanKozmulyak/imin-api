# M1-12 RetentionJob (slug `ap-m1-12-retention-job`)

Programme plan: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M1-12 (rev 2026-09-26).
Base: imin-api `origin/master` @ `94745c41` (M1-6 ConsentGate shipped); rebased onto `15368af5` (M3-R0), then onto `a9163c9b` in review round 1.
Reproduction test: n-a (new code).

## Goal and scope

A nightly job that removes the email marketing basis of members who have had no contact from the person
for more than 1095 days (the 3-year rule), in the organizer's timezone. It ships dark: with
`retention-job-enabled=false` (the default) it only counts and logs, and writes nothing.

- `service/RetentionJob.java`: `@Scheduled(cron = "0 0 4 * * *", zone = "Europe/Paris")`, `@SchedulerLock(name = "audience_retention")`.
- New key `imin.audience-plan.retention-job-enabled` (`${IMIN_AUDIENCE_RETENTION_ENABLED:false}`) in `AudiencePlanProperties`, `src/main/resources/application.yaml` and `src/test/resources/application.yaml`, with a binding test for its default.
- "Past the window" reuses the ConsentGate retention predicate (`ConsentGateSql.CONTACT_WITHIN_RETENTION`, cutoff per org timezone from `ConsentGate`), not a second copy.
- Enabled: per member, `ConsentService.unsubscribe(..., source "retention_3y", origin OPERATOR)` plus `fan_features` profiling cleared (taste, cities, formats) in one transaction.
- The fan-feature calculator keeps taste empty after a retention clear until the person makes contact again; otherwise the next recompute (live `ConsentChanged` or nightly) would refill it.

Rules carried forward verbatim:
- §4.2: "`retention-job-enabled: ${IMIN_AUDIENCE_RETENTION_ENABLED:false} # M1-12; dry-run logs when false`"
- §M1-12: "When enabled: memberships with `last_contact_from_person_at` older than 1095 days lose marketing basis via `ConsentService.unsubscribe(..., source "retention_3y", origin OPERATOR)` (no sticky opt-out), `fan_features.taste` cleared. Orders and tickets untouched (accounting). Fan-app 2-year rule is out of scope (IMIN controller, separate task)."
- §M1-12 tests: "1096 days → basis cleared + consent record `retention_3y`; 1095 untouched; recent open with 1096-day purchase still cleared; flag off → nothing written, count logged; idempotent second run."
- §M1-6: "`canTrack` = constant `false` (D3). The 3-year rule applies to every org in v1 (stricter than France-only; logged as assumption)."
- §M1-13: "Operator/retention unsubscribes (`OPERATOR`, `retention_3y`) do not set it." (`objected_profiling`)
- M1-4 carry-over: "imported contacts (class `imported`, 0 paid orders) have a **null** `last_contact_from_person_at`. A null must never be read as "within 1095 days". Use the provenance `last_purchase_date` from M1-7 for their retention and mailability window."
- M1-6 plan: "3-year rule in the org timezone (`organizations.timezone`, invalid/missing → UTC): cutoff date = today (org tz) − `legal.retention_days` (1095); within iff the last contact's org-tz date ≥ cutoff. Last contact = latest of `fan_features.last_contact_from_person_at`, the occurred_at of the proving consent when its source is a person source (`checkout`, `door_qr`, `survey`), and any accepted provenance row's `last_purchase_date` (a DATE, compared directly). A null last contact never counts as within 1095 days (reason `retention_3y`)."
- C13 / D3: opens and clicks never count as contact.
- Runner rule: no beta gating; `AudiencePlanAccess` stays a default-open kill switch; `retention-job-enabled` stays dry-run by default.

Targets (all must hold): member of the org, `status <> 'erase_pending'`, `consent_status = 'subscribed'` (email),
a `fan_features` row refreshed within the last 48 h, and NOT `CONTACT_WITHIN_RETENTION` (null-safe: evaluated as
`CASE WHEN … THEN 1 ELSE 0 END = 0`, so a member with no subscribing record and no contact is a target).
The freshness guard is a job-only safety rule: a destructive write never acts on a projection that may miss a
recent purchase; stale or missing rows are skipped and counted. Inside the per-member transaction the membership
is locked and the target predicate re-checked for that id, so a consent that lands between the scan and the write wins.
Still inside that lock the job reads the member's paid live orders directly (same org, `orders.email_normalized` =
the consumer's email, Stripe, non-zero, `test_mode = false`, a ticket neither refunded nor revoked) and skips the
member when one was created at or after the org-tz cutoff instant: the projection may lag a purchase.

Decision (orchestrator, review round 1): members whose only subscribing email grant is the legacy bulk
`organizer_import` source and who have no `import_row_provenance` row (pre-provenance imports) are **not**
unsubscribed. Their contact age is unknown, and an unsubscribe would silently stop manual/Momentum sends too. They
are counted separately (`Result.importedWithoutProvenance`, logged as "N imported without provenance skipped" in both
the dry-run and the enabled line); the plan tool already treats them as `legacy_unproven`. A member with any other
subscribing grant that carries a basis, or with any provenance row, is judged by the normal rule.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-12-retention-job` | `./mvnw test` |

## Affected files (per repo)

api:
- new `src/main/java/com/imin/iminapi/audienceplan/service/RetentionJob.java`
- `src/main/java/com/imin/iminapi/audienceplan/service/ConsentGate.java` (+ `retentionScan`, `isRetentionExpired`, `hasPaidOrderWithinRetention`)
- `src/main/java/com/imin/iminapi/audienceplan/repository/ConsentGateSql.java` (+ `RETENTION_EXPIRED`, `RETENTION_EXPIRED_FOR_IDS`, `LEGACY_IMPORT_ONLY`, `PAID_ORDERS_SINCE`)
- `src/main/java/com/imin/iminapi/audienceplan/repository/FanFeatureRepository.java` (+ the three queries, `findOrgIdsWithSubscribedMembers`)
- `src/main/java/com/imin/iminapi/audienceplan/engine/FanFeatureCalculator.java` (retention-cleared → empty profiling)
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java`
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml`
- tests: new `RetentionJobScenarios` (+ H2 `RetentionJobTest`, `RetentionJobPostgresTest`), `RetentionJobUnitTest`; `FanFeatureCalculatorTest`, `AudiencePlanAccessTest` (binding)

## Ordered steps

1. Property + both yaml files + binding test.
2. `ConsentGateSql` retention target queries built from the existing `LATEST_CONSENT_*` and `CONTACT_WITHIN_RETENTION` fragments; repository methods; `ConsentGate` methods that reuse `params(orgId)`.
3. `FanFeatureCalculator`: profiling empty when the latest `retention_3y` email unsubscribe is not followed by contact.
4. `RetentionJob`: per org (kill switch respected), count; enabled → per member tx (lock, re-check, unsubscribe OPERATOR, clear profiling); one failure skips only that member; log counts.
5. Tests; `./mvnw test`.

## Verification commands

`./mvnw test` from the worktree root.

## Test impact

New tests, one per branch:
- Integration (H2 + Postgres twin): 1096 days → consent_status `unsubscribed`, basis null, record `retention_3y`/`unsubscribed`, taste/cities/formats emptied, no `marketing_optouts` row, `objected_profiling` stays false; 1095 → untouched; recent `last_email_open` + 1096-day contact → cleared; imported null contact without provenance date → cleared; provenance `last_purchase_date` within → untouched; person-source consent within (old fan_features contact) → untouched; flag off → nothing written, count returned and logged; second run → nothing new, one `retention_3y` record; stale fan_features row → skipped; no fan_features row → skipped; erase_pending → skipped; org timezone boundary (Paris vs UTC); paid order and ticket still present after clear; other org's members untouched.
- Integration (review round 1): paid order on the cutoff instant missing from the projection → untouched; order one
  second before → cleared; recent test-mode order → cleared; recent order at another org → cleared; legacy
  `organizer_import`-only member → untouched and counted (dry-run and enabled log lines, both counts asserted);
  legacy import plus another grant → cleared; legacy import with a provenance row → cleared.
- Unit (review round 1): paid order within retention at write time → no unsubscribe; org with only legacy imports →
  counted, never locked.
- Unit: schedule/lock annotations pinned; `scheduled()` runs through the proxy; kill switch off → org skipped; re-check false → no unsubscribe; one member failing → others still cleared, failure counted.
- Calculator: retention clear with no later contact → empty taste/cities/formats; contact after the clear → taste back; SMS-channel `retention_3y` record ignored.
- Binding: `retention-job-enabled` defaults false, blank binds false.

## Live-test

Not needed: no endpoint, dark by default, scheduled job fully covered by H2 + Postgres integration tests. The first prod run is a dry run that logs counts only.

## Contract impact

none

## i18n impact

none

## Blast radius

Dark by default: in prod the job only logs counts (`RetentionJob: dry run …`). When enabled it unsubscribes
(email, non-sticky) members past 3 years across every org with fresh fan features, which changes `SendGateService`
results for manual/Momentum campaigns too (`consent_status` is shared). The calculator change only affects members
that already carry a `retention_3y` unsubscribe, which only the enabled job writes.

## Risks

- Stale projection → wrong unsubscribe: mitigated by the 48 h freshness guard and the in-lock re-check.
- SMS consent is not touched (plan scope is the email basis); a 3-year rule for SMS is a separate decision.
- Many clears in one night queue one live recompute each; the projector's bounded queue drops extras and the nightly pass rewrites them.

## Definition of done

`./mvnw test` green; flag default false in both yaml files; dry run writes nothing; reviewer pass.

## Live-test evidence

n-a (see Live-test). Verification: baseline `./mvnw test` on `94745c41` green; after rebase onto `15368af5`, `./mvnw test` EXIT 0, 4082 tests, 0 failures, 0 errors, 0 skipped (RetentionJobTest 15, RetentionJobPostgresTest 15, RetentionJobUnitTest 11, FanFeatureCalculatorTest 59, AudiencePlanAccessTest 30). Review round 1 on `a9163c9b`: `./mvnw test` EXIT 0, 4175 tests, 0 failures (RetentionJobTest 23, RetentionJobPostgresTest 23, RetentionJobUnitTest 13).

## Review rounds

(appended by /do-task)
