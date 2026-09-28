package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.model.PublishInvite;
import com.imin.iminapi.audienceplan.repository.AudiencePlanRepository;
import com.imin.iminapi.audienceplan.repository.AudiencePlanSegmentRepository;
import com.imin.iminapi.audienceplan.repository.PublishInviteRepository;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.repository.EventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The sweep's branches that need a concurrent sweeper, which the MockMvc scenarios cannot interleave. */
@ExtendWith(OutputCaptureExtension.class)
class InviteOnPublishServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");

    private final PublishInviteRepository invites = mock(PublishInviteRepository.class);
    private final InvitationService invitations = mock(InvitationService.class);
    private final PlanService planService = mock(PlanService.class);
    private final InviteOnPublishService service = new InviteOnPublishService(mock(AudiencePlanAccess.class),
            mock(EventRepository.class), mock(AudiencePlanRepository.class), mock(AudiencePlanSegmentRepository.class),
            invites, invitations, planService, mock(PlatformTransactionManager.class),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void aStaleClaimAnotherSweeperTookFirst_isSkipped() {
        PublishInvite row = row(NOW.minusSeconds(3600), 1);
        when(invites.findStale(eq(EventStatus.LIVE), any(), any(), any())).thenReturn(List.of(row));
        when(invites.reclaim(eq(row.getEventId()), eq(row.getClaimedAt()), any())).thenReturn(0);

        assertThat(service.sweepStale()).isZero();

        verify(invites, never()).findById(any());
        verifyNoInteractions(invitations, planService);
    }

    @Test
    void aNeverClaimedIntentAnotherRunTookFirst_isSkippedWithoutARefresh() {
        PublishInvite row = row(null, 0);
        when(invites.findStale(eq(EventStatus.LIVE), any(), any(), any())).thenReturn(List.of(row));
        when(invites.claim(eq(row.getEventId()), any())).thenReturn(0);

        assertThat(service.sweepStale()).isZero();

        verify(invites, never()).findById(any());
        verifyNoInteractions(invitations, planService);
    }

    @Test
    void anIntentAtTheCapAlreadyGone_logsNothing(CapturedOutput output) {
        PublishInvite row = row(NOW.minusSeconds(3600), InviteOnPublishService.MAX_ATTEMPTS);
        when(invites.findStale(eq(EventStatus.LIVE), any(), any(), any())).thenReturn(List.of(row));
        when(invites.complete(row.getEventId(), row.getClaimedAt())).thenReturn(0);

        assertThat(service.sweepStale()).isZero();

        verify(invites).complete(row.getEventId(), row.getClaimedAt());
        verifyNoInteractions(invitations, planService);
        assertThat(output.getOut()).doesNotContain("intent dropped");
    }

    private static PublishInvite row(Instant claimedAt, int attempts) {
        PublishInvite row = new PublishInvite();
        row.setEventId(UUID.randomUUID());
        row.setOrgId(UUID.randomUUID());
        row.setSegments("[]");
        row.setUpdatedAt(NOW.minusSeconds(7200));
        row.setClaimedAt(claimedAt);
        row.setAttempts(attempts);
        return row;
    }
}
