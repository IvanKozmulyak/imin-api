-- V62: last-touch UTM attribution stamped onto the order at checkout.
--
-- Until now orders carried no attribution key, so per-campaign revenue could only be
-- APPROXIMATED by tagged-visit share (AttributionService) and per-campaign revenue was
-- hardcoded 0 (MarketingHubService). These columns close that gap: imin-public sends the
-- landing utm_* (+ the /track beacon's anon_id) on POST /checkout, the values ride the
-- Stripe session/PaymentIntent metadata, and PaidCheckoutService/FreeCheckoutService
-- snapshot them onto the order at fulfilment. Revenue can then be summed per campaign by
-- a true per-order last-touch join instead of a visit-share estimate.
--
-- All columns NULLABLE — every existing row predates this, and the buyer may arrive with
-- no UTM tags at all (organic). Shapes mirror event_funnel_events (V43) so the funnel
-- beacon and the order agree: utm_* VARCHAR(128), anon_id VARCHAR(64).
--
-- anon_id is the SAME per-session id the /track beacon already mints in the buyer's
-- sessionStorage ('imin.anon') — reused, not a second identifier — so an order can be
-- joined back to the visit that drove it.

ALTER TABLE orders ADD COLUMN utm_source   VARCHAR(128);
ALTER TABLE orders ADD COLUMN utm_medium   VARCHAR(128);
ALTER TABLE orders ADD COLUMN utm_campaign VARCHAR(128);
ALTER TABLE orders ADD COLUMN anon_id      VARCHAR(64);

-- The per-campaign revenue read-model groups an org's orders by utm_campaign over a
-- 30-day window (MarketingHubService / CampaignService); the channel read-model groups
-- by utm_source (AttributionService). Both filter by org_id first.
CREATE INDEX idx_orders_org_utm_campaign ON orders(org_id, utm_campaign);
CREATE INDEX idx_orders_org_utm_source ON orders(org_id, utm_source);
