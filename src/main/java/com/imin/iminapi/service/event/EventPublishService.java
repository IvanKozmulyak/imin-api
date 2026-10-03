package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.event.EventDto;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.stripe.StripeConnectService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The Stripe refresh commits before the locked publish starts, and the locked publish reads only
 * the mirror. Deliberately not @Transactional, so no transaction spans the Stripe call and the lock.
 */
@Service
public class EventPublishService {

    private final EventService eventService;
    private final StripeConnectService stripeConnect;

    public EventPublishService(EventService eventService, StripeConnectService stripeConnect) {
        this.eventService = eventService;
        this.stripeConnect = stripeConnect;
    }

    public EventDto publish(AuthPrincipal p, UUID id) {
        if (eventService.precheckPublish(p, id)) stripeConnect.getStatus(p, p.orgId());
        return eventService.publish(p, id);
    }
}
