# M0-1: Alsace-Moselle Good Friday and 26 Dec in PublicHolidayCalendar
m0-1-alsace-moselle-holidays · api-only, stopgap (M1-4 replaces the table)

## Goal and scope
Programme: workspace `docs/superpowers/plans/2026-09-28-predictor-date-check.md` §3 M0-1.

`PublicHolidayCalendar` skips Good Friday for FR (line ~62 `if (!c.equals("FR"))`) and has no 26 Dec. Both are public holidays in Alsace-Moselle (départements 57, 67, 68; Metz is 57).

In scope:
- Regional rows FR-57/FR-67/FR-68: "Vendredi saint" (Easter −2) and "Saint-Étienne" (26 Dec), 2026 and 2027.
- `public static String regionOf(String country, String postalCode, String city)` → `"FR-57"|"FR-67"|"FR-68"|null`.
- `public static List<Holiday> near(String country, String region, LocalDate date, int windowDays)`; the existing 3-arg `near` delegates with `region = null` (unchanged behaviour).
- The one caller `PredictionInputSnapshotService.build` (lines ~96–103) passes the region from `e.getVenuePostalCode()` and the city.
- Class Javadoc bullet "no regional holidays" updated.

Out of scope: other regional holidays, `covers(...)` changes, snapshot record changes, `SNAPSHOT_VERSION` bump (decided: no bump; only Alsace-Moselle events near those dates change hash), migrations, endpoints, webapp.

Region resolution (most reliable first):
1. Country must be FR (trim, upper). Else null.
2. Postcode with whitespace removed; if exactly 5 digits, dept = first 2 chars; 57/67/68 → `"FR-"+dept`, else null. A valid postcode wins over the city.
3. Otherwise `EventNormalization.cityKey(city)` looked up in a fixed map — 57: metz, thionville, forbach, sarreguemines, montigny-lès-metz; 67: strasbourg, haguenau, schiltigheim, illkirch-graffenstaden; 68: colmar, mulhouse. Miss → null.

## Repos in ship order
| key | base | worktree | verification |
|---|---|---|---|
| api | master | `imin-api/.claude/worktrees/m0-1-alsace-moselle-holidays` | `./mvnw test` |

## Affected files (per repo)
- Modify `src/main/java/com/imin/iminapi/predictor/service/PublicHolidayCalendar.java`
- Modify `src/main/java/com/imin/iminapi/predictor/service/PredictionInputSnapshotService.java`
- Create `src/test/java/com/imin/iminapi/predictor/PublicHolidayCalendarTest.java`
- Modify `src/test/java/com/imin/iminapi/predictor/PredictionInputSnapshotServiceTest.java`
- This plan file

## Ordered steps
1. Red: write `PublicHolidayCalendarTest` (below); fails to compile.
2. Green: add `REGION_TABLE` + `putRegion`; in the static block after the western movable-feast loop add, with a 1-line comment ("Alsace-Moselle local law: Good Friday and 26 Dec are public holidays in 57/67/68."):
   ```java
   for (String r : List.of("FR-57", "FR-67", "FR-68")) {
       for (LocalDate easter : List.of(easter26, easter27)) putRegion(r, easter.minusDays(2), "Vendredi saint");
       putRegion(r, LocalDate.of(2026, 12, 26), "Saint-Étienne");
       putRegion(r, LocalDate.of(2027, 12, 26), "Saint-Étienne");
   }
   ```
   Constants `FR_POSTCODE = Pattern.compile("\\d{5}")`, `ALSACE_MOSELLE = Set.of("57","67","68")`, `FR_CITY_DEPT` map. `regionOf` as described. The 4-arg `near`: `covers` check, copy the national subMap into a TreeMap, merge the region's in-window rows with `putIfAbsent` only when the region starts with `country + "-"`, return in date order.
3. Red: 2 caller tests in `PredictionInputSnapshotServiceTest`.
4. Green: in `build`, inside `if (covered)`, `String region = PublicHolidayCalendar.regionOf(country, e.getVenuePostalCode(), city);` and call the 4-arg `near`. Comment: "Region only adds rows; coverage stays national."
5. Full gate `./mvnw test` (run on the untouched base first).

