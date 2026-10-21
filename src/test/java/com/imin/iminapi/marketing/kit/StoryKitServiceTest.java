package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.dto.event.EventSalesFigures;
import com.imin.iminapi.marketing.dto.StoryKitDto;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.EventVisibility;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.service.event.EventSalesTotals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryKitServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-20T10:00:00Z");

    EventRepository events = mock(EventRepository.class);
    KitImageStorage storage = mock(KitImageStorage.class);
    StoryKitFonts fonts = mock(StoryKitFonts.class);
    StoryKitRepository kits = mock(StoryKitRepository.class);
    EventSalesTotals sales = mock(EventSalesTotals.class);
    Event event;
    AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        UUID org = UUID.randomUUID();
        event = new Event();
        event.setId(UUID.randomUUID());
        event.setOrgId(org);
        event.setName("Night");
        event.setStatus(EventStatus.LIVE);
        event.setVisibility(EventVisibility.PUBLIC);
        event.setPublishedAt(Instant.parse("2026-10-01T00:00:00Z"));
        event.setStartsAt(Instant.parse("2026-11-07T21:00:00Z"));
        event.setPosterUrl("https://cdn.example/p.png");
        principal = new AuthPrincipal(UUID.randomUUID(), org, UserRole.OWNER, UUID.randomUUID());
        when(events.findActive(event.getId())).thenReturn(Optional.of(event));
        when(storage.isOwnPoster(any())).thenReturn(true);
        when(storage.storageAvailable()).thenReturn(true);
        when(fonts.available()).thenReturn(true);
        when(kits.findByEventId(event.getId())).thenReturn(Optional.empty());
        when(sales.forEvent(event.getId())).thenReturn(EventSalesFigures.EMPTY);
    }

    private StoryKitService service(boolean enabled) {
        return new StoryKitService(events, mock(OrganizationRepository.class), kits, null, null, storage,
                null, fonts, null, null, sales, Clock.fixed(NOW, ZoneOffset.UTC), enabled, 90);
    }

    private static void assertUnavailable(Throwable t) {
        assertThat(t).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(e.code()).isEqualTo(ErrorCode.UPSTREAM_UNAVAILABLE);
        });
    }

    // A prod image without fonts, the kill switch or missing storage must show "not available", not a button that fails.
    @ParameterizedTest
    @ValueSource(strings = {"fonts", "killSwitch", "storage"})
    void withAnyPartMissingTheKitIsUnavailableAndMakingItIs503(String missing) {
        if (missing.equals("fonts")) when(fonts.available()).thenReturn(false);
        if (missing.equals("storage")) when(storage.storageAvailable()).thenReturn(false);
        StoryKitService service = service(!missing.equals("killSwitch"));

        StoryKitDto.State state = service.state(principal, event.getId());

        assertThat(state.available()).isFalse();
        assertThatThrownBy(() -> service.make(principal, event.getId(), "en")).satisfies(StoryKitServiceTest::assertUnavailable);
        verify(storage, never()).loadPoster(any());
    }

    @Test
    void withFontsStorageAndTheSwitchOnTheKitIsAvailable() {
        assertThat(service(true).state(principal, event.getId()).available()).isTrue();
    }

    // Each row breaks one rule of an otherwise makeable event; poster ownership is owned by the integration test.
    @ParameterizedTest
    @CsvSource({
            "none,        ''",
            "noPoster,    NO_POSTER",
            "blankPoster, NO_POSTER",
            "draft,       NOT_PUBLIC",
            "private,     NOT_PUBLIC",
            "unpublished, NOT_PUBLIC",
            "noStart,     NOT_PUBLIC",
            "cancelled,   ENDED",
            "past,        ENDED",
            "started,     ENDED",
    })
    void blockedReasonNamesTheFirstRuleTheEventBreaks(String change, String expected) {
        switch (change) {
            case "noPoster" -> event.setPosterUrl(null);
            case "blankPoster" -> event.setPosterUrl("  ");
            case "draft" -> event.setStatus(EventStatus.DRAFT);
            case "private" -> event.setVisibility(EventVisibility.PRIVATE);
            case "unpublished" -> event.setPublishedAt(null);
            case "noStart" -> event.setStartsAt(null);
            case "cancelled" -> event.setStatus(EventStatus.CANCELLED);
            case "past" -> event.setStatus(EventStatus.PAST);
            case "started" -> event.setStartsAt(NOW.minusSeconds(3600));
            default -> { }
        }

        assertThat(service(true).blockedReason(event)).isEqualTo(expected.isEmpty() ? null : expected);
    }

    // The XMP marker follows either signal: the event's flag, or a poster that is one of our AI render files.
    @ParameterizedTest
    @CsvSource({
            "true,  https://cdn.example/events/e/poster.png, true",
            ",      https://cdn.example/ai-posters/abc.png,  true",
            "false, https://cdn.example/events/e/poster.png, false",
            ",      https://cdn.example/events/e/poster.png, false",
    })
    void aiDerivedFollowsTheFlagOrAnAiRenderFile(Boolean flag, String posterUrl, boolean expected) {
        event.setPosterAiGenerated(flag);
        event.setPosterUrl(posterUrl);

        assertThat(StoryKitService.aiDerived(event)).isEqualTo(expected);
    }

    // Decoding a poster is the memory peak; past the cap a make is refused before any poster is read.
    @Test
    void whenEveryRenderSlotIsTakenMakingIs503AndNoPosterIsRead() throws InterruptedException {
        StoryKitService service = service(true);
        service.renders.acquire(StoryKitService.MAX_CONCURRENT_RENDERS);

        assertThatThrownBy(() -> service.make(principal, event.getId(), "en")).satisfies(StoryKitServiceTest::assertUnavailable);
        verify(storage, never()).loadPoster(any());
    }

    // A failed make must give its slot back, or a few unreadable posters switch the kit off until a restart.
    @Test
    void aFailedMakeGivesItsRenderSlotBack() {
        when(storage.loadPoster(any())).thenThrow(new KitImageStorage.PosterUnreadableException("broken"));
        StoryKitService service = service(true);

        for (int i = 0; i <= StoryKitService.MAX_CONCURRENT_RENDERS; i++) {
            assertThatThrownBy(() -> service.make(principal, event.getId(), "en"))
                    .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.status()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(service.renders.availablePermits()).isEqualTo(StoryKitService.MAX_CONCURRENT_RENDERS);
    }
}
