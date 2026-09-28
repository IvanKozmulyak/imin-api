-- V157__audience_experiments_armed_at.sql
-- When the organizer approved a slump arm: its draft waits for Momentum's SLUMP trigger instead of a fixed time.
-- Nullable, no backfill; H2/PG-compatible.

ALTER TABLE audience_experiments ADD COLUMN armed_at TIMESTAMP WITH TIME ZONE;
