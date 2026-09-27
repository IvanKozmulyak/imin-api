package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.PrebuiltSegment;
import com.imin.iminapi.audience.service.SegmentRuleRow;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.service.audit.AuditLogger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * audience-10: {@code GET /audience/segments} resolves EVERY segment on every call, and a
 * custom segment resolved through findAllByOrgId — the whole memberships table, hydrated as
 * entities, once per custom segment per dashboard load. The Audience tab only ever wanted a
 * number, so liveCount() answers with a count query or a narrow projection instead.
 */
class SegmentLiveCountTest {

    private MembershipRepository membershipRepo;
    private com.imin.iminapi.audienceplan.service.ConsentGate consentGate;
    private SegmentService segmentService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        membershipRepo = mock(MembershipRepository.class);
        consentGate = mock(com.imin.iminapi.audienceplan.service.ConsentGate.class);
        segmentService = new SegmentService(mock(SegmentRepository.class), membershipRepo,
                mock(OrganizationRepository.class), mock(AuditLogger.class),
                mock(com.imin.iminapi.audienceplan.repository.FanFeatureRepository.class),
                mock(com.imin.iminapi.repository.EventRepository.class),
                consentGate,
                mock(com.imin.iminapi.audienceplan.config.AudiencePlanLogic.class));
    }

    @Test
    void a_prebuilt_segment_counts_with_its_indexed_query() {
        when(membershipRepo.countVips(orgId)).thenReturn(7L);

        assertThat(segmentService.liveCount(orgId, prebuilt(PrebuiltSegment.VIP))).isEqualTo(7);

        verify(membershipRepo, never()).findAllByOrgId(any());
        verify(membershipRepo, never()).findVips(any());
    }

    @Test
    void a_custom_segment_counts_over_a_projection_not_hydrated_entities() {
        when(membershipRepo.findRuleRowsByOrgId(orgId)).thenReturn(List.of(
                row(5), row(1), row(3)));

        Segment custom = new Segment();
        custom.setOrgId(orgId);
        custom.setName("Regulars");
        custom.setKind("dynamic");
        custom.setRulesJson("[{\"field\":\"events\",\"operator\":\">=\",\"value\":\"3\"}]");

        assertThat(segmentService.liveCount(orgId, custom)).isEqualTo(2);

        verify(membershipRepo, never()).findAllByOrgId(any());
    }

    @Test
    void a_static_segment_counts_its_snapshot_ids() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(membershipRepo.countByIdsAndOrgId(List.of(a, b), orgId)).thenReturn(2L);

        Segment snap = new Segment();
        snap.setOrgId(orgId);
        snap.setName("Frozen");
        snap.setKind("static");
        snap.setSnapshotIds("[\"" + a + "\",\"" + b + "\"]");

        assertThat(segmentService.liveCount(orgId, snap)).isEqualTo(2);

        verify(membershipRepo, never()).findByIdsAndOrgId(any(), any());
    }

    /** Same rule as resolution: rules the engine cannot read count nobody (audience-5). */
    @Test
    void an_unreadable_rule_set_counts_zero() {
        Segment broken = new Segment();
        broken.setOrgId(orgId);
        broken.setName("Truncated");
        broken.setKind("dynamic");
        broken.setRulesJson("[{\"field\":\"events\",\"operator\"");
        when(membershipRepo.findRuleRowsByOrgId(orgId)).thenReturn(List.of(row(9)));

        assertThat(segmentService.liveCount(orgId, broken)).isZero();
    }

    /**
     * audience-20: numeric-vs-string was chosen by whether the VALUE parsed as a long, so a
     * rule on an enum field with a numeric-looking value took the numeric branch, where an
     * unknown field fell through to 0 — and {@code consent_status == 0} matched everyone.
     */
    @Test
    void an_enum_rule_with_a_numeric_value_matches_nobody() {
        when(membershipRepo.findRuleRowsByOrgId(orgId)).thenReturn(List.of(row(1), row(2)));

        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Enum rule");
        seg.setKind("dynamic");
        seg.setRulesJson("[{\"field\":\"consent_status\",\"operator\":\"==\",\"value\":\"0\"}]");

        assertThat(segmentService.liveCount(orgId, seg)).isZero();
    }

    @Test
    void an_enum_rule_with_a_real_value_still_matches() {
        when(membershipRepo.findRuleRowsByOrgId(orgId)).thenReturn(List.of(row(1), row(2)));

        Segment seg = new Segment();
        seg.setOrgId(orgId);
        seg.setName("Subscribed");
        seg.setKind("dynamic");
        seg.setRulesJson("[{\"field\":\"consent_status\",\"operator\":\"==\",\"value\":\"subscribed\"}]");

        assertThat(segmentService.liveCount(orgId, seg)).isEqualTo(2);
    }

    /** matched counts only members the gate gave a verdict for, so it always equals mailable + excluded. */
    @Test
    void a_member_the_gate_returns_no_verdict_for_is_not_counted_as_matched() {
        com.imin.iminapi.audience.model.Membership covered = membership(10_000);
        com.imin.iminapi.audience.model.Membership unsubscribed = membership(10_000);
        com.imin.iminapi.audience.model.Membership uncovered = membership(10_000);
        when(membershipRepo.findAllByOrgId(orgId)).thenReturn(List.of(covered, unsubscribed, uncovered));
        when(consentGate.reasons(eq(orgId), any())).thenReturn(java.util.Map.of(
                covered.getMembershipId(), java.util.Optional.empty(),
                unsubscribed.getMembershipId(), java.util.Optional.of("unsubscribed")));

        var dto = segmentService.previewRules(orgId, null);

        assertThat(dto.matched()).isEqualTo(2);
        assertThat(dto.mailable()).isEqualTo(1);
        assertThat(dto.excluded()).isEqualTo(1);
        assertThat(dto.exclusions()).containsEntry("unsubscribed", 1);
        assertThat(dto.exclusions().values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(1);
    }

    private com.imin.iminapi.audience.model.Membership membership(long spend) {
        com.imin.iminapi.audience.model.Membership m = new com.imin.iminapi.audience.model.Membership();
        m.setMembershipId(UUID.randomUUID());
        m.setOrgId(orgId);
        m.setSpendMinor(spend);
        return m;
    }

    private Segment prebuilt(PrebuiltSegment key) {
        Segment s = new Segment();
        s.setOrgId(orgId);
        s.setName(key.displayName());
        s.setKind("dynamic");
        s.setPrebuilt(true);
        s.setPrebuiltKey(key.key());
        s.setRulesJson(key.rulesJson());
        return s;
    }

    private SegmentRuleRow row(int events) {
        return new SegmentRuleRow(UUID.randomUUID(), events, 0L, null, 0, null, "repeat", "subscribed", "explicit");
    }
}
