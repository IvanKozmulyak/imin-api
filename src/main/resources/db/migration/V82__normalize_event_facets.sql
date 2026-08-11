-- V82__normalize_event_facets.sql
-- Canonical venue city / country / genre, plus the derived city and genre merge keys.
--
-- WHY
-- Nothing normalised these three columns on the way in, so production accumulated several
-- spellings of one fact:
--   * one city as `Metz`/`FR`, `Metz`/NULL and `METZ`/`''` — three rows in the /events/cities
--     facet, three chips for one place, each returning a slice of the nights;
--   * one genre as both `techno` and `Techno` — and `?genre=` is an exact, case-sensitive
--     match, so those are two different queries and two identical-looking chips.
-- `EventService` now normalises on write (EventNormalization); this migration applies the
-- same rules to the rows written before it and adds the derived keys the read side groups on.
--
-- THE RULES (identical to com.imin.iminapi.util.EventNormalization)
--   city      -> whitespace runs collapsed to one space, then trimmed. Case PRESERVED — title-
--                casing destroys 's-Hertogenbosch and L'Aquila, and upper/lower-casing is worse.
--   genre     -> collapsed and trimmed. Case PRESERVED for the SAME reason the city's is: both
--                frontends render the stored string verbatim, and imin-webapp's wizard binds it
--                to a closed Title-Case GENRES list — a folded value matches no option there and
--                the "Music style" field renders empty when an organizer reopens the event.
--   country   -> trimmed + upper-cased, or NULL. NEVER ''. The `''` vs NULL split is precisely
--                what made one Metz look like two.
--   city_key  -> lower(city),  stored in the new venue_city_key column.
--   genre_key -> lower(genre), stored in the new genre_key column.
-- The keys are what the facets GROUP BY and what `?city=` / `?genre=` match with `=`; the
-- display columns are what the buyer reads. Every later write recomputes both keys in
-- Event.@PrePersist/@PreUpdate, so they cannot drift from their display strings.
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
ALTER TABLE events ADD COLUMN genre_key VARCHAR(255) NOT NULL DEFAULT '';

-- 1. Genre -> collapsed + trimmed display string, case untouched.
UPDATE events
   SET genre = trim(regexp_replace(genre, '\s+', ' ', 'g'))
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

-- 4. Derived merge keys. venue_city and genre are already collapsed + trimmed by steps 1-2,
--    so this is just the lower-casing.
UPDATE events
   SET venue_city_key = lower(coalesce(venue_city, '')),
       genre_key      = lower(coalesce(genre, ''));

-- The facets GROUP BY these columns and the public listing filters on them with `=`.
CREATE INDEX idx_events_venue_city_key ON events (venue_city_key);
CREATE INDEX idx_events_genre_key ON events (genre_key);
