-- Per-event DJ photo (uploaded by the organizer; drives character-reference poster generation).
ALTER TABLE events ADD COLUMN dj_photo_url TEXT;
-- Snapshot of the DJ photo URL a generation actually used (read back by regenerate).
ALTER TABLE poster_generations ADD COLUMN dj_photo_url TEXT;
