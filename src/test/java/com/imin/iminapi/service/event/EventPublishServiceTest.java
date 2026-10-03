package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.stripe.StripeConnectService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EventPublishServiceTest {

    EventService eventService = mock(EventService.class);
    StripeConnectService stripeConnect = mock(StripeConnectService.class);
    EventPublishService sut = new EventPublishService(eventService, stripeConnect);

    AuthPrincipal p = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());
    UUID id = UUID.randomUUID();

    @Test
    void precheck_failure_propagates_before_any_stripe_call_or_publish() {
        ApiException invalid = new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                ErrorCode.PUBLISH_VALIDATION_FAILED, "Event is not ready to publish");
        when(eventService.precheckPublish(p, id)).thenThrow(invalid);

        assertThatThrownBy(() -> sut.publish(p, id)).isSameAs(invalid);
        verify(stripeConnect, never()).getStatus(any(), any());
        verify(eventService, never()).publish(any(), any());
    }

    @Test
    void free_event_publishes_without_refreshing_stripe() {
        EventDto published = mock(EventDto.class);
        when(eventService.precheckPublish(p, id)).thenReturn(false);
        when(eventService.publish(p, id)).thenReturn(published);

        assertThat(sut.publish(p, id)).isSameAs(published);
        verify(stripeConnect, never()).getStatus(any(), any());
        verify(eventService, times(1)).publish(p, id);
    }

    @Test
    void paid_event_refreshes_stripe_before_the_locked_publish() {
        EventDto published = mock(EventDto.class);
        when(eventService.precheckPublish(p, id)).thenReturn(true);
        when(eventService.publish(p, id)).thenReturn(published);

        assertThat(sut.publish(p, id)).isSameAs(published);
        InOrder order = inOrder(eventService, stripeConnect);
        order.verify(eventService).precheckPublish(p, id);
        order.verify(stripeConnect).getStatus(p, p.orgId());
        order.verify(eventService).publish(p, id);
        order.verifyNoMoreInteractions();
    }
}
