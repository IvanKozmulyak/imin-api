package com.imin.iminapi.marketing;

import com.imin.iminapi.marketing.dto.RenderPreviewRequest;
import com.imin.iminapi.marketing.dto.RenderPreviewResponse;
import com.imin.iminapi.marketing.email.CampaignEmailProvider;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.send.EmailChannelSender;
import com.imin.iminapi.marketing.service.CampaignService;
import com.imin.iminapi.model.Event;
import com.imin.iminapi.model.EventStatus;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.model.User;
import com.imin.iminapi.repository.EventRepository;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.repository.UserRepository;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The dashboard preview is the email the send delivers, built from what is on screen, and writes nothing. */
@IminIntegrationTest
class CampaignRenderPreviewTest {

    @Autowired CampaignService service;
    @Autowired EmailChannelSender sender;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired CampaignEmailProvider provider;
    @Autowired MarketingEmailProperties marketingProps;
    @Autowired OrganizationRepository orgs;
    @Autowired UserRepository users;
    @Autowired EventRepository events;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> orgIds = new ArrayList<>();
    private final List<UUID> consumerIds = new ArrayList<>();
    private Organization org;
    private User owner;

    @BeforeEach
    void setUp() {
        flips.set(marketingProps, "fromAddress", "contact@imin.support");
        org = fx.org();
        org.setBrandName("Night");
        org.setLegalName("Night SAS");
        org.setLegalContact("legal@night.test");
        org = orgs.save(org);
        orgIds.add(org.getId());
        owner = fx.owner(org);
    }

    /** The send test leaves its campaign 'sending', which the global claim would pick up. */
    @AfterEach
    void tearDown() {
        CampaignRows.delete(jdbc, orgIds);
        for (UUID id : consumerIds) {
            jdbc.update("delete from memberships where consumer_id = ?", id);
            jdbc.update("delete from consumers where consumer_id = ?", id);
        }
    }

    private void ownerFirstName(String firstName) {
        owner.setFirstName(firstName);
        owner = users.save(owner);
    }

    private Campaign campaign(String status, String templateKey, UUID eventId, String subject, String bodyMd) {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(org.getId());
        c.setChannel("email");
        c.setName("Last tickets");
        c.setStatus(status);
        c.setOrigin("manual");
        c.setTemplateKey(templateKey);
        c.setEventId(eventId);
        c.setSubject(subject);
        c.setPreheader("Doors 23:00, {{firstName}}");
        c.setBodyMd(bodyMd);
        c.setLocale("fr");
        Instant at = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        c.setCreatedAt(at);
        c.setUpdatedAt(at);
        return campaigns.save(c);
    }

    /** A pending row for a subscribed member whose display name starts with {@code displayName}'s first word. */
    private String pendingMember(Campaign c, String displayName) {
        UUID consumer = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        consumerIds.add(consumer);
        String email = fx.email("fan");
        jdbc.update("insert into consumers (consumer_id, normalized_email, display_name) values (?, ?, ?)",
                consumer, email, displayName);
        jdbc.update("insert into memberships (membership_id, org_id, consumer_id, status, consent_status, consent_basis)"
                + " values (?, ?, ?, 'active', 'subscribed', 'explicit')", member, org.getId(), consumer);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(member);
        r.setEmail(email);
        r.setStatus("pending");
        recipients.save(r);
        return email;
    }

    @SuppressWarnings("unchecked")
    private CampaignEmailProvider.OutgoingEmail sentTo(String email) {
        ArgumentCaptor<List<CampaignEmailProvider.OutgoingEmail>> captor = ArgumentCaptor.forClass(List.class);
        verify(provider, atLeast(0)).sendBatch(any(), captor.capture());
        List<CampaignEmailProvider.OutgoingEmail> mine = captor.getAllValues().stream().flatMap(List::stream)
                .filter(e -> email.equals(e.to())).toList();
        assertThat(mine).hasSize(1);
        return mine.get(0);
    }

    /** Each fan's unsubscribe link carries their own token; everything else must match byte for byte. */
    private static String withoutUnsubscribeToken(String html) {
        return html.replaceAll("/unsubscribe/[^\"]+\"", "/unsubscribe/TOKEN\"");
    }

