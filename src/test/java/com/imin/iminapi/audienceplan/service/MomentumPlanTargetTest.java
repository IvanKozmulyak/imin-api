package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanLogic;
import com.imin.iminapi.audienceplan.config.LogicLoader;
import com.imin.iminapi.audienceplan.engine.CandidateBuilder;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Confidence;
import com.imin.iminapi.audienceplan.engine.ResponseModel.Fit;
import com.imin.iminapi.audienceplan.model.AudiencePlan;
import com.imin.iminapi.audienceplan.model.AudiencePlanSegment;
import com.imin.iminapi.audienceplan.repository.AudienceAssignmentRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanSegmentRepository;
import com.imin.iminapi.model.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** One test per branch of choosing a Momentum target from the event's current audience plan. */
class MomentumPlanTargetTest {

    static final AudiencePlanLogic LOGIC = shippedLogic();

    final AudiencePlanAccess access = mock(AudiencePlanAccess.class);
    final AudiencePlanRepository plans = mock(AudiencePlanRepository.class);
    final AudiencePlanSegmentRepository planSegments = mock(AudiencePlanSegmentRepository.class);
    final CandidateLoader candidates = mock(CandidateLoader.class);
    final AudienceAssignmentRepository assignments = mock(AudienceAssignmentRepository.class);
    final SegmentRepository segments = mock(SegmentRepository.class);
    final MomentumPlanTarget target =
            new MomentumPlanTarget(access, plans, planSegments, candidates, assignments, segments, LOGIC);

    UUID orgId;
    Event event;
    AudiencePlan plan;

    @BeforeEach
    void setUp() {
        orgId = UUID.randomUUID();
        event = new Event();
        event.setId(UUID.randomUUID());
        event.setOrgId(orgId);
        event.setName("Night Kit");
        plan = new AudiencePlan();
        plan.setId(UUID.randomUUID());
        plan.setOrgId(orgId);
        plan.setEventId(event.getId());
        plan.setTargetTickets(255);
        plan.setTicketsPerOrder(1.6);
        when(access.isEnabled(orgId)).thenReturn(true);
        when(access.sendsEnabled()).thenReturn(true);
        when(plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(orgId, event.getId()))
                .thenReturn(Optional.of(plan));
        when(assignments.findHeldOut(any(), any(), anyCollection())).thenReturn(List.of());
    }

    @Test
    void minimumIsTheLogicFilesSmallestShownSegment() {
        assertThat(LOGIC.logic().minSegmentToShow()).isEqualTo(10);
    }

    @Test
    void betaOff_noTarget_andNothingRead() {
        when(access.isEnabled(orgId)).thenReturn(false);
        assertThat(target.best(event)).isEmpty();
        verifyNoInteractions(plans, candidates);
    }

    @Test
    void audienceSendsOff_noTarget_andNothingRead() {
        when(access.sendsEnabled()).thenReturn(false);
        stored(seg(0, "loyal", "same", 40, 16));
        built(segment("loyal", Fit.SAME, ids(40)));

        assertThat(target.best(event)).isEmpty();
        verifyNoInteractions(plans, planSegments, candidates, assignments);
    }

    @Test
    void audienceSendsOn_targetsThePlanSegment() {
        stored(seg(0, "loyal", "same", 40, 16));
        List<UUID> loyal = ids(40);
        built(segment("loyal", Fit.SAME, loyal));

        assertThat(target.best(event).orElseThrow().membershipIds()).containsExactlyElementsOf(loyal);
        verify(access).sendsEnabled();
    }

    @Test
    void noCurrentPlan_noTarget() {
        when(plans.findFirstByOrgIdAndEventIdAndSupersededByIsNullOrderByCreatedAtDesc(orgId, event.getId()))
                .thenReturn(Optional.empty());
        assertThat(target.best(event)).isEmpty();
        verifyNoInteractions(candidates);
    }

    @Test
    void noShownSegmentOfTenMailable_noTarget() {
        stored(seg(0, "loyal", "same", 9, 40));
        assertThat(target.best(event)).isEmpty();
        verifyNoInteractions(candidates);
    }

    @Test
    void coldPlanWithoutSegments_noTarget() {
        stored();
        assertThat(target.best(event)).isEmpty();
    }

