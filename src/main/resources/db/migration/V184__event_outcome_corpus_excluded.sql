-- V184__event_outcome_corpus_excluded.sql
-- A finalized outcome whose event took only test-mode orders is not zero demand: the flag keeps it
-- out of the comparable corpus, the pacing curves and prediction scoring. Existing rows read false.
ALTER TABLE event_outcomes ADD COLUMN corpus_excluded BOOLEAN NOT NULL DEFAULT FALSE;
