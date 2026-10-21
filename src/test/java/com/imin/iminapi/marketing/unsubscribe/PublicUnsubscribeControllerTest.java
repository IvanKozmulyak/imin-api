package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.marketing.email.MarketingEmailProperties;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.repository.OrganizationRepository;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.support.AsyncDrain;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.RecordingRateLimiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The owned opt-out URL over the real ConsentService: GET reads or redirects, POST unsubscribes and projects the row. */
@IminIntegrationTest
class PublicUnsubscribeControllerTest {

    @Autowired MockMvc mvc;
    @Autowired UnsubscribeTokenService tokenService;
    @Autowired MembershipRepository memberships;
    @Autowired ConsumerRepository consumers;
    @Autowired ConsentRecordRepository consentRecords;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired IminFixtures fx;
    @Autowired OrganizationRepository organizations;
    @Autowired MarketingEmailProperties emailProps;
    @Autowired RecordingRateLimiter limiter;
    @Autowired JdbcTemplate jdbc;
    @Autowired @Qualifier(FanFeatureExecutors.LIVE) Executor fanFeatureExecutor;

    private final List<UUID> orgIds = new ArrayList<>();

    /** An unsubscribe queues a fan-feature recompute after commit; it finishes before the next test. */
    @AfterEach
    void drainAndDeleteOwnCampaigns() {
        try {
            AsyncDrain.drain(fanFeatureExecutor);
        } finally {
            CampaignRows.delete(jdbc, orgIds);
        }
    }

    private record Fixture(UUID orgId, UUID membershipId, UUID campaignId, UUID recipientId, String email) {}

    /** A subscribed member who received a sent campaign; the recipient row holds {@code recipientStatus}. */
    private Fixture seed(String recipientStatus) {
        return seed(recipientStatus, "en");
    }

    private Fixture seed(String recipientStatus, String locale) {
        Organization org = fx.org();
        org.setBrandName("Night Collective");
        org = organizations.save(org);
        UUID orgId = org.getId();
        orgIds.add(orgId);
        Consumer cn = new Consumer();
        cn.setNormalizedEmail(fx.email("unsub"));
        cn = consumers.save(cn);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(cn.getConsumerId());
        m.setStatus("active");
        m.setConsentStatus("subscribed");
        m.setConsentBasis("explicit");
        m = memberships.save(m);

        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("Blast");
        c.setStatus("sent");
        c.setLocale(locale);
        c.setCreatedAt(Instant.now());
        c.setUpdatedAt(Instant.now());
        campaigns.save(c);

        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(c.getId());
        r.setMembershipId(m.getMembershipId());
        r.setEmail(cn.getNormalizedEmail());
        r.setStatus(recipientStatus);
        recipients.save(r);
        return new Fixture(orgId, m.getMembershipId(), c.getId(), r.getId(), cn.getNormalizedEmail());
    }

    private String token(Fixture f, String channel) {
        return tokenService.sign(f.orgId(), f.membershipId(), f.campaignId(), channel);
    }

    private Membership membership(Fixture f) {
        return memberships.findByIdAndOrgId(f.membershipId(), f.orgId()).orElseThrow();
    }

    private List<String> optOutSources(Fixture f) {
        return jdbc.queryForList("select source from marketing_optouts where email_normalized = ? and org_id = ?",
                String.class, f.email(), f.orgId());
    }

    @Test
    void badTokenPost_returns404LeakSafe() throws Exception {
        mvc.perform(post("/api/v1/public/unsubscribe/{token}", "garbage.token")).andExpect(status().isNotFound());
    }

    private void assertNothingWritten(Fixture f, String recipientStatus) {
        assertThat(membership(f).getConsentStatus()).isEqualTo("subscribed");
        assertThat(consentRecords.findByMembershipId(f.membershipId())).isEmpty();
        assertThat(optOutSources(f)).isEmpty();
        assertThat(recipients.findById(f.recipientId()).orElseThrow().getStatus()).isEqualTo(recipientStatus);
    }