    @Test
    void picksTheHighestExpectedMid_amongSegmentsOfTenOrMore() {
        // position 0 has the highest mid but only 9 mailable, so it is not eligible.
        stored(seg(0, "loyal", "same", 9, 90), seg(1, "repeat", "same", 70, 13), seg(2, "first_timer", "same", 235, 23));
        List<UUID> ftIds = ids(235);
        built(segment("repeat", Fit.SAME, ids(70)), segment("first_timer", Fit.SAME, ftIds));

        MomentumPlanTarget.Target t = target.best(event).orElseThrow();

        assertThat(t.classKey()).isEqualTo("first_timer");
        assertThat(t.genreFit()).isEqualTo("same");
        assertThat(t.membershipIds()).containsExactlyElementsOf(ftIds);
        verify(candidates).build(orgId, event, 255, 1.6);
    }

    @Test
    void equalExpectedMid_keepsTheLowerPosition() {
        stored(seg(1, "repeat", "adjacent", 20, 13), seg(0, "loyal", "same", 40, 13));
        List<UUID> loyal = ids(40);
        built(segment("repeat", Fit.ADJACENT, ids(20)), segment("loyal", Fit.SAME, loyal));

        assertThat(target.best(event).orElseThrow().classKey()).isEqualTo("loyal");
    }

    @Test
    void segmentNoLongerDerivedNow_noTarget() {
        stored(seg(0, "loyal", "same", 40, 16));
        built(segment("loyal", Fit.ADJACENT, ids(40)));
        assertThat(target.best(event)).isEmpty();
    }

    @Test
    void holdoutMembersOfTheEvent_areRemoved() {
        stored(seg(0, "loyal", "same", 40, 16));
        List<UUID> members = ids(40);
        built(segment("loyal", Fit.SAME, members));
        List<UUID> held = List.of(members.get(0), members.get(7));
        when(assignments.findHeldOut(orgId, event.getId(), members)).thenReturn(held);

        MomentumPlanTarget.Target t = target.best(event).orElseThrow();

        assertThat(t.membershipIds()).hasSize(38).doesNotContainAnyElementsOf(held);
        verify(assignments).findHeldOut(orgId, event.getId(), members);
    }

    @Test
    void holdoutLookup_isChunkedByTheQueryLimit() {
        stored(seg(0, "loyal", "same", 2500, 16));
        List<UUID> members = ids(2500);
        built(segment("loyal", Fit.SAME, members));
        List<UUID> first = members.subList(0, 1000);
        List<UUID> second = members.subList(1000, 2000);
        List<UUID> last = members.subList(2000, 2500);
        when(assignments.findHeldOut(orgId, event.getId(), first)).thenReturn(List.of(members.get(3)));
        when(assignments.findHeldOut(orgId, event.getId(), last)).thenReturn(List.of(members.get(2499)));

        MomentumPlanTarget.Target t = target.best(event).orElseThrow();

        assertThat(t.membershipIds()).hasSize(2498).doesNotContain(members.get(3), members.get(2499));
        verify(assignments).findHeldOut(orgId, event.getId(), first);
        verify(assignments).findHeldOut(orgId, event.getId(), second);
        verify(assignments).findHeldOut(orgId, event.getId(), last);
        verify(assignments, times(3)).findHeldOut(eq(orgId), eq(event.getId()), anyCollection());
    }

    @Test
    void fewerThanTenLeftAfterHoldouts_noTarget() {
        stored(seg(0, "loyal", "same", 12, 16));
        List<UUID> members = ids(12);
        built(segment("loyal", Fit.SAME, members));
        when(assignments.findHeldOut(orgId, event.getId(), members)).thenReturn(members.subList(0, 3));

        assertThat(target.best(event)).isEmpty();
    }

