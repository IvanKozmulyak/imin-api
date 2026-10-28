package com.imin.iminapi.marketing;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.repository.SuppressionRepository;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.marketing.webhook.ResendWebhookProperties;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.PgFaults;
import com.imin.iminapi.support.PropertyFlips;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The signed Resend webhook end to end: signature, dedup claim and the real projector. */
@IminIntegrationTest
class ResendWebhookControllerTest {

    private static final String SECRET_B64 = "c3VwZXJzZWNyZXRrZXkw"; // base64("supersecretkey0")

    @Autowired MockMvc mvc;
    @Autowired CampaignRecipientRepository recipientRepo;
    @Autowired CampaignRepository campaignRepo;
    @Autowired ResendWebhookProperties webhookProps;
    @Autowired PropertyFlips flips;
    @Autowired IminFixtures fx;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumerRepository consumerRepo;
    @Autowired MembershipRepository membershipRepo;
    @Autowired MarketingEmailProperties marketingProps;
    @Autowired SuppressionRepository suppressions;

    private static final String MARKETING_SECRET_B64 = "bWFya2V0aW5nc2VjcmV0MQ=="; // base64("marketingsecret1")
    private final List<UUID> orgIds = new ArrayList<>();

    @BeforeEach
    void signingSecret() {
        flips.set(webhookProps, "secret", "whsec_" + SECRET_B64);
    }

    /** The campaigns are left `sending`, which the dispatcher reclaims for every org once stale. */
    @AfterEach
    void deleteOwnCampaigns() {
        CampaignRows.delete(jdbc, orgIds);
    }

