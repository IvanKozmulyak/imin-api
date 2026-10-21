package com.imin.iminapi.marketing.kit;

import com.imin.iminapi.marketing.kit.StoryKitCaptionService.CaptionLlm;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StoryKitCaptionServiceTest {

    private static final String FACTS = "Sat 7 Nov · 22:00\nLa Halle Noire, Paris\nTickets: link in bio";

    ChatClient chat;
    StoryKitCaptionService service;
    Event event;

    @BeforeEach
    void setUp() {
        chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        service = new StoryKitCaptionService(chat, null, null, null, Clock.systemUTC());
        event = new Event();
        event.setName("Warehouse Techno Night");
        event.setGenre("Techno");
        event.setDescription("Four hours of hard techno.");
    }

    private void model(CaptionLlm... answers) {
        var stub = when(chat.prompt().user(anyString()).call().entity(CaptionLlm.class));
        for (CaptionLlm a : answers) stub = stub.thenReturn(a);
    }

    @Test
    void aGroundedCaptionGetsTheServerFactsLineAndIsMarkedAi() {
        model(new CaptionLlm("Hard, hypnotic techno all night.", List.of("techno", "#paris nights")));

        StoryKitCaptionService.Result r = service.compose(event, "en", FACTS);

        assertThat(r.ai()).isTrue();
        assertThat(r.text()).isEqualTo("Hard, hypnotic techno all night.\n\n" + FACTS + "\n\n#techno #parisnights");
    }

    // The model must not invent a time or a price: it is retried once, then replaced by the plain caption.
    @ParameterizedTest
    @ValueSource(strings = {"Doors at 21:00, hard techno.", "Only €15 tonight.", "Info at https://x.example", "Ask @club",
            "Tickets on imin.wtf", "More at club.example tonight"})
    void anInventedNumberOrLinkIsRetriedOnceThenReplacedByThePlainCaption(String bad) {
        model(new CaptionLlm(bad, List.of()), new CaptionLlm(bad, List.of()));

        StoryKitCaptionService.Result r = service.compose(event, "en", FACTS);

        assertThat(r.ai()).isFalse();
        assertThat(r.text()).isEqualTo("Warehouse Techno Night\n\n" + FACTS);
        verify(chat.prompt().user(anyString()).call(), times(2)).entity(CaptionLlm.class);
    }

    @Test
    void theSecondAnswerIsUsedWhenTheFirstInventedANumber() {
        model(new CaptionLlm("Doors at 21:00.", List.of()), new CaptionLlm("Hard techno.", List.of()));

        StoryKitCaptionService.Result r = service.compose(event, "en", FACTS);

        assertThat(r.ai()).isTrue();
        assertThat(r.text()).startsWith("Hard techno.\n\n");
    }

    @Test
    void aNumberFromTheEventDataIsAllowed() {
        event.setName("Techno 2026");

        model(new CaptionLlm("Techno 2026 starts here.", List.of()));

        assertThat(service.compose(event, "en", FACTS).ai()).isTrue();
    }

    // The caption endpoint runs the kit's own gate first: no model call for a kit that cannot be posted.
    @ParameterizedTest
    @CsvSource({"unavailable, 503, ''", "ENDED, 409, ENDED"})
    void anUnavailableOrBlockedKitGetsNoModelCall(String gate, int status, String reason) {
        EventRepository events = mock(EventRepository.class);
        StoryKitRepository kits = mock(StoryKitRepository.class);
        StoryKitService kitService = mock(StoryKitService.class);
        UUID org = UUID.randomUUID();
        event.setId(UUID.randomUUID());
        event.setOrgId(org);
        StoryKit kit = new StoryKit();
        kit.setId(UUID.randomUUID());
        kit.setOrgId(org);
        kit.setLocale("en");
        kit.setSourceStartsAt(Instant.parse("2026-11-07T21:00:00Z"));
        kit.setSourceTimezone("Europe/Paris");
        when(events.findActive(event.getId())).thenReturn(Optional.of(event));
        when(kits.findByEventId(event.getId())).thenReturn(Optional.of(kit));
        when(kitService.isAvailable()).thenReturn(!gate.equals("unavailable"));
        when(kitService.blockedReason(event)).thenReturn(gate.equals("unavailable") ? null : gate);
        AuthPrincipal owner = new AuthPrincipal(UUID.randomUUID(), org, UserRole.OWNER, UUID.randomUUID());
        StoryKitCaptionService gated = new StoryKitCaptionService(chat, events, kits, kitService, Clock.systemUTC());

        assertThatThrownBy(() -> gated.write(owner, event.getId(), 0)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status().value()).isEqualTo(status);
            if (!reason.isEmpty()) assertThat(e.fields()).containsEntry("reason", reason);
        });
        verify(chat, never()).prompt();
    }

    @Test
    void aModelFailureYieldsThePlainCaptionWithoutRetryOrException() {
        when(chat.prompt().user(anyString()).call().entity(CaptionLlm.class)).thenThrow(new IllegalStateException("timeout"));

        StoryKitCaptionService.Result r = service.compose(event, "en", FACTS);

        assertThat(r.ai()).isFalse();
        assertThat(r.text()).isEqualTo("Warehouse Techno Night\n\n" + FACTS);
        verify(chat.prompt().user(anyString()).call(), times(1)).entity(CaptionLlm.class);
    }
}
