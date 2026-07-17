package com.imin.iminapi.audience;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.marketing.dto.PreviewAudienceResponse;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Spec §4 sixth exclusion class: "no deliverable address" (email) → reason {@code no_email}.
 *
 * <p><b>Why mocks rather than the H2/Postgres fixture.</b> A membership whose consumer row is
 * absent cannot be constructed through the persistence layer: {@code memberships.consumer_id}
 * is {@code NOT NULL REFERENCES consumers(consumer_id)} (V48) with no ON DELETE action, and the
 * only consumer-delete path ({@code DsarService.executeErase}) deletes the membership first and
 * then drops the consumer only when {@code countMembershipsByConsumerId == 0}. The orphan state
 * is therefore unreachable today — the null-address branch is defence-in-depth. Mocking the
 * repositories is the only way to drive that branch, and it pins the gate's behaviour so a future
 * schema or erasure-ordering change cannot reintroduce a silent pass into {@code sendable}.
 *
 * <p>The invariant under test is stronger than any single bucket: a membership with no address
 * must NEVER appear in {@code sendable}.
 */
class SendGateNoEmailTest {

    private MembershipRepository membershipRepo;
    private ConsumerRepository consumerRepo;
    private SuppressionRepository suppressionRepo;
    private SendGateService gate;

    private final UUID orgA = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        membershipRepo = mock(MembershipRepository.class);
        consumerRepo = mock(ConsumerRepository.class);
        suppressionRepo = mock(SuppressionRepository.class);
        gate = new SendGateService(membershipRepo, consumerRepo, suppressionRepo, mock(AuditLogger.class));

        // Default: nothing suppressed in either scope. Individual tests override.
        when(suppressionRepo.findMarketingSuppressedMembershipIds(any(), anyCollection()))
                .thenReturn(List.of());
        when(suppressionRepo.findDeliverabilityEmailsIn(anyCollection())).thenReturn(List.of());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    /** A fully sendable membership: subscribed + explicit basis. */
    private Membership member(UUID consumerId) {
        Membership m = new Membership();
        m.setMembershipId(UUID.randomUUID());
        m.setOrgId(orgA);
        m.setConsumerId(consumerId);
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        return m;
    }

    private Consumer consumer(UUID consumerId, String email) {
        Consumer c = new Consumer();
        c.setConsumerId(consumerId);
        c.setNormalizedEmail(email);
        return c;
    }

    /** Wire the gate's two batch loads: which memberships exist, and which consumers resolve. */
    private void given(List<Membership> memberships, List<Consumer> resolvableConsumers) {
        when(membershipRepo.findByIdsAndOrgId(anyCollection(), any())).thenReturn(memberships);
        when(consumerRepo.findAllByConsumerIdIn(anyCollection())).thenReturn(resolvableConsumers);
    }

    private List<UUID> ids(List<Membership> ms) {
        return ms.stream().map(Membership::getMembershipId).toList();
    }

    // ── the sixth exclusion class ───────────────────────────────────────────

