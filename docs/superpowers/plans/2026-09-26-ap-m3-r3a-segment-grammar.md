# M3-R3a Segment rule grammar and resolve reasons

Slug: `ap-m3-r3a-segment-grammar` · Programme: `docs/superpowers/plans/2026-09-26-audience-plan-tool.md` §M3-R3a (+ C1, C14, C20, M3-R3b "Promoters") · Mode: autonomous runner

## Goal and scope
Give custom segments the grammar the M3-R3b editor (M3-U0 `RuleGroupBuilder`) produces, and tell the organizer why matched people cannot be emailed:
- Rules JSON accepts **rule groups**: `{"groups":[{"combinator":"and"|"or"|"not","rules":[{field,operator,value}]}]}`. Groups are ANDed together. `and` = every rule matches, `or` = at least one, `not` = **none of these rules match** (NOT of OR, the M3-U0 `RuleGroupBuilder` semantics: "Leave out people who match any of these rules"). A legacy flat array stays valid and means one `and` group; an empty array or blank still means everyone. A group with no rules is neutral.
- New fields: `guest_class` (fan_features class; no row = `none`, as the read model counts it), `genre` (the 8 bucket keys; the member's taste has the bucket with weight > 0), `city` (purchase cities from `fan_features.cities`, compared with `EventNormalization.cityKey`), `attended_event` (event id of this org; the member holds a ticket not refunded/revoked on a non-test order for it). Operators `==` and `in` (comma-separated values) on the new fields; legacy fields keep their operators.
- `SegmentResolveDto` gains `exclusions: {reason → count}` from **ConsentGate** (all 8 reasons, 0 when none) for `resolve` and `previewRules`; `mailable` is ConsentGate's (same number as the Overview and the plan), `excluded = matched − mailable = Σ exclusions`.
- `POST /audience/segments/preview` evaluates unsaved rules (the editor's live count needs it; nothing is persisted).
- `SegmentDto` gains `ruleGroups` (schema `SegmentRuleGroup`, always populated; legacy = one `and` group).
- Prebuilt "Promoters" retired until NPS is collected: never provisioned for a new org, hidden from the segment list; an existing row still resolves (a campaign may point at it).
- AI draft: validation accepts `guest_class`, `genre`, `city`; lists as unsupported: non-bucket genres, `attended_event` (needs an event picked in the editor), `nps` (not collected), OR/NOT (the draft stays one AND group).
Out of scope: webapp editor (M3-R3b), class-based ready-made segments from the canvas, nested groups.

Rules carried forward verbatim:
- C1: "**Every genre field in this plan (taste, segment rules, portrait `?genre=`, survey, UI bars) uses only these 8 keys.**"
- C14: "Fan features never read those columns (tags can carry identity labels)." and M3-R3a: "`city` (purchase cities from `fan_features.cities`, never `memberships.city`, C14)".
- C20: "`SegmentResolveDto(int matched, int mailable, int excluded, long avgLtvMinor)`: one total, no reasons" → "M3-R3a adds a reasons map."
- M3-R3a: "`SegmentResolveDto` gains `exclusions: {reason → count}` (C20; reasons as M1-6/M1-9) alongside the existing `excluded` total, for both `resolve` and `previewRules`; `AiSegmentService` validation lists unsupported parts."
- M1-6 reasons: "`unsubscribed`, `suppressed`, `objected`, `no_basis`, `legacy_unproven`, `retention_3y`, `erase_pending`, `no_email`."
- M3-R3b: "Prebuilt "Promoters" removed until NPS is collected."
- §4.5: "Numbers shown must trace to a real API field (no sample numbers from the design canvas)."
- D1: "explicit consent only (`soft-opt-in-enabled=false`)."
- Runner: no beta gating; `AudiencePlanAccess` stays only as a global kill switch (not used here).

## Repos in ship order
| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-r3a-segment-grammar` | `./mvnw test` |

## Affected files (per repo)
api:
- new `audience/service/SegmentRules.java` (parse, validate, evaluate the grammar), `audience/service/SegmentFacts.java` (per-member class/taste/cities + event attendance, loaded only for fields used)
- new `audience/dto/SegmentRuleGroup.java`, `audience/dto/SegmentRule.java`, `audience/dto/SegmentPreviewRequest.java`
- `audience/dto/SegmentResolveDto.java` (+ `exclusions`), `audience/dto/SegmentDto.java` (+ `ruleGroups`)
- `audience/service/SegmentService.java`, `SegmentRuleRow.java` (+ `membershipId`), `SegmentRuleSchema.java`, `AiSegmentService.java`, `PrebuiltSegment.java`
- `audience/repository/MembershipRepository.java` (rule-row projection + attended query), `audienceplan/repository/FanFeatureRepository.java` (rule facts projection)
- `audience/controller/AudienceController.java` (preview endpoint)
- tests: new `SegmentRuleGrammarTest` (SpringBoot/H2), updated `AudienceSegmentTest`, `AiSegmentServiceTest`, `AudienceControllerWebTest`, `AudienceSegmentCreateWebTest`/`SegmentLiveCountTest` as needed.

## Ordered steps
1. `SegmentRules`: parse (legacy array / groups object / blank / unreadable → null), validate (400 `rulesJson` messages), evaluate over a row + facts.
2. Facts loading in `SegmentService` (fan features + attendance only when referenced); `SegmentRuleRow` carries the membership id.
3. `resolve`/`previewRules` via ConsentGate `reasons`; `exclusions` map; preview endpoint.
4. `SegmentDto.ruleGroups`; Promoters retired; AI schema/prompt.
5. Tests; `./mvnw test`; rebase onto latest origin/master; `./mvnw test` again.

## Verification commands
`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m3-r3a-segment-grammar && ./mvnw test`

## Test impact
Reproduction test: n-a (new behaviour). One test per branch:
- legacy flat array = AND; blank = everyone; unreadable = nobody; `and`, `or`, `not` (NOT excludes anyone matching any rule); groups ANDed; empty group neutral.
- `guest_class` `==` and `in`, member without fan_features = `none`; `genre` `==`/`in` (weight > 0 only), non-bucket genre rejected (400); `city` case-insensitive from `fan_features.cities`, never `memberships.city`; `attended_event` (ticket held; refunded / test-mode / other org's event not counted; event of another org rejected at create).
- validation: unknown combinator, `in` on a legacy field, empty `in` value, too many groups/values → 400.
- resolve & preview: exclusions carry all 8 reasons, sum to `excluded`, mailable = ConsentGate; liveCount honours groups.
- `SegmentDto.ruleGroups` for legacy and grouped rules.
- Promoters: not provisioned for a new org; existing row hidden from the list.
- AI: genre/class/city accepted; non-bucket genre, `attended_event`, `nps` listed unsupported.

## Live-test
Not needed separately: the full SpringBoot tests exercise the endpoints over H2 and the ConsentGate SQL; the contract check is the OpenAPI marker after deploy (`SegmentRuleGroup`).

## Contract impact
`/api/v1` — marker `SegmentRuleGroup` (+ `SegmentResolveDto.exclusions`, `SegmentDto.ruleGroups`, `POST /api/v1/audience/segments/preview`). Additive only.

## i18n impact
None in api (webapp M3-R3b renders the reasons in four locales).

## Blast radius
`SegmentService` resolution feeds `RecipientMaterializer`, Momentum and the CSV/handoff; legacy arrays evaluate exactly as before. `SegmentResolveDto.mailable` and the AI draft `mailableCount` switch from "subscribed + any basis" to ConsentGate (stricter; most legacy consents become `legacy_unproven` until M3-P1 consents accrue). Actual sends still go through SendGate (M3-7 flag off), so this changes only the numbers shown.

## Risks
- Segment preview now shows fewer mailable people than a campaign would send to until M3-7 flips; accepted to keep one "can email" number across Overview, plan and segments.
- Loading fan features for an org is one extra query per custom-segment count only when a new field is used.
- `in` values are comma-separated: a city name with a comma cannot be expressed (none in the city keys seen).

## Definition of done
`./mvnw test` green on the rebased branch; marker `SegmentRuleGroup` present in `/v3/api-docs.yaml` locally.

## Live-test evidence
n-a (see Live-test). Rebased onto origin/master a9163c9b (pre-rebase stash b6107073 "ap-m3-r3a-segment-grammar pre-rebase"); `./mvnw test` 4136 run, 0 failures, 0 errors. Review-fix round: rebased onto origin/master c6905493 (stash 039aac46); `./mvnw test` 4313 run, 0 failures, 0 errors (incl. `SegmentRuleGrammarPostgresTest` on Postgres 17). Marker pinned by `AudienceControllerWebTest.openapi_publishes_the_SegmentRuleGroup_marker_and_the_preview_endpoint` (`/v3/api-docs`: `SegmentRuleGroup.combinator` enum and/or/not, `SegmentResolveDto.exclusions`, `SegmentDto.ruleGroups`, `POST /api/v1/audience/segments/preview`).

## Notes for M3-R3b (editor)
- `POST /audience/segments/preview` validates before counting: a rule with `value: ''` is a 400, not a count. The editor must debounce the live count and strip incomplete rules (empty value, no field/operator) before calling it.
- Server caps: 10 groups, 20 rules per group, 50 `in` values, 200 characters per value, 65 536 characters of serialized rules (checked before parsing).
- A stored rule the engine cannot run makes the whole segment match nobody (fail closed; inside a `not` group it would otherwise mean everyone).

## Review rounds
(appended by orchestrator)
