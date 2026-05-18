package com.imin.iminapi.service.audit;

import com.imin.iminapi.dto.event.EventPatchRequest;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.TicketTier;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.PredictionRepository;
import com.imin.iminapi.repository.PromoCodeRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.event.EventService;
import com.imin.iminapi.service.event.EventValidator;
import com.imin.iminapi.service.event.TicketTierService;
import com.imin.iminapi.stripe.StripeConnectService;
import com.imin.iminapi.web.IfMatchSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Verifies {@link EventService} fires the correct {@link AuditLogger} calls
 * on its mutation paths (createDraft, publish, unpublish, patch). Uses mocked
 * collaborators so we can assert exact action constants and target ids.
 */
class EventServiceAuditIntegrationTest {

    EventRepository events = mock(EventRepository.class);
    TicketTierRepository tiers = mock(TicketTierRepository.class);
    PromoCodeRepository promos = mock(PromoCodeRepository.class);
    PredictionRepository predictions = mock(PredictionRepository.class);
    IfMatchSupport ifMatch = new IfMatchSupport();
    EventValidator validator = new EventValidator();
    TicketTierService tierService = mock(TicketTierService.class);
    StripeConnectService stripeConnect = mock(StripeConnectService.class);
    AuditLogger auditLogger = mock(AuditLogger.class);

    EventService sut = new EventService(events, tiers, promos, predictions, validator,
            ifMatch, tierService, stripeConnect, auditLogger);

    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), UserRole.OWNER, UUID.randomUUID());
    }

    @Test
    void createDraft_recordsEVENT_CREATED_withTargetEventId() {
        UUID newId = UUID.randomUUID();
        when(events.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(newId);
            return e;
        });

        sut.createDraft(principal, new EventPatchRequest(
                "My Show", null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<UUID> target = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).record(eq(principal), action.capture(), eq("event"),
                target.capture(), summary.capture());
        assertThat(action.getValue()).isEqualTo(AuditActions.EVENT_CREATED);
        assertThat(target.getValue()).isEqualTo(newId);
        assertThat(summary.getValue()).contains("My Show");
    }

    @Test
    void createDraft_withNoName_summaryFallsBackToUntitled() {
        UUID newId = UUID.randomUUID();
        when(events.save(any(Event.class))).thenAnswer(inv -> {
            Event e = inv.getArgument(0);
            e.setId(newId);
            return e;
        });

        sut.createDraft(principal, new EventPatchRequest(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null));

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(auditLogger).record(eq(principal), eq(AuditActions.EVENT_CREATED),
                eq("event"), any(UUID.class), summary.capture());
        assertThat(summary.getValue()).contains("Untitled");
    }

    @Test
    void publish_recordsEVENT_PUBLISHED_withEventTarget() {
        Event e = newPublishableEvent();
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.publish(principal, e.getId());

        verify(auditLogger).record(eq(principal), eq(AuditActions.EVENT_PUBLISHED),
                eq("event"), eq(e.getId()), any(String.class));
    }

    @Test
    void unpublish_recordsEVENT_UNPUBLISHED_withEventTarget() {
        Event e = newPublishableEvent();
        e.setStatus(EventStatus.LIVE);
        e.setPublishedAt(Instant.parse("2026-05-01T00:00:00Z"));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.unpublish(principal, e.getId());

        verify(auditLogger).record(eq(principal), eq(AuditActions.EVENT_UNPUBLISHED),
                eq("event"), eq(e.getId()), any(String.class));
    }

    @Test
    void publish_skipsAudit_whenValidationFails() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(principal.orgId());
        e.setName(""); // missing → validation fails
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> sut.publish(principal, e.getId()))
                .isInstanceOf(com.imin.iminapi.security.ApiException.class);
        verify(auditLogger, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void patch_withChangedField_recordsEVENT_UPDATED() {
        Event e = newPublishableEvent();
        e.setStatus(EventStatus.DRAFT);
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.patch(principal, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest("Renamed", null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));

        verify(auditLogger).record(eq(principal), eq(AuditActions.EVENT_UPDATED),
                eq("event"), eq(e.getId()), any(String.class));
    }

    @Test
    void patch_withNoFieldsProvided_doesNotRecordAudit() {
        Event e = newPublishableEvent();
        e.setStatus(EventStatus.DRAFT);
        Instant updated = Instant.parse("2026-04-23T10:00:00Z");
        e.setUpdatedAt(updated);
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(events.save(any(Event.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tiers.findByEventIdOrderBySortOrderAsc(e.getId())).thenReturn(List.of());
        when(promos.findByEventId(e.getId())).thenReturn(List.of());
        when(predictions.findById(e.getId())).thenReturn(Optional.empty());

        sut.patch(principal, e.getId(), "\"" + updated + "\"",
                new EventPatchRequest(null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null));

        verify(auditLogger, never()).record(any(), any(), any(), any(), any());
    }

    private Event newPublishableEvent() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(principal.orgId());
        e.setName("Show");
        e.setSlug("show");
        e.setStartsAt(Instant.parse("2026-06-01T20:00:00Z"));
        e.setEndsAt(Instant.parse("2026-06-02T04:00:00Z"));
        e.setVenueStreet("12 Main");
        e.setVenueCity("Berlin");
        e.setVenuePostalCode("10115");
        e.setDescription("d");

        TicketTier free = new TicketTier();
        free.setEventId(e.getId());
        free.setName("Free");
        free.setPriceMinor(0);
        // No need to wire stripe — free events skip stripe checks
        return e;
    }
}