    @Test
    void previewIsTheEmailTheSendDelivers() {
        ownerFirstName("Ivan");
        Event event = fx.event(org, owner, EventStatus.LIVE, Instant.now().plus(3, ChronoUnit.DAYS));
        event.setPosterUrl("https://cdn.imin.wtf/posters/" + UUID.randomUUID() + ".png");
        event = events.save(event);
        String body = "## Last tickets\n\nBonjour {{firstName}},\nil reste **40 billets**\npour vendredi.\n\n"
                + "Le line-up est sur [notre page](https://app.imin.wtf/lineup) et {{eventUrl}}.\n\n{{tickets_button}}";
        Campaign c = campaign("sending", "poster", event.getId(), "Derniers billets, {{firstName}}", body);
        String fan = pendingMember(c, "Ivan Petrenko");
        when(provider.sendBatch(any(), anyList())).thenReturn(CampaignEmailProvider.BatchResult.accepted(List.of("id-a")));

        RenderPreviewResponse preview = service.renderPreview(fx.principal(owner), c.getId(), null);
        sender.sendNextBatch(c);

        CampaignEmailProvider.OutgoingEmail sent = sentTo(fan);
        assertThat(withoutUnsubscribeToken(preview.html())).isEqualTo(withoutUnsubscribeToken(sent.html()));
        assertThat(preview.subject()).isEqualTo(sent.subject()).isEqualTo("Derniers billets, Ivan");
        // The preheader the inbox shows is the hidden block at the top of the delivered HTML.
        assertThat(preview.preheader()).isEqualTo("Doors 23:00, Ivan");
        assertThat(sent.html()).contains("opacity:0;\">" + preview.preheader() + "</div>");
        assertThat(sent.from()).isEqualTo("\"" + preview.fromName() + "\" <" + preview.fromAddress() + ">");
        assertThat(preview.locale()).isEqualTo("fr");
        // Proof the equality is not vacuous: the poster hero and the tagged tickets button are in both.
        String ticketsUrl = "https://app.imin.wtf/e/" + event.getId()
                + "?utm_source=imin&utm_medium=email&utm_campaign=" + c.getId();
        assertThat(preview.html()).contains("<img src=\"" + event.getPosterUrl() + "\"")
                .contains("href=\"" + ticketsUrl + "\"")
                .contains("<h2>Last tickets</h2>")
                .contains("Night &middot; Night SAS &middot; legal@night.test");
    }

    @Test
    void unsavedTextIsRenderedAndNothingIsWritten() {
        Campaign c = campaign("draft", "classic", null, "Saved subject", "Saved body");
        Map<String, Object> before = jdbc.queryForMap(
                "select subject, preheader, body_md, template_key, locale, updated_at from campaigns where id = ?", c.getId());

        RenderPreviewResponse preview = service.renderPreview(fx.principal(owner), c.getId(),
                new RenderPreviewRequest("On-screen subject", "On-screen preheader", "On-screen **body**",
                        "midnight", "es", null));

        assertThat(preview.subject()).isEqualTo("On-screen subject");
        assertThat(preview.preheader()).isEqualTo("On-screen preheader");
        assertThat(preview.html()).contains("On-screen <strong>body</strong>").doesNotContain("Saved body")
                // the Midnight template's background, and the Spanish footer
                .contains("background:#0f0a1f").contains("Darse de baja");
        assertThat(preview.locale()).isEqualTo("es");
        assertThat(jdbc.queryForMap(
                "select subject, preheader, body_md, template_key, locale, updated_at from campaigns where id = ?", c.getId()))
                .isEqualTo(before);
    }

    @ParameterizedTest(name = "{0} with organizer first name ''{1}''")
    @CsvSource({
            "named,   Ivan, '<p>Bonjour Ivan,</p>', Ivan",
            "unnamed, Ivan, '<p>Bonjour,</p>',      ",
            "named,   '',   '<p>Bonjour,</p>',      "})
    void firstNameVersions(String recipient, String organizerFirstName, String greeting, String previewFirstName) {
        ownerFirstName(organizerFirstName);
        Campaign c = campaign("draft", "classic", null, "S", "Bonjour {{firstName}},\n\nÀ vendredi.");

        RenderPreviewResponse preview = service.renderPreview(fx.principal(owner), c.getId(),
                new RenderPreviewRequest(null, null, null, null, null, recipient));

        assertThat(preview.html()).contains(greeting);
        assertThat(preview.previewFirstName()).isEqualTo(previewFirstName);
    }
}