    @Test
    void snapshot_writesAStaticSegmentOfExactlyTheTarget() {
        List<UUID> members = ids(12);
        when(segments.save(any(Segment.class))).thenAnswer(inv -> {
            Segment s = inv.getArgument(0);
            s.setId(UUID.randomUUID());
            return s;
        });

        UUID id = target.snapshot(orgId, "Night Kit", new MomentumPlanTarget.Target("loyal", "same", members));

        ArgumentCaptor<Segment> saved = ArgumentCaptor.forClass(Segment.class);
        verify(segments).save(saved.capture());
        Segment s = saved.getValue();
        assertThat(id).isEqualTo(s.getId());
        assertThat(s.getOrgId()).isEqualTo(orgId);
        assertThat(s.getKind()).isEqualTo("static");
        assertThat(s.isPrebuilt()).isFalse();
        assertThat(s.getOrigin()).isEqualTo(Segment.ORIGIN_MOMENTUM);
        assertThat(s.getPrebuiltKey()).isNull();
        assertThat(s.getRulesJson()).isNull();
        assertThat(s.getName()).isEqualTo("Momentum: loyal guests, same genre fit · Night Kit");
        for (UUID m : members) assertThat(s.getSnapshotIds()).contains("\"" + m + "\"");
        assertThat(s.getSnapshotIds().split(",")).hasSize(12);
        assertThat(s.getCreatedAt()).isNotNull();
        assertThat(s.getUpdatedAt()).isEqualTo(s.getCreatedAt());
    }

    @Test
    void name_withoutEventName_andClampedToTheColumn() {
        MomentumPlanTarget.Target t = new MomentumPlanTarget.Target("repeat", "adjacent", List.of());
        assertThat(MomentumPlanTarget.name(null, t)).isEqualTo("Momentum: repeat guests, adjacent genre fit");
        assertThat(MomentumPlanTarget.name("  ", t)).isEqualTo("Momentum: repeat guests, adjacent genre fit");
        assertThat(MomentumPlanTarget.name("x".repeat(300), t)).hasSize(MomentumPlanTarget.MAX_NAME);
    }

    @Test
    void discard_deletesOnlyANonPrebuiltSegmentOfTheOrg() {
        UUID segmentId = UUID.randomUUID();
        target.discard(orgId, segmentId);
        verify(segments).deleteByIdAndOrgIdAndNotPrebuilt(segmentId, orgId);
    }

    @Test
    void noTarget_neverWritesASegment() {
        stored(seg(0, "loyal", "same", 9, 16));
        target.best(event);
        verify(segments, never()).save(any());
        verify(candidates, never()).build(any(), any(), anyInt(), anyDouble());
        verify(assignments, never()).findHeldOut(eq(orgId), any(), anyCollection());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private void stored(AudiencePlanSegment... segs) {
        List<AudiencePlanSegment> sorted = new ArrayList<>(List.of(segs));
        sorted.sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        when(planSegments.findByPlanIdOrderByPositionAsc(plan.getId())).thenReturn(sorted);
    }

    private void built(CandidateBuilder.Segment... segs) {
        when(candidates.build(orgId, event, 255, 1.6))
                .thenReturn(new CandidateBuilder.Result(List.of(segs), 0, false, 0, Map.of()));
    }

    private AudiencePlanSegment seg(int position, String classKey, String fit, int mailable, int expectedMid) {
        AudiencePlanSegment s = new AudiencePlanSegment();
        s.setId(UUID.randomUUID());
        s.setPlanId(plan.getId());
        s.setPosition(position);
        s.setClassKey(classKey);
        s.setGenreFit(fit);
        s.setMailable(mailable);
        s.setExpectedMid(expectedMid);
        return s;
    }

    private static CandidateBuilder.Segment segment(String classKey, Fit fit, List<UUID> members) {
        AudiencePlanLogic.Band band = new AudiencePlanLogic.Band(0.1, 0.2, 0.3);
        return new CandidateBuilder.Segment(classKey, fit, band, Confidence.PRIOR, band, members);
    }

    private static List<UUID> ids(int n) {
        return IntStream.range(0, n).mapToObj(i -> UUID.randomUUID()).toList();
    }

    private static AudiencePlanLogic shippedLogic() {
        try (InputStream l = resource("audienceplan/logic-v1.yaml");
             InputStream p = resource("audienceplan/priors-v1.yaml");
             InputStream g = resource("audienceplan/genres-v1.yaml")) {
            return LogicLoader.parse(l, p, g);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InputStream resource(String path) {
        return MomentumPlanTargetTest.class.getClassLoader().getResourceAsStream(path);
    }
}