    @Test
    void detailsOfASubscribedMember_describeTheLink_andWriteNothing() throws Exception {
        Fixture f = seed("delivered", "fr");

        mvc.perform(get("/api/v1/public/unsubscribe/{token}/details", token(f, "email")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.state").value("subscribed"))
                .andExpect(jsonPath("$.channel").value("email"))
                .andExpect(jsonPath("$.organizerName").value("Night Collective"))
                .andExpect(jsonPath("$.locale").value("fr"))
                .andExpect(jsonPath("$.email").doesNotExist());

        assertNothingWritten(f, "delivered");
    }

    @Test
    void detailsOfAnUnsubscribedMember_sayUnsubscribed() throws Exception {
        Fixture f = seed("delivered", "uk");
        Membership m = membership(f);
        m.setConsentStatus("unsubscribed");
        memberships.save(m);

        mvc.perform(get("/api/v1/public/unsubscribe/{token}/details", token(f, "email")))
                .andExpect(jsonPath("$.state").value("unsubscribed"))
                .andExpect(jsonPath("$.locale").value("uk"));
    }

    @Test
    void detailsOfAnErasedMembership_sayUnsubscribed() throws Exception {
        Fixture f = seed("delivered", "fr");
        jdbc.update("delete from memberships where membership_id = ?", f.membershipId());

        mvc.perform(get("/api/v1/public/unsubscribe/{token}/details", token(f, "email")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("unsubscribed"))
                .andExpect(jsonPath("$.organizerName").value("Night Collective"));
    }

    @Test
    void detailsOfASignedTestToken_areTestWithOrgAndLocale() throws Exception {
        Fixture f = seed("delivered", "fr");

        mvc.perform(get("/api/v1/public/unsubscribe/{token}/details", tokenService.signTest(f.orgId(), f.campaignId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("test"))
                .andExpect(jsonPath("$.organizerName").value("Night Collective"))
                .andExpect(jsonPath("$.locale").value("fr"));

        assertNothingWritten(f, "delivered");
    }

    @Test
    void detailsOfTheLegacyPreviewToken_areTestWithNulls() throws Exception {
        mvc.perform(get("/api/v1/public/unsubscribe/{token}/details", "preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("test"))
                .andExpect(jsonPath("$.channel").doesNotExist())
                .andExpect(jsonPath("$.organizerName").doesNotExist())
                .andExpect(jsonPath("$.locale").doesNotExist());
    }

    @Test
    void detailsForABadCutOrNotifyToken_is404() throws Exception {
        Fixture f = seed("delivered");
        String good = token(f, "email");
        for (String bad : List.of("garbage.token", good.substring(0, good.length() - 3), tokenService.signNotify(UUID.randomUUID()))) {
            mvc.perform(get("/api/v1/public/unsubscribe/{token}/details", bad)).andExpect(status().isNotFound());
        }
    }

    /** Mail already sent, and clients that open the header URL, land on the human page; nothing is written. */
    @Test
    void oldApiLink_forwardsToTheBuyerPage_withoutWriting() throws Exception {
        Fixture f = seed("delivered");
        for (String token : List.of(token(f, "email"), "garbage.token")) {
            mvc.perform(get("/api/v1/public/unsubscribe/{token}", token))
                    .andExpect(status().isSeeOther())
                    .andExpect(header().string("Location", emailProps.unsubscribePageUrl(token)));
        }
        assertNothingWritten(f, "delivered");
    }

    /** The organizer's own mail client one-tap on a test email must neither error nor unsubscribe anyone. */
    @Test
    void oneClickOnATestEmail_writesNothing() throws Exception {
        Fixture f = seed("delivered");
        for (String token : List.of(tokenService.signTest(f.orgId(), f.campaignId()), "preview")) {
            mvc.perform(post("/api/v1/public/unsubscribe/{token}", token))
                    .andExpect(status().isOk())
                    .andExpect(content().string("test"));
        }
        assertNothingWritten(f, "delivered");
    }

    /** RFC 8058 one-click and the confirm page's own submit: a data-subject opt-out, sticky, projected onto the row. */
    @Test
    void oneClickPost_unsubscribesStickilyAndMarksTheRecipientRow() throws Exception {
        Fixture f = seed("delivered");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token(f, "email")))
                .andExpect(status().isOk());

        assertThat(membership(f).getConsentStatus()).isEqualTo("unsubscribed");
        assertThat(optOutSources(f)).containsExactly("one_click");
        CampaignRecipient after = recipients.findById(f.recipientId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo("unsubscribed");
        assertThat(after.getLastEventAt()).isNotNull();
        assertThat(recipients.countByCampaignIdAndStatus(f.campaignId(), "unsubscribed")).isEqualTo(1L);
    }

    /** A complaint outranks an opt-out: the projection must not walk a terminal status back. */
    @Test
    void oneClickDoesNotOverwriteAComplaint() throws Exception {
        Fixture f = seed("complained");

        mvc.perform(post("/api/v1/public/unsubscribe/{token}", token(f, "email")))
                .andExpect(status().isOk());

        assertThat(recipients.findById(f.recipientId()).orElseThrow().getStatus()).isEqualTo("complained");
        assertThat(membership(f).getConsentStatus()).isEqualTo("unsubscribed");
    }
}