    /** campaign_recipients.campaign_id is a NOT NULL FK, so each recipient gets a real parent campaign. */
    private CampaignRecipient recipient(String email) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("webhook-ctrl-test");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setEmail(email);
        r.setStatus("sent");
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        return recipientRepo.save(r);
    }

    private MockHttpServletRequestBuilder signed(String svixId, String body) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        return post("/api/v1/public/webhooks/resend")
                .header("svix-id", svixId).header("svix-timestamp", ts)
                .header("svix-signature", sign(svixId, ts, body))
                .contentType("application/json").content(body);
    }

    private String sign(String id, String ts, String body) throws Exception {
        return sign(SECRET_B64, id, ts, body);
    }

    private MockHttpServletRequestBuilder signedWith(String secretB64, String svixId, String body) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000L);
        return post("/api/v1/public/webhooks/resend")
                .header("svix-id", svixId).header("svix-timestamp", ts)
                .header("svix-signature", sign(secretB64, svixId, ts, body))
                .contentType("application/json").content(body);
    }

    private String sign(String secretB64, String id, String ts, String body) throws Exception {
        byte[] key = Base64.getDecoder().decode(secretB64);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return "v1," + Base64.getEncoder().encodeToString(
            mac.doFinal((id + "." + ts + "." + body).getBytes(StandardCharsets.UTF_8)));
    }

    private CampaignRecipient reload(CampaignRecipient r) {
        return recipientRepo.findById(r.getId()).orElseThrow();
    }

    @Test
    void validDeliveredWebhookMarksTheRecipientDelivered() throws Exception {
        String email = fx.email("delivered");
        CampaignRecipient r = recipient(email);
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"" + r.getProviderMessageId()
                + "\",\"to\":[\"" + email + "\"]},\"created_at\":\"2026-07-11T00:00:00Z\"}";

        mvc.perform(signed("svix_" + UUID.randomUUID(), body)).andExpect(status().isOk());

        CampaignRecipient after = reload(r);
        assertThat(after.getStatus()).isEqualTo("delivered");
        assertThat(after.getDeliveredAt()).isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
        assertThat(after.getLastEventAt()).isEqualTo(Instant.parse("2026-07-11T00:00:00Z"));
    }

    /**
     * The handler must pass {@code data.bounce.type} on: an absent type reads as transient, so only a
     * Permanent bounce reaches the projector as hard_bounce; the suppression is ResendWebhookProjectorTest's.
     */
    @Test
    void aPermanentBounceReachesTheProjectorAsAHardBounce() throws Exception {
        String email = fx.email("bounce");
        CampaignRecipient r = recipient(email);
        String body = "{\"type\":\"email.bounced\",\"data\":{\"email_id\":\"" + r.getProviderMessageId() + "\","
                + "\"to\":[\"" + email + "\"],"
                + "\"bounce\":{\"type\":\"Permanent\",\"subType\":\"General\",\"message\":\"no such user\"}}}";

        mvc.perform(signed("svix_" + UUID.randomUUID(), body)).andExpect(status().isOk());

        assertThat(reload(r).getErrorCode()).isEqualTo("hard_bounce");
    }

    @Test
    void badSignatureIs401AndLeavesTheRecipientUnchanged() throws Exception {
        String email = fx.email("forged");
        CampaignRecipient r = recipient(email);
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"" + r.getProviderMessageId()
                + "\",\"to\":[\"" + email + "\"]}}";

        mvc.perform(post("/api/v1/public/webhooks/resend")
                .header("svix-id", "svix_" + UUID.randomUUID())
                .header("svix-timestamp", String.valueOf(System.currentTimeMillis() / 1000L))
                .header("svix-signature", "v1,deadbeef")
                .contentType("application/json").content(body))
           .andExpect(status().isUnauthorized());

        CampaignRecipient after = reload(r);
        assertThat(after.getStatus()).isEqualTo("sent");
        assertThat(after.getDeliveredAt()).isNull();
    }

    @Test
    void aReplayedSvixIdIsAckedWithoutProjectingAgain() throws Exception {
        String email = fx.email("replay");
        CampaignRecipient r = recipient(email);
        Instant first = Instant.parse("2026-07-11T10:00:00Z");
        String svixId = "svix_dup_" + UUID.randomUUID();
        String body = "{\"type\":\"email.delivered\",\"data\":{\"email_id\":\"" + r.getProviderMessageId()
                + "\",\"to\":[\"" + email + "\"]},\"created_at\":\"%s\"}";

        mvc.perform(signed(svixId, body.formatted(first))).andExpect(status().isOk());
        // Same delivery id, re-signed, with a later timestamp: a second projection would move delivered_at.
        mvc.perform(signed(svixId, body.formatted(first.plus(1, ChronoUnit.HOURS)))).andExpect(status().isOk());

        assertThat(reload(r).getDeliveredAt()).isEqualTo(first);
    }

    private static final Instant SENT_AT = Instant.parse("2026-07-11T10:00:00Z");
    private static final Instant ENGAGED_AT = Instant.parse("2026-07-11T10:05:00Z");

    /** Which column a webhook should leave stamped with {@link #ENGAGED_AT}. */
    record Stamps(boolean opened, boolean clicked, boolean ticketClicked) {}

    /** The {@code data.click} member of a click webhook; null for an open. Built from the campaign's event id. */
    private record Click(String name, Function<UUID, String> link) {
        @Override public String toString() { return name; }
    }

    static Stream<Arguments> engagementWebhooks() {
        return Stream.of(
                arguments("opened", "email.opened", null, new Stamps(true, false, false)),
                arguments("click on the campaign event's page", "email.clicked",
                        new Click("event page", eventId -> "/e/" + eventId + "?utm_source=imin&utm_medium=email"),
                        new Stamps(false, true, true)),
                arguments("click on another link", "email.clicked",
                        new Click("external", eventId -> "https://instagram.com/imin"), new Stamps(false, true, false)),
                arguments("click on the unsubscribe link", "email.clicked",
                        new Click("unsubscribe", eventId -> "unsubscribe"), new Stamps(false, false, false)),
                arguments("click with no link", "email.clicked", new Click("no link", eventId -> null),
                        new Stamps(false, true, false)));
    }

    /** Opens and clicks stamp the recipient row only (not the send time caps read); the claim names no one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engagementWebhooks")
    void anEngagementWebhookStampsTheRecipientRow(String name, String type, Click click, Stamps expected)
            throws Exception {
        Engaged e = engagedRecipient(fx.email("engagement"), false);
        String svixId = "svix_" + UUID.randomUUID();

        mvc.perform(signed(svixId, engagementBody(e, type, click))).andExpect(status().isOk());

        CampaignRecipient after = reload(e.recipient());
        assertThat(after.getOpenedAt()).as("opened_at").isEqualTo(expected.opened() ? ENGAGED_AT : null);
        assertThat(after.getClickedAt()).as("clicked_at").isEqualTo(expected.clicked() ? ENGAGED_AT : null);
        assertThat(after.getTicketClickedAt()).as("ticket_clicked_at")
                .isEqualTo(expected.ticketClicked() ? ENGAGED_AT : null);
        assertThat(after.getLastEventAt()).as("last_event_at").isEqualTo(SENT_AT);
        assertThat(after.getStatus()).as("status").isEqualTo("delivered");
        assertClaimedAnonymously(svixId, type);
    }

    /** Art. 21: after an objection nothing about this person's opens or clicks is written; the claim names no one. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("engagementWebhooks")
    void anObjectedMembersOpenOrClickIsNotStamped(String name, String type, Click click, Stamps ignored)
            throws Exception {
        Engaged e = engagedRecipient(fx.email("objected"), true);
        String svixId = "svix_" + UUID.randomUUID();

        mvc.perform(signed(svixId, engagementBody(e, type, click))).andExpect(status().isOk());

        CampaignRecipient after = reload(e.recipient());
        assertThat(after.getOpenedAt()).as("opened_at").isNull();
        assertThat(after.getClickedAt()).as("clicked_at").isNull();
        assertThat(after.getTicketClickedAt()).as("ticket_clicked_at").isNull();
        assertThat(after.getLastEventAt()).as("last_event_at").isEqualTo(SENT_AT);
        assertClaimedAnonymously(svixId, type);
    }

    private record Engaged(CampaignRecipient recipient, UUID eventId, String email) {}

    /** A delivered row of an email campaign linked to an event, for a member who has or has not objected. */
    private Engaged engagedRecipient(String email, boolean objected) {
        UUID orgId = UUID.randomUUID();
        orgIds.add(orgId);
        Consumer consumer = new Consumer();
        consumer.setNormalizedEmail(email.toLowerCase(Locale.ROOT));
        consumer = consumerRepo.save(consumer);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        m.setConsentStatus(objected ? "unsubscribed" : "subscribed");
        m.setConsentBasis("explicit");
        m.setObjectedProfiling(objected);
        m = membershipRepo.save(m);

        UUID eventId = UUID.randomUUID();
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("webhook-engagement");
        c.setStatus("sending");
        c.setEventId(eventId);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus("delivered");
        r.setDeliveredAt(SENT_AT);
        r.setLastEventAt(SENT_AT);
        r.setProviderMessageId("msg_" + UUID.randomUUID());
        return new Engaged(recipientRepo.save(r), eventId, email);
    }

    /** A Resend open or click body; a click link is resolved against the configured buyer site or unsubscribe URL. */
    private String engagementBody(Engaged e, String type, Click click) {
        String clickMember = "";
        if (click != null) {
            String link = click.link().apply(e.eventId());
            if ("unsubscribe".equals(link)) link = marketingProps.unsubscribeUrl("tok_" + UUID.randomUUID());
            else if (link != null && link.startsWith("/")) link = marketingProps.getBuyerSiteBaseUrl() + link;
            clickMember = link == null ? ",\"click\":{\"timestamp\":\"" + ENGAGED_AT + "\"}"
                    : ",\"click\":{\"link\":\"" + link + "\",\"timestamp\":\"" + ENGAGED_AT + "\"}";
        }
        return "{\"type\":\"" + type + "\",\"created_at\":\"" + ENGAGED_AT + "\",\"data\":{\"email_id\":\""
                + e.recipient().getProviderMessageId() + "\",\"to\":[\"" + e.email() + "\"]" + clickMember + "}}";
    }

    /** The privacy policy keeps only the latest open or click per row, so the dedup claim links to no one. */
    private void assertClaimedAnonymously(String svixId, String type) {
        Map<String, Object> row = jdbc.queryForMap("SELECT type, campaign_id, recipient_id, provider_message_id"
                + " FROM provider_events WHERE provider = 'resend' AND provider_event_id = ?", svixId);
        assertThat(row.get("type")).isEqualTo(type);
        assertThat(row.get("campaign_id")).as("campaign_id").isNull();
        assertThat(row.get("recipient_id")).as("recipient_id").isNull();
        assertThat(row.get("provider_message_id")).as("provider_message_id").isNull();
    }

    private record Queued(UUID orgId, UUID campaignId, UUID membershipId, CampaignRecipient row, String email) {}

    /** A real org's sending campaign with one member's row in {@code status}, sent without a provider id. */
    private Queued queued(String status) {
        UUID orgId = fx.org().getId();
        orgIds.add(orgId);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("tagged");
        c.setStatus("sending");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);
        String email = fx.email("tagged");
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(email);
        cn = consumerRepo.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m = membershipRepo.save(m);
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(email);
        r.setStatus(status);
        if (!"pending".equals(status)) r.setErrorCode("no_provider_id");
        return new Queued(orgId, c.getId(), m.getMembershipId(), recipientRepo.save(r), email);
    }

    private static String taggedBody(String type, String messageId, String email, UUID campaignId, Instant createdAt) {
        return "{\"type\":\"" + type + "\",\"created_at\":\"" + createdAt + "\",\"data\":{\"email_id\":\""
                + messageId + "\",\"to\":[\"" + email + "\"],\"tags\":{\"imin_campaign\":\"" + campaignId + "\"}}}";
    }

    private int claimed(String svixId) {
        return jdbc.queryForObject("select count(*) from provider_events where provider_event_id = ?", Integer.class, svixId);
    }

    @Test
    void anEarlyEventForATaggedCampaignIsRetriedThenProjected() throws Exception {
        Queued q = queued("pending");
        String messageId = "msg_" + UUID.randomUUID();
        String svixId = "svix_" + UUID.randomUUID();
        String body = taggedBody("email.complained", messageId, q.email(), q.campaignId(), Instant.now());

        mvc.perform(signed(svixId, body)).andExpect(status().isServiceUnavailable());
        assertThat(claimed(svixId)).isZero();
        assertThat(reload(q.row()).getStatus()).isEqualTo("pending");

        // The batch commits: the row is recorded sent with the id Resend returned.
        jdbc.update("update campaign_recipients set status = 'sent', provider_message_id = ?, error_code = null"
                + " where id = ?", messageId, q.row().getId());
        mvc.perform(signed(svixId, body)).andExpect(status().isOk());

        assertThat(reload(q.row()).getStatus()).isEqualTo("complained");
        assertThat(suppressions.findMarketingSuppressedMembershipIds(q.orgId(), List.of(q.membershipId())))
                .containsExactly(q.membershipId());
        assertThat(claimed(svixId)).isEqualTo(1);
    }

    @Test
    void aLateEventFindsItsRowByCampaignAndAddress() throws Exception {
        Queued q = queued("sent");
        String messageId = "msg_" + UUID.randomUUID();
        String svixId = "svix_" + UUID.randomUUID();
        Instant twoHoursAgo = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

        mvc.perform(signed(svixId, taggedBody("email.delivered", messageId, q.email().toUpperCase(Locale.ROOT),
                q.campaignId(), twoHoursAgo))).andExpect(status().isOk());

        CampaignRecipient after = reload(q.row());
        assertThat(after.getStatus()).isEqualTo("delivered");
        assertThat(after.getDeliveredAt()).isEqualTo(twoHoursAgo);
        assertThat(after.getProviderMessageId()).isEqualTo(messageId);
        assertThat(jdbc.queryForObject("select recipient_id from provider_events where provider_event_id = ?",
                UUID.class, svixId)).isEqualTo(q.row().getId());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"untaggedPermanentBounce", "untaggedTransientBounce", "signedWithTheMarketingSecret",
            "signedWithNeither"})
    void unmatchedBouncesAndBothSecrets(String scenario) throws Exception {
        flips.set(webhookProps, "marketingSecret", "whsec_" + MARKETING_SECRET_B64);
        String email = fx.email("ticket-buyer");
        String bounceType = "untaggedTransientBounce".equals(scenario) ? "Transient" : "Permanent";
        String body = "{\"type\":\"email.bounced\",\"created_at\":\"" + Instant.now() + "\",\"data\":{"
                + "\"email_id\":\"msg_" + UUID.randomUUID() + "\",\"to\":[\"" + email + "\"],"
                + "\"bounce\":{\"type\":\"" + bounceType + "\"}}}";
        String svixId = "svix_" + UUID.randomUUID();

        var result = switch (scenario) {
            case "signedWithTheMarketingSecret" -> mvc.perform(signedWith(MARKETING_SECRET_B64, svixId, body));
            case "signedWithNeither" -> mvc.perform(signedWith("bmVpdGhlcnNlY3JldDAw", svixId, body));
            default -> mvc.perform(signed(svixId, body));
        };

        if ("signedWithNeither".equals(scenario)) {
            result.andExpect(status().isUnauthorized());
            assertThat(claimed(svixId)).isZero();
            assertThat(suppressions.findDeliverabilityEmailsIn(List.of(email))).isEmpty();
        } else if ("untaggedTransientBounce".equals(scenario)) {
            result.andExpect(status().isOk());
            assertThat(suppressions.findDeliverabilityEmailsIn(List.of(email))).isEmpty();
        } else {
            result.andExpect(status().isOk());
            assertThat(suppressions.findDeliverabilityEmailsIn(List.of(email))).containsExactly(email);
        }
    }

    /** A complaint about a tagged email whose row is gone still protects the person and counts for the org's breaker. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"memberInsideTheGrace", "noMemberAfterTheGrace"})
    void aTaggedComplaintWithNoRowSuppressesByAddress(String scenario) throws Exception {
        UUID orgId = fx.org().getId();
        orgIds.add(orgId);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("purged rows");
        c.setStatus("sent");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);
        String email = fx.email("complainer");
        UUID membershipId = null;
        if ("memberInsideTheGrace".equals(scenario)) {
            Consumer cn = new Consumer();
            cn.setNormalizedEmail(email);
            cn = consumerRepo.save(cn);
            Membership m = new Membership();
            m.setOrgId(orgId);
            m.setConsumerId(cn.getConsumerId());
            m.setConsentStatus("subscribed");
            m.setConsentBasis("explicit");
            membershipId = membershipRepo.save(m).getMembershipId();
        }
        Instant createdAt = "memberInsideTheGrace".equals(scenario) ? Instant.now() : Instant.now().minus(2, ChronoUnit.HOURS);
        String svixId = "svix_" + UUID.randomUUID();

        mvc.perform(signed(svixId, taggedBody("email.complained", "msg_" + UUID.randomUUID(), email, c.getId(), createdAt)))
                .andExpect(status().isOk());

        if (membershipId != null) {
            assertThat(suppressions.findMarketingSuppressedMembershipIds(orgId, List.of(membershipId))).containsExactly(membershipId);
        } else {
            assertThat(jdbc.queryForObject("select count(*) from marketing_optouts where email_normalized = ? and org_id = ?",
                    Integer.class, email, orgId)).isEqualTo(1);
        }
        // Filed under the campaign, so the breaker's per-campaign and org counts include it.
        assertThat(jdbc.queryForObject("select campaign_id from provider_events where provider_event_id = ?",
                UUID.class, svixId)).isEqualTo(c.getId());
    }

    /** A complaint whose opt-out could not be written is not acknowledged: nothing is claimed and Resend's retry records it. */
    @Test
    void aTaggedComplaintWhoseOptOutWriteFailsIsRetriedUntilRecorded() throws Exception {
        UUID orgId = fx.org().getId();
        orgIds.add(orgId);
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("purged rows");
        c.setStatus("sent");
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaignRepo.save(c);
        String email = fx.email("complainer");            // no consumer, no membership: the opt-out row is the whole record
        String svixId = "svix_" + UUID.randomUUID();
        String body = taggedBody("email.complained", "msg_" + UUID.randomUUID(), email, c.getId(),
                Instant.now().minus(2, ChronoUnit.HOURS));

        try (PgFaults.Fault ignored = PgFaults.failWrites(jdbc, "marketing_optouts", "org_id", orgId)) {
            mvc.perform(signed(svixId, body)).andExpect(status().isInternalServerError());
        }
        assertThat(optOuts(email, orgId)).isZero();
        assertThat(claimed(svixId)).isZero();

        mvc.perform(signed(svixId, body)).andExpect(status().isOk());
        assertThat(optOuts(email, orgId)).isEqualTo(1);
        assertThat(claimed(svixId)).isEqualTo(1);
    }

    private int optOuts(String email, UUID orgId) {
        return jdbc.queryForObject("select count(*) from marketing_optouts where email_normalized = ? and org_id = ?",
                Integer.class, email, orgId);
    }
}
