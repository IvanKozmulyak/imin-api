# ap-m1-4-fan-features-calc: fan feature calculator (pure)

Programme: `/Users/ivan/imin/docs/superpowers/plans/2026-09-26-audience-plan-tool.md`, task M1-4. Base: `origin/master` @ `86c92ed7` (M1-2 `e15a478e` and M1-3 `86c92ed7` on origin).

## Goal and scope

A pure calculator (no Spring beans, no repositories) that turns one membership's org-scoped orders, tickets, bought events, consent records, survey response times, org timezone and a `Clock` into the values of a `fan_features` row: paid orders, first/last paid purchase, class, taste, cities, formats, no-shows, average group size, last contact from the person, logic version. Nothing persists it and nothing calls it yet (M1-5 does). No migration, no endpoint, no config key.

Reproduction test: n-a (new code).

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-4-fan-features-calc` | `./mvnw test` |

## Affected files (per repo)

api (all new):
- `src/main/java/com/imin/iminapi/audienceplan/engine/PaidOrderRules.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/ClassRules.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/TasteCalculator.java`
- `src/main/java/com/imin/iminapi/audienceplan/engine/FanFeatureCalculator.java`
- `src/test/java/com/imin/iminapi/audienceplan/engine/FanFeatureCalculatorTest.java`

## Ordered steps

1. `PaidOrderRules`: `isPaid(order, ticketsOfOrder)` and `countable(ticket)`.
2. `ClassRules`: `importBasisValid(consents)`, `classify(rules, paidOrders, daysSinceLastPaid, daysSinceLastContact, importBasisValid)` reading the `classes` list of `logic-v1.yaml` in order (first match).
3. `TasteCalculator`: decayed, whitelisted, normalized bucket weights.
4. `FanFeatureCalculator`: `calculate(Input, Clock)` → `Result`.
5. `FanFeatureCalculatorTest`, one test per branch (list under Test impact), against the shipped YAML via `LogicLoader.parse`.

Rules carried forward verbatim from the programme plan:
- C6: "New classes are computed on `fan_features` from **paid** orders only: `payment_method='stripe' ∧ total_minor > 0 ∧ ¬test_mode ∧ ≥ 1 ticket not in {refunded, revoked}`. `lifecycle` is left untouched until M3-R replaces it in the UI."
- M1-4 inputs: "Never reads `memberships.city/genres/tags/vibe/notes/last_email_open/last_email_click` (C13, C14)."
- C13: "M1-4 never reads them" (opens and clicks). C14: "Fan features never read those columns (tags can carry identity labels). Test pins it."
- C1: "**Every genre field in this plan (taste, segment rules, portrait `?genre=`, survey, UI bars) uses only these 8 keys.**"
- "**`importBasisValid` without the provenance table (chosen over ordering M1-4 after M1-7):** true iff the latest subscribing consent record has `basis='explicit'` and `source='organizer_import_row'`. Only M1-7 writes that source, always in the same transaction as its provenance row, so before M1-7 ships the value is false for everyone (fail-closed) and legacy `organizer_import` (bulk-attested) rows correctly classify as `none`."
- "Rules: paid order per C6; `days_since_last_paid` = whole days between last paid purchase and "as of" in the org timezone; classes first match per spec §6 (`loyal` ≥3 ∧ ≤90; `repeat` =2 ∧ ≤90; `first_timer` =1 ∧ ≤90; `lapsing` 91-180; `dormant` ≥181 ∧ `days_since_last_contact` ≤ 1095; `imported` paid=0 ∧ `importBasisValid`; else `none`). Taste = Σ whitelisted bucket weight × `0.5^(age_days/180)`, normalized to 1; `objected_profiling` → empty taste. `last_contact_from_person_at` = max(last paid purchase, explicit consent capture, survey response); opens never count. `cities` and `formats` are stored (purchase-derived) for later use but do not enter segments in v1 (see Deferred). `price_tier` not computed in v1 (stored null)."
- D1: "explicit consent only now (`soft-opt-in-enabled=false`)".
- D3: "Resend open/click tracking OFF for everyone" — "`canTrack` is constant false."

Interpretations (made here, flagged for review):
- Taste weight is one per distinct bought event (not per ticket or per order), aged from the event's `starts_at` (order `created_at` when the event has none); future events have age 0.
- (review round 1) "Explicit consent capture" for last contact = a `subscribed` record with basis `explicit` whose source is on an **allowlist of person-created sources**: `checkout`, `door_qr`, `survey`, `preference_centre_row`, `order_confirmation` (SMS). Everything else — `manual` and other organizer-typed `/consent/capture` sources, `organizer_import`, `organizer_import_row` — does not count, because those timestamps show organizer activity, not contact from the person (the 1095-day rule is from the person's last contact).
- **Required follow-up (consent hardening):** today an organizer can type a reserved source such as `checkout` on `POST /consent/capture`, and that record would pass the allowlist. The consent-hardening task (reject reserved system `source` values on `/consent/capture`) must ship before M1-5 flips `fan-features-all-orgs`.
- **Note for M1-6 / M1-12:** imported contacts (class `imported`, 0 paid orders) have a **null** `last_contact_from_person_at`. A null must never be read as "within 1095 days". Use the provenance `last_purchase_date` from M1-7 for their retention and mailability window.
- (review round 1) `formats` = the event `type`, mapped to a closed whitelist taken from the webapp wizard's `EVENT_TYPES` (`Festival`, `Rave`, `Club`, `Concert`, `Open Air`) → `festival`, `rave`, `club`, `concert`, `open_air` (trim, lower-case, whitespace → `_`). Any other value (organizer free text such as "queer night" or "Club Night") is dropped.
- (review round 1) `objected_profiling` → empty taste, cities **and** formats. Paid orders, class, last contact, no-shows and group size are still computed.
- (review round 1) `importBasisValid` reads only `channel = 'email'` records.
- `no_show_n` = distinct ended events (ends_at, else starts_at, before as-of) of paid orders with an unscanned countable ticket and no redeemed ticket (same semantics as `MembershipProjector`, paid orders only).
- `avg_group_size` = mean countable tickets per paid order, scale 3 half-up; null with no paid order.
- `sends_30d` is not an input here (M1-5 or later sets it).

## Verification commands

`cd /Users/ivan/imin/imin-api/.claude/worktrees/ap-m1-4-fan-features-calc && ./mvnw test`

## Test impact

New `FanFeatureCalculatorTest`, one test per branch: free order ignored; test-mode ignored; zero-total stripe order ignored; fully refunded ignored; revoked ignored; partially refunded counts; days 90 → first_timer, 91 → lapsing, 180 → lapsing, 181 → dormant; days counted in the org timezone; dormant with last contact 1095 → dormant, 1096 → none; explicit consent refreshes last contact; organizer-import consent does not; survey response refreshes last contact; 3 paid ≤90 → loyal; 2 → repeat; `organizer_import_row` explicit, 0 paid → imported; legacy `organizer_import` explicit, 0 paid → none; later non-import subscribing record wins → none; no import record → none; taste only from this org's events (another org's order ignored); non-whitelisted genre contributes nothing; an event whose name/type says "queer night" contributes only its bucket and is not a format; 180-day-old event weighs 0.5 of today's; objection → empty taste; recent `last_email_open` does not move `last_contact_from_person_at` (and membership city/genres/tags/vibe/notes are not inputs); no-show counted only for ended events; avg group size and cities; logic version copied. Existing tests unaffected.

## Live-test

Not needed: pure code with no caller, endpoint or schema change.

## Contract impact

none

## i18n impact

none

## Blast radius

New package `audienceplan/engine` only; no existing class modified; nothing calls it until M1-5.

## Risks

- Interpretations above (taste per event, import sources excluded from last contact, format filter) may differ from what M1-5/M1-6 expect; each is pinned by a test so a change is deliberate.

## Definition of done

Four engine classes + test; `./mvnw test` green; no existing file changed.

## Live-test evidence

n-a (see Live-test).

## Review rounds

(appended by the orchestrator)

## Verification results (2026-09-26)

- Baseline on untouched `86c92ed7`: `./mvnw test` green, 3389 tests, 0 failures.
- `FanFeatureCalculatorTest`: 41 tests, 0 failures.
- After rebase check (origin/master still `86c92ed7`): full `./mvnw test` 3430 tests, 0 failures. One earlier full run had one failure in `BuyerMailListenerTest.the_send_happens_with_no_transaction_in_scope`, which this diff does not touch. It passed 3/3 when run alone. The cause is a race in the test: `verify(timeout)` can return before the `doAnswer` body sets `txActive`. That is its own card.

## Review round 1 fixes (2026-09-27)

HIGH: last-contact person-source allowlist (replaces the denylist) + consent-hardening follow-up note. MEDIUM: objection clears cities/formats; formats use a closed whitelist. LOW: email-channel import basis; tests for a non-explicit import row, an SMS import row, a later SMS consent, an event with no start or end, an order whose event belongs to another org or is missing, and TasteCalculator null genre key / null time; M1-6/M1-12 null-last-contact note.
