package com.imin.iminapi.service.analytics;

import com.imin.iminapi.dto.analytics.AttributionResponse;
import com.imin.iminapi.dto.analytics.UntaggedLinksResponse;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the two organizer-facing UTM attribution read-models, both scoped to
 * the caller's org ({@code p.orgId()}) the same way the rest of the app
 * org-scopes: every underlying query filters by org id, so a caller only ever
 * sees their own data.
 */
@Service
public class AttributionService {

    private static final int UNTAGGED_LINKS_LIMIT = 10;

    private final FunnelEventRepository funnel;
    private final OrderRepository orders;

    public AttributionService(FunnelEventRepository funnel, OrderRepository orders) {
        this.funnel = funnel;
        this.orders = orders;
    }

    @Transactional(readOnly = true)
    public AttributionResponse attribution(AuthPrincipal p) {
        var rows = funnel.countVisitsBySourceForOrg(p.orgId());

        long totalVisits = 0;
        long untaggedVisits = 0;
        long taggedVisits = 0;
        record Bucket(String source, long visits) {}
        List<Bucket> tagged = new ArrayList<>();
        for (Object[] r : rows) {
            String source = (String) r[0];
            long visits = ((Number) r[1]).longValue();
            totalVisits += visits;
            if (source == null || source.isBlank()) {
                untaggedVisits += visits;
            } else {
                taggedVisits += visits;
                tagged.add(new Bucket(source, visits));
            }
        }

        long pool = orders.sumTotalMinorByOrgId(p.orgId());

        // ponytail: last-touch revenue is attributed by tagged-visit SHARE, not
        // by a per-order utm_source join — orders carry no anon_id/utm today, so
        // there is no key to join a paid order back to the visit that drove it.
        // Upgrade path: stamp anon_id (and the landing utm_*) onto the checkout
        // session metadata, copy it onto the Order at fulfilment, then GROUP the
        // order revenue by Order.utmSource for a true per-order last-touch sum.
        List<AttributionResponse.Channel> channels = new ArrayList<>();
        long assigned = 0;
        tagged.sort(Comparator.comparingLong(Bucket::visits).reversed()
                .thenComparing(Bucket::source));
        for (int i = 0; i < tagged.size(); i++) {
            Bucket b = tagged.get(i);
            long revenue;
            if (i == tagged.size() - 1) {
                revenue = pool - assigned;          // last channel absorbs rounding remainder
            } else {
                revenue = taggedVisits == 0 ? 0 : Math.round((double) pool * b.visits() / taggedVisits);
                assigned += revenue;
            }
            channels.add(new AttributionResponse.Channel(b.source(), Math.max(0, revenue), (int) b.visits()));
        }

        int untaggedPct = totalVisits == 0 ? 0
                : (int) Math.round(100.0 * untaggedVisits / totalVisits);

        // Reuse the existing repeat-rate definition: share of distinct buyers
        // (by email) who placed >1 order. Instant.EPOCH = all-time window.
        int repeatBuyerPct = repeatRatePct(orders.orderCountsByEmailSince(p.orgId(), Instant.EPOCH));

        // No tagged visits ⇒ no channel divides the pool, so attributed = 0.
        long attributedRevenueMinor = taggedVisits == 0 ? 0 : pool;

        return new AttributionResponse(attributedRevenueMinor, untaggedPct, repeatBuyerPct, channels);
    }

    @Transactional(readOnly = true)
    public UntaggedLinksResponse untagged(AuthPrincipal p) {
        var rows = funnel.countUntaggedByReferrerHostForOrg(p.orgId());
        List<UntaggedLinksResponse.Link> links = new ArrayList<>();
        for (Object[] r : rows) {
            if (links.size() >= UNTAGGED_LINKS_LIMIT) break;
            String host = (String) r[0];
            int visits = ((Number) r[1]).intValue();
            ChannelSuggester.Suggestion s = ChannelSuggester.suggest(host);
            links.add(new UntaggedLinksResponse.Link(
                    host,
                    null, // ponytail: sampleUrl reserved — beacon stores host only today
                    visits,
                    new UntaggedLinksResponse.Suggested(s.source(), s.medium(), s.campaign())));
        }
        return new UntaggedLinksResponse(links);
    }

    /**
     * % of distinct buyers who placed >1 order. Mirrors
     * {@code DashboardService.repeatRatePct} — 0 when there are no orders.
     */
    private static int repeatRatePct(List<Object[]> rows) {
        if (rows.isEmpty()) return 0;
        long total = rows.size();
        long repeat = rows.stream().filter(r -> ((Number) r[1]).longValue() > 1).count();
        return (int) Math.round(100.0 * repeat / total);
    }
}