## Verification commands
`./mvnw test -Dtest=PublicHolidayCalendarTest` · `./mvnw test -Dtest=PredictionInputSnapshotServiceTest` · `./mvnw test`

## Test impact
Reproduction test: none (plan-driven stopgap). New tests, `PublicHolidayCalendarTest`:
- `metzGoodFriday2027IsHoliday`: `near("FR","FR-57",2027-03-26,0)` == `[Holiday(2027-03-26,"Vendredi saint")]`
- `parisGoodFriday2027IsNotHoliday`: `regionOf("FR","75001","Paris")` null and `near("FR",null,2027-03-26,0)` empty
- `strasbourgBoxingDay2026IsHoliday`: `regionOf("FR","67000","Strasbourg")` == "FR-67"; `near("FR","FR-67",2026-12-26,0)` == `[Saint-Étienne]`
- `unknownRegionFallsBackToNational`: `near("FR","FR-75",2026-12-25,1)` == `near("FR",2026-12-25,1)` == `[Noël]`
- `regionOfAnotherCountryIsIgnored`: `near("ES","FR-57",2027-03-26,0)` empty
- `regionalAndNationalMergeInDateOrder`: `near("FR","FR-68",2026-12-25,1)` == `[Noël 12-25, Saint-Étienne 12-26]`
- `regionalRowsNeedCoverage`: `near("FR","FR-57",2028-04-14,0)` empty
- `postcodeResolvesDepartment` (parameterized): 57000/Metz→FR-57, 67000/Strasbourg→FR-67, 68100/Mulhouse→FR-68, " 57 000 "/Metz→FR-57
- `postcodeOutsideAlsaceMoselleWinsOverCity`: `regionOf("FR","75011","Metz")` null
- `blankOrMalformedPostcodeFallsBackToCity`: ("", " METZ ")→FR-57; (null,"Colmar")→FR-68; ("F-57000","Metz")→FR-57
- `unknownCityWithoutPostcodeHasNoRegion`: ("","Lyon") and (null,null) → null
- `nonFrenchCountryHasNoRegion`: ("DE","67000","Strasbourg") and (null,"57000","Metz") → null
- `existingThreeArgNearIsUnchanged`: `near("FR",2027-03-26,0)` empty

`PredictionInputSnapshotServiceTest` (+2):
- `metzEventSnapshotIncludesGoodFriday`: FR, Metz, 57000, Europe/Paris, startsAt 2027-03-26T19:00Z → `holidayTableCovers()` true; lines contain ("2027-03-26","Vendredi saint") and ("2027-03-29","Easter Monday").
- `parisEventSnapshotOmitsGoodFriday`: same instant, Paris 75011 → no "Vendredi saint"; ("2027-03-29","Easter Monday") present.

## Live-test
Not needed: the snapshot isn't exposed; only its hash is stored. After deploy check Railway serves `/v3/api-docs.yaml`.

## Contract impact
none

## i18n impact
none (holiday names are internal prompt data)

## Blast radius
Only caller is `PredictionInputSnapshotService.build`. Only Alsace-Moselle events within ±3 days of those dates change snapshot hash → re-scored on next edit (intended). No money/auth/Flyway.

## Risks
- No `SNAPSHOT_VERSION` bump (deliberate; record shape unchanged).
- Free-text, often blank postcodes; city fallback covers main cities only (under-signal, never a wrong holiday). M1-4 replaces this.
- `cityKey` keeps accents, so "montigny-les-metz" without accent misses.

## Definition of done
All tests above pass; `./mvnw test` green; 3-arg `near` unchanged; Javadoc updated; comments 1–2 lines.

## Live-test evidence
(filled at ship)

## Review rounds

round 1 → PASS (0 CRITICAL/HIGH/MEDIUM; 2 LOW fixed in main session: ticket-free comment, 2026 Good Friday + 2027 Saint-Étienne asserted)
