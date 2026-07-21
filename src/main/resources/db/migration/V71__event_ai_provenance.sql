-- V71: AI-provenance stamps on events (predictor spec §6.1) — makes the V68 honesty columns
-- event_outcomes.concept_ai_generated / poster_ai_generated FILLABLE for the first time.
--
-- WHY ON events AND NOT DERIVED: the AI-studio "promote" path is frontend-only — the webapp's
-- AiStudioPage.applyConcept() navigates to /events/new with router-state prefill and the wizard
-- then POSTs /api/v1/events like any manual creation. NO concept id crosses the wire today, so
-- the backend cannot distinguish an AI-promoted draft from a manual one. This migration plus the
-- optional EventPatchRequest.sourceConceptId field creates the truthful seam:
--
--   * concept_ai_generated = TRUE   — creation carried a verified sourceConceptId (a Concept row
--                                     belonging to the caller's org). Stamped in EventService.createDraft.
--   * poster_ai_generated  = FALSE  — organizer uploaded their own poster file (MediaUploadService,
--                                     kind=poster). A PATCH that CHANGES poster_url resets the stamp
--                                     to NULL (provenance of a pasted URL is unknown).
--   * NULL                          — unknown. NO-FABRICATION RULE: until the FE wizard always sends
--                                     a provenance signal, an absent sourceConceptId does NOT mean
--                                     "manual" (today's AI-promote flow also sends nothing), so we
--                                     never stamp concept_ai_generated = FALSE on absence. NULL is
--                                     the honest value; the FE adopting sourceConceptId turns the
--                                     TRUE side on without a re-migration.
--
-- EventOutcomeService passes these through to the outcome snapshot at publish-freeze, replacing
-- the hard-coded NULLs from V68.

ALTER TABLE events ADD COLUMN concept_ai_generated BOOLEAN;
ALTER TABLE events ADD COLUMN poster_ai_generated BOOLEAN;
