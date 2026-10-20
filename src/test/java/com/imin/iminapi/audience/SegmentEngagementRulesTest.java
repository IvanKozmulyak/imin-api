package com.imin.iminapi.audience;

import com.imin.iminapi.audience.model.Consumer;
import com.imin.iminapi.audience.model.Membership;
import com.imin.iminapi.audience.model.Segment;
import com.imin.iminapi.audience.repository.ConsumerRepository;
import com.imin.iminapi.audience.repository.MembershipRepository;
import com.imin.iminapi.audience.service.SegmentService;
import com.imin.iminapi.marketing.model.Campaign;
import com.imin.iminapi.marketing.model.CampaignRecipient;
import com.imin.iminapi.marketing.repository.CampaignRecipientRepository;
import com.imin.iminapi.marketing.repository.CampaignRepository;
import com.imin.iminapi.model.Organization;
import com.imin.iminapi.security.AuthPrincipal;
import com.imin.iminapi.support.CampaignRows;
import com.imin.iminapi.support.IminFixtures;
import com.imin.iminapi.support.IminIntegrationTest;
import com.imin.iminapi.support.MutableClock;
import com.imin.iminapi.support.OrgRows;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The engagement segment fields read this org's recipient rows, as of the injected clock, skipping objected members. */
@IminIntegrationTest
class SegmentEngagementRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-20T00:30:00Z");

    @Autowired MockMvc mvc;
    @Autowired IminFixtures fx;
    @Autowired MutableClock clock;
    @Autowired JdbcTemplate jdbc;
    @Autowired ConsumerRepository consumers;
    @Autowired MembershipRepository memberships;
    @Autowired CampaignRepository campaigns;
    @Autowired CampaignRecipientRepository recipients;
    @Autowired SegmentService segmentService;

    private UUID orgId;
    private AuthPrincipal principal;

    @BeforeEach
    void setUp() {
        Organization org = fx.org();
        orgId = org.getId();
        principal = fx.principal(fx.owner(org));
        clock.setInstant(NOW);
    }

    /** campaigns.org_id has no FK, so own campaigns (recipients cascade) go first, then memberships and the org. */
    @AfterEach
    void tearDown() {
        try {
            CampaignRows.delete(jdbc, List.of(orgId));
            jdbc.update("delete from memberships where org_id = ?", orgId);
        } finally {
            OrgRows.delete(jdbc, List.of(orgId));
        }
    }

    @Test
    void previewCountsReadThisOrgsOpensAndTicketClicks() throws Exception {
        UUID campaign = emailCampaign();
        UUID objected = member(true);
        recipient(campaign, objected, daysAgo(1), daysAgo(1), daysAgo(1));
        UUID recent = member(false);
        recipient(campaign, recent, daysAgo(10), daysAgo(3), daysAgo(3));
        UUID stale = member(false);
        recipient(campaign, stale, daysAgo(100), daysAgo(2), null);
        UUID never = member(false);
        recipient(campaign, never, null, null, null);

        String opened = "[{\"field\":\"days_since_open\",\"operator\":\"<=\",\"value\":\"90\"}]";
        assertThat(preview(opened)).isEqualTo(1);
        assertThat(resolved(opened)).containsExactly(recent);

        String ticketClicked = "[{\"field\":\"days_since_ticket_click\",\"operator\":\"<=\",\"value\":\"7\"}]";
        assertThat(preview(ticketClicked)).isEqualTo(1);
        assertThat(resolved(ticketClicked)).containsExactly(recent);
    }

    private int preview(String rules) throws Exception {
        String json = mvc.perform(post("/api/v1/audience/segments/preview").with(auth(principal))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rulesJson\":" + rules + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(json, "$.matched");
    }

    private List<UUID> resolved(String rules) {
        Segment transientSegment = new Segment();
        transientSegment.setOrgId(orgId);
        transientSegment.setKind("dynamic");
        transientSegment.setRulesJson(rules);
        return segmentService.resolveMembers(orgId, transientSegment).stream()
                .map(Membership::getMembershipId).toList();
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    private UUID emailCampaign() {
        Campaign c = new Campaign();
        c.setId(UUID.randomUUID());
        c.setOrgId(orgId);
        c.setChannel("email");
        c.setName("engagement-segment");
        c.setStatus("sent");
        c.setCreatedAt(NOW);
        c.setUpdatedAt(NOW);
        return campaigns.save(c).getId();
    }

    private UUID member(boolean objected) {
        Consumer consumer = new Consumer();
        consumer.setNormalizedEmail(fx.email("engagement"));
        consumer = consumers.save(consumer);
        Membership m = new Membership();
        m.setOrgId(orgId);
        m.setConsumerId(consumer.getConsumerId());
        m.setObjectedProfiling(objected);
        return memberships.save(m).getMembershipId();
    }

    private void recipient(UUID campaignId, UUID membershipId, Instant openedAt, Instant clickedAt,
                           Instant ticketClickedAt) {
        CampaignRecipient r = new CampaignRecipient();
        r.setId(UUID.randomUUID());
        r.setCampaignId(campaignId);
        r.setMembershipId(membershipId);
        r.setStatus("delivered");
        r.setOpenedAt(openedAt);
        r.setClickedAt(clickedAt);
        r.setTicketClickedAt(ticketClickedAt);
        recipients.save(r);
    }

    private static RequestPostProcessor auth(AuthPrincipal p) {
        return authentication(new UsernamePasswordAuthenticationToken(p, null,
                List.of(new SimpleGrantedAuthority("ROLE_" + p.role().name()))));
    }
}
