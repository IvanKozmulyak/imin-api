-- Data repair for bug 86ca74h6c.
--
-- Events created before timezone derivation all carry the events.timezone = 'UTC' default
-- (the create flow never set a real zone), so the public site rendered event start times in
-- UTC — a 20:00 Paris show displayed as 18:00.
--
-- Backfill the timezone from the venue country's primary IANA zone, but ONLY for rows still on
-- the 'UTC' default (never override an organizer's explicit non-UTC choice) and ONLY where the
-- venue country is known and mapped. Rows whose venue_country is NULL/blank or unmapped are left
-- as 'UTC': their zone is not derivable from stored data. Those self-heal the next time the event
-- is patched with a country (EventService.applyTimezone), and going forward every create/patch
-- derives the zone — so this is a one-shot repair of the historical backlog, not a recurring gap.
--
-- CASE map mirrors util/CountryTimeZones.java — keep the two in sync.
-- CEILING: multi-zone countries use the capital/mainland zone (FR=Paris ignores overseas
-- territories, ES=Madrid ignores the Canaries, PT=Lisbon ignores the Azores, US=New_York,
-- CA=Toronto), matching the Java map's documented heuristic.

UPDATE events
SET timezone = CASE venue_country
    WHEN 'AT' THEN 'Europe/Vienna'
    WHEN 'BE' THEN 'Europe/Brussels'
    WHEN 'BG' THEN 'Europe/Sofia'
    WHEN 'HR' THEN 'Europe/Zagreb'
    WHEN 'CY' THEN 'Asia/Nicosia'
    WHEN 'CZ' THEN 'Europe/Prague'
    WHEN 'DK' THEN 'Europe/Copenhagen'
    WHEN 'EE' THEN 'Europe/Tallinn'
    WHEN 'FI' THEN 'Europe/Helsinki'
    WHEN 'FR' THEN 'Europe/Paris'
    WHEN 'DE' THEN 'Europe/Berlin'
    WHEN 'GR' THEN 'Europe/Athens'
    WHEN 'HU' THEN 'Europe/Budapest'
    WHEN 'IE' THEN 'Europe/Dublin'
    WHEN 'IT' THEN 'Europe/Rome'
    WHEN 'LV' THEN 'Europe/Riga'
    WHEN 'LT' THEN 'Europe/Vilnius'
    WHEN 'LU' THEN 'Europe/Luxembourg'
    WHEN 'MT' THEN 'Europe/Malta'
    WHEN 'NL' THEN 'Europe/Amsterdam'
    WHEN 'PL' THEN 'Europe/Warsaw'
    WHEN 'PT' THEN 'Europe/Lisbon'
    WHEN 'RO' THEN 'Europe/Bucharest'
    WHEN 'SK' THEN 'Europe/Bratislava'
    WHEN 'SI' THEN 'Europe/Ljubljana'
    WHEN 'ES' THEN 'Europe/Madrid'
    WHEN 'SE' THEN 'Europe/Stockholm'
    WHEN 'NO' THEN 'Europe/Oslo'
    WHEN 'IS' THEN 'Atlantic/Reykjavik'
    WHEN 'LI' THEN 'Europe/Vaduz'
    WHEN 'CH' THEN 'Europe/Zurich'
    WHEN 'GB' THEN 'Europe/London'
    WHEN 'GI' THEN 'Europe/Gibraltar'
    WHEN 'MC' THEN 'Europe/Monaco'
    WHEN 'AD' THEN 'Europe/Andorra'
    WHEN 'SM' THEN 'Europe/San_Marino'
    WHEN 'VA' THEN 'Europe/Vatican'
    WHEN 'UA' THEN 'Europe/Kyiv'
    WHEN 'MD' THEN 'Europe/Chisinau'
    WHEN 'RS' THEN 'Europe/Belgrade'
    WHEN 'ME' THEN 'Europe/Podgorica'
    WHEN 'BA' THEN 'Europe/Sarajevo'
    WHEN 'MK' THEN 'Europe/Skopje'
    WHEN 'AL' THEN 'Europe/Tirane'
    WHEN 'XK' THEN 'Europe/Belgrade'
    WHEN 'US' THEN 'America/New_York'
    WHEN 'CA' THEN 'America/Toronto'
    ELSE timezone
END
WHERE timezone = 'UTC'
  AND venue_country IS NOT NULL;
