-- Snapshot of the ConceptRequest that produced a generation.
--
-- POST /ai/events/concept/regenerate rebuilds its ConceptRequest from the stored
-- generated_event row, but that row only ever persisted vibe/tone/genre/city/
-- event_date/platforms. Everything else was passed as NULL, with two visible
-- consequences: (a) the regenerated posters carried neither the event title nor
-- the venue, because PosterTextSpecFactory was handed title=null/location=null
-- and a synthetic date two months out — and the text gate could not notice,
-- since it was checking a contract that no longer contained them; (b) a vibe the
-- organizer explicitly pinned at create time was replaced by the genre default,
-- silently changing reference flyers, style preset and style card.
--
-- All nullable, no backfill: rows written before this migration keep answering
-- "not recorded", which is exactly the pre-existing behaviour. request_lineup is
-- comma-joined, the same convention as generated_event.platforms and
-- palette_hexes — the lineup already collapses to a comma-joined string on the
-- way to the renderer.
ALTER TABLE generated_event ADD COLUMN request_title           TEXT        NULL;
ALTER TABLE generated_event ADD COLUMN request_venue           TEXT        NULL;
ALTER TABLE generated_event ADD COLUMN request_lineup          TEXT        NULL;
ALTER TABLE generated_event ADD COLUMN request_address         TEXT        NULL;
ALTER TABLE generated_event ADD COLUMN request_rsvp_url        TEXT        NULL;
ALTER TABLE generated_event ADD COLUMN request_vibe_id         VARCHAR(64) NULL;
ALTER TABLE generated_event ADD COLUMN request_event_id        UUID        NULL;
ALTER TABLE generated_event ADD COLUMN request_event_date      DATE        NULL;
ALTER TABLE generated_event ADD COLUMN request_capacity        INTEGER     NULL;
ALTER TABLE generated_event ADD COLUMN request_logo_on_posters BOOLEAN     NULL;
