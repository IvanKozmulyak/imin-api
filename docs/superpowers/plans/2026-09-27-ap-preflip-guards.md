# ap-preflip-guards — pre-flip send guards

Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md` (programme audit 2026-09-27 actions; follow-ups M3-7 review, M3-1). Mode: autonomous runner. Reproduction test: n-a (new behaviour behind flags and new guards).

## Goal and scope

Three pre-flip cards, api only:

- (a) Legal identity for every marketing campaign, behind new flag `imin.audience-plan.legal-identity-all-campaigns` (`IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS`, default false). When on, the M3-6 guard (409 `ORG_LEGAL_IDENTITY_MISSING` on send/schedule/retry, `claimDue` skip, sender fail, `ORG_LEGAL_IDENTITY_IN_USE` on clearing while queued) applies to every origin, not only `audience_plan`. Off = today's behaviour exactly. Also: composer preview shows the send-path skips (`experiment_holdout`, `event_cap`, `monthly_cap`, `consent_gate`) exactly as `RecipientMaterializer` applies them via `SendPathGuard.skipReasons`; the hub sendable count and the Momentum min-audience floor intersect SendGate with ConsentGate while `consent-gate-all-campaigns` is on.
- (b) Composer `PATCH` on an `origin='audience_plan'` campaign refuses a change of `segmentId` or `eventId` (409 `INVALID_STATE`); resubmitting the same value passes; `DELETE` of such a draft stays allowed (M3-1 reports `draftMissing`).
- (c) `docs/ops/pre-flip-counts.md`: read-only SQL for the pre-flip counts (per org SendGate-mailable but ConsentGate-blocked by reason; orgs without legal identity with live campaigns; historical `soft_opt_in` rows by type). Not run against prod.

Out of scope: webapp display of the new preview fields; flipping any flag; env changes.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-preflip-guards` | `./mvnw test` (full run via `/Users/ivan/.imin-pipeline/mvnlock.sh test`) |

## Affected files (per repo)

