package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.SubjectVariantsLlm;
import com.imin.iminapi.marketing.dto.SubjectVariantsResponse;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.SubjectVariantsService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import com.imin.iminapi.model.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SubjectVariantsServiceTest {

    // Deep stubs cover the fluent chain chat.prompt().user(...).call().entity(SubjectVariantsLlm.class).
    private final ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final TicketTierRepository tiers = mock(TicketTierRepository.class);

    private final SubjectVariantsService sut =
            new SubjectVariantsService(chat, campaigns, events, tiers);

    private final UUID orgId = UUID.randomUUID();

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Campaign campaign(UUID eventId) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("July newsletter");
        c.setStatus("draft");
        c.setEventId(eventId);
        c.setSubject("Original subject");
        return c;
    }

    private Event event() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(orgId);
        e.setName("Warehouse Mass");
        e.setStartsAt(Instant.parse("2026-09-12T21:00:00Z"));
        e.setVenueName("Berghain");
        e.setVenueCity("Berlin");
        e.setGenre("Techno");
        e.setType("Rave");
        return e;
    }

    private void stubModel(List<String> variants) {
        when(chat.prompt().user(anyString()).call().entity(SubjectVariantsLlm.class))
                .thenReturn(new SubjectVariantsLlm(variants));
    }

    private void stubValidCampaignWithEvent() {
        Event e = event();
        Campaign c = campaign(e.getId());
        when(campaigns.findByIdAndOrgId(eq(c.getId()), eq(orgId))).thenReturn(Optional.of(c));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
        when(tiers.sumQuantityByEventId(e.getId())).thenReturn(300);
        when(tiers.sumSoldByEventId(e.getId())).thenReturn(210);
        // Reuse the campaign id as the addressed id in tests below.
        currentCampaignId = c.getId();
    }

    private UUID currentCampaignId;

    @Test
    void returnsExactlyThreeNonEmptyDistinctVariantsForValidCampaign() {
        stubValidCampaignWithEvent();
        stubModel(List.of(
                "Only hours left for Warehouse Mass",
                "What happens after midnight?",
                "Your Berlin techno night awaits"));

        SubjectVariantsResponse res = sut.generate(principal(), currentCampaignId, null);

        assertThat(res.variants()).hasSize(3).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(res.variants()).allSatisfy(s -> assertThat(s).isNotBlank());
        assertThat(res.variants()).containsExactly(
                "Only hours left for Warehouse Mass",
                "What happens after midnight?",
                "Your Berlin techno night awaits");
    }

    @Test
    void anotherOrgCampaignIsA404NoLeak() {
        UUID otherCampaignId = UUID.randomUUID();
        // Org-scoped lookup misses -> the service must 404, never reveal the row exists.
        when(campaigns.findByIdAndOrgId(eq(otherCampaignId), eq(orgId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.generate(principal(), otherCampaignId, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }

    @Test
    void malformedShortOutputIsPaddedToThreeWithoutError() {
        stubValidCampaignWithEvent();
        // Model returns only ONE line — must not 500; padded to three with grounded fallbacks.
        stubModel(List.of("Only hours left for Warehouse Mass"));

        SubjectVariantsResponse res = sut.generate(principal(), currentCampaignId, null);

        assertThat(res.variants()).hasSize(3).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(res.variants()).allSatisfy(s -> assertThat(s).isNotBlank());
        assertThat(res.variants().get(0)).isEqualTo("Only hours left for Warehouse Mass");
        // The pad is grounded in the event name.
        assertThat(res.variants().subList(1, 3)).allSatisfy(s -> assertThat(s).contains("Warehouse Mass"));
    }

    @Test
    void blankAndDuplicateModelLinesAreCleanedThenPadded() {
        stubValidCampaignWithEvent();
        // Two blanks + a duplicate collapse to a single usable line, then pad to three.
        stubModel(java.util.Arrays.asList(
                "  ", null, "Doors close soon", "doors close soon", ""));

        SubjectVariantsResponse res = sut.generate(principal(), currentCampaignId, null);

        assertThat(res.variants()).hasSize(3).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(res.variants()).allSatisfy(s -> assertThat(s).isNotBlank());
        assertThat(res.variants().get(0)).isEqualTo("Doors close soon");
    }

    @Test
    void llmFailureDegradesToThreeDeterministicVariants() {
        stubValidCampaignWithEvent();
        when(chat.prompt().user(anyString()).call().entity(SubjectVariantsLlm.class))
                .thenThrow(new RuntimeException("upstream down"));

        SubjectVariantsResponse res = sut.generate(principal(), currentCampaignId, null);

        assertThat(res.variants()).hasSize(3).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(res.variants()).allSatisfy(s -> assertThat(s).isNotBlank());
        assertThat(res.variants()).allSatisfy(s -> assertThat(s).contains("Warehouse Mass"));
    }

    @Test
    void campaignWithoutLinkedEventStillReturnsThree() {
        Campaign c = campaign(null); // no event linked
        when(campaigns.findByIdAndOrgId(eq(c.getId()), eq(orgId))).thenReturn(Optional.of(c));
        stubModel(List.of()); // force the fallback path entirely

        SubjectVariantsResponse res = sut.generate(principal(), c.getId(), null);

        assertThat(res.variants()).hasSize(3).doesNotContainNull().doesNotHaveDuplicates();
        assertThat(res.variants()).allSatisfy(s -> assertThat(s).isNotBlank());
        // Fallbacks are grounded in the campaign name when there is no event.
        assertThat(res.variants()).anySatisfy(s -> assertThat(s).contains("July newsletter"));
    }

    @Test
    void modelReturningMoreThanThreeIsTruncatedToThree() {
        stubValidCampaignWithEvent();
        stubModel(List.of("One", "Two", "Three", "Four", "Five"));

        SubjectVariantsResponse res = sut.generate(principal(), currentCampaignId, null);

        assertThat(res.variants()).containsExactly("One", "Two", "Three");
    }
}
