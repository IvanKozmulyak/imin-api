package com.imin.iminapi.marketing.send;

import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SendGateService;
import com.imin.iminapi.audienceplan.config.AudiencePlanAccess;
import com.imin.iminapi.audienceplan.config.AudiencePlanProperties;
import com.imin.iminapi.audienceplan.service.SendPathGuard;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.render.CampaignEmailRenderer;
import com.imin.iminapi.marketing.render.UtmLinkRewriter;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.service.CampaignTemplateService;
import com.imin.iminapi.marketing.service.MarketingGuardProperties;
import com.imin.iminapi.marketing.template.BuiltinTemplates;
import com.imin.iminapi.marketing.unsubscribe.UnsubscribeTokenService;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A failing org lookup never fails a live send, and never sends a plan campaign without its legal identity. */
class EmailChannelSenderOrgLookupTest {

    private final CampaignRecipientRepository recipients = mock(CampaignRecipientRepository.class);
    private final CampaignRepository campaigns = mock(CampaignRepository.class);
    private final CampaignEmailProvider provider = mock(CampaignEmailProvider.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final CampaignTemplateService templates = mock(CampaignTemplateService.class);
    private EmailChannelSender sender;

    @BeforeEach
    void setUp() {
        MarketingEmailProperties props = new MarketingEmailProperties();
        props.setFromAddress("contact@imin.support");
        props.setFromName("Alex");
        sender = new EmailChannelSender(recipients, campaigns, new CampaignEmailRenderer(new UtmLinkRewriter()),
                provider, new UnsubscribeTokenService("", "unit-test-secret-at-least-32-bytes"), props, templates,
                organizations, mock(EventRepository.class), mock(MembershipRepository.class),
                mock(ConsumerRepository.class), mock(SendGateService.class), new MarketingGuardProperties(),
                mock(SendPathGuard.class), mock(AddressSourceLines.class),
                new AudiencePlanAccess(new AudiencePlanProperties()));
        // The per-batch status re-read finds the campaign still on its way out.
        when(campaigns.findStatusById(any())).thenReturn(Optional.of("sending"));
        when(organizations.findById(any())).thenThrow(new IllegalStateException("db hiccup"));
        when(templates.resolve(any(), any())).thenReturn(BuiltinTemplates.all().get(0));
        when(provider.sendBatch(anyList())).thenReturn(List.of("id-a"));
    }

    private Campaign campaignWithPendingRow(String origin) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(UUID.randomUUID());
        c.setChannel("email");
        c.setStatus("sending");
        c.setOrigin(origin);
        c.setSubject("Subject");
        c.setBodyMd("Hello");
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setEmail("fan@example.test");
        r.setStatus("pending");
        when(recipients.claimPendingBatch(eq(c.getId()), anyInt(), any(Instant.class))).thenReturn(List.of(r));
        return c;
    }

    @Test
    void audiencePlanCampaign_failsWithItsRowsPending_andSendsNothing() {
        Campaign c = campaignWithPendingRow("audience_plan");
        when(campaigns.markFailedIfActive(eq(c.getId()), eq("ORG_LEGAL_IDENTITY_MISSING"), any())).thenReturn(1);

        assertThat(sender.sendNextBatch(c)).isFalse();

        verify(provider, never()).sendBatch(anyList());
        verify(recipients, never()).save(any());
        // The conditional UPDATE increments attempts in SQL; the drive's copy carries the outcome.
        verify(campaigns).markFailedIfActive(eq(c.getId()), eq("ORG_LEGAL_IDENTITY_MISSING"), any());
        assertThat(c.getStatus()).isEqualTo("failed");
        assertThat(c.getLastError()).isEqualTo("ORG_LEGAL_IDENTITY_MISSING");
    }

    @SuppressWarnings("unchecked")
    @Test
    void audiencePlanCampaign_canceledBeforeItCouldBeFailed_stopsOnTheStoredStatus() {
        Campaign c = campaignWithPendingRow("audience_plan");
        // 'sending' at the batch's re-read, canceled by the time the conditional fail runs.
        when(campaigns.findStatusById(c.getId())).thenReturn(Optional.of("sending"), Optional.of("canceled"));
        when(campaigns.markFailedIfActive(any(), any(), any())).thenReturn(0);

        assertThat(sender.sendNextBatch(c)).isFalse();

        verify(provider, never()).sendBatch(anyList());
        assertThat(c.getStatus()).isEqualTo("canceled");
    }

    @Test
    void manualCampaign_stillSendsFromTheConfiguredHeader() {
        Campaign c = campaignWithPendingRow("manual");

        sender.sendNextBatch(c);

        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> batch = ArgumentCaptor.forClass(List.class);
        verify(provider).sendBatch(batch.capture());
        assertThat(batch.getValue()).singleElement()
                .satisfies(e -> assertThat(e.from()).isEqualTo("Alex <contact@imin.support>"));
    }
}
