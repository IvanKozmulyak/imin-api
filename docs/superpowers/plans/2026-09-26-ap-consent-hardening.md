# ap-consent-hardening

Follow-up card from `docs/superpowers/plans/2026-09-26-audience-plan-tool.md` ("Decisions taken", Follow-up cards), decided by Ivan.

## Goal and scope

1. `POST /api/v1/audience/consent/capture` and `POST /api/v1/audience/consent/unsubscribe` reject reserved system `source` values with `400 FIELD_INVALID` on `source`. Organizer-typed sources are NOT prefixed server-side (explicitly not wanted); reserved ones are simply rejected.
2. Hosted `POST /api/v1/public/events/{id}/checkout` (`StripeCheckoutController` → `StripeCheckoutService.createCheckout`, both the free branch via `FreeCheckoutService` and the paid Stripe-session branch) downgrades `marketingOptIn` to `false` when `marketingOptInProofText` is blank/absent, mirroring `StripePaymentIntentService.create`.

Rules carried forward verbatim from the programme plan:
- "Follow-ups outside this plan: ... consent hardening /do-task (api): `/consent/capture` rejects reserved system `source` values, hosted `/checkout` downgrades `marketingOptIn` to false when proof text is missing."
- D1: "explicit consent only now (`soft-opt-in-enabled=false`)".
- §1 prerequisite 1: "no proof text → no consent record; nothing writes `soft_opt_in` any more."
- M1-6: "`explicit` counts only when the latest subscribing consent record's `(source, proof)` is on `legal.explicit_sources`" and "organizer-typed `/consent/capture` entries" are `legacy_unproven` — which is why an organizer must not be able to type a system source.

Reserved set (grepped from every `ConsentService.capture/unsubscribe` caller, `logic-v1.yaml legal.explicit_sources`, V85 `marketing_optouts.source` vocabulary, programme plan M1-7/M1-12):
exact `checkout`, `organizer_import`, `organizer_import_row`, `door_qr`, `survey`, `preference_centre_row`, `order_confirmation`, `one_click`, `footer_link`, `buyer_account_deletion`, `sms_stop`; prefixes `dsar_`, `retention_`, `sms_stop_` (covers `sms_stop_reply`; `sms_stopwatch` stays allowed). Match is on the normalised value: NFKC, `Cf` format chars (zero-width etc.) removed, `strip()`, trailing ASCII punctuation removed, `Locale.ROOT` lower-case.

Accepted gap: cross-script lookalikes (e.g. Cyrillic `с` in `сheckout`) are not reserved. Readers (ConsentGate, FanFeatureCalculator) match exact strings, so such a value never counts as system evidence — no gate bypass.

Reproduction test: n-a for (1) (new rule). (2) is hardening of a path that today records the flag on the order; tests pin the downgrade on both branches.

## Repos in ship order

| key | base | worktree | verification command |
|---|---|---|---|
| api | master | `/Users/ivan/imin/imin-api/.claude/worktrees/ap-consent-hardening` | `./mvnw test` |

## Affected files

api:
- new `src/main/java/com/imin/iminapi/audience/service/ReservedConsentSources.java`
- `src/main/java/com/imin/iminapi/audience/controller/AudienceController.java` (capture endpoint check)
- `src/main/java/com/imin/iminapi/stripe/StripeCheckoutService.java` (downgrade in the `CheckoutConsent` overload of `createCheckout`, before replay/pricing, same position as the native service)
- tests: new `audience/ReservedConsentSourcesTest.java`; new `audienceplan/engine/ReservedConsentSourcesSyncTest.java`; `audience/AudienceConsentCaptureValidationTest.java`; `stripe/StripeCheckoutServiceTest.java`

## Ordered steps

1. Baseline `./mvnw test` on the untouched worktree.
2. Add `ReservedConsentSources.isReserved`.
3. Controller throws `ApiException(400, FIELD_INVALID, "Validation failed", {source: "is reserved for system-recorded consent"})` before `consentService.capture`.
4. `StripeCheckoutService.createCheckout(..., CheckoutConsent)`: `marketingOptIn && proof == null` → warn + `marketingOptIn = false` (CheckoutConsent already collapses blank to null).
5. Tests per branch; rebase onto latest origin/master (tagged stash + SHA); final `./mvnw test`.

## Verification commands

`./mvnw test` (worktree root); targeted: `./mvnw test -Dtest='ReservedConsentSourcesTest,AudienceConsentCaptureValidationTest,StripeCheckoutServiceTest'`.

## Test impact

