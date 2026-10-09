# Campaign attributed revenue: live orders, net of refunds

Base: `origin/master` e532b161. Repo: `imin-api` only.

## Problem

Campaign attributed revenue counts refunded money and test-mode orders:
`OrderRepository.sumTotalMinorByOrgIdAndUtmCampaign` (OrderRepository.java:223-230) and
`sumRevenueByUtmCampaignIn` (OrderRepository.java:207-214) sum `o.totalMinor` with no refund
or `testMode` filter. Channel attribution (`revenueRowsByUtmSource`, OrderRepository.java:232-246,
folded in `AttributionService.attribution`, AttributionService.java:64-69) already takes each live
order's total minus its SUCCEEDED refunds, clamped at 0. The campaign figure must use the same rule.

## Callers (all keep their semantics apart from the net amount)

- `CampaignAttributionService.attributedRevenueMinor` (:89-92) ← `CampaignService.get` →
  `attributedRevenue` (CampaignService.java:206-210, incl. the canceled-but-sent rule) → `CampaignDto.revMinor`.
- `CampaignAttributionService.attributedRevenueMinorByCampaign` (:100-122) ← `CampaignService.list`
  (CampaignService.java:163-188, `CampaignSummary.revMinor`) and `MarketingHubService` hub tile
  `attributedRevMinor` (MarketingHubService.java:105-107).
- Momentum `attributedMinor` is a literal 0 (MomentumService.java:218-222) — no revenue read, untouched.
- No export, dashboard or AI-suggestion path reads either query (grep of `src/main`).

## Design

- One query shape per attribution key, one net rule. Add
  `OrderRepository.revenueRowsByUtmCampaignIn(orgId, keys)` returning
  `[utmCampaign, totalMinor, succeededRefundMinor]` per live tagged order — the same join/filters
  as `revenueRowsByUtmSource`. Delete `sumRevenueByUtmCampaignIn` and `sumTotalMinorByOrgIdAndUtmCampaign`.
- New `service/analytics/NetOrderRevenue.sumByKey(rows)`: clamps each row at
  `max(0, total - refunded)` and sums per key. `AttributionService` and `CampaignAttributionService` both use it.
- `attributedRevenueMinor(orgId, id)` delegates to the batched form with one id, so single and
  batched cannot drift.
- Null/0 semantics unchanged: never-sent → null (CampaignService), sent with nothing → 0.

## Affected files

- `src/main/java/com/imin/iminapi/repository/OrderRepository.java`
- `src/main/java/com/imin/iminapi/service/analytics/NetOrderRevenue.java` (new)
- `src/main/java/com/imin/iminapi/service/analytics/AttributionService.java`
- `src/main/java/com/imin/iminapi/marketing/service/CampaignAttributionService.java`
- `src/test/java/com/imin/iminapi/marketing/CampaignAttributionServiceTest.java`
- Javadoc only: `marketing/dto/CampaignDto.java`, `marketing/dto/MarketingHubMetricsDto.java` if they claim gross totals.

## Tests (integration, `@IminIntegrationTest`, in CampaignAttributionServiceTest)

1. Full refund SUCCEEDED → order contributes 0; partial SUCCEEDED refunds subtracted; PENDING and
   FAILED refunds ignored — asserted on both the single and the batched form.
2. Test-mode order (and its refund) excluded.
3. Per-campaign split: refunds on campaign A's order do not move campaign B's figure.

Written before the fix and run red against e532b161. The clamp, org scoping, non-UUID tags and empty
input stay covered by the existing tests (channel tests cover the shared helper through AttributionService).

## Ordered steps

1. Write tests 1-3; run red on base.
2. Add the query + `NetOrderRevenue`; switch both services; delete the two gross queries.
3. Update javadoc that claims gross sums.
4. Run verification.

## Verification commands

- `docker info`
- `./mvnw test -Dtest='Campaign*Test,Attribution*Test,Momentum*Test,MarketingHub*Test,SpringContextGuardTest'`

## Follow-up (imin-webapp, not in this change)

Campaign revenue labels (`campaignsTab.colAttributed`, stat tile `viaUtm`) say nothing about refunds;
add "refunds taken off" in EN/ES/FR/UK.
