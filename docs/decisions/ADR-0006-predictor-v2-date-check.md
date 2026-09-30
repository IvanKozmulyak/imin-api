# ADR-0006: The date check extends the predictor, it is not a second engine

Status: Accepted · **dark in production** — `PREDICTOR_DATE_CHECK_ENABLED` unset, `PREDICTOR_DATE_CHECK_BETA_ORGS` empty
Date: 2026-09-30

Implements the spec `docs/superpowers/specs/2026-09-28-predictor-v2-model.md` and the programme
`docs/superpowers/plans/2026-09-28-predictor-date-check.md` (both in the workspace root repo).

## Context

A product spec (tech-spec-v1, 2026-09-25) proposed a "Prediction Tool" that checks 1–5 candidate dates for a
city and genre before an event exists and returns good / adjust / move. It was written as a greenfield engine
with its own tables, alert system, calibration report and a shared `city_fact` database filled from Resident
Advisor and Shotgun search snippets.

imin already ships an AI Success Predictor in `predictor/`: a ledger written before render, `event_outcomes`,
a Brier/APE calibration job, reforecast milestones and a band-crossing alert, a holiday table, weather and
competing nights. It answers "how will this saved event sell", not "is this date good", and it needs a saved
event (`prediction_ledger.event_id` is NOT NULL).

## Decision

1. **One predictor, two modes.** The date check is a new `DATE_CHECK` surface inside `predictor/`. It reuses
   the ledger (with a nullable `event_id` only for that surface), `event_outcomes`, `PredictionScoringJob`,
   the reforecast milestones and the single `ReforecastAlertNotifier`. No second alert path.
2. **Code decides the verdict.** Findings come from a versioned question-bank YAML; a scorer in code sums
   capped points into separate risk and opportunity scores and a coverage ratio. The LLM never sets the verdict.
3. **Web research is cite-only.** A web finding keeps URL, quote and fetch time for that check only, must quote
   a date inside its window, can never be a stop factor, and is capped at strength 2. There is no shared
   `city_fact` table: RA §4.4 and Shotgun §11.1 forbid commercial extraction, and EU database right covers a
   systematically filled copy.
4. **No LLM summarizer.** Finding text and actions come from i18n templates keyed by question id, so the output
   cannot contain invented facts and renders in any locale at view time.
5. **Silence is not safety.** Every applicable question is `found`, `clear` or `not_checked`; coverage below
   0.6 yields `not_enough_data`, and dates are ranked only against dates with similar coverage.
6. **The risk score ships as a sum, not a probability.** It is always shown with its breakdown and a Beta
   label until the verdict beats an "always Good" baseline on at least 30 outcomes.
7. **Dark by default.** `DateCheckAccess` answers 404 unless the flag is on and the org is listed; an empty
   beta list admits nobody (the opposite of the audience plan gate, on purpose).

## Consequences

- The date check inherits the predictor's existing guarantees (ledger-before-render, quota, calibration) and
  its limits (the in-memory Stage 0 executor stays; date-check jobs use a DB job table).
- Several checks in the original question bank have no lawful source and are omitted or shown as
  `not_checked` (notably city music charts). Organizer-authorized connectors (Shotgun, DICE) are the only
  lawful path to competitor-grade sales history.
- Weights and thresholds are guesses until calibration; they change only by bumping the YAML version.
