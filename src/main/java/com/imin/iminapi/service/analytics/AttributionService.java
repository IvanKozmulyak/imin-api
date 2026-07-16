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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        // TRUE per-order last-touch revenue by channel (V62): orders now carry the landing
        // utm_source, stamped at checkout and snapshotted at fulfilment, so each order's full
        // total is counted ONCE against the channel the buyer actually arrived through. This
        // replaces the old tagged-visit-SHARE approximation, which had to divide the org's
        // whole revenue pool across channels because orders carried no utm key to join on.
        //
        // Consequences of the switch, both deliberate:
        //  - Revenue from orders placed BEFORE V62 has no utm_source and is no longer counted
        //    for any channel. It cannot be back-filled — the tag was never captured — so it
        //    reports as unattributed rather than being spread across channels on a guess.
        //  - A channel with visits but no paid orders now correctly reads 0 revenue instead of
        //    receiving a share of unrelated revenue.
        Map<String, Long> revenueBySource = new HashMap<>();
        for (Object[] r : orders.sumRevenueByUtmSource(p.orgId())) {
            revenueBySource.put((String) r[0], ((Number) r[1]).longValue());
        }

        List<AttributionResponse.Channel> channels = new ArrayList<>();
        tagged.sort(Comparator.comparingLong(Bucket::visits).reversed()
                .thenComparing(Bucket::source));
        for (Bucket b : tagged) {
            long revenue = revenueBySource.getOrDefault(b.source(), 0L);
            channels.add(new AttributionResponse.Channel(b.source(), Math.max(0, revenue), (int) b.visits()));
        }

        int untaggedPct = totalVisits == 0 ? 0
                : (int) Math.round(100.0 * untaggedVisits / totalVisits);

        // Reuse the existing repeat-rate definition: share of distinct buyers
        // (by email) who placed >1 order. Instant.EPOCH = all-time window.
        int repeatBuyerPct = repeatRatePct(orders.orderCountsByEmailSince(p.orgId(), Instant.EPOCH));

        // Total attributed revenue = the sum actually assigned to channels above. A source
        // with orders but no recorded visits still counts as attributed revenue, so sum the
        // map rather than the (visit-derived) channel list.
        long attributedRevenueMinor = revenueBySource.values().stream()
                .mapToLong(Long::longValue).filter(v -> v > 0).sum();

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
