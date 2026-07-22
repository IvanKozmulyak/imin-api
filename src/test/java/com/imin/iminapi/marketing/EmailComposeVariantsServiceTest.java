package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsLlm;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsLlm.Variant;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse.EmailVariant;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.EmailComposeVariantsService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.security.ApiException;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.security.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EmailComposeVariantsServiceTest {

    // Deep stubs cover chat.prompt().user(...).call().entity(EmailComposeVariantsLlm.class).
    private final ChatClient chat = mock(ChatClient.class, RETURNS_DEEP_STUBS);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final EventRepository events = mock(EventRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final SegmentRepository segments = mock(SegmentRepository.class);
    private final SegmentService segmentService = mock(SegmentService.class);

    private final EmailComposeVariantsService sut =
            new EmailComposeVariantsService(chat, campaigns, events, organizations, segments, segmentService);

    private final UUID orgId = UUID.randomUUID();
    private UUID campaignId;

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
        c.setTemplateKey("midnight");
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
        return e;
    }

    private void stubCampaignWithEvent() {
        Event e = event();
        Campaign c = campaign(e.getId());
        campaignId = c.getId();
        when(campaigns.findByIdAndOrgId(eq(c.getId()), eq(orgId))).thenReturn(Optional.of(c));
        when(events.findActive(e.getId())).thenReturn(Optional.of(e));
    }

    private void stubCampaignNoEvent() {
        Campaign c = campaign(null);
        campaignId = c.getId();
        when(campaigns.findByIdAndOrgId(eq(c.getId()), eq(orgId))).thenReturn(Optional.of(c));
    }

    private void stubModel(List<Variant> variants) {
        when(chat.prompt().user(anyString()).call().entity(EmailComposeVariantsLlm.class))
                .thenReturn(new EmailComposeVariantsLlm(variants));
    }

    private static Variant v(String subject, String preheader, String body) {
        return new Variant(subject, preheader, body);
    }

    private static int tokenCount(String body) {
        return body.split("\\{\\{\\s*tickets_button", -1).length - 1;
    }

    @Test
    void happyPath_returnsValidatedWholeEmailVariants() {
        stubCampaignWithEvent();
        stubModel(List.of(
                v("Only hours left for Warehouse Mass", "Doors at 21:00 — don't miss it",
                        "Hi there,\n\nWarehouse Mass is nearly here.\n\n{{tickets_button}}"),
                v("What happens after midnight?", "A Berlin techno night to remember",
                        "The lineup is set.\n\n{{tickets_button}}"),
                v("Your Berlin techno night awaits", "Berghain, one night only",
                        "Come dance with us.\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, null, null);

        assertThat(res.variants()).hasSize(3);
        assertThat(res.variants()).allSatisfy(x -> {
            assertThat(x.subject()).isNotBlank();
            assertThat(x.preheader()).isNotBlank();
            assertThat(x.bodyMarkdown()).isNotBlank();
            assertThat(tokenCount(x.bodyMarkdown())).isEqualTo(1);
        });
        assertThat(res.variants().get(0).subject()).isEqualTo("Only hours left for Warehouse Mass");
    }

    @Test
    void stripsHtmlFromBody() {
        stubCampaignWithEvent();
        stubModel(List.of(
                v("Clean subject", "Clean preheader",
                        "Hello <script>alert('x')</script><b>bold</b> world\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 1, null);

        assertThat(res.variants()).hasSize(1);
        String body = res.variants().get(0).bodyMarkdown();
        assertThat(body).doesNotContain("<");
        assertThat(body).contains("bold");
    }

    @Test
    void dropsOverlongSubject() {
        stubCampaignWithEvent();
        String overlong = "x".repeat(120); // > 80
        stubModel(List.of(
                v(overlong, "fine preheader", "Body one\n\n{{tickets_button}}"),
                v("A perfectly fine subject", "fine preheader", "Body two\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 3, null);

        assertThat(res.variants()).hasSize(1);
        assertThat(res.variants().get(0).subject()).isEqualTo("A perfectly fine subject");
    }

    @Test
    void dropsSpammyAllCapsSubject() {
        stubCampaignWithEvent();
        stubModel(List.of(
                v("SALE ENDS TONIGHT ACT NOW", "hurry", "Body\n\n{{tickets_button}}"),
                v("Sale ends tonight", "hurry", "Body\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 3, null);

        assertThat(res.variants()).hasSize(1);
        assertThat(res.variants().get(0).subject()).isEqualTo("Sale ends tonight");
    }

    @Test
    void appendsTicketsButtonWhenEventLinkedAndTokenMissing() {
        stubCampaignWithEvent();
        stubModel(List.of(v("Subject", "Preheader", "A body with no CTA token at all.")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 1, null);

        String body = res.variants().get(0).bodyMarkdown();
        assertThat(tokenCount(body)).isEqualTo(1);
        assertThat(body).endsWith("{{tickets_button}}");
    }

    @Test
    void collapsesDuplicateTicketsButtonsToOne() {
        stubCampaignWithEvent();
        stubModel(List.of(v("Subject", "Preheader",
                "Intro {{tickets_button}}\n\nMore copy\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 1, null);

        assertThat(tokenCount(res.variants().get(0).bodyMarkdown())).isEqualTo(1);
    }

    @Test
    void stripsTicketsButtonWhenNoEventLinked() {
        stubCampaignNoEvent();
        stubModel(List.of(v("Subject", "Preheader",
                "Hallucinated CTA here\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 1, null);

        String body = res.variants().get(0).bodyMarkdown();
        assertThat(tokenCount(body)).isZero();
        assertThat(body).doesNotContain("tickets_button");
    }

    @Test
    void countIsClampedToRequested() {
        stubCampaignWithEvent();
        stubModel(List.of(
                v("One", "p1", "b1\n\n{{tickets_button}}"),
                v("Two", "p2", "b2\n\n{{tickets_button}}"),
                v("Three", "p3", "b3\n\n{{tickets_button}}")));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 1, null);

        assertThat(res.variants()).hasSize(1);
    }

    @Test
    void emptyOrAllInvalidModelOutputFallsBackToOneGroundedVariant() {
        stubCampaignWithEvent();
        stubModel(List.of()); // model returned nothing usable

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 3, null);

        assertThat(res.variants()).hasSize(1);
        EmailVariant only = res.variants().get(0);
        assertThat(only.subject()).contains("Warehouse Mass");
        assertThat(only.bodyMarkdown()).contains("Warehouse Mass");
        // Event linked → the deterministic fallback still carries the CTA token.
        assertThat(tokenCount(only.bodyMarkdown())).isEqualTo(1);
    }

    @Test
    void llmFailureDegradesToFallbackWithoutError() {
        stubCampaignWithEvent();
        when(chat.prompt().user(anyString()).call().entity(EmailComposeVariantsLlm.class))
                .thenThrow(new RuntimeException("upstream down"));

        EmailComposeVariantsResponse res = sut.generate(principal(), campaignId, 3, null);

        assertThat(res.variants()).hasSize(1);
        assertThat(res.variants().get(0).subject()).contains("Warehouse Mass");
    }

    @Test
    void anotherOrgCampaignIsA404NoLeak() {
        UUID otherCampaignId = UUID.randomUUID();
        when(campaigns.findByIdAndOrgId(eq(otherCampaignId), eq(orgId))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.generate(principal(), otherCampaignId, null, null))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.status()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(api.code()).isEqualTo(ErrorCode.NOT_FOUND);
                });
    }
}
