# Public buyer API contract — moved

**The authoritative contract for all `/api/v1/public/...` endpoints lives in the consumer repo:**

→ [`imin-public/docs/PUBLIC_PAGE_API.md`](../../imin-public/docs/PUBLIC_PAGE_API.md)

This copy was a fork that silently drifted (it ended at the cities endpoint while the real doc grew checkout, quote, orders, tickets, refunds, recovery and consent sections). Per the workspace `CLAUDE.md` sync rule, the imin-public doc is authoritative for the buyer contract and moves in lockstep with controller changes in this repo — update **that** file when changing any public endpoint here.

Organizer-facing endpoints: see `superpowers/API_CONTRACT.md`. No-leak eligibility behavior is additionally pinned by `PublicEventControllerTest` / `PublicOrderControllerTest` snapshot allow-lists.
