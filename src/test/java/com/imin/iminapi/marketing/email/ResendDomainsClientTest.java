package com.imin.iminapi.marketing.email;

import com.imin.iminapi.marketing.email.ResendDomainsClient.DnsRecordRow;
import com.resend.Resend;
import com.resend.core.exception.ResendException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Unit coverage for {@link ResendDomainsClient}. The Resend HTTP round-trip is the overridable
 * {@link ResendDomainsClient#fetchRecordsForDomain(String)} seam — every test stubs it with canned
 * records or a simulated error, so nothing here touches the network. Verifies:
 * <ul>
 *   <li>record statuses map onto SPF/DKIM/DMARC correctly, including worst-status aggregation;</li>
 *   <li>DMARC is absent (null) when Resend reports no DMARC record — not defaulted to verified;</li>
 *   <li>an unrecognised Resend status degrades to UNKNOWN, never VERIFIED;</li>
 *   <li>any API failure — and a domain not registered in the account — degrades to an ABSENT
 *       result ({@link Optional#empty()}), never a fabricated status;</li>
 *   <li>results (present AND absent) are cached so a channels read does not re-hit Resend.</li>
 * </ul>
 */
class ResendDomainsClientTest {

    private static final String FROM = "contact@imin.support";
    private static final String DOMAIN = "imin.support";

    @FunctionalInterface
    private interface RowsSupplier {
        List<DnsRecordRow> get(String domain) throws Exception;
    }

    private static MarketingEmailProperties props(String from) {
        MarketingEmailProperties p = new MarketingEmailProperties();
        p.setFromAddress(from);
        return p;
    }

    private static ResendDomainsClient client(String from, RowsSupplier supplier) {
        return new ResendDomainsClient(mock(Resend.class), props(from)) {
            @Override
            protected List<DnsRecordRow> fetchRecordsForDomain(String domain) throws Exception {
                return supplier.get(domain);
            }
        };
    }

    @Test
    void mapsVerifiedSpfAndDkim_dmarcAbsentWhenResendReportsNone() {
        ResendDomainsClient c = client(FROM, d -> List.of(
                new DnsRecordRow("SPF", "MX", "verified"),
                new DnsRecordRow("SPF", "TXT", "verified"),
                new DnsRecordRow("DKIM", "TXT", "verified"),
                new DnsRecordRow("Tracking", "CNAME", "verified")));   // unrelated record ignored

        SendingDomainDns dns = c.sendingDomainDns().orElseThrow();

        assertThat(dns.domain()).isEqualTo(DOMAIN);
        assertThat(dns.spf()).isEqualTo(DnsRecordStatus.VERIFIED);
        assertThat(dns.dkim()).isEqualTo(DnsRecordStatus.VERIFIED);
        assertThat(dns.dmarc()).isNull();          // Resend provisions no DMARC record → honest null
        assertThat(dns.checkedAt()).isNotNull();
    }

    @Test
    void spfAggregatesToWorstStatusInTheGroup() {
        // One SPF record still not started, the other verified → the group is NOT fully verified.
        ResendDomainsClient c = client(FROM, d -> List.of(
                new DnsRecordRow("SPF", "MX", "not_started"),
                new DnsRecordRow("SPF", "TXT", "verified"),
                new DnsRecordRow("DKIM", "TXT", "verified")));

        SendingDomainDns dns = c.sendingDomainDns().orElseThrow();

        assertThat(dns.spf()).isEqualTo(DnsRecordStatus.NOT_STARTED);
        assertThat(dns.dkim()).isEqualTo(DnsRecordStatus.VERIFIED);
    }

    @Test
    void failedBeatsEverythingInAggregation() {
        ResendDomainsClient c = client(FROM, d -> List.of(
                new DnsRecordRow("DKIM", "TXT", "verified"),
                new DnsRecordRow("DKIM", "TXT", "pending"),
                new DnsRecordRow("DKIM", "TXT", "failed")));

        assertThat(c.sendingDomainDns().orElseThrow().dkim()).isEqualTo(DnsRecordStatus.FAILED);
    }

    @Test
    void mapsDmarcWhenResendActuallyReportsIt() {
        ResendDomainsClient c = client(FROM, d -> List.of(
                new DnsRecordRow("SPF", "TXT", "verified"),
                new DnsRecordRow("DKIM", "TXT", "verified"),
                new DnsRecordRow("DMARC", "TXT", "pending")));

        assertThat(c.sendingDomainDns().orElseThrow().dmarc()).isEqualTo(DnsRecordStatus.PENDING);
    }

    @Test
    void unrecognisedResendStatusDegradesToUnknownNeverVerified() {
        ResendDomainsClient c = client(FROM, d -> List.of(
                new DnsRecordRow("SPF", "TXT", "some_future_state")));

        assertThat(c.sendingDomainDns().orElseThrow().spf()).isEqualTo(DnsRecordStatus.UNKNOWN);
    }

    @Test
    void apiFailureDegradesToAbsent_notFabricated() {
        ResendDomainsClient c = client(FROM, d -> {
            throw new ResendException("insufficient scope: domains");
        });

        assertThat(c.sendingDomainDns()).isEmpty();
    }

    @Test
    void domainNotRegisteredDegradesToAbsent() {
        ResendDomainsClient c = client(FROM, d -> null);   // null = from-domain not in the account

        assertThat(c.sendingDomainDns()).isEmpty();
    }

    @Test
    void blankFromAddressReturnsAbsentWithoutCallingResend() {
        AtomicInteger calls = new AtomicInteger();
        ResendDomainsClient c = client("", d -> {
            calls.incrementAndGet();
            return List.of();
        });

        assertThat(c.sendingDomainDns()).isEmpty();
        assertThat(calls.get()).isZero();
    }

    @Test
    void cachesPresentResultWithinTtl_singleFetch() {
        AtomicInteger calls = new AtomicInteger();
        ResendDomainsClient c = client(FROM, d -> {
            calls.incrementAndGet();
            return List.of(new DnsRecordRow("SPF", "TXT", "verified"));
        });

        assertThat(c.sendingDomainDns()).isPresent();
        assertThat(c.sendingDomainDns()).isPresent();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void cachesAbsentResultWithinTtl_singleFetch() {
        // A scope-less key must not be hammered on every channels read: the miss is cached too.
        AtomicInteger calls = new AtomicInteger();
        ResendDomainsClient c = client(FROM, d -> {
            calls.incrementAndGet();
            throw new ResendException("insufficient scope: domains");
        });

        assertThat(c.sendingDomainDns()).isEmpty();
        assertThat(c.sendingDomainDns()).isEmpty();
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void statusEnumMapsResendVocabulary() {
        assertThat(DnsRecordStatus.fromResend("verified")).isEqualTo(DnsRecordStatus.VERIFIED);
        assertThat(DnsRecordStatus.fromResend("PENDING")).isEqualTo(DnsRecordStatus.PENDING);
        assertThat(DnsRecordStatus.fromResend("not_started")).isEqualTo(DnsRecordStatus.NOT_STARTED);
        assertThat(DnsRecordStatus.fromResend("temporary_failure"))
                .isEqualTo(DnsRecordStatus.TEMPORARY_FAILURE);
        assertThat(DnsRecordStatus.fromResend("failed")).isEqualTo(DnsRecordStatus.FAILED);
        assertThat(DnsRecordStatus.fromResend(null)).isEqualTo(DnsRecordStatus.UNKNOWN);
        assertThat(DnsRecordStatus.fromResend("whatever")).isEqualTo(DnsRecordStatus.UNKNOWN);
    }
}