    /** A membership whose consumer row is missing → excluded as no_email, NOT sendable. */
    @Test
    void orphaned_membership_is_excluded_as_no_email_and_never_sendable() {
        Membership orphan = member(UUID.randomUUID());
        // The consumer row is absent → findAllByConsumerIdIn resolves nothing.
        given(List.of(orphan), List.of());

        SendGateService.GateResult r = gate.evaluate(orgA, ids(List.of(orphan)));

        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).hasSize(1);
        assertThat(r.excluded().get(0).membershipId()).isEqualTo(orphan.getMembershipId());
        assertThat(r.excluded().get(0).reason()).isEqualTo("no_email");
    }

    /**
     * A no-email member must NOT be mislabelled deliverability_suppressed. An address we do not
     * have was never checked against the suppression list — claiming otherwise would be a false
     * audit record.
     */
    @Test
    void no_email_member_is_not_counted_as_deliverability_suppressed() {
        Membership orphan = member(UUID.randomUUID());
        given(List.of(orphan), List.of());
        // Even with a non-empty deliverability list in play, the orphan is not attributed to it.
        when(suppressionRepo.findDeliverabilityEmailsIn(anyCollection()))
                .thenReturn(List.of("someone-else@example.com"));

        SendGateService.GateResult r = gate.evaluate(orgA, ids(List.of(orphan)));

        assertThat(r.excluded()).extracting(ExclusionReason::reason)
                .containsExactly("no_email")
                .doesNotContain("deliverability_suppressed");

        PreviewAudienceResponse preview = SendGateService.bucket(r);
        assertThat(preview.excluded().noEmail()).isEqualTo(1);
        assertThat(preview.excluded().deliverabilitySuppressed()).isZero();
    }

    /** The preview response's noEmail bucket increments; a normal member is unaffected. */
    @Test
    void preview_counts_bucket_no_email_alongside_an_unaffected_normal_member() {
        UUID goodConsumer = UUID.randomUUID();
        Membership good = member(goodConsumer);
        Membership orphan = member(UUID.randomUUID());
        given(List.of(good, orphan), List.of(consumer(goodConsumer, "ok@example.com")));

        PreviewAudienceResponse preview =
                gate.previewCounts(orgA, ids(List.of(good, orphan)), "email");

        // The member with an address is untouched by the new clause.
        assertThat(preview.sendable()).isEqualTo(1);
        assertThat(preview.excluded().noEmail()).isEqualTo(1);
        assertThat(preview.excluded().deliverabilitySuppressed()).isZero();
        assertThat(preview.excluded().noBasis()).isZero();
        assertThat(preview.excluded().unsubscribed()).isZero();
        assertThat(preview.excluded().marketingSuppressed()).isZero();
    }

    /** A member WITH an address still resolves normally through every downstream clause. */
    @Test
    void member_with_an_address_is_unaffected_by_the_new_clause() {
        UUID cid = UUID.randomUUID();
        Membership good = member(cid);
        given(List.of(good), List.of(consumer(cid, "fine@example.com")));

        SendGateService.GateResult r = gate.evaluate(orgA, ids(List.of(good)));

        assertThat(r.sendable()).containsExactly(good.getMembershipId());
        assertThat(r.excluded()).isEmpty();
    }

    /** A member WITH an address that IS deliverability-suppressed still lands in that bucket. */
    @Test
    void deliverability_suppression_still_applies_when_an_address_exists() {
        UUID cid = UUID.randomUUID();
        Membership bounced = member(cid);
        given(List.of(bounced), List.of(consumer(cid, "bounce@example.com")));
        when(suppressionRepo.findDeliverabilityEmailsIn(anyCollection()))
                .thenReturn(List.of("bounce@example.com"));

        SendGateService.GateResult r = gate.evaluate(orgA, ids(List.of(bounced)));

        assertThat(r.sendable()).isEmpty();
        assertThat(r.excluded()).extracting(ExclusionReason::reason)
                .containsExactly("deliverability_suppressed");
    }

    // ── the invariant ───────────────────────────────────────────────────────

    /**
     * REGRESSION PIN — the invariant, independent of which bucket the member lands in:
     * a membership with no resolvable address can NEVER appear in sendable, under ANY
     * combination of consent status, lawful basis, and suppression state.
     *
     * <p>This is the exact defect being fixed: the old clause 3 read
     * {@code if (email != null && deliverabilityBlocked.contains(email))}, so a null address
     * skipped the deliverability check, passed the basis clause, and fell into sendable.
     */
    @Test
    void invariant_a_null_address_can_never_appear_in_sendable() {
        List<Membership> orphans = new ArrayList<>();
        for (String status : List.of("subscribed", "never", "unsubscribed")) {
            for (String basis : new String[]{"explicit", "legitimate_interest", null}) {
                Membership m = new Membership();
                m.setMembershipId(UUID.randomUUID());
                m.setOrgId(orgA);
                m.setConsumerId(UUID.randomUUID()); // consumer row deliberately absent
                m.setConsentStatus(status);
                m.setConsentBasis(basis);
                orphans.add(m);
            }
        }
        // No consumer resolves → every one of these has a null address.
        given(orphans, List.of());

        SendGateService.GateResult r = gate.evaluate(orgA, ids(orphans));

        assertThat(r.sendable())
                .as("a membership with no deliverable address must never be sendable")
                .isEmpty();
        // Every one is accounted for — none silently vanishes.
        assertThat(r.excluded()).hasSize(orphans.size());
        assertThat(SendGateService.bucket(r).sendable()).isZero();
    }

    /** Org scoping is unchanged: IDs the repo does not return for this org are never evaluated. */
    @Test
    void org_scoping_unchanged_cross_org_ids_are_not_leaked_as_no_email() {
        UUID foreignId = UUID.randomUUID();
        // Tenant isolation happens in the query: a cross-org ID yields no membership row.
        given(List.of(), List.of());

        SendGateService.GateResult r = gate.evaluate(orgA, List.of(foreignId));

        assertThat(r.sendable()).isEmpty();
        // Critically: must NOT surface as no_email (that would leak existence).
        assertThat(r.excluded()).isEmpty();
    }

    /** Sanity: the gate does not query deliverability at all when no address resolves. */
    @Test
    void no_deliverability_lookup_is_attempted_when_no_address_resolves() {
        Membership orphan = member(UUID.randomUUID());
        given(List.of(orphan), List.of());

        gate.evaluate(orgA, ids(List.of(orphan)));

        // evaluate() short-circuits the suppression query on an empty email set.
        org.mockito.Mockito.verify(suppressionRepo, org.mockito.Mockito.never())
                .findDeliverabilityEmailsIn(any(Collection.class));
    }
}
