package com.imin.iminapi.marketing.email;

import com.resend.Resend;
import com.resend.services.domains.model.Domain;
import com.resend.services.domains.model.ListDomainsResponse;
import com.resend.services.domains.model.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reads the marketing sending domain's SPF / DKIM / DMARC verification status from the Resend
 * <b>domains</b> API and caches it. Reuses the app's existing {@link Resend} SDK client — the same
 * bean that already sends mail — so there is no new HTTP dependency and no second credential.
 *
 * <p><b>Honest by construction.</b> Every failure path degrades to {@link Optional#empty()}:
 * the key lacking the {@code domains} scope (Resend answers 401/403), Resend unreachable, the
 * from-domain not registered in the account, a blank from-address, or a response shape this code
 * does not understand. The caller then renders nothing — it never substitutes a hardcoded
 * "verified". A per-record status is only ever a value Resend actually returned.
 *
 * <p>The result (present <i>or</i> empty) is cached for {@link #CACHE_TTL} so a channels read does
 * not fan out to Resend on every call, and a scope-less key is not hammered on every request.
 *
 * <p><b>Testing seam.</b> {@link #fetchRecordsForDomain(String)} is the only method that touches
 * the network; tests override it to feed canned records or simulate an error, so the suite never
 * hits Resend.
 */
@Component
public class ResendDomainsClient {

    private static final Logger log = LoggerFactory.getLogger(ResendDomainsClient.class);

    /** Cache lifetime for the domains lookup — within the 30–60s brief. Applies to hits AND misses. */
    static final Duration CACHE_TTL = Duration.ofSeconds(45);

    /**
     * Worst-status-wins order when collapsing a group of same-purpose records: a group is reported
     * {@link DnsRecordStatus#VERIFIED} only when EVERY record in it is verified; otherwise the most
     * severe non-verified status present wins.
     */
    private static final DnsRecordStatus[] SEVERITY = {
            DnsRecordStatus.FAILED,
            DnsRecordStatus.TEMPORARY_FAILURE,
            DnsRecordStatus.NOT_STARTED,
            DnsRecordStatus.PENDING,
            DnsRecordStatus.UNKNOWN
    };

    private final Resend resend;
    private final MarketingEmailProperties emailProps;

    private volatile Cached cache;

    public ResendDomainsClient(Resend resend, MarketingEmailProperties emailProps) {
        this.resend = resend;
        this.emailProps = emailProps;
    }

    /**
     * SPF/DKIM/DMARC status for the configured marketing from-domain, or {@link Optional#empty()}
     * when it cannot be read truthfully (see the class javadoc). Cached for {@link #CACHE_TTL}.
     */
    public synchronized Optional<SendingDomainDns> sendingDomainDns() {
        String domain = hostOf(emailProps.getFromAddress());
        if (domain.isBlank()) return Optional.empty();

        Instant now = Instant.now();
        Cached c = cache;
        if (c != null && c.domain.equals(domain)
                && Duration.between(c.at, now).compareTo(CACHE_TTL) < 0) {
            return c.value;
        }

        Optional<SendingDomainDns> value;
        try {
            List<DnsRecordRow> rows = fetchRecordsForDomain(domain);
            value = (rows == null) ? Optional.empty() : Optional.of(map(domain, rows, now));
        } catch (Exception e) {
            // Missing 'domains' scope, unreachable API, or a response shape change — all degrade to
            // absent. Logged at INFO, not ERROR: on a send-only key this is an expected, tolerated
            // condition, not a fault of this read.
            log.info("Resend domains lookup for '{}' unavailable ({}); DNS status omitted",
                    domain, e.getClass().getSimpleName());
            value = Optional.empty();
        }
        cache = new Cached(domain, now, value);
        return value;
    }

    /**
     * The Resend round-trip — the sole network seam, overridden in tests to avoid hitting Resend.
     * Returns the from-domain's DNS records, or {@code null} when the domain is not registered in
     * this Resend account. Throws on any API/transport error (caller degrades to empty).
     */
    protected List<DnsRecordRow> fetchRecordsForDomain(String domain) throws Exception {
        ListDomainsResponse list = resend.domains().list();
        String id = (list == null || list.getData() == null) ? null : list.getData().stream()
                .filter(d -> domain.equalsIgnoreCase(d.getName()))
                .map(com.resend.services.domains.model.AbstractDomain::getId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        if (id == null) return null;   // from-domain not in this account → honestly unknown

        Domain d = resend.domains().get(id);
        List<Record> records = (d == null || d.getRecords() == null) ? List.of() : d.getRecords();
        return records.stream()
                .map(r -> new DnsRecordRow(r.getRecord(), r.getType(), r.getStatus()))
                .toList();
    }

    /** Build the per-record aggregate view over one domain's DNS records. */
    static SendingDomainDns map(String domain, List<DnsRecordRow> rows, Instant at) {
        return new SendingDomainDns(
                domain,
                aggregate(rows, "SPF"),
                aggregate(rows, "DKIM"),
                aggregate(rows, "DMARC"),
                at);
    }

    /**
     * Collapse every record carrying {@code label} into a single status, or null when Resend
     * reported none of that kind (so the field is honestly absent rather than defaulted).
     */
    static DnsRecordStatus aggregate(List<DnsRecordRow> rows, String label) {
        List<DnsRecordStatus> group = rows.stream()
                .filter(r -> label.equalsIgnoreCase(r.record()))
                .map(r -> DnsRecordStatus.fromResend(r.status()))
                .toList();
        if (group.isEmpty()) return null;
        for (DnsRecordStatus s : SEVERITY) {
            if (group.contains(s)) return s;
        }
        return DnsRecordStatus.VERIFIED;
    }

    private static String hostOf(String fromAddress) {
        if (fromAddress == null) return "";
        int at = fromAddress.lastIndexOf('@');
        if (at < 0 || at == fromAddress.length() - 1) return "";
        return fromAddress.substring(at + 1).trim().toLowerCase(Locale.ROOT);
    }

    /** One Resend DNS record reduced to the fields the aggregation needs (purpose/type/status). */
    public record DnsRecordRow(String record, String type, String status) {}

    private record Cached(String domain, Instant at, Optional<SendingDomainDns> value) {}
}
