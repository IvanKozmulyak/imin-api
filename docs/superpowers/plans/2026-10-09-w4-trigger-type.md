# W4: triggerType on CampaignDetailDto

Add `triggerType` to `CampaignDetailDto`, read from the Momentum suggestion linked by `Campaign.momentumSuggestionId` (org-scoped); null for manual campaigns or a missing/foreign suggestion.

Affected: `CampaignDetailDto`, `CampaignService.detailWithStats`, `CampaignListNamesTest`.
Verification: `./mvnw test -Dtest='Campaign*Test,Momentum*Test,SpringContextGuardTest'`.
Follow-up: after deploy, `npm run api:sync` in imin-webapp.
