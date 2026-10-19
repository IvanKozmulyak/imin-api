package com.imin.iminapi.marketing.unsubscribe;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.repository.ConsentRecordRepository;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audienceplan.config.FanFeatureExecutors;
import com.imin.iminapi.marketing.model.Campaign;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The owned opt-out URL over the real ConsentService: GET only confirms, POST unsubscribes and projects the row. */
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
        UUID orgId = UUID.randomUUID();
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"GET", "POST"})
    void badToken_returns404LeakSafe(String method) throws Exception {
        var request = "GET".equals(method)
                ? get("/api/v1/public/unsubscribe/{token}", "garbage.token")
                : post("/api/v1/public/unsubscribe/{token}", "garbage.token");
        mvc.perform(request).andExpect(status().isNotFound());
    }

    /**
     * The GET used to write the opt-out, so a link prefetcher, a mail-client image proxy or a corporate URL
     * scanner unsubscribed people who never clicked. It renders a confirm page and touches nothing.
     */
    @Test
    void confirmationPageGet_doesNotMutate_and_offers_a_post_form_to_the_same_url() throws Exception {
        Fixture f = seed("delivered");
        String token = token(f, "email");

        String html = mvc.perform(get("/api/v1/public/unsubscribe/{token}", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(html).contains("method=\"post\"");
        assertThat(html).contains("/api/v1/public/unsubscribe/" + token);
        assertThat(membership(f).getConsentStatus()).isEqualTo("subscribed");
        assertThat(consentRecords.findByMembershipId(f.membershipId())).isEmpty();
        assertThat(optOutSources(f)).isEmpty();
        assertThat(recipients.findById(f.recipientId()).orElseThrow().getStatus()).isEqualTo("delivered");
    }

    @Test
    void confirmationPage_smsToken_returnsHtml() throws Exception {
        Fixture f = seed("delivered");

        mvc.perform(get("/api/v1/public/unsubscribe/{token}", token(f, "sms")))
                .andExpect(status().isOk());
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

    /** A broken or old link must show a 404 page, not a server error, for a browser Accept and for strict text/html. */
    @ParameterizedTest
    @ValueSource(strings = {
            "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "text/html"})
    void confirmationPageGet_withHtmlAcceptAndBadToken_is404Html(String accept) throws Exception {
        mvc.perform(get("/api/v1/public/unsubscribe/{token}", "not-a-valid-token").accept(accept))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("<form"))))
                .andExpect(content().string(not(containsString("not-a-valid-token"))));
    }

    /** A recipient on a busy shared IP gets a "wait, then retry" page, not raw JSON, so the opt-out stays completable. */
    @Test
    void confirmationPageGet_overRateLimit_is429Html() throws Exception {
        limiter.limit("unsubscribe", 0);
        mvc.perform(get("/api/v1/public/unsubscribe/{token}", "not-a-valid-token")
                        .accept("text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"))
                .andExpect(status().isTooManyRequests())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(not(containsString("<form"))));
    }
}
