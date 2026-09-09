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
import static org.mockito.Mockito.*;

/**
 * audience-10: {@code GET /audience/segments} resolves EVERY segment on every call, and a
 * custom segment resolved through findAllByOrgId — the whole memberships table, hydrated as
 * entities, once per custom segment per dashboard load. The Audience tab only ever wanted a
 * number, so liveCount() answers with a count query or a narrow projection instead.
 */
class SegmentLiveCountTest {

    private MembershipRepository membershipRepo;
    private SegmentService segmentService;

    private final UUID orgId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        membershipRepo = mock(MembershipRepository.class);
        segmentService = new SegmentService(mock(SegmentRepository.class), membershipRepo,
                mock(OrganizationRepository.class), mock(AuditLogger.class));
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
        return new SegmentRuleRow(events, 0L, null, 0, null, "repeat", "subscribed", "explicit");
    }
}
