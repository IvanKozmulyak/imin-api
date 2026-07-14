package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.dto.ExclusionReason;
import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.config.TestRateLimitConfig;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@SpringBootTest
@Import(TestRateLimitConfig.class)
class RecipientMaterializerTest {

    @Autowired RecipientMaterializer materializer;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @MockitoBean SendGateService sendGate;
    @MockitoBean SegmentService segmentService;

    private Campaign persistedCampaign(UUID orgId, UUID segmentId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Test");
        c.setStatus("sending");
        c.setSegmentId(segmentId);
        c.setSubject("Subj");
        c.setBodyMd("Hi");
        // Campaign has NO @PrePersist; created_at/updated_at are NOT NULL and are set by
        // CampaignService at creation time — mirror that here so the insert satisfies the schema.
        Instant now = Instant.now();
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return campaigns.save(c);
    }

    private Membership persistMember(UUID orgId, String email) {
        // Consumer.consumerId and Membership.membershipId are @GeneratedValue(UUID) — let JPA
        // assign them (setting a non-null id makes save() a merge → optimistic-lock failure on
        // an absent row). Mirrors the working ConsentServiceChannelTest fixture.
        Consumer con = new Consumer();
        con.setNormalizedEmail(email);
        con = consumers.save(con);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(con.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m.setDisplayName("Test Person");
        return memberships.save(m);
    }

    @Test
    void materializesSendableAndSkippedRows_idempotently() {
        UUID orgId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        Membership sendable = persistMember(orgId, "ok-" + UUID.randomUUID() + "@example.com");
        Membership excluded = persistMember(orgId, "no-" + UUID.randomUUID() + "@example.com");
        Campaign c = persistedCampaign(orgId, segmentId);

        // Real SegmentService surface: requireSegmentForOrg(orgId, segmentId) -> Segment,
        // then resolveMembers(orgId, segment) -> List<Membership>. NO resolveMembershipIds.
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        when(segmentService.requireSegmentForOrg(any(), any())).thenReturn(seg);
        when(segmentService.resolveMembers(any(), any()))
                .thenReturn(List.of(sendable, excluded));
        when(sendGate.evaluate(any(), anyCollection())).thenReturn(
                new SendGateService.GateResult(List.of(sendable.getMembershipId()),
                        List.of(new ExclusionReason(excluded.getMembershipId(), "no_lawful_basis"))));

        materializer.materialize(c);
        assertThat(recipients.countByCampaignId(c.getId())).isEqualTo(2L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(1L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "skipped")).isEqualTo(1L);

        // exclusion_summary must round-trip as the serialized JSON String the materializer wrote
        // (guards against a jsonb/varchar column-type mismatch — see Phase-1 dependency line 21).
        Campaign reloaded = campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow();
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"no_lawful_basis\":1}");

        // Idempotent: a second materialize (crash-resume) must not duplicate.
        materializer.materialize(c);
        assertThat(recipients.countByCampaignId(c.getId())).isEqualTo(2L);
    }

    @Test
    void frequencyCappedSendableMemberIsMaterializedAsSkipped() {
        UUID orgId = UUID.randomUUID();
        UUID segmentId = UUID.randomUUID();
        Membership member = persistMember(orgId, "freq-" + UUID.randomUUID() + "@example.com");

        // Seed a recent send for this member on a PRIOR campaign so the frequency floor trips.
        Campaign prior = persistedCampaign(orgId, segmentId);
        com.imin.iminapi.marketing.model.CampaignRecipient recent =
                new com.imin.iminapi.marketing.model.CampaignRecipient();
        recent.setId(UUID.randomUUID());
        recent.setCampaignId(prior.getId());
        recent.setMembershipId(member.getMembershipId());
        recent.setEmail("m@example.com");
        recent.setStatus("sent");
        recent.setLastEventAt(Instant.now().minus(1, java.time.temporal.ChronoUnit.HOURS));
        recipients.save(recent);

        Campaign c = persistedCampaign(orgId, segmentId);
        Segment seg = new Segment();
        seg.setOrgId(orgId);
        when(segmentService.requireSegmentForOrg(any(), any())).thenReturn(seg);
        when(segmentService.resolveMembers(any(), any())).thenReturn(List.of(member));
        // SendGate says the member is sendable; the frequency floor is the materializer's own gate.
        when(sendGate.evaluate(any(), anyCollection())).thenReturn(
                new SendGateService.GateResult(List.of(member.getMembershipId()), List.of()));

        materializer.materialize(c);

        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "pending")).isEqualTo(0L);
        assertThat(recipients.countByCampaignIdAndStatus(c.getId(), "skipped")).isEqualTo(1L);
        Campaign reloaded = campaigns.findByIdAndOrgId(c.getId(), orgId).orElseThrow();
        assertThat(reloaded.getRecipientCount()).isEqualTo(0);
        assertThat(reloaded.getExcludedCount()).isEqualTo(1);
        assertThat(reloaded.getExclusionSummary()).isEqualTo("{\"frequency_capped\":1}");
    }
}
