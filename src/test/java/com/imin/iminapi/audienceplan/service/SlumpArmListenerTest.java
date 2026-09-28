package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.marketing.service.MomentumTriggered;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SlumpArmListenerTest {

    private final TimingArmScheduler scheduler = mock(TimingArmScheduler.class);
    private final SlumpArmListener listener = new SlumpArmListener(scheduler);
    private final UUID org = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @Test
    void aSlumpTrigger_firesTheEventsSlumpArms() {
        listener.onMomentumTriggered(new MomentumTriggered(org, event, "slump"));
        verify(scheduler).fireSlump(org, event);
    }

    @Test
    void anyOtherTrigger_leavesSlumpArmsAlone() {
        for (String trigger : new String[] {"launch_push", "urgency_72h", "sold_out"}) {
            listener.onMomentumTriggered(new MomentumTriggered(org, event, trigger));
        }
        verify(scheduler, never()).fireSlump(any(), any());
    }

    @Test
    void aFailure_isLoggedAndNeverReachesTheEvaluator() {
        when(scheduler.fireSlump(org, event)).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> listener.onMomentumTriggered(new MomentumTriggered(org, event, "slump")))
                .doesNotThrowAnyException();
    }
}