- `ReservedConsentSourcesTest`: each exact value reserved; each prefix reserved (`dsar_object`, `dsar_erase`, `retention_3y`, `sms_stop`, `sms_stop_reply`); case/whitespace variants reserved; organizer-typed values (`signup-form`, `newsletter`, `dsar`, `checkout-form`, `surveys`) not reserved; null not reserved.
- `ReservedConsentSourcesTest` (review fix): `checkout` + zero-width space, + NBSP, + `.`, `CHECKOUT`, full-width `ｃｈｅｃｋｏｕｔ`, embedded ZWJ + trailing `!?` all reserved; `sms_stop`/`sms_stop_reply` reserved, `sms_stopwatch` not.
- new `audienceplan/engine/ReservedConsentSourcesSyncTest`: every key of shipped `logic-v1.yaml legal.explicit_sources` and every `FanFeatureCalculator.PERSON_CONSENT_SOURCES` entry is reserved (same package, so the set stays package-private).
- `AudienceConsentCaptureValidationTest`: exact reserved → 400 FIELD_INVALID with the `source` message, capture never called; prefix reserved → 400; organizer-typed → 200 and capture called with every argument.
- `StripeCheckoutServiceTest`: paid opt-in + null proof → `marketing_opt_in=false` on Session and PI metadata, no proof key, warn logged; paid + blank proof → false; paid + proof → true, proof key present, no warn; paid no opt-in → false; free opt-in + no proof → `issueFreeOrder(marketingOptIn=false)` + warn; free + proof → true.
- Existing `MarketingOptInWriteTest.freeCheckout_persistsMarketingOptInFlag` calls `FreeCheckoutService` directly (not the hosted path) and is unaffected.

## Live-test

Not needed: the behaviour is fully covered by MockMvc (real controller + validation + exception handler) and the service unit tests; no new wiring, config or migration.

## Contract impact

`/api/v1` — 400 behaviour change only on `POST /api/v1/audience/consent/capture` (reserved `source` → `400 FIELD_INVALID`, `error.fields.source`). No schema change, no OpenAPI diff, no marker. The hosted `/api/v1/public/events/{id}/checkout` request/response shape is unchanged (opt-in without proof was already not recorded as consent; now the order flag agrees).

## i18n impact

None (api only; the error message is a developer-facing field message like the other `@Size`/`@NotBlank` messages on this endpoint).

## Blast radius

- Webapp `/consent/capture` callers that sent a reserved source now get 400 (webapp sends organizer-typed sources; see Risks).
- Hosted checkout: an order with a ticked box but no proof now stores `marketing_opt_in=false` (paid via metadata, free inline). No consent record was being written for it already.

## Risks

- A webapp form that hard-codes a reserved source (e.g. `survey`) would start failing. Checked: grep in imin-webapp for the capture call below.
- Near-miss sources (`checkout-form`) are still accepted by design; ConsentGate matches exact source strings, so they never count as system evidence.

## Definition of done

Both behaviours implemented, tests per branch green, full `./mvnw test` green after rebase onto latest origin/master, no contract schema change.

## Live-test evidence

n-a (see Live-test).

## Verification results

- Baseline on untouched `ba00cf07`: `./mvnw test` green, 3475 tests, 0 failures/errors.
- Guard proof: with both new `if` conditions forced to `false`, 5 new tests fail (2 controller, 3 checkout); restored.
- Rebased twice (tagged stashes `876a742f` onto `87682652`, `63f00d37` onto `1b90d117`); neither upstream commit adds a consent source.
- Final on `1b90d117` + this diff: `./mvnw test` green, 3579 tests, 0 failures/errors/skipped.
- Extension (orchestrator): `POST /audience/consent/unsubscribe` gets the same reserved-`source` 400; tests: exact reserved (`one_click`) → 400 with message, unsubscribe never called; prefix (`dsar_object`) → 400; organizer value → 200 with `OPERATOR` origin. Guard forced off → both reject tests red (clean build).
- Rebased onto `217f58cf` (stash `0bd0534e`); final `./mvnw clean test`: 3616 tests, 0 failures/errors/skipped, BUILD SUCCESS.
- Review fix (normalisation, sync test, `sms_stop_`): with `normalise` bypassed, 5 new variant tests red; restored. Worktree already on latest `origin/master` `217f58cf`, no rebase/stash needed. `./mvnw clean test`: 3627 tests, 0 failures/errors/skipped, BUILD SUCCESS.
- imin-webapp `origin/main`: `useConsentCapture` takes a free-text `source`; no reserved value is hard-coded.

## Review rounds

(orchestrator)
