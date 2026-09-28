package com.imin.iminapi.audienceplan.service;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.scheduling.annotation.Scheduled;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class InviteOnPublishSweeperTest {

    private final InviteOnPublishService service = mock(InviteOnPublishService.class);
    private final InviteOnPublishSweeper sweeper = new InviteOnPublishSweeper(service);

    @Test
    void aPassThatReRanIntents_logsTheCount(CapturedOutput output) {
        when(service.sweepStale()).thenReturn(2);

        sweeper.sweep();

        verify(service).sweepStale();
        assertThat(output.getOut()).contains("InviteOnPublishSweeper: re-ran 2 invite-on-publish intent(s)");
    }

    @Test
    void anEmptyPass_logsNothing(CapturedOutput output) {
        when(service.sweepStale()).thenReturn(0);

        sweeper.sweep();

        verify(service).sweepStale();
        assertThat(output.getOut()).doesNotContain("InviteOnPublishSweeper");
    }

    @Test
    void aFailedPass_isLogged_andNeverThrown(CapturedOutput output) {
        when(service.sweepStale()).thenThrow(new IllegalStateException("database unavailable"));

        sweeper.sweep();

        assertThat(output.getOut()).contains("InviteOnPublishSweeper: pass failed (the next one retries): "
                + "IllegalStateException");
    }

    @Test
    void runsEvery15Minutes_underItsOwnSchedulerLock() throws Exception {
        var sweep = InviteOnPublishSweeper.class.getMethod("sweep");
        assertThat(sweep.getAnnotation(Scheduled.class).fixedDelay()).isEqualTo(900_000L);
        assertThat(sweep.getAnnotation(SchedulerLock.class).name()).isEqualTo("audience_plan_invite_on_publish_sweep");
    }
}
