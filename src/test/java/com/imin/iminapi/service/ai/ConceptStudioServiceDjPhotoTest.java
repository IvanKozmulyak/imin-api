package com.imin.iminapi.service.ai;

import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.GeneratedEventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.PosterGenerationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.service.AiEventDescriptionService;
import com.imin.iminapi.service.PricingService;
import com.imin.iminapi.service.poster.DjPhotoSnapshot;
import com.imin.iminapi.service.poster.PosterImageStorage;
import com.imin.iminapi.service.poster.PosterOrchestrator;
import com.imin.iminapi.service.poster.VibeLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptStudioServiceDjPhotoTest {

    private final EventRepository events = mock(EventRepository.class);
    private final PosterImageStorage storage = mock(PosterImageStorage.class);
    private final PosterGenerationRepository generations = mock(PosterGenerationRepository.class);
    private ConceptStudioService service;
    private final UUID orgId = UUID.randomUUID();
    private final UUID eventId = UUID.randomUUID();
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        principal = new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
        service = new ConceptStudioService(
                mock(AiEventDescriptionService.class), mock(PosterOrchestrator.class),
                mock(PricingService.class), mock(ConceptOverviewLlm.class),
                mock(GeneratedEventRepository.class), mock(OrganizationRepository.class),
                mock(VibeLibrary.class), events, storage, generations);
    }

    private Event ownedEventWithPhoto() {
        Event e = new Event();
        e.setOrgId(orgId);
        e.setDjPhotoUrl("https://cdn.example/events/x/dj-photo-abc.jpg");
        return e;
    }

    @Test
    void resolvesSnapshotForOwnedEventWithPhoto() {
        when(events.findActive(eventId)).thenReturn(Optional.of(ownedEventWithPhoto()));
        when(storage.download("https://cdn.example/events/x/dj-photo-abc.jpg")).thenReturn(new byte[]{1});
        DjPhotoSnapshot snap = service.resolveDjPhotoFromEvent(principal, eventId);
        assertThat(snap).isNotNull();
        assertThat(snap.mimeType()).isEqualTo("image/jpeg");
    }

    @Test
    void crossOrgEventIsNotFoundNotForbidden() {
        Event foreign = ownedEventWithPhoto();
        foreign.setOrgId(UUID.randomUUID());
        when(events.findActive(eventId)).thenReturn(Optional.of(foreign));
        assertThatThrownBy(() -> service.resolveDjPhotoFromEvent(principal, eventId))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("status", org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Test
    void nullEventIdOrNoPhotoYieldsNull() {
        assertThat(service.resolveDjPhotoFromEvent(principal, null)).isNull();
        Event noPhoto = ownedEventWithPhoto();
        noPhoto.setDjPhotoUrl(null);
        when(events.findActive(eventId)).thenReturn(Optional.of(noPhoto));
        assertThat(service.resolveDjPhotoFromEvent(principal, eventId)).isNull();
    }

    @Test
    void downloadFailureDegradesToNullNotThrow() {
        when(events.findActive(eventId)).thenReturn(Optional.of(ownedEventWithPhoto()));
        when(storage.download(anyString())).thenThrow(new RuntimeException("R2 down"));
        assertThat(service.resolveDjPhotoFromEvent(principal, eventId)).isNull();
    }
}
