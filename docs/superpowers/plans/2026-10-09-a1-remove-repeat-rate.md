# A1: remove `repeatRatePct` from the dashboard

Owner decision: the figure counted test and refunded orders and rounded 99.5% to 100%. The dashboard no longer shows it; Audience `cameBackPct` is the honest figure.

## Affected files
- `src/main/java/com/imin/iminapi/dto/dashboard/DashboardResponse.java` drop `Business.repeatRatePct`.
- `src/main/java/com/imin/iminapi/service/dashboard/DashboardService.java` drop the computation and `repeatRatePct` helper.
- `src/test/java/com/imin/iminapi/service/dashboard/DashboardServiceTest.java` drop the two assertions, the empty stub and the test that only pinned the field.
- `docs/superpowers/API_CONTRACT.md` drop the field from the sample.

## Kept
`OrderRepository.orderCountsByEmailSince` stays: `AttributionService` (repeatBuyerPct) still uses it.

## Contract
OpenAPI `Business` loses `repeatRatePct`; imin-webapp runs `api:sync` after deploy.

## Verification commands
`./mvnw test -Dtest='Dashboard*Test,OrderRepository*Test,SpringContextGuardTest'`

- Also removed `AttributionResponse.repeatBuyerPct` (same flaws, no UI read) and `OrderRepository.orderCountsByEmailSince`, now unused; `MembershipProjectorTest.recompute_does_not_run_the_whole_org_order_aggregate` only guarded that query and goes with it.
