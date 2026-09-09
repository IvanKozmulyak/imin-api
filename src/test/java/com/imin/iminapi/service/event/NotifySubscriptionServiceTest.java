package com.imin.iminapi.service.event;

import com.imin.iminapi.dto.publicapi.NotifySubscriptionRequest;
import com.imin.iminapi.dto.publicapi.NotifySubscriptionResponse;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.NotifySubscription;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.NotifySubscriptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The idempotency promise in {@code NotifySubscriptionService}'s class javadoc —
 * "duplicate inserts are swallowed at the database level and surfaced as a successful
 * idempotent 200" — pinned at the boundary where it can actually fail (events-12).
 *
 * <p>{@code NotifySubscription} uses an in-VM id generator, so {@code save()} emits no SQL
 * and the {@code uk_notify_event_email} violation used to be raised at commit, outside the
 * try/catch, reaching the client as a 409 DUPLICATE. It needs two simultaneous first-time
 * subscribes of the same address on the same event — the SELECT pre-check handles every
 * sequential repeat — which is exactly why only a stubbed failure can prove it.
 */
class NotifySubscriptionServiceTest {

    private EventRepository events;
    private NotifySubscriptionRepository subscriptions;
    private NotifySubscriptionService svc;

    private final UUID eventId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        events = mock(EventRepository.class);
        subscriptions = mock(NotifySubscriptionRepository.class);
        svc = new NotifySubscriptionService(events, subscriptions);

        Event e = new Event();
        e.setId(eventId);
        e.setOrgId(UUID.randomUUID());
        e.setName("Release Night");
        e.setSlug("release-night");
        e.setStatus(EventStatus.LIVE);
        e.setVisibility(EventVisibility.PUBLIC);
        when(events.findPublic(eventId)).thenReturn(Optional.of(e));
        when(subscriptions.findByEventIdAndEmail(eq(eventId), anyString()))
                .thenReturn(Optional.empty());
        when(subscriptions.save(any(NotifySubscription.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void subscribe_returnsOk_whenTheUniqueConstraintRejectsAConcurrentFirstInsert() {
        doThrow(new DataIntegrityViolationException("uk_notify_event_email"))
                .when(subscriptions).flush();

        NotifySubscriptionResponse r = svc.subscribe(eventId,
                new NotifySubscriptionRequest("ada@example.com", null));

        assertThat(r.subscribed()).isTrue();
        // The insert has to be forced inside the try — a deferred flush raises the
        // violation at commit, past the catch, and the buyer gets a 409 instead.
        verify(subscriptions).flush();
    }
}
