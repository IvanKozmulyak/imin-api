package com.imin.iminapi.service.analytics;

import com.imin.iminapi.dto.analytics.AttributionResponse;
import com.imin.iminapi.dto.analytics.UntaggedLinksResponse;
import com.imin.iminapi.repository.FunnelEventRepository;
import com.imin.iminapi.repository.OrderRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
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

    /** Every client. */
    @Transactional(readOnly = true)
    public AttributionResponse attribution(AuthPrincipal p) {
        return attribution(p, null);
    }

    /**
     * @param client optional slice: {@code "web"}, {@code "ios"} or
     *               {@code "android"}. Null (or anything unrecognised) means
     *               every client, which is the pre-app behaviour.
     *
     * <p>The filter applies to <b>visits only</b>, not to revenue. Orders carry
     * no client column, so a per-client revenue figure would have to be
     * apportioned — an invented number, and the one thing this read-model must
     * not produce. A sliced call therefore reports that client's visits against
     * the org's real revenue; read it as traffic mix, not as per-client ROAS.
     */
    @Transactional(readOnly = true)
    public AttributionResponse attribution(AuthPrincipal p, String client) {
        // Visitors are distinct anon ids: reloads and a checkout start from one browser count once.
        record Bucket(String source, long visitors) {}
        List<Bucket> tagged = new ArrayList<>();
        for (Object[] r : visitorsBySource(p, client)) {
            String source = (String) r[0];
            if (source == null || source.isBlank()) continue;
            tagged.add(new Bucket(source, ((Number) r[1]).longValue()));
        }

        // Each live tagged order counts once under its landing source, less SUCCEEDED refunds and LOST
        // chargebacks, clamped at zero (NetOrderRevenue); untagged (pre-V62) orders stay unattributed.
        Map<String, Long> revenueBySource = NetOrderRevenue.sumByKey(orders.revenueRowsByUtmSource(p.orgId()));

        List<AttributionResponse.Channel> channels = new ArrayList<>();
        tagged.sort(Comparator.comparingLong(Bucket::visitors).reversed()
                .thenComparing(Bucket::source));
        for (Bucket b : tagged) {
            long revenue = revenueBySource.getOrDefault(b.source(), 0L);
            channels.add(new AttributionResponse.Channel(b.source(), revenue, (int) b.visitors()));
        }

        // Share of visitors with at least one untagged beacon; per-source rows would double count
        // a visitor seen on two sources, so this reads its own distinct totals.
        Object[] totals = visitorTotals(p, client);
        long visitors = ((Number) totals[0]).longValue();
        long untaggedVisitors = ((Number) totals[1]).longValue();
        int untaggedPct = visitors == 0 ? 0
                : (int) Math.round(100.0 * untaggedVisitors / visitors);

        // A source with orders but no recorded visitors still counts, so sum the map, not the channels.
        long attributedRevenueMinor = revenueBySource.values().stream()
                .mapToLong(Long::longValue).sum();

        return new AttributionResponse(attributedRevenueMinor, untaggedPct, channels);
    }

    @Transactional(readOnly = true)
    public UntaggedLinksResponse untagged(AuthPrincipal p) {
        var rows = funnel.countUntaggedByReferrerHostForOrg(p.orgId());
        List<UntaggedLinksResponse.Link> links = new ArrayList<>();
        for (Object[] r : rows) {
            if (links.size() >= UNTAGGED_LINKS_LIMIT) break;
            String host = (String) r[0];
            int visits = ((Number) r[1]).intValue(); // distinct visitors
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
     * Picks the query for the requested client slice. Three separate queries,
     * never one with a nullable bind: a null {@code String} in a comparison is
     * the {@code lower(bytea)} trap that 500s on Postgres.
     */
    private List<Object[]> visitorsBySource(AuthPrincipal p, String client) {
        return switch (slice(client)) {
            case "web" -> funnel.countWebVisitsBySourceForOrg(p.orgId());
            case "ios", "android" -> funnel.countVisitsBySourceForOrgAndClient(p.orgId(), slice(client));
            default -> funnel.countVisitsBySourceForOrg(p.orgId());
        };
    }

    /** {@code [visitors, untaggedVisitors]} for the same slice as {@link #visitorsBySource}. */
    private Object[] visitorTotals(AuthPrincipal p, String client) {
        List<Object[]> rows = switch (slice(client)) {
            case "web" -> funnel.countWebVisitorsAndUntaggedForOrg(p.orgId());
            case "ios", "android" -> funnel.countVisitorsAndUntaggedForOrgAndClient(p.orgId(), slice(client));
            default -> funnel.countVisitorsAndUntaggedForOrg(p.orgId());
        };
        return rows.get(0);
    }

    /**
     * The client slice, or {@code "all"}. An unrecognised label is not an empty result set — an
     * empty chart reads as "nobody came from there", a fabricated fact — so it means every client.
     */
    private static String slice(String client) {
        if (client == null || client.isBlank()) return "all";
        String c = client.trim().toLowerCase();
        return switch (c) {
            case "web", "ios", "android" -> c;
            default -> "all";
        };
    }
}
