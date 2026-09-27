package com.imin.iminapi.audienceplan.service;

import com.imin.iminapi.audienceplan.config.PlanRefreshExecutor;
import com.imin.iminapi.marketing.service.MomentumTriggered;
import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MomentumPlanRefreshTest {

    private final PlanService plans = mock(PlanService.class);
    private final MomentumPlanRefresh listener = new MomentumPlanRefresh(plans);

    @Test
    void refreshesTheTriggeredEventsPlan() {
        UUID eventId = UUID.randomUUID();
        when(plans.refresh(eventId)).thenReturn(PlanService.Refresh.CREATED);

        listener.onMomentumTriggered(new MomentumTriggered(UUID.randomUUID(), eventId, "slump"));

        verify(plans).refresh(eventId);
    }

    @Test
    void aFailingRefresh_isSwallowed() {
        UUID eventId = UUID.randomUUID();
        when(plans.refresh(eventId)).thenThrow(new IllegalStateException("db down"));

        assertThatCode(() -> listener.onMomentumTriggered(new MomentumTriggered(UUID.randomUUID(), eventId, "slump")))
                .doesNotThrowAnyException();
    }

    @Test
    void listensOffThread_onThePlanRefreshExecutor() throws NoSuchMethodException {
        var method = MomentumPlanRefresh.class.getMethod("onMomentumTriggered", MomentumTriggered.class);
        assertThat(method.getAnnotation(EventListener.class)).isNotNull();
        assertThat(method.getAnnotation(Async.class).value()).isEqualTo(PlanRefreshExecutor.NAME);
    }
}