api:
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java` — `legalIdentityAllCampaigns` (Boolean, blank binds false)
- `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanAccess.java` — `legalIdentityAllCampaigns()`, `legalIdentityRequired(origin)`, `requireLegalIdentity` uses it
- `src/main/resources/application.yaml`, `src/test/resources/application.yaml` — new key
- `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java` — legal guard via access; PATCH lock on plan campaigns; preview applies `SendPathGuard.skipReasons`
- `src/main/java/com/imin/iminapi/marketing/dto/PreviewAudienceResponse.java` — `Excluded` + `experimentHoldout`, `eventCap`, `monthlyCap`, `consentGate` (6-arg constructor kept for the SendGate bucket)
- `src/main/java/com/imin/iminapi/marketing/repository/CampaignRepository.java` — `claimDue` param `legalIdentityAllCampaigns`; `existsByOrgIdAndStatusIn`
- `src/main/java/com/imin/iminapi/marketing/send/CampaignDispatcher.java` — pass the flag
- `src/main/java/com/imin/iminapi/marketing/send/EmailChannelSender.java` — sender fail for any origin when flag on
- `src/main/java/com/imin/iminapi/service/org/OrgService.java` — IN_USE for any queued campaign when flag on
- `src/main/java/com/imin/iminapi/audienceplan/service/AllCampaignsConsent.java` (new) — SendGate-sendable ∩ ConsentGate-mailable when `consent-gate-all-campaigns` is on
- `src/main/java/com/imin/iminapi/marketing/service/MarketingHubService.java`, `MomentumEvaluator.java` — use it
- `docs/ops/pre-flip-counts.md` (new); `CLAUDE.md` (env var line)
- tests: `AudiencePlanAccessTest`, `AudiencePlanLegalIdentityGuardTest`, `EmailChannelSenderIdentityTest`, `AudiencePlanLegalIdentityDispatchTest`, `CampaignClaimPostgresTest`, `OrgServiceTest`, `MarketingHubServiceTest`, `MomentumEvaluatorPlanTargetTest`, new `AllCampaignsConsentTest`, new `CampaignPreviewSendPathTest`, new `AudiencePlanCampaignPatchLockTest`

SendPathGuard is not edited (concurrent ap-consent-hardening).

## Ordered steps

1. Flag: property + both yamls + access helpers; tests default/blank/true/yaml/null-setter + `legalIdentityRequired` per origin × flag.
2. CampaignService send/retry: guard via `access.legalIdentityRequired(origin)`; tests flag on: manual and momentum 409 on send/schedule/retry and stay draft/failed; with identity schedule.
3. `claimDue` SQL: `(origin <> 'audience_plan' AND :legalIdentityAllCampaigns = FALSE) OR EXISTS(identity)`; dispatcher passes the flag; tests flag on: manual campaign of an org without identity not claimed, other org's sends; Postgres claim test updated.
4. EmailChannelSender: `access.legalIdentityRequired(origin)`; test flag on: manual without identity fails with rows pending.
5. OrgService: with flag on, any scheduled/sending campaign blocks clearing (`existsByOrgIdAndStatusIn`); tests both flag states.
6. PATCH lock (b): tests change segment → 409 unchanged; change event → 409; unlink (null) → 409; same values + other fields → 200; manual campaign retarget still allowed; delete plan draft → allowed.
7. Preview: sendGate.evaluate → bucket → `skipReasons(c, sendable, now)` → counts per reason, `sendable` reduced; tests one per reason plus manual-no-event unchanged plus flag-on consent_gate.
8. `AllCampaignsConsent`: flag off returns input untouched and never calls ConsentGate; on returns only ids with empty verdict (absent ids dropped). Hub + Momentum (plan target floor and Repeat floor) use it; tests flag on/off for each.
9. Docs SQL (c), validated on a local throwaway Postgres only.
10. CLAUDE.md env line for `IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS` and preview/hub/Momentum notes.

## Verification commands

- `/Users/ivan/.imin-pipeline/mvnlock.sh test` (= `./mvnw test`) from the worktree root.

## Test impact

New tests listed above; existing constructor-built tests (`OrgServiceTest`, `MarketingHubServiceTest`, `MomentumEvaluatorPlanTargetTest`, `CampaignControllerTest` unaffected via 6-arg constructor) updated for new constructor args. `CampaignClaimPostgresTest` gains the new bind param.

## Live-test

Not needed: every behaviour change is behind a default-false flag or covered by Spring integration tests on the real controller/service path; docs SQL validated on a local Postgres (never prod).

## Contract impact

/api/v1 — `PreviewAudienceResponse.excluded` gains `experimentHoldout`, `eventCap`, `monthlyCap`, `consentGate` (additive). Marker: `eventCap`. PATCH 409 uses existing `INVALID_STATE`; no new error codes.

## i18n impact

None in api. Webapp follow-up: render the four new preview counts (EN/ES/FR/UK).

## Blast radius

Flag off: send/schedule/retry/claim/sender/org-patch unchanged. Unconditional changes: (b) PATCH of `audience_plan` campaigns (only drafts created by invitations; no real sends yet, sends switch off) and the composer preview numbers (now subtract holdout/cap/consent skips that already happen at send — the preview gets more honest, `sendable` can drop for event campaigns). Hub/Momentum unchanged while `consent-gate-all-campaigns` is off.

## Risks

- Preview adds 3 queries per event campaign (chunked, same as materializer) — acceptable for a composer step.
- Docs SQL mirrors `ConsentGateSql` by hand and can drift; the doc names its source and date.
- Flipping `IMIN_LEGAL_IDENTITY_ALL_CAMPAIGNS` blocks every org without legal fields — run doc query 2 first.

## Definition of done

All steps implemented with tests per branch; full `./mvnw test` green; docs written; no env change; report delivered.

## Live-test evidence

n-a (see Live-test). Docs SQL validated by `PreFlipCountsSqlPostgresTest` on a Testcontainers Postgres 17 (all 4 blocks run; query 1 equals SendGate + ConsentGate on seeded members). Never run against prod.

Verification (rebased on origin/master 235fb05f): `mvnlock.sh clean test` → `Tests run: 5172, Failures: 0, Errors: 0, Skipped: 3`, `BUILD SUCCESS`.
Earlier run on c83debac: 5132 tests, 0 failures, 3 skipped, `BUILD SUCCESS`.
Baseline (412865e8, untouched): 5109 tests, 1 failure `CampaignSendCrashResumeTest` — order-dependent: a claimable campaign left by another dispatcher test was sent first. My first full run showed the same failure; `AudiencePlanLegalIdentityDispatchTest` now deletes its campaigns in `@AfterEach`, and the suite is green.

## Review rounds

(orchestrator)

- Round 1 → FIX_REQUIRED. Fixes: rebased onto origin/master 235fb05f (consent hardening + segment ranking); doc query 1 and query 3's `latest` CTE now carry ConsentGateSql's confirmation filter (`r.confirmation_required = FALSE OR r.confirmed_at IS NOT NULL`), the only ConsentGateSql change affecting them; `PreFlipCountsSqlPostgresTest` adds a member with an older legacy import and a newer unconfirmed door_qr record (query 1 = ConsentGate `legacy_unproven`; red without the filter: 2 vs 1); `AllCampaignsConsent.mailable` drops null ids in both flag states (new test).
