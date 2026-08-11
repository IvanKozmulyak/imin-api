-- V80__event_venue_coordinates.sql
-- Venue coordinates for the buyer event page's map tile.
--
-- `events` already carries the denormalised venue strings (name/street/city/
-- postal_code/country) but no point, so the public page could only ever render a
-- "open in maps" deep link built from the address text. These two columns let it
-- render a real tile.
--
-- BOTH ARE NULLABLE AND STAY NULLABLE. Population is best-effort geocoding on the
-- organizer write path (VenueGeocodingListener, AFTER_COMMIT + @Async), which is
-- OFF by default (imin.geocoding.enabled=false) and fails soft. A null coordinate
-- is the normal, expected state; PublicVenueDto emits null and the buyer page must
-- degrade to today's deep link. Never write a placeholder/0,0 point — a wrong pin
-- is worse than no pin (see the no-fabricated-data rule).
--
-- double precision (not numeric): WGS84 degrees need ~7 decimals, well inside a
-- double, and it maps to a plain Java Double on both Postgres and H2 PG-compat.

ALTER TABLE events ADD COLUMN venue_latitude  DOUBLE PRECISION;
ALTER TABLE events ADD COLUMN venue_longitude DOUBLE PRECISION;

-- Sanity bounds so a bad geocode can never persist an off-globe point. Both
-- columns must be set together or both left NULL — a lone latitude is not a
-- location and would render a pin on the prime meridian.
ALTER TABLE events ADD CONSTRAINT ck_events_venue_coords_valid CHECK (
  (venue_latitude IS NULL AND venue_longitude IS NULL)
  OR (venue_latitude BETWEEN -90 AND 90 AND venue_longitude BETWEEN -180 AND 180)
);
