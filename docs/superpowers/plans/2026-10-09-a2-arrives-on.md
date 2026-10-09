# A2: ISO arrivesOn on PayoutsSummaryResponse

## Affected files
- imin-api/src/main/java/com/imin/iminapi/controller/payout/dto/PayoutsSummaryResponse.java (add `LocalDate arrivesOn`)
- imin-api/src/main/java/com/imin/iminapi/service/payout/PayoutService.java (derive date; label derived from it)
- imin-api/src/test/java/com/imin/iminapi/service/payout/PayoutServiceTest.java
- imin-api/docs/superpowers/API_CONTRACT.md (document field)

## Design
- Source: IN_TRANSIT PAYOUT settlements of the org (full list, not the newest-20 history page); earliest `arrivalAt` wins; null when none.
- Zone: UTC. Stripe `arrival_date` is a UTC-midnight timestamp that stands for a bank calendar date, so the UTC date is the date Stripe means; a viewer zone would shift it (America/Los_Angeles shows the previous day).
- `arrivesOnLabel` kept (still read by the webapp until W3); now derived from the same date.

## Tests (unit, pure; PayoutServiceTest)
1. Soonest of several in-transit rows wins regardless of repository order (newest-first row has the later arrival); transfer-type rows ignored.
2. Null when no in-transit payout / arrival unknown (existing empty-table test extended).
3. Zone disagreement: arrival 2026-06-20T22:30Z must give 2026-06-20 (Europe/Paris would give the 21st). Proven red by computing in Paris.

## Verification
`./mvnw test -Dtest='Payout*Test,SpringContextGuardTest'`

## Follow-up
api:sync in webapp after deploy; W3 uses `arrivesOn` + formatDate.
