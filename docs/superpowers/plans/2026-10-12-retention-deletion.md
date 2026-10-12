
# Retention and deletion that match the published legal pack
retention-deletion · Subagent · Notion: none given

## Goal and scope

Make the promises in the published legal pack (imin-public `origin/main` bc655da, `content/legal/{fr,en}/{privacy,dpa}.md`; FR is binding) true in imin-api:

| # | Promise (FR binding text) | Where | State today | This plan |
|---|---|---|---|---|
| P1 | "Prospection (organisateur) : 3 ans à compter du dernier achat ou du dernier contact de votre part" / DPA Annex II "Marketing data: 3 years from the last purchase or last contact from the person" | privacy §3, dpa Annex II | `RetentionJob` only unsubscribes and clears taste (RetentionJob.java:134-142), only for subscribed members with a fresh fan-features row (ConsentGateSql.java:118-127), only for `AudiencePlanAccess`-enabled orgs (RetentionJob.java:101), and stays a dry run while `IMIN_AUDIENCE_RETENTION_ENABLED` is unset (RetentionJob.java:93,108). No membership is ever deleted. Campaign recipient rows keep email and rendered body forever (V53). | Phase A: delete memberships 3 years after the last contact, for every org; redact recipient rows older than 3 years; flip the flag |
| P2 | "la liste d'opposition est conservée 3 ans" | privacy §3, dpa Annex II | `marketing_optouts` is never deleted (V85) | Phase A: delete rows 3 years after `created_at` (see OPEN_QUESTIONS 2) |
| P3 | Survey answers: 3 years (survey notice `survey-notice-2026-10`, logic-v1.yaml:45) | survey notice | `SurveyRetentionJob` exists (SurveyRetentionJob.java:58-71) but is a dry run while the flag is unset | Phase A: flip only |
| P4 | "on termination, export then deletion within 60 days unless the law requires keeping" | dpa Annex II | There is no closure. `OrgService.delete` refuses any org with orders, tickets, settlements or payouts (OrgService.java:138-160). When it does delete an org it leaves `memberships`, `segments`, `campaigns`, `marketing_optouts`, `survey_responses` and `audience_imports` behind, because none of them has a foreign key to `organizations` (V48:3, V51, V52, V85, V134, V143) | Phase B: closure state, guards on every path into a closed org, purge at +30 days, and the untraded-org delete also purges audience rows |
| P5 | "Comptes organisateurs : durée du contrat, puis 5 ans en archive" | privacy §3 | No closure state, so the clock can never start | Phase B: 5 years after closure, anonymize org user accounts and contact fields |
| P6 | "Commandes et billets : … archivage pour les obligations légales (comptabilité : 10 ans ; litiges : durée de prescription)" | privacy §3 | Orders and tickets are kept (DsarScopeService.java:43-49) | Not deleted by anything in this plan. A 10-year deletion job is a follow-up; nothing is due before 2036 |

The pack does not contradict accounting retention. Privacy §2 keeps "factures" for 10 years under a legal obligation; §3 archives "comptes organisateurs" for 5 years. The plan reads the 5-year rule as covering account and login identity only. The org row (`name`, `legal_name`) and all money rows stay for the 10-year accounting period.

Out of scope, each stated in Risks: the DPA "CI scanner" clause (OPEN_QUESTIONS 1), the 12-month technical-log promise for `audit_logs` (OPEN_QUESTIONS 4), backups (OPEN_QUESTIONS 6), artist photos and R2 media, the 10-year order job, a self-serve closure endpoint, and the Stripe Connect account itself.

