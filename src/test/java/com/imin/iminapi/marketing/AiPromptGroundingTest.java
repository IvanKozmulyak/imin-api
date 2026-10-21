package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.repository.SegmentRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.marketing.dto.EmailComposeVariantsResponse;
import com.imin.iminapi.marketing.dto.GenerateTemplateRequest;
import com.imin.iminapi.marketing.dto.MomentumDraftPayload;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.MomentumTriggerType;
import com.imin.iminapi.marketing.repository.CampaignEmailTemplateRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignAiSuggestions;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.marketing.service.EmailComposeVariantsService;
import com.imin.iminapi.marketing.service.MomentumCopyGenerator;
import com.imin.iminapi.marketing.service.SubjectVariantsService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.UserRole;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.TicketTierRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.security.AuthPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Every AI entry point tells the model the event's local start and the language to write in. */
class AiPromptGroundingTest {

    /** A Paris event at 00:30 on Saturday; in UTC it is still Friday 23:30. */
    private static final Instant STARTS = Instant.parse("2026-10-30T23:30:00Z");
    private static final String LOCAL_START = "Saturday 31 October 2026, 00:30 (Europe/Paris)";

    private final UUID orgId = UUID.randomUUID();
    private final List<String> prompts = new ArrayList<>();
    private final ChatClient chat = capturingChat(prompts, null);

    /** A ChatClient whose model answers {@code answer} (null = no answer); every prompt it was asked is recorded. */
    @SuppressWarnings("unchecked")
    private static ChatClient capturingChat(List<String> sink, Object answer) {
        ChatClient chat = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chat.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenAnswer(inv -> {
            sink.add(inv.getArgument(0));
            return spec;
        });
        when(spec.call()).thenReturn(call);
        when(call.entity(any(Class.class))).thenReturn(answer);
        return chat;
    }

    private AuthPrincipal principal() {
        return new AuthPrincipal(UUID.randomUUID(), orgId, UserRole.OWNER, UUID.randomUUID());
    }

    private Event parisEvent() {
        Event e = new Event();
        e.setId(UUID.randomUUID());
        e.setOrgId(orgId);
        e.setName("Nuit Blanche");
        e.setStartsAt(STARTS);
        e.setTimezone("Europe/Paris");
        return e;
    }

    private Campaign campaign(UUID eventId, String locale) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Newsletter");
        c.setStatus("draft");
        c.setEventId(eventId);
        c.setLocale(locale);
        return c;
    }

    @ParameterizedTest
    @CsvSource({"compose,French", "subject,French", "template,French"})
    void promptCarriesLocalStartAndLanguage(String entryPoint, String language) {
        Event event = parisEvent();
        CampaignRepository campaigns = mock(CampaignRepository.class);
        EventRepository events = mock(EventRepository.class);
        OrganizationRepository organizations = mock(OrganizationRepository.class);
        Campaign campaign = campaign(event.getId(), "fr");
        when(campaigns.findByIdAndOrgId(eq(campaign.getId()), eq(orgId))).thenReturn(Optional.of(campaign));
        when(events.findActive(event.getId())).thenReturn(Optional.of(event));
        Organization org = new Organization();
        org.setName("Collectif");
        org.setCountry("FR");
        when(organizations.findById(orgId)).thenReturn(Optional.of(org));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

        switch (entryPoint) {
            case "compose" -> new EmailComposeVariantsService(chat, campaigns, events, organizations,
                    mock(SegmentRepository.class), mock(SegmentService.class), mock(CampaignAiSuggestions.class), tx)
                    .generate(principal(), campaign.getId(), 3, null);
            case "subject" -> new SubjectVariantsService(chat, campaigns, events, mock(TicketTierRepository.class),
                    mock(CampaignAiSuggestions.class), tx).generate(principal(), campaign.getId(), null);
            default -> {
                CampaignEmailTemplateRepository templates = mock(CampaignEmailTemplateRepository.class);
                when(templates.save(any())).thenAnswer(inv -> inv.getArgument(0));
                new CampaignTemplateService(chat, templates, events, organizations, tx, mock(UserRepository.class))
                        .generate(principal(), new GenerateTemplateRequest(event.getId(), null));
            }
        }

        assertThat(prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains(LOCAL_START).contains(language);
            assertThat(prompt).doesNotContain("2026-10-30T23:30:00Z");
        });
    }

    @Test
    void momentumWritesCopyInTheOrgLanguageAndKeepsWhyEnglish() {
        ChatClient momentumChat = capturingChat(prompts, new MomentumDraftPayload("s", "p", "b", null, null, "why"));
        MomentumDraftPayload out = new MomentumCopyGenerator(momentumChat).generate(
                MomentumTriggerType.URGENCY_72H, "Нічний сет", LOCAL_START, "Docker", "72 hours left", null, null, "uk");

        assertThat(prompts).singleElement().satisfies(prompt -> {
            assertThat(prompt).contains("in Ukrainian");
            assertThat(prompt).contains("Event date (local to the event): " + LOCAL_START);
        });
        assertThat(out.locale()).isEqualTo("uk");
    }

    @ParameterizedTest
    @CsvSource({
            "compose,fr,Vous êtes invité,Bonjour {{firstName}}",
            "compose,uk,Вас запрошено,'Привіт, {{firstName}}!'",
            "subject,fr,Ne manquez pas,",
            "subject,uk,Не пропустіть,"})
    void fallbacksAreInTheCampaignLanguage(String entryPoint, String locale, String subjectPart, String greeting) {
        Event event = parisEvent();
        CampaignRepository campaigns = mock(CampaignRepository.class);
        EventRepository events = mock(EventRepository.class);
        Campaign campaign = campaign(event.getId(), locale);
        when(campaigns.findByIdAndOrgId(eq(campaign.getId()), eq(orgId))).thenReturn(Optional.of(campaign));
        when(events.findActive(event.getId())).thenReturn(Optional.of(event));
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);

        if ("compose".equals(entryPoint)) {
            EmailComposeVariantsResponse res = new EmailComposeVariantsService(chat, campaigns, events,
                    mock(OrganizationRepository.class), mock(SegmentRepository.class), mock(SegmentService.class),
                    mock(CampaignAiSuggestions.class), tx).generate(principal(), campaign.getId(), 1, null);
            assertThat(res.variants()).singleElement().satisfies(v -> {
                assertThat(v.subject()).contains(subjectPart);
                assertThat(v.bodyMarkdown()).startsWith(greeting).contains("{{tickets_button}}");
                assertThat(v.bodyMarkdown() + v.preheader()).doesNotContain("Hi there").doesNotContain("Save your spot");
            });
        } else {
            var res = new SubjectVariantsService(chat, campaigns, events, mock(TicketTierRepository.class),
                    mock(CampaignAiSuggestions.class), tx).generate(principal(), campaign.getId(), null);
            assertThat(res.variants()).hasSize(3).allSatisfy(s ->
                    assertThat(s).doesNotContain("Don't miss").doesNotContain("You're invited").doesNotContain("Last chance"));
            assertThat(res.variants()).anyMatch(s -> s.contains(subjectPart));
        }
    }
}
