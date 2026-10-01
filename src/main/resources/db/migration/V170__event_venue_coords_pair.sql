-- V80's CHECK let a lone coordinate through: (NULL, 13.44) makes the bounds disjunct NULL, and a NULL CHECK passes.
-- Require both columns non-null before the bounds are tested, so a pin is a pair or nothing.
ALTER TABLE events DROP CONSTRAINT ck_events_venue_coords_valid;

ALTER TABLE events ADD CONSTRAINT ck_events_venue_coords_valid CHECK (
  (venue_latitude IS NULL AND venue_longitude IS NULL)
  OR (venue_latitude IS NOT NULL AND venue_longitude IS NOT NULL
      AND venue_latitude BETWEEN -90 AND 90 AND venue_longitude BETWEEN -180 AND 180)
);