### Decisions
- D1 Three flags. `IMIN_AUDIENCE_RETENTION_ENABLED` (existing; RetentionJob stage 1 and survey deletion). New `IMIN_AUDIENCE_RETENTION_PURGE_ENABLED` (membership deletion, opt-out purge, recipient redaction). New `IMIN_ORG_CLOSURE_PURGE_ENABLED` (Phase B). All default `false`; blank binds false. Deletion is irreversible, so each deleting step is flipped separately.
- D2 The 3-year cutoff is one rule with one owner. A new `RetentionCutoff` computes `cutoffDate = LocalDate.now(clock in org zone) − legal.retention_days` and `cutoffAt = cutoffDate at start of day in the org zone`, falling back to UTC for a blank, invalid or missing zone. This is the formula in ConsentGate.java:252-254 and :258-266, which `ConsentGate.params` will call instead of its own copy.
- D3 A membership is due for deletion when it is not `erase_pending` (the DSAR job owns those) and `COALESCE(GREATEST(last contact dates), memberships.created_at) < cutoffAt`. The contact dates are: `memberships.last_purchase`, `memberships.last_attended`, `fan_features.last_contact_from_person_at`, the newest `orders.created_at` of the org for the consumer's normalized email (any order), the newest person-sourced subscribing email consent (`source IN personSources`, the same set as ConsentGate.java:249-250: `checkout`, `door_qr`, `survey` today), and the newest `import_row_provenance.last_purchase_date` taken as start of day in the org zone. `created_at` counts only when no contact date is known (unknown age = age of collection).
- D4 Deleting a membership removes its dependent rows: marketing suppressions, campaign recipient rows (pending ones diverted, then redacted), `fan_features`, import provenance, experiment assignments, the membership itself (consent records and confirmation tokens cascade), and the consumer if no membership is left and no verified buyer-account address anchors it. Unlike DSAR, it writes no erasure-ledger row (`erased_addresses`, which would block the person's future door/survey consent; ConsentConfirmationService.java:142, DoorOptInService.java:164) and no per-member audit row; one `AUDIENCE_RETENTION_PURGED` audit row per org per run carries counts only. `notify_subscriptions` are not touched: a notify-me sign-up is a recent action with its own life cycle.
- D5 Recipient redaction: rows with `status <> 'pending'` of campaigns with `status IN ('sent','canceled','failed')` and `COALESCE(sent_at, created_at) < cutoffAt` get `email`, `phone_e164` and `rendered_body` set to null. Same columns as `CampaignRecipientRepository.redactPiiByMembershipId` (CampaignRecipientRepository.java:457-462).
- D6 Closure is done by support: `docs/ops/org-closure.md` checks preconditions and writes `organizations.closed_at`. The org can be cancelled while `closed_at > now() − 29 days`. The purge runs once `closed_at <= now − 30 days`, and an ERROR log fires at 45 days if the org is still unpurged (whatever the flag says). DPA limit: 60 days.
- D7 Five years after closure, the org's users are anonymized: email becomes `purged-<userId>@purged.invalid`, names are blanked, the password hash is cleared, and identities, sessions and tokens are deleted. `organizations.contact_email` becomes `purged-<orgId>@purged.invalid` and `legal_contact` is set to null. `name`, `legal_name`, `slug` and every money row stay.
- D8 The batch sizes and grace periods (30 days, 45 days, 5 years, 500 per batch, 5,000 memberships per run) are Java constants, not configuration, so no loader validation or CHECK alignment is needed.

### Copy ledger
Phase A adds organizer-facing strings in imin-webapp: labels for two recipient skip reasons, which `RecipientsTable` shows on a `skipped` row through `describeReason` (imin-webapp `src/features/marketing/exclusions.ts`; before this, unknown tokens rendered raw). Both tokens are written only when a recipient row was still `pending` (queued) when its membership was deleted (`CampaignRecipientRepository.divertPendingForDeletedMembership`, `status='pending'` only). Other new API error messages are not shown: the webapp maps unknown codes to `copy.errors.genericMutation` (imin-webapp `src/shared/api/humanizeError.ts:9-12,75-80` on `origin/main`), and audit rows are not rendered anywhere in the webapp (`git grep DSAR_ERASE_EXECUTED` on imin-webapp `origin/main` src finds nothing).

| String (copy key, `copy.sendGatePanel`) | EN / ES / FR / UK | Field and every writer (api file:line) | Meaning, scope | Rendered next to |
|---|---|---|---|---|
| `dsarErasedLabel` | Erased from your audience (data erasure) / Borrado de tu audiencia (supresión de datos) / Effacé de votre audience (effacement des données) / Стерто з вашої аудиторії (стирання даних) | `campaign_recipients.skip_reason = 'dsar_erased'`, set by `CampaignRecipientRepository.divertPendingForErasedMembership` (:440) from `DsarService.executeErase` (DsarService.java:319), reached by (a) `AudienceErasureJob.run` (AudienceErasureJob.java:67) after an organizer erase request `POST /audience/members/{id}/erase` (AudienceController.java:305-309, `DsarService.requestErase`) and its 30-day grace, and (b) a buyer deleting their imin account (BuyerAccountErasureService.java:202) | The member was erased from this org's audience, by the organizer or by the buyer, while this row was queued; not always the person's own request, so the copy does not say so | The recipient row's status chip in `RecipientsTable` (skipped rows) |
| `dsarErasedDesc` | This person’s data was erased from your audience before this message went out / Los datos de esta persona se borraron de tu audiencia antes de que saliera este mensaje / Les données de cette personne ont été effacées de votre audience avant l’envoi de ce message / Дані цієї людини стерто з вашої аудиторії до того, як вийшло це повідомлення | same | same | The reason's description (tooltip/secondary line) |
| `retentionPurgedLabel` | No purchase or contact from them in 3 years (data retention) / Sin compras ni contacto suyo en 3 años (conservación de datos) / Aucun achat ni contact de sa part depuis 3 ans (durée de conservation) / Жодної покупки чи контакту від неї за 3 роки (строк зберігання даних) | `campaign_recipients.skip_reason = 'retention_purged'`, set only by `MembershipGraphDeleter.delete` (MembershipGraphDeleter.java:72, `SKIP_REASON`) from `AudienceRetentionPurgeJob` while `IMIN_AUDIENCE_RETENTION_PURGE_ENABLED` is on | The purge found no purchase, attendance, order, contact from the person, person-sourced email consent or imported purchase in the org-zone 3-year window (`RetentionPurgeSql`); the organizer's own campaigns never count as contact | The recipient row's status chip in `RecipientsTable` |
| `retentionPurgedDesc` | Deleted under the 3-year rule before this message went out; your own campaigns don’t count as contact / Se eliminó por la regla de 3 años antes de que saliera este mensaje; tus propias campañas no cuentan como contacto / Supprimé selon la règle des 3 ans avant l’envoi de ce message ; vos propres campagnes ne comptent pas comme un contact / Видалено за правилом 3 років до того, як вийшло це повідомлення; ваші власні кампанії не вважаються контактом | same | same | The reason's description |

## Repos in ship order
1. `api` (imin-api, base `master`). Phase A ships first; Phase B ships second, either as its own task (recommended, see Risks) or in the same release once Phase A is green.

2. `webapp` (imin-webapp, base `main`), worktree `/Users/ivan/imin/imin-webapp/.claude/worktrees/retention-deletion`: skip-reason labels for `dsar_erased` and `retention_purged` (`src/features/marketing/exclusions.ts`, `src/shared/copy{,.es,.fr,.uk}.ts`, `exclusions.test.ts`). No contract change, so it does not wait for the api deploy; `dsar_erased` rows already exist in prod. Gate: `npm run typecheck && npm run lint && npm test && npm run check:i18n` (plus `npm run api:check`).

No public, gate or fan-app change.

## Affected files (per repo)

### imin-api: Phase A
| File | Change |
|---|---|
| `src/main/java/com/imin/iminapi/audienceplan/service/RetentionCutoff.java` (new) | `@Component`. `record Cutoff(LocalDate date, Instant at, ZoneId zone)` and `Cutoff forOrg(UUID orgId)`: the D2 rule, reading `OrganizationRepository`, `AudiencePlanLogic`, `Clock`. |
| `src/main/java/com/imin/iminapi/audienceplan/service/ConsentGate.java` | `params()` (:238-256) gets `cutoffAt`/`cutoffDate` from `RetentionCutoff`; `orgZone` (:258-266) moves there. No behaviour change. |
| `src/main/java/com/imin/iminapi/audience/service/MembershipGraphDeleter.java` (new) | `@Component`, `@Transactional boolean delete(UUID orgId, UUID membershipId, BooleanSupplier stillDue)`: (1) `lockByIdAndOrgId` first, the projector's lock order (DsarService.java:305-306); (2) return false if missing or `!stillDue`; (3) the D4 cascade in the order DsarService.java:311-376 uses it, without the notify, scope, ledger and audit steps. Reuses `suppressionRepo.deleteMarketingByOrgAndMembership`, `campaignRecipientRepo.divertPendingForErasedMembership` + `redactPiiByMembershipId`, `fanFeatureRepo.deleteByMembershipId`, `provenanceRepo.deleteByMembershipId`, `assignmentRepo.deleteByMembershipId`, `membershipRepo.deleteByIdAndOrgId`, `consumerRepo.countMembershipsByConsumerId` / `deleteByConsumerId`, `buyerAccountEmailRepo.existsByVerifiedKey`. `DsarService.executeErase` is left unchanged so the DSAR path is not touched; the overlap is noted in Risks. |
| `src/main/java/com/imin/iminapi/audience/repository/RetentionPurgeSql.java` (new) | Native SQL for D3: `CANDIDATES` (by org, `ORDER BY m.created_at, m.membership_id LIMIT :limit`) and `CANDIDATES_AMONG` (`AND m.membership_id IN (:ids)`), with params `:orgId, :cutoffAt, :cutoffDate, :personSources, :tz`. |
| `src/main/java/com/imin/iminapi/audience/repository/MembershipRepository.java` | `findRetentionPurgeCandidates(...)`, `findRetentionPurgeCandidatesAmong(...)`, `@Query("select distinct m.orgId from Membership m") List<UUID> findDistinctOrgIds()`. |
| `src/main/java/com/imin/iminapi/audience/repository/MarketingOptOutRepository.java` | `@Modifying` `int deleteByOrgIdAndCreatedAtBefore(UUID orgId, Instant cutoff)`, `long countByOrgIdAndCreatedAtBefore(...)`, `List<UUID> findDistinctOrgIds()`. |
| `src/main/java/com/imin/iminapi/marketing/repository/CampaignRecipientRepository.java` | Native `@Modifying int redactPiiForCampaignsEndedBefore(UUID orgId, Instant cutoff)` and its `count…` twin (D5). |
| `src/main/java/com/imin/iminapi/audience/service/AudienceRetentionPurgeJob.java` (new) | `@Scheduled(cron = "0 15 4 * * *", zone = "Europe/Paris")` wrapper calling `self.getObject().run()` (the RetentionJob.java:81-89 pattern), plus `@SchedulerLock(name = "audience_retention_purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M") Result run()`. Loops over `membershipRepo.findDistinctOrgIds()` ∪ `optOutRepo.findDistinctOrgIds()` filtered by a constructor `Predicate<UUID> orgFilter` (`id -> true` in the `@Autowired` constructor; tests pass their own orgs). No `AudiencePlanAccess` gate. Per org: compute the cutoff; for memberships, fetch candidates in pages of 500 (cap 5,000 per run) and call `deleter.delete(org, id, () -> !repo.findRetentionPurgeCandidatesAmong(..., List.of(id)).isEmpty())`, each in its own transaction, catching per id; then delete opt-outs and redact recipients. While the purge flag is false it only counts. Writes one `AUDIENCE_RETENTION_PURGED` audit row per org that had work (system principal as AudienceErasureJob.java:51-53). Logs one summary line `AudienceRetentionPurgeJob: done|dry run, orgs= memberships expired= deleted= failed= capped= optOuts expired= deleted= recipientRows expired= redacted=`: ERROR with the last throwable when failed > 0 and nothing was deleted, WARN on partial failure or cap, else INFO. `Result` record with the same counts. |
| `src/main/java/com/imin/iminapi/repository/OrderRepository.java` | `findDistinctOrgAndEmailPairs` (:260-261) becomes `select o.orgId, lower(o.email), max(o.createdAt) from Order o where o.email is not null group by o.orgId, lower(o.email)`. Its only caller is AudienceBackfillJob.java:81 (grep confirmed). |
| `src/main/java/com/imin/iminapi/audience/service/AudienceBackfillJob.java` | Inject `Clock` and `RetentionCutoff`. Skip a pair whose newest order is before `cutoff.at` for that org (cache the cutoff per org per run); count it as `skippedPastRetention` in the done line (:110-111). |
| `src/main/java/com/imin/iminapi/audience/service/AudienceOrderProjector.java` | `restorable` (:253-265) returns false when `order.getCreatedAt()` is before `retentionCutoff.forOrg(orgId).at()`: an archived order's checkout tick is never replayed. |
| `src/main/java/com/imin/iminapi/audienceplan/config/AudiencePlanProperties.java` | `retentionPurgeEnabled` (default `FALSE`, setter `Boolean.TRUE.equals`), javadoc as at :34-35. |
| `src/main/resources/application.yaml` | Under `imin.audience-plan` next to :962: `retention-purge-enabled: ${IMIN_AUDIENCE_RETENTION_PURGE_ENABLED:false}` with a one-line comment. |
| `src/main/java/com/imin/iminapi/service/audit/AuditActions.java` | `AUDIENCE_RETENTION_PURGED`. |
| `.env.example` | `IMIN_AUDIENCE_RETENTION_PURGE_ENABLED=false` with a one-line comment. |
| `CLAUDE.md` | Under the `IMIN_AUDIENCE_RETENTION_ENABLED` bullet (:76), a new bullet for `IMIN_AUDIENCE_RETENTION_PURGE_ENABLED`: job, cron, ShedLock name, D3 rule, what is kept, flag behaviour. |
| `docs/ops/retention-counts.md` (new) | Read-only SQL blocks R1-R5 (see Verification commands), how to run them read-only, and what each mirrors. |
| `docs/privacy/retained-after-erasure.md` | New section "Retention purge and account closure": what each purge removes and keeps. |

### imin-api: Phase B
| File | Change |
|---|---|
| `src/main/resources/db/migration/V185__org_closure.sql` (new; re-pick the number per CLAUDE.md:334) | `ALTER TABLE organizations ADD COLUMN closed_at TIMESTAMPTZ, ADD COLUMN purged_at TIMESTAMPTZ, ADD COLUMN archive_purged_at TIMESTAMPTZ`; `ADD CONSTRAINT ck_org_purge_after_close CHECK (purged_at IS NULL OR (closed_at IS NOT NULL AND purged_at >= closed_at))`; `ADD CONSTRAINT ck_org_archive_after_purge CHECK (archive_purged_at IS NULL OR purged_at IS NOT NULL)`; `CREATE INDEX ix_organizations_closed_at ON organizations (closed_at) WHERE closed_at IS NOT NULL`. Additive and nullable; no data change. |
| `src/main/java/com/imin/iminapi/model/Organization.java` | Three fields, `@Column(insertable = false, updatable = false)`, so a full-entity save (OrgService patch, Stripe mirror) can never revert them. |
| `src/main/java/com/imin/iminapi/repository/OrganizationRepository.java` | Scalar reads `closedAt(UUID)` / `purgedAt(UUID)`; `@Modifying` `markPurged(id, now)` and `markArchivePurged(id, now)` (each `WHERE … IS NULL`); `lockClosedAt(id)` = native `SELECT closed_at … FOR UPDATE` returning the scalar (CLAUDE.md:298); candidate queries `findPurgeDue(cutoff)`, `findArchiveDue(cutoff)`, `findOverdueUnpurged(cutoff)`. |
| `src/main/java/com/imin/iminapi/service/org/OrgClosureState.java` (new) | `boolean isClosed(UUID orgId)` (closed_at not null), `boolean isPurged(UUID orgId)`, `void requireOpen(UUID orgId)` → 409 `ORG_CLOSED`. |
| `src/main/java/com/imin/iminapi/service/org/OrgDataPurger.java` (new) | JdbcTemplate statements scoped to one org id, in this order: campaigns (cascades recipients and AI suggestions; experiments get `campaign_id` set null), `audience_experiments` (cascades assignments and outcomes), `momentum_suggestions`, `segments`, `campaign_email_templates`, `audience_imports` (cascades provenance), `suppression_entries WHERE scope='marketing' AND org_id`, memberships via `MembershipGraphDeleter.delete(org, id, () -> true)` in pages of 500, `marketing_optouts`, `erased_addresses WHERE org_id = ?`, `survey_responses`, `notify_subscriptions` of the org's events, `event_funnel_events` of the org's events, `meta_capi_events`, `meta_pixel_connections`, `gate_sessions`, `gate_credentials`, `notifications` of org users; then `revokeAllForUser` for each org user. Returns counts per table. Never touches `orders`, `tickets`, `refunds`, `refund_requests`, `disputes`, `settlements`, `payout_runs`, `events`, `ticket_tiers`, `audit_logs`, `organizations`, `users`. Also `archive(orgId)` for D7, including `auditLogs.redactActorEmail(orgId, email)` per user (DsarScopeService.java:177). |
| `src/main/java/com/imin/iminapi/service/org/OrgClosurePurgeJob.java` (new) | `@Scheduled(cron = "0 30 3 * * *", zone = "Europe/Paris")` and `@SchedulerLock(name = "org_closure_purge", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")`. Candidates: (a) `closed_at <= now − 30 d AND purged_at IS NULL`; (b) org ids found in `memberships`, `marketing_optouts`, `survey_responses`, `campaigns`, `segments` or `audience_imports` with no `organizations` row (left by `OrgService.delete`); (c) `purged_at` set, `archive_purged_at IS NULL` and `closed_at + 5 y <= now` (UTC calendar). For (a), each batch transaction first re-reads `lockClosedAt` and stops if it is null or not yet due. `purged_at` is set only after every step has succeeded. ERROR log for each org with `closed_at <= now − 45 d AND purged_at IS NULL`, flag or not. Dry run when the flag is false. Audit `ORG_DATA_PURGED` / `ORG_ARCHIVE_PURGED` with counts. Constructor takes a `Predicate<UUID> orgFilter` for tests. |
| `src/main/java/com/imin/iminapi/service/org/OrgClosureProperties.java` (new) | `@ConfigurationProperties("imin.org-closure")`, `purgeEnabled` default false, blank binds false; registered the way the other properties classes are (the worker greps `@EnableConfigurationProperties`). |
| `src/main/java/com/imin/iminapi/service/org/OrgService.java` | `delete` (:138-148) calls `orgDataPurger.purge(orgId)` before `orgs.deleteById`; the class javadoc (:118-136) now describes closure. |
| `src/main/java/com/imin/iminapi/security/ErrorCode.java` | `ORG_CLOSED`. |
| `src/main/java/com/imin/iminapi/service/audit/AuditActions.java` | `ORG_DATA_PURGED`, `ORG_ARCHIVE_PURGED`. |
| `src/main/java/com/imin/iminapi/service/event/EventService.java` | `publish` (:324) → `closure.requireOpen(p.orgId())` first. |
| `src/main/java/com/imin/iminapi/marketing/service/CampaignService.java` | `send` (:448) and `retry` (:698) → `requireOpen` first. |
| `src/main/java/com/imin/iminapi/marketing/repository/CampaignRepository.java` | `claimDue` (:190-219): add `AND o.closed_at IS NULL` to the existing `EXISTS (… o.marketing_paused_at IS NULL)` (:209-210). |
| `src/main/java/com/imin/iminapi/audience/service/AudienceImportService.java` | `importContacts` (:126, :132) → `requireOpen`. |
| `src/main/java/com/imin/iminapi/audienceplan/service/SurveyService.java` | `page` (:149) and `submit` (:157): a closed org gets the same 404 as the other refusals. |
| `src/main/java/com/imin/iminapi/audienceplan/service/DoorOptInService.java` | `page` (:143) and `optIn` (:151): same 404. |
| `src/main/java/com/imin/iminapi/audience/service/ConsentConfirmationService.java` | `confirm` (:109): a closed org answers `invalid` and confirms nothing (the token is still burned). `preview` (:96): `invalid`. |
| `src/main/java/com/imin/iminapi/audience/service/AudienceBackfillJob.java` | Skip pairs whose org is closed (load the closed set once per run). |
| `src/main/java/com/imin/iminapi/audience/service/AudienceOrderProjector.java` | `project` (:98): return early for a closed org. |
| `src/main/java/com/imin/iminapi/audience/service/AudienceRedeemProjector.java` | `onTicketRedeemed` (:51): return early for a closed org. |
| `src/main/java/com/imin/iminapi/service/auth/AuthService.java` | `issueSession` (:274-289): refuse with the same 401 `AUTH_INVALID_CREDENTIALS` as a disabled user when the user's org is purged. Covers `login` (:154) and `verifyEmail` (:193), which both mint here. |
| `src/main/java/com/imin/iminapi/oauth/OAuthAccountService.java` | `issueSession` (:226-241): same check. |
| `src/main/resources/application.yaml`, `.env.example`, `CLAUDE.md` | `imin.org-closure.purge-enabled: ${IMIN_ORG_CLOSURE_PURGE_ENABLED:false}`; env line; CLAUDE.md bullet (job, cron, ShedLock name, grace periods, what is kept, the 45-day ERROR). |
| `docs/ops/org-closure.md` (new) | Precondition SQL, the close statement, the cancel statement (`… AND purged_at IS NULL AND closed_at > now() - interval '29 days'`), what happens on which day. |
| `docs/ops/retention-counts.md` | Block R5 (orphan-org audience rows). |

Files named in Blast radius that need no change: `DsarService.java` (DSAR path stays as it is; D4), `RetentionJob.java` (stage 1 unchanged), `SurveyRetentionJob.java` (flip only), `FanFeatureProjector.java`, `PlanRefreshJob.java` and `OutcomeCollector.java` (read memberships only; after a purge they find none), `BearerTokenAuthFilter.java` (purge revokes every session, and the purge sets `purged_at` before revoking, so a session minted afterwards is refused at `issueSession`), `DsarServiceLockOrderTest.java` (DsarService constructor unchanged).

## Ordered steps

### Phase A
1. Run the baseline gate: `docker info && ./mvnw test` on the untouched worktree, and record the result. A red baseline is its own card.
2. `RetentionCutoff` + `RetentionCutoffTest` (unit). Point `ConsentGate.params` at it. `RetentionJobIntegrationTest` and `RetentionJobUnitTest` must stay green unedited: they read only `logic.legal().retentionDays()` and the gate's behaviour, which does not change (grep confirms neither touches `orgZone` or `params`).
3. `RetentionPurgeSql` + `MembershipRepository` queries. Write `docs/ops/retention-counts.md` block R2 with the same predicate set (D3), so the SQL test in step 8 can pin agreement.
4. `MembershipGraphDeleter` + `MembershipGraphDeleterTest` (unit: lock before fan-features delete; `stillDue=false` deletes nothing).
5. `MarketingOptOutRepository` and `CampaignRecipientRepository` methods; `AudienceRetentionPurgeJob`; `AudiencePlanProperties` flag + yaml + `.env.example` + CLAUDE.md + `AuditActions`.
6. Backfill guards: the `OrderRepository` query shape, `AudienceBackfillJob` skip, `AudienceOrderProjector.restorable` cutoff.
7. Tests T3-T5 and T7. For each keep-branch in T3 and each guard in T5, delete the guarding predicate once, confirm red, restore. Note in the PR body which line was removed for which test.
8. `docs/ops/retention-counts.md` blocks R1-R4 + `RetentionCountsSqlPostgresTest` (T6).
9. Update `docs/privacy/retained-after-erasure.md`. Run the full gate (see Verification commands).

### Phase B
10. V185 migration (re-pick the number against `git ls-tree origin/master src/main/resources/db/migration/`), the `Organization` fields, the `OrganizationRepository` methods.
11. `OrgClosureState`, `ErrorCode.ORG_CLOSED`; guards on every path that can add personal data or send to a closed org's audience. Write paths: `EventService.publish`, `CampaignService.send`/`retry`, `AudienceImportService.importContacts`, `CampaignRepository.claimDue`. Public capture: `SurveyService.page`/`submit`, `DoorOptInService.page`/`optIn`, `ConsentConfirmationService.preview`/`confirm`. Projection: `AudienceBackfillJob`, `AudienceOrderProjector.project`, `AudienceRedeemProjector.onTicketRedeemed`. Sign-in: `AuthService.issueSession`, `OAuthAccountService.issueSession`. Checkout is not guarded: closure requires no LIVE future event (runbook precondition 1) and `publish` is refused, so no closed org has a buyable event. Momentum, invitations and invite-on-publish create drafts only, and sending them goes through the guarded `send`/`claimDue`.
12. `OrgDataPurger`, `OrgClosurePurgeJob`, `OrgClosureProperties` + yaml + env + CLAUDE.md + `AuditActions`; wire `OrgService.delete` to `OrgDataPurger.purge` and edit `OrgServiceTest`.
13. `docs/ops/org-closure.md` with these preconditions, one read-only SQL each, all must return 0 rows:
  1. LIVE, non-deleted events of the org with `COALESCE(ends_at, starts_at) > now()`.
  2. `ticket_reservations` with status `HELD` on the org's events.
  3. Refunds not terminal. The worker binds the open set from `RefundAttemptReconciler` and V179:9.
  4. Disputes not closed (`DisputeRepository` statuses), and the org absent from `findOrgIdsOwingDisputeMoney` (DisputeRepository.java:189).
  5. When `stripe_payout_schedule_manual`, ended events with `revenue_minor > 0` and no `PAID` payout run (the `EventRepository.findRetentionMonitorCandidates` predicate, EventRepository.java:675-696, without the cutoff).
  6. Campaigns with status `sending`.
  
  Then add R5 to `retention-counts.md` and bump T6's block count to 5.
14. Tests T8-T12; prove each guard red as in step 7. Run the full gate.

## Verification commands
- `docker info` (must succeed before the gate)
- `cd /Users/ivan/imin/imin-api/.claude/worktrees/retention-deletion && ./mvnw test`. Green only if the `Tests run:` line shows no skipped Testcontainers classes.
- Targeted: `./mvnw test -Dtest=RetentionCutoffTest,MembershipGraphDeleterTest,AudienceRetentionPurgeJobIntegrationTest,AudienceBackfillRetentionTest,RetentionCountsSqlPostgresTest,AudiencePlanAccessTest` (comma-separated; judge only its own `Tests run:` line).
- Before review and again in `/ship-imin`: `git fetch && git ls-tree origin/master src/main/resources/db/migration/`. Renumber V185 if needed, then `./mvnw clean`.

### Read-only prod counts (Ivan runs; `railway connect` → `psql` → `SET default_transaction_read_only = on;`)
Run after Phase A deploys, before any flip. Each block is plain `SELECT` and is checked by T6 against the job's own count.

R1: survey answers `SurveyRetentionJob` would delete (mirrors SurveyRetentionJob.java:61-62, UTC midnight on purpose):
```sql
SELECT count(*) AS survey_answers_past_window
FROM survey_responses
 WHERE created_at < (date_trunc('day', now() AT TIME ZONE 'UTC') - interval '1095 days') AT TIME ZONE 'UTC';
```

R2: memberships the purge would delete, per org (D3; zone fallback UTC; an invalid zone name makes Postgres error where the app falls back to UTC, so first run `SELECT DISTINCT timezone FROM organizations`):
```sql
WITH org_cut AS (
  SELECT x.org_id, x.tz,
         ((now() AT TIME ZONE x.tz)::date - 1095) AS cutoff_date,
         (((now() AT TIME ZONE x.tz)::date - 1095)::timestamp AT TIME ZONE x.tz) AS cutoff_at
    FROM (SELECT DISTINCT m.org_id, COALESCE(NULLIF(TRIM(o.timezone), ''), 'UTC') AS tz
            FROM memberships m LEFT JOIN organizations o ON o.id = m.org_id) x
)
SELECT m.org_id, count(*) AS memberships_to_delete
FROM memberships m
JOIN org_cut oc ON oc.org_id = m.org_id
JOIN consumers c ON c.consumer_id = m.consumer_id
LEFT JOIN fan_features f ON f.membership_id = m.membership_id
 WHERE m.status <> 'erase_pending'
 AND COALESCE(GREATEST(
       m.last_purchase,
       m.last_attended,
       f.last_contact_from_person_at,
       (SELECT max(o.created_at) FROM orders o
         WHERE o.org_id = m.org_id AND o.email_normalized = c.normalized_email),
       (SELECT max(r.occurred_at) FROM consent_records r
         WHERE r.membership_id = m.membership_id AND r.channel = 'email' AND r.status = 'subscribed'
           AND r.source IN ('checkout', 'door_qr', 'survey')),
       (SELECT max(p.last_purchase_date)::timestamp AT TIME ZONE oc.tz FROM import_row_provenance p
         WHERE p.membership_id = m.membership_id)),
     m.created_at) < oc.cutoff_at
 GROUP BY m.org_id
 ORDER BY m.org_id;
```

R3: opposition-list rows the purge would delete, per org:
```sql
WITH org_cut AS (
  SELECT x.org_id,
         (((now() AT TIME ZONE x.tz)::date - 1095)::timestamp AT TIME ZONE x.tz) AS cutoff_at
    FROM (SELECT DISTINCT mo.org_id, COALESCE(NULLIF(TRIM(o.timezone), ''), 'UTC') AS tz
            FROM marketing_optouts mo LEFT JOIN organizations o ON o.id = mo.org_id) x
)
SELECT mo.org_id, count(*) AS opt_outs_to_delete
FROM marketing_optouts mo
JOIN org_cut oc ON oc.org_id = mo.org_id
 WHERE mo.created_at < oc.cutoff_at
 GROUP BY mo.org_id
 ORDER BY mo.org_id;
```

R4: campaign recipient rows the purge would redact, per org:
```sql
WITH org_cut AS (
  SELECT x.org_id,
         (((now() AT TIME ZONE x.tz)::date - 1095)::timestamp AT TIME ZONE x.tz) AS cutoff_at
    FROM (SELECT DISTINCT c.org_id, COALESCE(NULLIF(TRIM(o.timezone), ''), 'UTC') AS tz
            FROM campaigns c LEFT JOIN organizations o ON o.id = c.org_id) x
)
SELECT c.org_id, count(*) AS recipient_rows_to_redact
FROM campaign_recipients r
JOIN campaigns c ON c.id = r.campaign_id
JOIN org_cut oc ON oc.org_id = c.org_id
 WHERE c.status IN ('sent', 'canceled', 'failed')
 AND r.status <> 'pending'
 AND COALESCE(c.sent_at, c.created_at) < oc.cutoff_at
 AND (r.email IS NOT NULL OR r.phone_e164 IS NOT NULL OR r.rendered_body IS NOT NULL)
 GROUP BY c.org_id
 ORDER BY c.org_id;
```

R5 (Phase B): audience rows left by deleted orgs, which the closure purge will remove:
```sql
SELECT 'memberships' AS tbl, count(*) AS orphan_rows FROM memberships t WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = t.org_id)
UNION ALL SELECT 'marketing_optouts', count(*) FROM marketing_optouts t WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = t.org_id)
UNION ALL SELECT 'survey_responses', count(*) FROM survey_responses t WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = t.org_id)
UNION ALL SELECT 'campaigns', count(*) FROM campaigns t WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = t.org_id)
UNION ALL SELECT 'segments', count(*) FROM segments t WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = t.org_id)
UNION ALL SELECT 'audience_imports', count(*) FROM audience_imports t WHERE NOT EXISTS (SELECT 1 FROM organizations o WHERE o.id = t.org_id);
```

Stage 1 (unsubscribe, no deletion) and the survey job already log their exact dry-run counts every night in prod. In Railway logs, search for `RetentionJob: dry run,` (04:00 Paris) and `SurveyRetentionJob: dry run,` (04:30 Paris). R1 should equal the second line's number.

### Flip runbook
0. Phase A deployed (both flags false). Wait one night; read the `RetentionJob`, `SurveyRetentionJob` and new `AudienceRetentionPurgeJob: dry run` lines.
1. Run R1-R4. Each total must equal the matching dry-run figure (R2 = `memberships expired`, R3 = `optOuts expired`, R4 = `recipientRows expired`, R1 = survey `expired`). Stop and investigate on any mismatch.
2. Railway: set `IMIN_AUDIENCE_RETENTION_ENABLED=true` (a single save is a deploy). Next morning check `RetentionJob: done` and `SurveyRetentionJob: done … deleted` (= R1).
3. After one clean night, set `IMIN_AUDIENCE_RETENTION_PURGE_ENABLED=true`. Next morning check `AudienceRetentionPurgeJob: done`, `failed=0`, then re-run R2-R4: each should be 0 for every org not `capped`.
4. Phase B deployed: run R5, set `IMIN_ORG_CLOSURE_PURGE_ENABLED=true`, next morning check `OrgClosurePurgeJob: done`, re-run R5 = 0.

## Test impact

Branches of the changed logic: purge candidate (expired vs each keep-reason vs erase_pending vs no-contact age), zone (Paris vs UTC day), deleter (still due vs no longer due; consumer orphan vs anchored vs shared), opt-out (before vs on cutoff day; org scope), recipient (old vs recent campaign; pending vs not), flag (dry vs enforce), per-id failure isolation, backfill (old pair vs recent pair; archived vs recent consent), closure guards (each path), purge (due vs not due vs cancelled vs orphan org; kept money rows), archive (due vs not due), sign-in (grace vs purged).

| # | File | Kind | "What gets a test" item | Test → bug it catches |
|---|---|---|---|---|
| T1 | `src/test/java/com/imin/iminapi/audienceplan/service/RetentionCutoffTest.java` (new) | unit | timezone; consent | Parameterized. Clock `2026-10-12T23:30:00Z`, org `Europe/Paris` → `cutoffDate = 2023-10-14`, `cutoffAt = 2023-10-13T22:00:00Z`; blank, invalid and missing org zone → UTC: `2023-10-13`, `2023-10-13T00:00:00Z`. Source: 2023-10-13 → 2026-10-13 is 1096 days (2024 is a leap year), so the Paris date 2026-10-13 − 1095 = 2023-10-14; the CEST offset +02:00 on 2023-10-14 and 2026-10-13 comes from IANA tzdata (EU DST ends on the last Sunday of October). Bug: a buyer is deleted (or kept) a day early/late around midnight. Red check: compute in UTC. |
| T2 | `src/test/java/com/imin/iminapi/audience/service/MembershipGraphDeleterTest.java` (new) | unit | erasure; concurrency (lock order) | (a) `InOrder`: `lockByIdAndOrgId` before `fanFeatureRepo.deleteByMembershipId`. Bug: the nightly purge deadlocks against a recompute and fails. (b) `stillDue=false` → no delete call. Bug: a member who bought a ticket a minute after the scan is deleted. (c) consumer kept when `existsByVerifiedKey` is true. Bug: a buyer's IMIN account loses its identity. Red check: drop the lock call / the `stillDue` check / the anchor check. |
| T3 | `src/test/java/com/imin/iminapi/audience/service/AudienceRetentionPurgeJobIntegrationTest.java` (new) | integration (`@IminIntegrationTest`, hand-built job with an org filter, real repositories, backdated fixtures via `JdbcTemplate`, real clock except the zone case) | consent, DSAR, erasure, suppression; scheduled job effect | (a) Expired member → membership, consent records, fan-features, provenance, assignments and marketing suppression gone; recipient row redacted; orphan consumer gone; `AuditRows.assertRecorded(orgId, AUDIENCE_RETENTION_PURGED, "org", orgId)`. Bug: a 3-year-old contact list survives, contradicting the privacy policy. (b) `@ParameterizedTest` keep-reasons, each kept member created before the expired one so it would come back first: recent order, recent `checkout`/`door_qr`/`survey` consent, provenance `last_purchase_date` exactly on `cutoffDate`, recent `fan_features.last_contact_from_person_at`, recent `last_attended`, no contact and `created_at` within the window, `erase_pending`. Bug: an active customer's record is deleted. Red check: remove each predicate in turn. (c) Zone: org `Europe/Paris`, job clock = today UTC at 23:30Z, only order at (today UTC − 1095 d) 12:00Z → deleted. Bug: off-by-one-day deletion. Red check: compute the cutoff in UTC. (d) Same consumer in two orgs, one expired → only that org's membership goes, consumer stays. Bug: one organizer's retention deletes another organizer's customer. (e) Flag false → result counts the member and the member still exists. Bug: the dry run deletes. (f) `PgFaults.failWrites(jdbc, "campaign_recipients", "membership_id", id1)` → id1 kept, id2 deleted, `failed=1`. Bug: one bad row stops the whole purge for every org. (g) Opt-outs: a row created the day before `cutoffDate` is deleted; a row on `cutoffDate` (inserted first) is kept; another org's row for the same email is kept. Bug: a refusal is forgotten early, or kept forever. (h) Recipients: a non-pending row of a sent campaign older than the cutoff is redacted; a pending row, and rows of a campaign within the window, are untouched. Bug: old campaign bodies with buyers' emails are kept forever, or an in-flight send loses its address. |
| T4 | `src/test/java/com/imin/iminapi/audience/AudienceBackfillRetentionTest.java` (new) | integration | erasure; consent | (a) A pair whose only order is older than the cutoff, membership deleted → after `AudienceBackfillJob.run()` there is still no membership; a second pair with a recent order is projected (positive interaction). Bug: deleted contacts reappear next morning. (b) Member re-created by a new order without a tick, plus an old order with `marketing_opt_in` proof older than the cutoff → after backfill `consent_status` is still `never` and there is no `checkout` consent record for the old order. Bug: a person gets marketing again from a 4-year-old checkout tick. Red check: remove the pair skip / the `restorable` cutoff. Expire the `audience_backfill` ShedLock row before each call (CLAUDE.md:146). |
| T5 | `src/test/java/com/imin/iminapi/audienceplan/config/AudiencePlanAccessTest.java` (edit) | config check | a property default that protects prod | Add a `protectingFlags()` row (:79-86): `arguments("IMIN_AUDIENCE_RETENTION_PURGE_ENABLED", AudiencePlanProperties::getRetentionPurgeEnabled)`. Bug: deploying with the variable unset or blank starts deleting. Asserts the Java default with the yaml key absent (CLAUDE.md:303) through the existing `ShippedYaml.run`. |
| T6 | `src/test/java/com/imin/iminapi/audienceplan/RetentionCountsSqlPostgresTest.java` (new) | integration | consent/erasure (the ops numbers the flip relies on) | (a) Every `sql` block of `docs/ops/retention-counts.md` runs on Postgres; `hasSize(4)` in Phase A, `5` after Phase B. (b) For a Paris org seeded with one expired and one kept member, one old opt-out and one old recipient row: R2/R3/R4 rows for that org equal the hand-built purge job's dry-run counts for that org. Bug: the SQL Ivan runs before the flip disagrees with what the job deletes. |
| T7 | `src/test/java/com/imin/iminapi/audienceplan/service/RetentionJobIntegrationTest.java`, `RetentionJobUnitTest.java` | existing, unedited | – | They read `logic.legal().retentionDays()` and ConsentGate results only; the `RetentionCutoff` extraction keeps both values (grep: no reference to `orgZone`/`params`). Expected: green, same counts. |
| T8 | `src/test/java/com/imin/iminapi/service/org/ClosedOrgGuardsIntegrationTest.java` (new) | integration | auth/org scoping; consent; money adjacent (send path) | (a) Parameterized MockMvc: publish, campaign send, campaign retry, import on a closed org → 409 `ORG_CLOSED`, nothing written. Bug: a closing organizer can still sell or email. (b) Parameterized public capture (survey GET/POST, door GET/POST) → 404; consent confirm → `invalid` and no `confirmed_at`. Bug: personal data is collected for an org about to be purged. (c) `claimDue`: a closed org's due campaign, scheduled earlier than an open org's → only the open one claimed (asserted by id). Bug: a closing org's failed campaign is retried to its audience. (d) Backfill: a closed org with an order and no membership → none created; an open org's pair is projected. Bug: a purged audience is rebuilt from retained orders. (e) Projector: tickets issued and redeemed for a closed-org order → no membership. (f) Sign-in: during the grace period password login works; after `purged_at` both password login and Google login (spy `GoogleOAuthService.exchangeCode` stubbed) → 401 `AUTH_INVALID_CREDENTIALS`. Bug: a purged account still signs in. Red check: remove each guard once. |
| T9 | `src/test/java/com/imin/iminapi/service/org/OrgClosurePurgeJobIntegrationTest.java` (new) | integration (hand-built job with an org filter; `closed_at` backdated by SQL) | erasure; money (records kept); scheduled job effect | (a) Org closed 31 days ago with every purged table seeded → all gone; `orders`, `tickets`, `refunds`, `disputes`, `settlements`, `payout_runs`, `events`, `audit_logs` row counts for the org unchanged; `purged_at` set; sessions revoked; `ORG_DATA_PURGED` audit row. Bug: the DPA's 60-day deletion never happens, or accounting records are lost. (b) Parameterized not-purged: closed 29 days ago, closure cancelled (`closed_at` null), each created before the due org → untouched. Bug: an org is purged before its export window ends. (c) Orphan org (memberships, opt-outs and survey rows with no org row) → purged. Bug: a deleted organizer's contacts linger. (d) Flag false → counts only, nothing deleted. (e) `PgFaults.failWrites` on one table for the due org → `purged_at` stays null, next run completes. Bug: an org is marked purged with data left. (f) Archive: purged org closed 5 years + 1 day ago → users anonymized, a new `users` row with the old email can be inserted, the audit actor email is redacted, orders kept; closed 5 years − 1 day → untouched. Bug: organizer identities kept past 5 years, or a former organizer locked out of their own email forever. |
| T10 | `src/test/java/com/imin/iminapi/service/org/OrgServiceTest.java` (edit) | unit | erasure | Constructor gains `mock(OrgDataPurger.class)`; `delete_owner_cascades` (:145-149) also verifies `purger.purge(orgId)` before `orgs.deleteById(orgId)` (`InOrder`). `delete_is_refused_once_the_org_has_taken_an_order` (:158) also verifies `purger` is never called. Bug: deleting an untraded org leaves its contacts behind, or refusing a traded org still wipes its audience. |
| T11 | `src/test/java/com/imin/iminapi/service/org/OrgClosurePropertiesTest.java` (new) | config check | property default that protects prod | `ShippedYaml.run(List.of("IMIN_ORG_CLOSURE_PURGE_ENABLED"), …)` → `purgeEnabled` false; blank → false. Bug: deploy deletes closed orgs' data before ops is ready. |
| T12 | V185 | none | – | Adds nullable columns and CHECKs, changes no data ("column exists" is on the no-test list). |

No planned test is from the "does not get a test" list: no log-level assertions, no cron or annotation reflection, no getters.

## Live-test
- Phase A: after deploy and one night, read the three dry-run log lines, run R1-R4 (Ivan) and compare. After each flip, read the `done` lines the next morning and re-run the matching blocks (expect 0, or a smaller number when `capped`).
- Phase B: after deploy, run R5. With Ivan's OK (a prod write), create a throwaway organizer via signup, add one door-QR test sign-up, close it via `docs/ops/org-closure.md` with `closed_at = now() - interval '31 days'`, enable the flag, and the next morning confirm `OrgClosurePurgeJob: done`, that the throwaway org's membership is gone, and that its login answers 401.
- `/live-test api` covers boot and health; no OpenAPI marker (no contract change).

## Contract impact
None. No `/api/v1` path or schema changes: `ApiError.Body.code` is a `String` (ApiError.java:18), so the new `ORG_CLOSED` value is not an OpenAPI change, and `OrganizationDto` is unchanged. Public endpoints answer the same 404 / `invalid` they already document, so `PUBLIC_PAGE_API.md` does not move. No `types.ts` edit; webapp `api:check` stays clean.

## i18n impact
Four webapp strings × EN/ES/FR/UK (see Copy ledger), checked by `check:i18n` and `exclusions.test.ts`. Optional webapp follow-up: map `ORG_CLOSED` in `humanizeError.ts` with EN/ES/FR/UK text; until then the generic sentence shows.

## Blast radius
- **Personal data, irreversible deletes.** `AudienceRetentionPurgeJob` and `OrgClosurePurgeJob` delete rows for every org. Mitigations: default-off flags, dry-run counts in logs, SQL counts pinned to the job by T6, per-id transactions, a re-check under the lock, a 5,000-memberships-per-run cap, and per-org audit rows.
- **Shared consent rule.** `ConsentGate.params` (the send gate for audience-plan campaigns and the `IMIN_CONSENT_GATE_ALL_CAMPAIGNS` path) now reads its cutoff from `RetentionCutoff`; covered by the existing ConsentGate/RetentionJob tests (T7) plus T1.
- **Audience projection for every org.** `AudienceBackfillJob` (also at boot) and `AudienceOrderProjector.restorable` change which orders are replayed. Live order projection is unchanged except for the closed-org early return.
- **Send path.** `CampaignRepository.claimDue` gets one predicate; `CampaignService.send`/`retry` get one guard.
- **Auth.** `AuthService.issueSession` and `OAuthAccountService.issueSession` (the only two places that create an `AuthSession`; grep `new AuthSession`) refuse purged orgs.
- **Flyway V185** on `organizations`: additive nullable columns; old instances ignore them during the rolling deploy; `insertable/updatable=false` on the entity so no existing save path writes them.
- **Money.** No money row is written or deleted. The closure preconditions (runbook, step 13) keep money in flight from being stranded; the Stripe Connect account is not closed by this plan (ops closes it at Stripe after the last payout).
- **DSAR path untouched.** `DsarService.executeErase` is unchanged.

## Risks
- **Size.** Phase A touches about 18 main files plus 6 test files; Phase B about 25 main files plus 4 test files. That is more than ~15 files and two concerns. Proposed split: ship Phase A under this slug, and Phase B as `org-closure-purge` from the same plan (its steps depend only on `MembershipGraphDeleter` and `RetentionCutoff` from Phase A). Phase A could split further (opt-out purge and recipient redaction as A2) if review wants smaller diffs.
- **Overlap.** `MembershipGraphDeleter` repeats part of `DsarService.executeErase` (DsarService.java:311-376). This keeps DSAR stable; a follow-up can make DSAR call the deleter.
- A membership re-created by a new purchase recomputes its counters over all of that person's orders, archived ones included (AudienceOrderProjector.java:267-275).
- The pending recipient rows of a campaign that failed more than 3 years ago keep their address (D5 skips pending rows).
- An organizer's consent edits count as contact only through person-sourced records, so organizer actions cannot keep a record alive. An unsubscribe does not count as contact either; the refusal is kept separately in `marketing_optouts` for 3 years.
- Fresh imports with old stated purchase dates are deleted the night after import (OPEN_QUESTIONS 3).
- After the purge, a closed organizer's email stays taken (`users.email_lower` is unique) until the 5-year archive step.
- Late chargebacks after the purge are handled by IMIN; the organizer can no longer sign in.
- `audit_logs` (12-month promise), backups, R2 artist photos and the 10-year order deletion are not handled here (OPEN_QUESTIONS 4 and 6).
- The DPA "CI scanner" clause is false today (OPEN_QUESTIONS 1).
- The 5-year step anonymizes contact emails; if a debt-collection case needs the organizer's email after 5 years, it is gone (the legal name stays).

## Definition of done
- Every Phase A row in Affected files is implemented; T1-T7 pass, and each guard test was shown red with its guard removed (noted in the PR body).
- The full `./mvnw test` is green with Docker up and no skipped Testcontainers classes, re-run after the last rebase.
- CLAUDE.md, `.env.example`, `application.yaml`, `docs/ops/retention-counts.md` and `docs/privacy/retained-after-erasure.md` updated in the same diff.
- Phase B: the same, with T8-T11, V185 renumbered against fresh origin, and `docs/ops/org-closure.md` dry-run against the prod schema (`\d organizations` after deploy) before first use.
- The flip runbook steps 0-3 are done and their outputs recorded under Live-test evidence.

## Live-test evidence
(filled in by /do-task: dry-run log lines, R1-R5 outputs, post-flip `done` lines, throwaway-org purge result)

## Review rounds
(filled in by /do-task)- Gate 1 approved by the user 2026-10-12 "as recommended": reword the DPA CI-scanner clause to the runtime LlmPayloadGuard + follow-up card to extend the guard to every LLM caller; delete marketing_optouts at exactly 3 years; imports aged by stated last purchase (deleted next night if > 3y); audit_logs retention → separate card; self-serve closure → separate card; backups → user decision outside this task; ship Phase A and Phase B as two tasks from this plan (Phase A first).
- round 1 → APPROVE (MEDIUM: retention purge reuses skip_reason dsar_erased → falsely shows a DSAR to organizers — add retention_purged; keep-filters r.source/person, r.status=subscribed, r.channel=email untested — add parameterized non-keeping consents. LOW: AudienceBackfillCompleted lacks skippedPastRetention; backfill/projector cutoffs active from deploy even with purge off — mention in ship note. Rebase: certain conflict in CampaignRecipientRepository.redactPiiByMembershipId (8ed8d3fe removed renderedBody) — keep upstream; drop rendered_body from new native SQL/ops R4/fixtures.)
- round 2 → FIX_REQUIRED (labels untrue: dsar_erased implied the person's request; retention label ignored that organizer campaigns don't count; plan claimed no webapp change; rebase onto a54553b3) — fixed.
- round 3 → APPROVE (LOW: flaky ResendTrackingHealthIndicatorTest — separate card).
