-- V82__normalize_event_facets.sql
-- Canonical venue city / country / genre, plus the derived city merge key.
--
-- WHY
-- Nothing normalised these three columns on the way in, so production accumulated several
-- spellings of one fact:
--   * one city as `Metz`/`FR`, `Metz`/NULL and `METZ`/`''` — three rows in the /events/cities
--     facet, three chips for one place, each returning a slice of the nights;
--   * one genre as both `techno` and `Techno` — and `?genre=` is an exact, case-sensitive
--     match, so those are two different queries and two identical-looking chips.
-- `EventService` now normalises on write (EventNormalization); this migration applies the
-- same rules to the rows written before it and adds the derived key the read side groups on.
--
-- THE RULES (identical to com.imin.iminapi.util.EventNormalization)
--   city    -> whitespace runs collapsed to one space, then trimmed. Case PRESERVED — title-
--              casing destroys 's-Hertogenbosch and L'Aquila, and upper/lower-casing is worse.
--   country -> trimmed + upper-cased, or NULL. NEVER ''. The `''` vs NULL split is precisely
--              what made one Metz look like two.
--   genre   -> collapsed, trimmed, lower-cased. It is an internal facet token, not a label;
--              both frontends must title-case it for display.
--   key     -> lower(city), stored in the new venue_city_key column.
--
-- PORTABILITY
-- `regexp_replace(x, '\s+', ' ', 'g')` behaves identically on PostgreSQL 17 (prod) and
-- H2 2.4 in PostgreSQL mode (tests) — both replace globally with the 'g' flag and both read
-- `\s` as the six ASCII whitespace characters. Verified against both engines before writing
-- this file. Do NOT switch to `[[:space:]]`: H2 runs the Java regex engine, where that POSIX
-- class is not a class at all and silently mangles the value.
--
-- `updated_at` is deliberately NOT bumped. This is a spelling correction, not an organizer
-- edit; bumping it would invalidate every held ETag and light up "changed" in the dashboards
-- for a change no organizer made.

ALTER TABLE events ADD COLUMN venue_city_key VARCHAR(255) NOT NULL DEFAULT '';

-- 1. Genre -> canonical lower-case token.
UPDATE events
   SET genre = lower(trim(regexp_replace(genre, '\s+', ' ', 'g')))
 WHERE genre IS NOT NULL;

-- 2. City -> collapsed + trimmed, case untouched.
UPDATE events
   SET venue_city = trim(regexp_replace(venue_city, '\s+', ' ', 'g'))
 WHERE venue_city IS NOT NULL;

-- 3. Country -> upper ISO-3166 alpha-2 or NULL, never ''. Anything that is not two characters
--    cannot be a country code and is unreachable by the API anyway (the public listing rejects
--    a `country` filter that is not length 2), so it becomes NULL rather than staying as junk
--    that silently splits a city's facet row. The impact report lists every such value before
--    this runs — check it is empty, or that what it holds really is junk.
UPDATE events
   SET venue_country = CASE WHEN length(trim(venue_country)) = 2
                            THEN upper(trim(venue_country))
                            ELSE NULL
                       END;

-- 4. Derived merge key. venue_city is already collapsed + trimmed by step 2, so this is just
--    the lower-casing. Every later write recomputes it in Event.@PrePersist/@PreUpdate.
UPDATE events
   SET venue_city_key = lower(coalesce(venue_city, ''));

-- The city facet GROUPs BY this column and the public listing filters on it with `=`.
CREATE INDEX idx_events_venue_city_key ON events (venue_city_key);
